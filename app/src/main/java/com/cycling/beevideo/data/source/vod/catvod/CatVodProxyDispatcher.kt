package com.cycling.beevideo.data.source.vod.catvod

import android.util.Log
import com.cycling.beevideo.data.proxy.ProxyHandler
import com.github.catvod.crawler.Spider
import java.lang.reflect.Method
import java.util.concurrent.ConcurrentHashMap

/**
 * `/proxy` 请求的派发 —— 决定这一次请求交给谁。
 *
 * 三条分支照搬参考实现 `BaseLoader.proxy` 的判断顺序：
 * ```
 * if (params.containsKey("siteKey")) return getSpider(params.get("siteKey")).proxy(params);
 * if ("js".equals(params.get("do")))  return jsLoader.proxy(params);
 * if ("py".equals(params.get("do")))  return pyLoader.proxy(params);
 * return jarLoader.proxy(params);
 * ```
 * 中间两条是 JS / Python 引擎。JS 那条已实现（[jsProxy]）；
 * Python 仍然不支持（见 [SiteClientFactory] 的引擎分派），所以没有对应分支。
 *
 * ─── ⚠️ `siteKey` 分支**排在 `do=js` 前面**，这不是顺序问题而是分工 ──────
 * JS 源的 `js2Proxy` 拼出来的地址**同时带** `siteKey` 和 `do=js`
 * （见 `Global.js2Proxy`），它必须走第一条、交给那个站点**自己的**
 * `proxy()`；只有**裸的** `?do=js`（`Global.getProxy()`，不带 siteKey）
 * 才落到第二条，由"最近用过的 JS 源"接手。
 * 两条对调的话，所有 JS 源的真实请求都会被"最近用过的那个源"接走 ——
 * 表现是**多开一个 JS 源就互相串流**。
 *
 * ─── 两处「反直觉」的边界，都是有意的 ──────────────────────────────────
 * 这两条都不要"顺手改得像更有道理"，它们各自对应参考实现里的一行代码：
 *
 * 1. **`siteKey` 找不到 spider 时会落到静态分支**，而不是直接放弃。
 *    配置里写了 `siteKey` 但站点已被删除、或者 jar 加载失败，这时那个 jar 的
 *    静态 Proxy 仍然可能是对的处理者 —— 参考实现里 `getSpider` 返回 null 之后
 *    代码继续往下走，同样是这个结果。
 *
 * 2. **`siteKey` 找得到 spider、但它 `proxy()` 返回 null 时，直接返回 null**，
 *    **不**继续试静态 Proxy。参考实现写的是 `return getSpider(...).proxy(params)`
 *    —— 一个 `return`，没有回退。理由也成立：这个 jar 明确说了"我不处理"，
 *    再去问同一个 jar 的静态入口是重复劳动，而且会让"谁接手的"变得不可预测。
 *
 * 这两条都**没有**真实站点的对照实验（本项目手头的源里没有一个会发带 `siteKey`
 * 的 `/proxy` 请求），所以它们由 [CatVodProxyDispatcherTest] 钉住行为，
 * 而不是靠"看起来对"。
 */
