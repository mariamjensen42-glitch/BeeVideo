package com.cycling.beevideo.data.source.vod.catvod

import android.content.Context
import android.util.Log
import com.github.catvod.crawler.Spider
import dalvik.system.DexClassLoader
import java.io.File
import java.util.concurrent.ConcurrentHashMap

/**
 * csp jar 的下载、缓存与加载。
 *
 * ─── ⚠️ 为什么不是参考项目里那套 `URLClassLoader` ─────────────────────
 * 参考项目（TV-Multiplatform）的 `JarLoader` 用的是 `URLClassLoader` ——
 * 那是 **JVM** 的类加载器，只能加载标准 `.class` 字节码。
 * Android 跑的是 **DEX**，`URLClassLoader` 在设备上加载 jar 会直接失败。
 * 那份实现只能在它的 desktop target 上工作，**不能照搬**。
 *
 * Android 侧唯一的路子是 `dalvik.system.DexClassLoader`。
 *
 * ─── ⚠️ API 34+ 的只读要求 ────────────────────────────────────────────
 * 从 Android 14（API 34）起，动态加载的代码文件**必须标记为只读**，
 * 否则 `DexClassLoader` 直接抛 `SecurityException`。本项目 targetSdk 37、
 * minSdk 31，所以这条必须处理，不能"测试机上没事"就跳过。
 *
 * ─── 父类加载器必须是 App 的 ──────────────────────────────────────────
 * `DexClassLoader` 的第四个参数传 `Spider::class.java.classLoader`。
 * jar 里的 `csp_*` 类继承 `com.github.catvod.crawler.Spider`，子类解析父类时会
 * 沿委托链上溯到 App 的 ClassLoader，从而找到我们自带的那份兼容层。
 * 传 null 的话就是 bootstrap 加载器，父类找不到 → `NoClassDefFoundError`。
 */
object DexJarLoader {

    private const val DIR_JAR = "catvod/jar"
    private const val DIR_DEX = "catvod/dex"

    /**
     * 按「文件路径 + 大小 + 修改时间」缓存 ClassLoader。
     *
     * 不用 URL 当键：同一个 URL 在不同时间可能指向不同内容，
     * 而 ClassLoader 一旦建好就无法重新加载同名类，所以键必须能反映文件本身。
     */
    private val loaders = ConcurrentHashMap<String, DexClassLoader>()

    /**
     * 确保 jar 已下载到本地并返回文件。
     *
     * 下载走「先写 `.part` 再改名」：直接往目标文件名写，中断时（用户切走、
     * 进程被杀）会留下一个半个的 jar，而它**下次会被当成缓存命中**，
     * 于是站点永远加载失败且看不出原因。
     */
    suspend fun ensureJar(context: Context, url: String, md5: String): File {
        if (url.isEmpty()) throw CatVodException("配置里没有指定 jar 地址")

        val dir = File(context.filesDir, DIR_JAR).apply { mkdirs() }
        val name = md5.ifEmpty { "h${url.hashCode().toUInt().toString(16)}" } + ".jar"
        val target = File(dir, name)
        if (target.exists() && target.length() > 0) return target

        val bytes = try {
            CatVodHttp.getBytes(url)
        } catch (e: Exception) {
            throw CatVodException("下载 jar 失败：$url（${e.message}）", e)
        }
        if (bytes.isEmpty()) throw CatVodException("下载到的 jar 是空的：$url")

        val part = File(dir, "$name.part")
        part.writeBytes(bytes)
        if (!part.renameTo(target)) {
            part.copyTo(target, overwrite = true)
            part.delete()
        }
        return target
    }

    /** 取得（或建立）jar 对应的 ClassLoader。 */
    fun loader(context: Context, jarFile: File): DexClassLoader {
        if (!jarFile.exists() || jarFile.length() == 0L) {
            throw CatVodException("jar 文件不可用：${jarFile.absolutePath}")
        }
        val key = "${jarFile.absolutePath}@${jarFile.length()}@${jarFile.lastModified()}"
        return loaders.getOrPut(key) {
            enforceReadOnly(jarFile)
            val dexDir = File(context.codeCacheDir, DIR_DEX).apply { mkdirs() }
            val loader = try {
                DexClassLoader(
                    jarFile.absolutePath,
                    dexDir.absolutePath,
                    null,
                    Spider::class.java.classLoader,
                )
            } catch (e: SecurityException) {
                throw CatVodException(
                    "系统拒绝加载动态代码（${e.message}）。" +
                        "Android 14+ 要求被加载的文件是只读的。",
                    e,
                )
            }
            invokeJarInit(context, loader)
            loader
        }
    }

