package com.cycling.beevideo.data.source.vod.catvod

import android.util.Log
import com.github.catvod.crawler.Spider
import com.github.catvod.crawler.SpiderApi

/**
 * 把一个**已造好的** [Spider] 装成可用的站点客户端。
 *
 * 存在的理由：jar 与 `.js` 两条装配链以前各写了一遍「赋 `siteKey` → `init` → `initApi`」，
 * 而这三步的**顺序是承重的**。留两个副本就是其中一份将来静默漂移的方式，表现是
 * 「站点能加载、结果永远为空、不报任何错」。现在只写在这里，两条链只差"怎么造 spider"。
 *
 * 故意不认识 `Context`（`init` 那一步由工厂关进 lambda）：这样顺序契约能在纯 JVM
 * 单测里钉住（`SpiderHostTest` 用假 spider 记录调用顺序）。
 */
internal class SpiderHost(
    private val spiderApi: SpiderApi,
    /** 本地代理是否已在监听。没起来时给 `SpiderApi.noop`。 */
    private val proxyReady: () -> Boolean,
    /**
     * `spider.init(context, ext)`。
     *
     * ⚠️ 必须是**两参版本** —— 真实 jar 覆写的就是这一个。只调一参版的话，
     * 真实爬虫的覆写会变成没人调用的普通方法：站点照常加载、结果永远为空、不报错。
     */
    private val initSpider: (spider: Spider, ext: String) -> Unit,
) {

    /** @param logTag `initApi` 失败时用的日志 tag（`CatVodJar` / `CatVodJs`）。 */
    fun host(site: SiteConfig, spider: Spider, flags: List<String>, logTag: String): SiteClient {
        // ⚠️ siteKey 必须在 init **之前**赋值：一个 jar 里的同一个类被配成十几个站点是
        // 常态，爬虫靠 siteKey 区分这次该用哪套 ext / header，init 里已经把状态定下来了
        spider.siteKey = site.key

        runCatching { initSpider(spider, site.ext) }
            .onFailure { throw CatVodException("站点「${site.name}」初始化失败：${it.message}", it) }

        // 可选钩子：传的是真实现，只有代理**没起来**（端口全被占）才退回空实现 ——
        // 那时给一个空地址，失败点在同一处且可预期；给 null 的话失败点会跑到若干秒后的
        // 某次查询里，表现成 NullPointerException，完全指不到这里。失败不阻断站点。
        runCatching { spider.initApi(if (proxyReady()) spiderApi else SpiderApi.noop) }
            .onFailure { Log.w(logTag, "站点「${site.name}」initApi 失败：$it") }

        return SpiderSiteClient(site, spider, flags)
    }
}