class CatVodProxyDispatcher(
    private val spiderOf: (String) -> Spider?,
    /**
     * `do=js` 且**不带 siteKey** 时的接手者 —— 由 JS 引擎按"最近用过的源"决定。
     *
     * 做成参数而不是直接依赖 `JsLoader`：一是让分支逻辑可测（同 [staticProxies]），
     * 二是**依赖方向**——`catvod` 这层不该反向认识 `js` 包。
     * `null` 表示宿主没装 JS 引擎，那条分支直接不接手（返回 null → 502）。
     */
    private val jsProxy: ((Map<String, String>) -> Array<Any?>?)? = null,
    /**
     * 各 jar 的静态 `com.github.catvod.spider.Proxy.proxy(Map)`。
     *
     * 做成参数而不是直接调 [DexJarLoader.proxyEntries]，是为了让分支逻辑
     * **可测**：那个方法落在 `DexJarLoader` 这个 `object` 上，必须先建
     * `DexClassLoader`、加载真 jar 才有条目，单测环境里造不出来。
     * 生产代码走默认值，行为与之前逐字一致。
     */
    private val staticProxies: () -> List<Method> = DexJarLoader::proxyEntries,
) : ProxyHandler {

    /**
     * 已经记过日志的「异常路径」。NanoHTTPD 是多线程处理请求的，必须用并发集合。
     *
     * 为什么按 key 去重而不是逐条打：一次播放会打进来几十个 `/proxy`
     * （m3u8 + 每个分片），逐条记的话真正的错会被冲掉 —— `LocalProxyServer`
     * 里已经为同一件事吃过一次亏（见那里的 `loggedActions`）。
     */
    private val loggedAnomalies = ConcurrentHashMap.newKeySet<String>()

    @Suppress("UNCHECKED_CAST")
    override fun proxy(params: Map<String, String>): Array<Any?>? {
        // 1) 带 siteKey 的：直接找那个站点自己的实例方法。
        //    一个 jar 里的同一个类被配成十几个站点是常态，所以按 key 取才有意义。
        val siteKey = params["siteKey"]
        if (!siteKey.isNullOrEmpty()) {
            val spider = spiderOf(siteKey)
            if (spider == null) {
                warnOnce(
                    "no-spider:$siteKey",
                    "siteKey=$siteKey 没有对应的 spider（站点可能已被删除或 jar 加载失败），" +
                        "改走 jar 静态 Proxy",
                )
            } else {
                val result = spider.proxy(params).widen()
                if (result == null) {
                    // 见类注释第 2 条：这里**不回退**，所以值得留一条日志 ——
                    // 从外面看，它和"谁都没接手"长得一模一样。
                    warnOnce(
                        "spider-declined:$siteKey",
                        "siteKey=$siteKey 的 spider.proxy() 返回 null（明确不处理这个请求），" +
                            "按参考实现不再回退到静态 Proxy",
                    )
                }
                return result
            }
        }

        // 2) `do=js` 且不带 siteKey —— 裸的 `?do=js`（`Global.getProxy()` 拼出）。
        //    交给"最近用过的那个 JS 源"。
        //
        //    ⚠️ 这里**必须直接 return**（哪怕拿到的就是 null）：参考实现写的是
        //    `return jsLoader.proxy(params);`，后面那行 `jarLoader.proxy` 够不着。
        //    改成"null 就继续往下试 jar 静态 Proxy"会让一个**明确属于 JS 引擎**的
        //    请求被某个 jar 抢走 —— 它返回的东西形状可能完全不对。
        if (params["do"] == "js") {
            val result = jsProxy?.invoke(params)
            if (result == null) {
                // 不记日志的话，这一条和"谁都没接手"在 logcat 里长得一模一样，
                // 而它俩的排查方向不同（前者看 JS 源有没有建起来，后者看有没有代理实现）
                warnOnce(
                    "js-no-spider",
                    "do=js 的请求没有 JS 源接手（宿主没装 JS 引擎，或还没有任何 JS 源被加载过），" +
                        "按参考实现不再回退到 jar 静态 Proxy",
                )
            }
            return result
        }

        // 3) 不带 siteKey —— 多数 jar 发的播放地址就是这种形态（实测那条是
        //    `/proxy?do=m3u8&url=…`）。这时只能挨个试各 jar 的静态 Proxy：
        //    最近用过的优先，**返回非 null 就算命中**，null 表示"我不管这个请求"。
        val entries = staticProxies()
        if (entries.isEmpty()) {
            // 不是错误，但排查"代理没反应"时这是第一个该确认的事实：
            // 没有任何 jar 提供静态 Proxy，那么不带 siteKey 的请求必然无人接手。
            warnOnce("no-static-proxy", "没有任何 jar 提供静态 Proxy，不带 siteKey 的请求无法派发")
        }
        for (method in entries) {
            val result = runCatching { method.invoke(null, params) as? Array<Any?> }
                .onFailure { Log.w(TAG, "jar 静态 Proxy 调用失败：$it") }
                .getOrNull()
            if (result != null) return result
        }
        return null
    }

    /** 同一条异常路径只记一次。 */
    private fun warnOnce(key: String, message: String) {
        if (loggedAnomalies.add(key)) Log.w(TAG, message)
    }

    /**
     * `Spider.proxy` 声明的元素类型是 `Any`（非空），而 [ProxyHandler] 要 `Any?`。
     * Kotlin 的数组**不变**，两者不能直接赋值；但 `as` 在运行期只是
     * `instanceof Object[]`，且两者擦除后本就是同一个类型 —— 这是一次零成本的
     * 类型放宽，不是数据转换。
     *
     * 之所以不直接改 `Spider.proxy` 的声明：那个文件的每个签名都是逐条核对过的
     * ABI，能不动就不动。
     */
    @Suppress("UNCHECKED_CAST")
    private fun Array<Any>?.widen(): Array<Any?>? = this as Array<Any?>?

    private companion object {
        const val TAG = "CatVodProxy"
    }
}
