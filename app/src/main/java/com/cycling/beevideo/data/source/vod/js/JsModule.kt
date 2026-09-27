package com.cycling.beevideo.data.source.vod.js

import com.github.catvod.net.OkHttp
import com.github.catvod.utils.Asset

/**
 * JS 模块（`import`）的取源与缓存，对齐参考宿主的 `Module.java`。
 * 被 `createJsContext()` 里的模块加载器调用：QuickJS 遇到 `import` 时，
 * 宿主负责把那个模块的**源码**取回来。
 *
 * 三种来源按**前缀**判：`http…` 网络取、`assets…` APK 内置、`lib/…` APK 内置但要补
 * `js/` 前缀。其余一律返回 `null`（QuickJS 会报 `could not load module`）。
 *
 * ⚠️ `lib/` 那条**必须补 `js/`**：JS 侧写的是 `import { gbkTool } from 'lib/gbk.js'`
 * （drpy 的约定，路径相对 assets 根），而真实文件在 `assets/js/lib/gbk.js`。
 * 少了这一步所有 drpy 源的 `lib/…` 导入全失败，而且报错在 QuickJS 内部。
 *
 * ⚠️ 不用 `android.util.LruCache`：`isReturnDefaultValues = true` 下纯 JVM 单测里
 * `LruCache.get(...)` **返回 null 而不是抛异常**、`put` 静默丢弃 —— "缓存有没有生效"
 * 这件事在单测里永远测不到，而且看起来一切正常。换成 50 条上限的 LinkedHashMap
 * （accessOrder = true，即 LRU），行为等价。
 *
 * ⚠️ 空结果**不缓存**（与参考实现一致，它的判据是 `!TextUtils.isEmpty(cached)`）。
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
     * @return 取到就是源码；**取不到返回 null**（不是空串）—— 调用方要拿它去
     *   `compileModule`，而 `compileModule(null)` 会抛 `Script cannot be null`，
     *   比"模块加载失败"更难归因。
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
