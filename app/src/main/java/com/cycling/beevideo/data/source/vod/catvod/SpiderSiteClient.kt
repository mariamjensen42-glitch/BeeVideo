package com.cycling.beevideo.data.source.vod.catvod

import android.util.Log
import com.cycling.beevideo.domain.model.Vod
import com.cycling.beevideo.domain.model.VodPage
import com.github.catvod.crawler.Spider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * 任何由 `com.github.catvod.crawler.Spider` 驱动的站点 —— **jar 与 `.js` 的都在内**。
 * JS 引擎能零成本接进现有的缓存 / 派发 / 释放链路，靠的正是 `JsSpider` 继承了同一个基类。
 *
 * 只做两件事：调用 spider，把返回的 JSON 交给统一解析器（协议规定其返回值与内建
 * JSON 源同构，所以之后的路和 [JsonSiteClient] 完全一样）。
 *
 * ⚠️ [call] 里 catch 的是 `Throwable` 而不是 `Exception`：spider 是外部代码，
 * 抛 `Error` 也是常态，这一层是它与 App 之间唯一的闸门，漏出去一个就是一个崩溃。
 */
class SpiderSiteClient(
    override val site: SiteConfig,
    /** 本地代理按 `siteKey` 派发时要拿到它（见 `CatVodProxyDispatcher`）。 */
    internal val spider: Spider,
    flags: List<String>,
) : SiteClient {

    // 类型必须跟权威签名一致：原版是 playerContent(String, String, List<String>)，
    // 传 List<String?> 擦除后虽然一样，但会在读代码的人脑子里留下"这里可能有 null"的错觉
    private val vipFlags: List<String> = flags

    override suspend fun homeContent(): HomeContent = call("取首页内容") {
        // ⚠️ 首页是**两次**调用，非空则覆盖：只调 homeContent 的话，那些把轮播/精选
        // 单独放在 homeVideoContent 的站点会没有精选，且不报任何错
        val home = CatVodResponse.parseHome(spider.homeContent(true), site.key)

        // ⚠️ 与原版的一处不同：原版让 homeVideoContent() 的异常上抛，这里单独兜住。
        // 两次调用地位不对等 —— 分类在主契约里，推荐位只是补充，不该把首页搞成错误页
        val video = runCatching {
            CatVodResponse.parseVods(spider.homeVideoContent(), site.key, categoryId = "")
        }.onFailure {
            Log.w("CatVodJar", "站点「${site.name}」homeVideoContent 失败，退回只用 homeContent：$it")
        }.getOrDefault(emptyList())

        if (video.isEmpty()) home else home.copy(featured = video)
    }

    override suspend fun categoryContent(tid: String, page: Int): VodPage =
        call("取分类内容") {
            val json = spider.categoryContent(
                tid = tid,
                pg = page.toString(),
                filter = false,
                extend = HashMap(),
            )
            CatVodResponse.parsePage(json, site.key, tid)
        }

    override suspend fun detailContent(sourceId: String): Vod? = call("取详情") {
        CatVodResponse.parseDetail(spider.detailContent(listOf(sourceId)), site.key)
    }

    override suspend fun searchContent(keyword: String): List<Vod> {
        if (keyword.isBlank()) return emptyList()
        return call("搜索") {
            // ⚠️ 调**两参**重载，不是三参：原版是第一页走两参版，而绝大多数现存 jar
            // 实现的是两参版 —— 无脑调三参会让它们落到基类空实现上，搜索永远没结果且一声不响。
            // 将来要翻页时照原版：只有 page != "1" 才走三参版。
            //
            // quick 取配置里的 quickSearch，不写死 false：它是爬虫的语义开关，
            // 配置里标了 quickSearch: 1 就是想让它生效（恒传 false 等于把这个字段吞了）
            val json = spider.searchContent(keyword, site.quickSearch)
            CatVodResponse.parseVods(json, site.key, categoryId = "")
        }
    }

    override suspend fun playerContent(flag: String?, id: String): PlaySource? =
        call("取播放地址") {
            CatVodResponse.parsePlayer(spider.playerContent(flag, id, vipFlags))
        }

    /** 站点被移除 / 换配置时调用。实现 [SiteClient.close] 是为了让"释放"留在 seam 上。 */
    override fun close() {
        runCatching { spider.destroy() }
    }

    private suspend fun <T> call(what: String, block: () -> T): T = withContext(Dispatchers.IO) {
        try {
            block()
        } catch (e: CatVodException) {
            throw e
        } catch (e: Throwable) {
            throw CatVodException("站点「${site.name}」$what 失败：${e.message}", e)
        }
    }
}
