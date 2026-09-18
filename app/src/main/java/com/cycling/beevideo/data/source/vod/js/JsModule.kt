package com.cycling.beevideo.data.source.vod.js

import com.github.catvod.net.OkHttp
import com.github.catvod.utils.Asset

/**
 * JS 模块（`import`）的取源与缓存
 * —— 对齐参考宿主的 `quickjs/src/main/java/com/fongmi/quickjs/utils/Module.java`。
 *
 * 被 [JsSpider.createContext] 里的 `BytecodeModuleLoader` 调用：
 * QuickJS 遇到 `import ... from 'xxx'` 时，宿主负责把那个模块的**源码**取回来。
 *
 * ─── 三种来源，判据是**前缀** ─────────────────────────────────────────
 * ```
 * http…   → 网络取（OkHttp.string）
 * assets… → APK 内置（Asset.read）
 * lib/…   → APK 内置，但要补上 `js/` 前缀（见下）
 * ```
 * 其余一律返回 `null` —— QuickJS 会把"模块取不到"报成
 * `could not load module`。
 *
 * ⚠️ `lib/` 那条**必须补 `js/`**：JS 侧写的是 `import { gbkTool } from 'lib/gbk.js'`
 * （drpy 的约定，路径相对 assets 根），而真实文件在 `assets/js/lib/gbk.js`。
 * 少了这一步，所有 drpy 源的 `lib/…` 导入全失败 —— 而且报错在 QuickJS 内部。
 *
 * ─── 与参考实现唯一的差异：不用 `android.util.LruCache` ────────────────
 * 参考实现用 `android.util.LruCache(50)`。这里换成一个 50 条上限的
 * `LinkedHashMap`（accessOrder = true，即 LRU），理由**是单测会静默错**：
 * 本项目开了 `unitTests.isReturnDefaultValues = true`，纯 JVM 单测里
 * `LruCache.get(...)` **返回 null 而不是抛异常**，`put` 静默丢弃 ——
 * 于是"缓存有没有生效"这件事在单测里**永远测不到**，而且看起来一切正常。
 *
 * 行为等价：容量 50、按访问顺序淘汰最久未用的、线程安全（外部加锁）。
 *
 * ⚠️ 空结果**不缓存** —— 这与参考实现一致：它的判据是
 * `!TextUtils.isEmpty(cached)`，缓存的空串每次都会重新去取。
 */
object JsModule {

    private const val MAX_SIZE = 50

    private val cache = object : LinkedHashMap<String, String>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, String>): Boolean =
            size > MAX_SIZE
    }

    /** 保留参考实现的静态工厂形态，调用处 `Module.get().fetch(x)` 的写法不变。 */
    fun get(): JsModule = this

    /**
     * 取模块源码。
     *
     * @return 取到就是源码；**取不到返回 null**（不是空串）——
     *   `BytecodeModuleLoader.getModuleBytecode` 要拿它去 `compileModule`，
     *   而 `compileModule(null)` 会抛 `NullPointerException: Script cannot be null`，
     *   比"模块加载失败"更难归因（见 [JsSpider.createContext] 的处理）。
     */
    fun fetch(name: String): String? {
        synchronized(cache) { cache[name] }?.takeIf { it.isNotEmpty() }?.let { return it }

        val content = when {
            name.startsWith("http") -> OkHttp.string(name)
            name.startsWith("assets") -> Asset.read(name)
            name.startsWith("lib/") -> Asset.read("js/$name")
            else -> null
        }

        if (content != null && content.isNotEmpty()) {
            synchronized(cache) { cache[name] = content }
        }
        return content?.takeIf { it.isNotEmpty() }
    }

    /** 换配置时清空（见 `SiteClientFactory.clear`）。 */
    fun clear() {
        synchronized(cache) { cache.clear() }
    }
}
