package com.cycling.beevideo.data.source.vod.js

import com.cycling.beevideo.data.source.vod.catvod.CatVodException
import com.whl.quickjs.android.QuickJSLoader
import dalvik.system.DexClassLoader

/**
 * `.js` 爬虫引擎的门面 —— 对齐参考宿主那**两个**同名类的职责之和：
 * `quickjs/src/main/java/com/fongmi/quickjs/crawler/Loader.java`（原生库初始化 + 造 spider）
 * 与 `app/src/main/java/com/fongmi/android/tv/api/loader/JsLoader.java`（"最近用过的源"）。
 *
 * ─── 谁在用 ──────────────────────────────────────────────────────────
 * `SiteClientFactory` 持有它的一个实例：
 * ```
 * 建站点   → newSpider(api, dex) + markRecent(key)
 * /proxy   → currentRecent() 找出该由哪个 JS 源接这一单
 * 换配置   → clear()
 * ```
 *
 * ─── ⚠️ 原生库必须先 init，否则 `QuickJSContext.create()` 直接抛 ──────
 * 捆绑库在没有加载 `libquickjs.so` 时会抛：
 * ```
 * QuickJSException: The so library must be initialized before createContext!
 * ```
 * 参考实现是在 `Loader` 的**构造器**里调 `QuickJSLoader.init()`。这里改成
 * 进程级一次性初始化（见 [companion object] 的 `nativeReady`）：
 * `SiteClientFactory` 可以有多个实例（换配置会重建），而 `System.loadLibrary`
 * 是进程级的事 —— 把"加载没加载过"的状态挂在实例上，就会出现
 * "第二个工厂实例重复加载 / 或以为没加载"这类没有意义的状态。
 *
 * ─── `recent` 为什么存在 ─────────────────────────────────────────────
 * `Global.getProxy(local)` 拼出的是**裸的** `…/proxy?do=js`，**不带 siteKey**
 * （带 siteKey 的那条是 `Global.js2Proxy`，它会把 siteKey 拼进去）。
 * 本地代理服务收到不带 siteKey 的 `do=js` 时，只能靠"最近用过的那个 JS 源"
 * 来猜该交给谁 —— 这正是参考实现 `JsLoader.recent` 的用途，一字不差地照搬。
 *
 * 猜错的代价是"这个请求没人接手"，返回 502；不会错交给别的源
 * （因为 [currentRecent] 只会返回存在的那一个 key）。
 */
class JsLoader {

    /** 最近一次被站点流程用到的 JS 站点 key。 */
    @Volatile
    private var recent: String? = null

    /**
     * 造一个 JS spider（**不**在这里做 `init`）。
     *
     * ⚠️ 调用方必须按这个顺序用：
     * ```
     * val spider = jsLoader.newSpider(api, dex)
     * spider.siteKey = key          // ← 必须在 init 之前！
     * spider.init(context, ext)     // ← 里面才会建 QuickJS 上下文并求值源码
     * jsLoader.markRecent(key)
     * ```
     * `siteKey` 的顺序理由与 jar 那边完全相同（见 `Spider.siteKey` 的注释）：
     * [JsSpider.getExt] 会把 `skey` 交给 JS 的 `init`，晚了就晚了。
     *
     * @param dex 可选的 jar ClassLoader，只有主 spider.jar 才有
     *   `com.github.catvod.js.Function`；没有就传 null（见 [JsSpider.createFun]）。
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

        /**
         * 进程级标记。`@Volatile` + 双检锁：多个线程可能同时建第一个 JS 站点
         * （首页会并发拉多个源）。
         */
        @Volatile
        private var nativeReady = false

        /**
         * 加载 `libquickjs.so`（幂等）。
         *
         * ⚠️ 失败**必须**抛成 [CatVodException] 而不是让 `UnsatisfiedLinkError`
         * 裸奔：那个错误的默认文案是
         * `Couldn't load quickjs: findLibrary returned null`，
         * 用户看到的是"找不到库"，而真正的原因通常是这台设备的 ABI
         * 没有对应的 `.so`（我们没做 abiFilters，理应全都有）。
         * 壳一层之后，报错里至少带着"QuickJS 原生库加载失败"这个前缀。
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
