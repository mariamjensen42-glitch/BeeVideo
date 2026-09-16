package com.cycling.beevideo.data.source.vod.catvod

import com.cycling.beevideo.domain.model.Category
import com.cycling.beevideo.domain.model.Vod

/**
 * type=1：HTTP JSON 接口，事实标准是 MacCMS（苹果 CMS v10）的 `ac=` 接口族。
 *
 * 流程（URL 构造、请求、播放地址规则）全在 [HttpSiteClient] 里，这里只负责
 * 把 JSON 文本拆成模型。
 *
 * ─── 播放地址**不**经二次请求 ────────────────────────────────────────
 * MacCMS 的 `ac=detail` 响应里 `vod_play_url` 已经是可播地址，所以传进来的
 * 剧集 `id` 本身就是 URL，直接返回。
 */
class JsonSiteClient(site: SiteConfig) : HttpSiteClient(site) {

    override fun parseHome(body: String): HomeContent =
        CatVodResponse.parseHome(body, site.key)

    override fun parseVods(body: String, categoryId: String): List<Vod> =
        CatVodResponse.parseVods(body, site.key, categoryId)

    override fun parseDetail(body: String): Vod? =
        CatVodResponse.parseDetail(body, site.key)
}
