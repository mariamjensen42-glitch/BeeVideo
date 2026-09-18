package com.cycling.beevideo.data.source.vod.catvod

import com.cycling.beevideo.data.proxy.LocalProxyServer
import com.github.catvod.Proxy
import com.github.catvod.crawler.SpiderApi

/**
 * 宿主写给爬虫的**真实**能力对象 —— [SpiderApi] 的实现版。
 *
 * 之前传给爬虫的是 [SpiderApi.noop]（`getAddress` 返回空串），结果是：
 * 依赖本地代理取流、取图的站点的 `ProxyOrigin` 拼不出有效地址，
 * 播放页报 `MalformedURLException: invalid port: -1`。
 *
 * ─── 只在 `getAddress` / `getPort` 上做实事 ──────────────────────────
 * 这两个是「本地代理」这一件事的两种问法（要完整地址 / 只要端口），
 * 而本地代理服务现在真的存在了，所以这里就该返回真值。
 *
 * [multiReq]（宿主代爬虫并发取多个地址）与 [webParse]（服务端 VIP 解析）
 * **仍然是空实现**：前者需要一套明确的"输入 JSON 数组、输出什么形状"的约定，
 * 后者需要一整套解析服务。两件都不在本轮范围，且空实现的行为是可预期的
 * （返回空串 → 爬虫自己走自己的路），不会像 `-1` 端口那样把整条路堵死。
 * 真要支持时在这里覆写即可，爬虫侧一行不用改 —— 这正是把签名钉准的价值。
 */
class ServerSpiderApi : SpiderApi() {

    /**
     * @param forceHttps 爬虫想要 https 时传 true。本项目**不做** https：那需要
     *   自签证书 + 把证书塞进系统信任链，而调用方（同机的 ExoPlayer）走
     *   `127.0.0.1` 明文没有任何问题。所以这里忽略它，恒返回 http 地址。
     *   忽略而不是随便返回个 https 地址 —— 后者会让播放器去连一个不存在的
     *   TLS 服务，是更难查的错。
     */
    override fun getAddress(forceHttps: Boolean): String = LocalProxyServer.address()

    override fun getPort(): String = Proxy.getPort().toString()
}
