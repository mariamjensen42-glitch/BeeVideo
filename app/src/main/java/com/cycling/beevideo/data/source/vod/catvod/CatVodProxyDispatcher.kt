package com.cycling.beevideo.data.source.vod.catvod

import android.util.Log
import com.cycling.beevideo.data.proxy.ProxyHandler
import com.github.catvod.crawler.Spider
import java.lang.reflect.Method
import java.util.concurrent.ConcurrentHashMap

/**
 * `/proxy` 请求的派发：带 `siteKey` → 那个站点自己的 `proxy()`；
 * 裸 `do=js` → 最近用过的 JS 源；其余 → 各 jar 的静态 Proxy 挨个试。
 *
 * ⚠️ `siteKey` 分支**必须排在 `do=js` 前面**：JS 源 `js2Proxy` 拼出的地址同时带这两者，
 * 它得走第一条、交给那个站点自己的 proxy；只有裸的 `?do=js` 才落到第二条。
 * 对调的话表现是「多开一个 JS 源就互相串流」。
 *
 * ⚠️ 两处反直觉边界，都对应参考实现的一行代码，别"顺手改得像更有道理"：
 *  1. `siteKey` 找不到 spider 时**落到静态分支**，不是直接放弃；
 *  2. 找得到、但 `proxy()` 返回 null 时**直接返回 null，不回退到静态 Proxy**。
 * 两条都由 `CatVodProxyDispatcherTest` 钉住（手头的源没有会发带 siteKey 请求的）。
 */
class CatVodProxyDispatcher(
    private val spiderOf: (String) -> Spider?,
    // 做成参数是为了可测，也为了让 catvod 这层不反向认识 js 包。null = 没装 JS 引擎
    private val jsProxy: ((Map<String, String>) -> Array<Any?>?)? = null,
    // 做成参数是为了可测：DexJarLoader::proxyEntries 要先建 DexClassLoader 才有条目
    private val staticProxies: () -> List<Method> = DexJarLoader::proxyEntries,
) : ProxyHandler {

    // NanoHTTPD 多线程，必须并发集合。按 key 去重：一次播放会打进几十个 /proxy，逐条打会冲掉真正的错
    private val loggedAnomalies = ConcurrentHashMap.newKeySet<String>()

    @Suppress("UNCHECKED_CAST")
    override fun proxy(params: Map<String, String>): Array<Any?>? {
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
                    // 见类注释第 2 条：这里不回退，所以值得留日志 —— 从外面看它和"谁都没接手"一样
                    warnOnce(
                        "spider-declined:$siteKey",
                        "siteKey=$siteKey 的 spider.proxy() 返回 null（明确不处理这个请求），" +
                            "按参考实现不再回退到静态 Proxy",
                    )
                }
                return result
            }
        }

        // ⚠️ 必须直接 return（哪怕拿到 null）：改成"null 就继续试 jar"会让一个明确属于
        // JS 引擎的请求被某个 jar 抢走，它返回的东西形状可能完全不对
        if (params["do"] == "js") {
            val result = jsProxy?.invoke(params)
            if (result == null) {
                warnOnce(
                    "js-no-spider",
                    "do=js 的请求没有 JS 源接手（宿主没装 JS 引擎，或还没有任何 JS 源被加载过），" +
                        "按参考实现不再回退到 jar 静态 Proxy",
                )
            }
            return result
        }

        // 不带 siteKey 是多数 jar 发的形态（实测是 /proxy?do=m3u8&url=…）：
        // 最近用过的优先，返回非 null 就算命中
        val entries = staticProxies()
        if (entries.isEmpty()) {
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

    // Spider.proxy 声明 Any（非空），ProxyHandler 要 Any?；数组不变但擦除后同一个类型，
    // 这是一次零成本的类型放宽。不直接改 Spider.proxy 的声明：那是逐条核对的 ABI
    @Suppress("UNCHECKED_CAST")
    private fun Array<Any>?.widen(): Array<Any?>? = this as Array<Any?>?

    private companion object {
        const val TAG = "CatVodProxy"
    }
}
