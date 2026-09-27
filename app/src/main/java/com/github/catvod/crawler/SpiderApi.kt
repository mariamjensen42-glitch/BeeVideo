package com.github.catvod.crawler

import com.google.gson.JsonArray

/**
 * 宿主向爬虫暴露的「本地代理 + 服务端解析」接口 —— **本项目自带的兼容层**。
 *
 * 它不在 FongMi/TV 那份 `catvod` 模块里，是**更新的 catvod** 才有的成员，而现实中的
 * csp jar 已经用上了：jar 会覆写 `Spider.initApi` 并在里面调 `super.initApi(api)`，
 * 之后拿这个 api 去问代理地址。宿主不提供就是 `NoSuchMethodError`。
 *
 * ⚠️ 必须是 **class**，不能是 interface：DEX 里的调用指令是 `invoke-virtual`（面向类方法），
 * 写成 interface 即使签名分毫不差，运行到调用点也会抛 `IncompatibleClassChangeError`。
 *
 * 实现程度：**只保证不崩，不保证可用** —— 拿地址/端口返回空串、解析返回空串。
 * 依赖本地代理取图的站点会失效，那是"没有代理服务"的直接后果。装上代理服务后，
 * 只需让宿主传一个真正的子类实例进 `initApi`，爬虫侧一行都不用改。
 */
open class SpiderApi {

    /** 本地代理的基地址，例如 `http://127.0.0.1:9978`。 */
    open fun getAddress(forceHttps: Boolean): String = ""

    open fun getPort(): String = ""

    open fun log(msg: String?) {
        SpiderDebug.log(msg)
    }

    /**
     * 批量并发请求。入参是 Gson 的 `JsonArray` —— 这是**签名事实**，
     * 实测 jar 引用的描述符就是 `(Lcom/google/gson/JsonArray;)Ljava/lang/String;`。
     */
    open fun multiReq(array: JsonArray?): String = ""

    /** 服务端解析能力（`webParse`）。 */
    open fun webParse(url: String?, flag: String?): String = ""

    companion object {

        /**
         * 传给爬虫的默认实例：什么都不做，但**不是 null**。
         *
         * ⚠️ 真实 jar 会把参数存进字段、之后取数据时使用。存到 null 的话失败点会在
         * **若干秒后的某次查询**里，表现为 `NullPointerException`，完全指不到"宿主没给 api"。
         */
        @JvmField
        val noop: SpiderApi = SpiderApi()
    }
}