    /**
     * 调用 jar 自带的一次性初始化钩子 `com.github.catvod.spider.Init.init(Context)`。
     *
     * 原版 `JarLoader.load` 在建好 `DexClassLoader` 之后立刻做这件事：
     * ```java
     * Class<?> clz = loader.loadClass("com.github.catvod.spider.Init");
     * clz.getMethod("init", Context.class).invoke(clz, App.get());
     * ```
     * 它是**可选**的（多数 jar 没有这个类），但有的 jar 靠它做全局准备
     * ——设置静态 Context、装载它自己那份网络库、注册 UA 之类。
     * 漏掉这一步的表现是"jar 能加载、类也能 new，但某些站点行为异常"，
     * 属于最难查的一类。
     *
     * 失败**必须吞掉**，和原版一致：绝大多数 jar 根本没有 `Init`，
     * 报 `ClassNotFoundException` 是正常路径，不是错误。
     *
     * 对照原版还有一处 `com.github.catvod.spider.Proxy`（把 `proxy(Map)` 反射缓存起来
     * 供本地代理服务调用）—— 本项目没有本地代理服务，所以不做，见 `Spider.proxy`。
     */
    private fun invokeJarInit(context: Context, loader: DexClassLoader) {
        runCatching {
            val clazz = loader.loadClass("com.github.catvod.spider.Init")
            clazz.getMethod("init", Context::class.java).invoke(null, context)
        }.onFailure { e ->
            // 只有"类存在但初始化炸了"才值得说一声；ClassNotFound 是常态。
            // 用 Log 而不是 SpiderDebug：后者默认静音（那是给爬虫自己用的），
            // 而这条是宿主侧的诊断信息，应该始终能在 logcat 里看到。
            if (e !is ClassNotFoundException) {
                Log.w("CatVodJar", "jar 的 Init 钩子执行失败：$e")
            }
        }
    }

    /**
     * 加载一个 spider 实例。
     *
     * 每种失败都给了**能直接指向原因**的消息 —— 动态加载这一层的报错原本
     * 非常不友好（`NoClassDefFoundError: com/github/catvod/crawler/Spider`
     * 不会告诉你到底是 jar 里没有、还是你忘了自带父类）。
     */
    fun newSpider(context: Context, jarFile: File, className: String): Spider {
        val loader = loader(context, jarFile)

        val clazz = try {
            loader.loadClass(className)
        } catch (e: ClassNotFoundException) {
            throw CatVodException(
                "jar 里找不到类 $className。" +
                    "检查配置的 api 字段（应是 csp_ 加类名）与该 jar 是否匹配。",
                e,
            )
        }

        val instance = try {
            clazz.getDeclaredConstructor().newInstance()
        } catch (e: NoClassDefFoundError) {
            throw CatVodException(
                "类 $className 初始化时找不到 ${e.message}。" +
                    "通常是该 jar 还依赖别的库（OkHttp / Gson / jsoup 等），" +
                    "而那个库没有被打进 jar，也没被宿主提供。",
                e,
            )
        } catch (e: Exception) {
            throw CatVodException("实例化 $className 失败：${e.message}", e)
        }

        return instance as? Spider ?: throw CatVodException(
            "类 $className 不是 com.github.catvod.crawler.Spider 的子类。" +
                "它继承的是别处的 Spider —— 说明该 jar 打包时带上了自己那份父类，" +
                "与宿主自带的这份不是同一个 Class。",
        )
    }

    /** 清空缓存（换配置时用）。ClassLoader 无法卸载，只能丢掉引用。 */
    fun clear() {
        loaders.clear()
    }

    private fun enforceReadOnly(file: File) {
        if (!file.canWrite()) return
        file.setReadOnly()
        if (file.canWrite()) {
            throw CatVodException(
                "无法将 ${file.name} 置为只读，系统会拒绝加载它。" +
                    "检查该文件是否被其他进程占用。",
            )
        }
    }
}
