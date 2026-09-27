package com.cycling.beevideo.data.source.vod.catvod

import android.content.Context
import android.util.Log
import com.github.catvod.crawler.Spider
import dalvik.system.DexClassLoader
import java.io.File
import java.lang.reflect.Method
import java.util.concurrent.ConcurrentHashMap

/**
 * csp jar 的下载、缓存与加载。
 *
 * ⚠️ 不能用参考项目的 `URLClassLoader` —— 那是 JVM 的类加载器，只能加载 `.class`；
 * Android 跑 DEX，设备上会直接失败。唯一路子是 `dalvik.system.DexClassLoader`。
 *
 * ⚠️ API 34+ 要求动态加载的代码文件**只读**（targetSdk 37 必须处理，否则 `SecurityException`）。
 *
 * ⚠️ 父类加载器必须是 App 的：jar 里的 `csp_*` 继承宿主自带的 `Spider`，传 null 就是
 * bootstrap 加载器 → `NoClassDefFoundError`。
 */
object DexJarLoader {

    private const val DIR_JAR = "catvod/jar"
    private const val DIR_DEX = "catvod/dex"
    private const val TAG = "CatVodJar"

    private val loaders = ConcurrentHashMap<String, DexClassLoader>()

    /**
     * jar → 它自带的**静态** `com.github.catvod.spider.Proxy.proxy(Map)`，本地代理的回路终点。
     *
     * ⚠️ 多数 jar 根本没有这个类，反射失败是常态不是错误。
     */
    private val proxyMethods = ConcurrentHashMap<String, Method>()

    @Volatile
    private var recentKey: String? = null

    // ⚠️ 键不能用 URL：同一 URL 在不同时间指向不同内容，而 ClassLoader 建好就无法重载同名类
    private fun keyOf(jarFile: File): String =
        "${jarFile.absolutePath}@${jarFile.length()}@${jarFile.lastModified()}"

    /**
     * 确保 jar 已下载到本地并返回文件。
     *
     * ⚠️ 先写 `.part` 再改名：直接写目标名，中断时会留下半个 jar，而它下次会被当成
     * 缓存命中，站点永远加载失败且看不出原因。
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

    fun loader(context: Context, jarFile: File): DexClassLoader {
        if (!jarFile.exists() || jarFile.length() == 0L) {
            throw CatVodException("jar 文件不可用：${jarFile.absolutePath}")
        }
        val key = keyOf(jarFile)
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
            invokeJarProxy(key, loader)
            loader
        }
    }

    /**
     * jar 自带的一次性初始化钩子 `com.github.catvod.spider.Init.init(Context)`。
     *
     * ⚠️ 失败**必须吞掉**：绝大多数 jar 没有这个类，`ClassNotFoundException` 是正常路径。
     * 漏掉这一步的症状是"jar 能加载、类也能 new，但某些站点行为异常"，最难查的一类。
     */
    private fun invokeJarInit(context: Context, loader: DexClassLoader) {
        runCatching {
            val clazz = loader.loadClass("com.github.catvod.spider.Init")
            clazz.getMethod("init", Context::class.java).invoke(null, context)
        }.onFailure { e ->
            // 只有"类存在但初始化炸了"才值得说一声。用 Log 不用 SpiderDebug：后者默认静音
            if (e !is ClassNotFoundException) {
                Log.w(TAG, "jar 的 Init 钩子执行失败：$e")
            }
        }
    }

    /**
     * 反射拿 jar 里的**静态** `com.github.catvod.spider.Proxy.proxy(Map)` 并缓存。
     *
     * ⚠️ 它是静态方法，调用时第一个参数传 `null`；写成实例方法调会抛
     * `IllegalArgumentException: object is not an instance of declaring class`。
     */
    private fun invokeJarProxy(key: String, loader: DexClassLoader) {
        runCatching {
            val clazz = loader.loadClass("com.github.catvod.spider.Proxy")
            proxyMethods[key] = clazz.getMethod("proxy", Map::class.java)
        }.onFailure { e ->
            if (e !is ClassNotFoundException) {
                Log.w(TAG, "jar 的静态 Proxy 钩子不可用：$e")
            }
        }
    }

    /**
     * 当前可用的「jar → 静态 proxy 方法」，**最近用过的排在最前**。
     *
     * 顺序有意义：多数 jar 发的 `/proxy` 请求不带 `siteKey`，宿主只能挨个试，
     * 而实际要处理的几乎总是刚在用的那个。
     */
    fun proxyEntries(): List<Method> {
        val recent = recentKey
        if (recent == null) return proxyMethods.values.toList()
        val first = proxyMethods[recent]
        val rest = proxyMethods.filterKeys { it != recent }.values.toList()
        return if (first == null) rest else listOf(first) + rest
    }

    fun markRecent(jarFile: File) {
        recentKey = keyOf(jarFile)
    }

    /** 每种失败都给出能直接指向原因的消息 —— 动态加载那一层的原始报错非常不友好。 */
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
        // 静态 proxy 方法挂在旧 ClassLoader 上，不丢的话 /proxy 会去调上一份配置的 jar
        proxyMethods.clear()
        recentKey = null
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
