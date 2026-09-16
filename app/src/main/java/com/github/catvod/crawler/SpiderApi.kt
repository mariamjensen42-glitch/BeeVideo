package com.github.catvod.crawler

import com.google.gson.JsonArray

/**
 * 宿主向爬虫暴露的「本地代理 + 服务端解析」接口 —— **本项目自带的兼容层**。
 *
 * ─── 这个类为什么存在 ────────────────────────────────────────────────
 * 它**不在** FongMi/TV 那份 `catvod` 模块里（那份只有 Spider / SpiderDebug /
 * SpiderNull）。它是**更新的 catvod** 才有的成员，而现实中的 csp jar 已经用上了：
 *
 * ```
 * invoke-super {v2, v3}, Lcom/github/catvod/crawler/Spider;.initApi:(Lcom/github/catvod/crawler/SpiderApi;)V
 * invoke-virtual {v3, v0}, Lcom/github/catvod/crawler/SpiderApi;.log:(Ljava/lang/String;)V
 * invoke-virtual {v9, v4}, Lcom/github/catvod/crawler/SpiderApi;.getAddress:(Z)Ljava/lang/String;
 * ```
 *
 * 也就是说 jar 会覆写 `Spider.initApi`、并在里面调 `super.initApi(api)`，
 * 之后拿着这个 api 去问代理地址。宿主不提供这两个类就是 `NoSuchMethodError` /
 * `NoClassDefFoundError`。
 *
 * ─── ⚠️ 必须是 **class**，不能是 interface ────────────────────────────
 * DEX 里的调用指令是 `invoke-virtual`（面向类方法），不是 `invoke-interface`。
 * 把它写成 Kotlin 的 `interface`，即使方法签名分毫不差，
 * 运行到调用点也会抛 `IncompatibleClassChangeError`。
 *
 * ─── 本项目的实现程度：**只保证不崩，不保证可用** ─────────────────────
 * 这几个方法背后的东西是「本地 HTTP 代理服务」和「服务端解析（VIP 解析）」——
 * 本项目没有实现（见 `Spider.proxy` 的说明）。所以：
 *   - 拿地址/端口 → 返回空串（= 没有代理）
 *   - 解析/批量请求 → 返回空串
 *   - 日志 → 转给 [SpiderDebug]
 *
 * **代价说清楚**：依赖本地代理取图、取播放地址的那部分站点会失效。
 * 这是"没有代理服务"的直接后果，不是这里的 bug。装上代理服务之后，
 * 只要让宿主持有一个真正的 [SpiderApi] 子类实例传进 `initApi` 即可，
 * 爬虫侧一行都不用改 —— 这正是把签名钉准的价值。
 */
open class SpiderApi {

    /**
     * 本地代理的基地址，例如 `http://127.0.0.1:9978`。
     *
     * @param forceHttps 爬虫希望强制 https 时传 true。真实实现里这会影响
     *                   返回的 scheme 与是否附加证书信息。
     */
    open fun getAddress(forceHttps: Boolean): String = ""

    /** 本地代理端口（字符串形态）。 */
    open fun getPort(): String = ""

    open fun log(msg: String?) {
        SpiderDebug.log(msg)
    }

    /**
     * 批量请求：一次把多个地址交给宿主并发取回，省掉爬虫自己起线程池。
     *
     * 入参是 Gson 的 `JsonArray`（这是个**签名事实**，不是选择 ——
     * 实测 jar 引用的描述符就是 `(Lcom/google/gson/JsonArray;)Ljava/lang/String;`）。
     */
    open fun multiReq(array: JsonArray?): String = ""

    /**
     * 交给宿主的服务端解析能力（`webParse`）。
     *
     * @param url  待解析的页面 / 播放地址
     * @param flag 解析线路标识
     */
    open fun webParse(url: String?, flag: String?): String = ""

    companion object {

        /**
         * 传给爬虫的默认实例：什么都不做，但**不是 null**。
         *
         * 为什么坚持给一个实例而不是干脆不调 `initApi`：真实 jar 会把参数存进字段，
         * 之后在取数据时使用。存到 null 的话，失败点在**若干秒后的某次查询**里，
         * 表现为 `NullPointerException`，完全指不到"宿主没给 api"；给一个空实现，
         * 至少失败点还在同一处、且行为可预期（拿到空地址 → 请求失败 → 报错可读）。
         */
        @JvmField
        val noop: SpiderApi = SpiderApi()
    }
}
