package com.cycling.beevideo.data.source.vod.js

import com.cycling.beevideo.data.source.vod.catvod.CatVodException
import com.whl.quickjs.android.QuickJSLoader
import dalvik.system.DexClassLoader

/**
 * `.js` 爬虫引擎的门面，对齐参考宿主两个同名类的职责之和（原生库初始化 + 造 spider、
 * 以及"最近用过的源"标记）。
 *
 * ⚠️ 原生库必须先 init，否则 `QuickJSContext.create()` 直接抛
 * `The so library must be initialized before createContext!`。
 * 这里做成**进程级一次性**初始化：`SiteClientFactory` 可以有多个实例，
 * 而 `System.loadLibrary` 是进程级的事。
 *
 * `recent` 存在的理由：`Global.getProxy(local)` 拼出的是**裸的** `…/proxy?do=js`，
 * 不带 siteKey，本地代理只能靠"最近用过的那个 JS 源"猜该交给谁。
 * 猜错的代价是返回 502，不会错交给别的源。
 */
class JsLoader {

    @Volatile
    private var recent: String? = null

    /**
     * 造一个 JS spider（**不**在这里做 `init`）。
     *
     * ⚠️ 调用方必须按这个顺序用：`newSpider` → `siteKey = key` → `init(context, ext)`
     * → `markRecent(key)`。`siteKey` 必须在 init 之前，与 jar 那边同理：
     * `JsSpider.getExt` 会把 `skey` 交给 JS 的 `init`，晚了就晚了。
     *
     * @param dex 可选的 jar ClassLoader，只有主 spider.jar 才有
     *   `com.github.catvod.js.Function`；没有就传 null。
     */
    fun newSpider(api: String, dex: DexClassLoader?): JsSpider {
        ensureNative()
        return JsSpider(api, dex)
    }

    /** 由站点创建流程调用：这个 JS 源刚被用过，优先接 `/proxy`。 */
    fun markRecent(key: String) {
        recent = key
    }

    fun currentRecent(): String? = recent

    /** 换配置时调用。原生上下文由各 spider 自己 `destroy()` 释放，这里只清缓存与标记。 */
    fun clear() {
        JsModule.get().clear()
        recent = null
    }

    companion object {

        /** 进程级标记。多个线程可能同时建第一个 JS 站点（首页会并发拉多个源）。 */
        @Volatile
        private var nativeReady = false

        /**
         * 加载 `libquickjs.so`（幂等）。
         *
         * ⚠️ 失败必须抛成 [CatVodException] 而不是让 `UnsatisfiedLinkError` 裸奔：
         * 那个错误的默认文案看不出真正原因（通常是这台设备的 ABI 没有对应 `.so`）。
         */
        @Synchronized
        fun ensureNative() {
            if (nativeReady) return
            try {
                QuickJSLoader.init()
            } catch (e: Throwable) {
                throw CatVodException(
                    "QuickJS 原生库加载失败（${e.message}）。" +
                        "检查 APK 是否带上了对应 ABI 的 libquickjs.so。",
                    e,
                )
            }
            nativeReady = true
        }
    }
}
