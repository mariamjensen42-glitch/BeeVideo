package com.cycling.beevideo.data.source.vod.catvod

import com.cycling.beevideo.domain.model.Category
import com.cycling.beevideo.domain.model.Vod

/**
 * 一个「可查询的内容站点」。
 *
 * 这是 CatVod 三种站点类型（JSON / XML / jar）在本项目里的统一抽象：
 * 上游拿到的东西完全一样，差别只在"怎么把响应搞到手"。
 * 上层（仓储、UI）只认这个接口，不知道底下是 HTTP 还是反射调 jar。
 */
interface SiteClient {

    val site: SiteConfig

    /** 站点首页：分类 + 推荐内容（对应 CatVod 的 `homeContent`） */
    suspend fun homeContent(): HomeContent

    /** 按分类取列表，page 从 1 开始 */
    suspend fun categoryContent(tid: String, page: Int): List<Vod>

    /** 取详情。`sourceId` 是**源内的** id（不带站点前缀） */
    suspend fun detailContent(sourceId: String): Vod?

    /** 搜索 */
    suspend fun searchContent(keyword: String): List<Vod>

    /**
     * 取播放地址。
     *
     * @param flag 线路名（CatVod 里叫 flag，就是 `vod_play_from` 里的那一段）
     * @param id   剧集的播放标识 —— **不一定是 URL**。很多源这里放的是一个内部 id，
     *             真实地址要由站点服务端二次兑换，所以这一步不能省。
     */
    suspend fun playerContent(flag: String?, id: String): PlaySource?

    /**
     * 释放这个客户端持有的资源（spider 的内部线程、连接池、jar 的 ClassLoader）。
     *
     * 有默认空实现：JSON / XML 源是无状态的，没什么可释放。
     *
     * ⚠️ 这条以前**不在接口上** —— 释放靠工厂把缓存里的值向下转型成具体实现再调
     * `destroy()`。也就是说 `SiteClient` 这个 seam 上根本没有"释放"这个能力，
     * 而 seam 上那个声明了 `release()` 的 `SiteClientHandle` 接口
     * **零实现、零调用**。接回接口之后，工厂不必再知道具体实现是谁。
     */
    fun close() {}
}
