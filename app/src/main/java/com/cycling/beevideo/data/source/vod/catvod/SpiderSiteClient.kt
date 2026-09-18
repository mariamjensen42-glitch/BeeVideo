package com.cycling.beevideo.data.source.vod.catvod

import android.util.Log
import com.cycling.beevideo.domain.model.Vod
import com.github.catvod.crawler.Spider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * 任何由 `com.github.catvod.crawler.Spider` 驱动的站点 —— **jar 的与 `.js` 的都在内**。
 *
 * 名字里的 "jar" 曾经是误导：类诞生于 jar 的场景，但它服务的是**任何 Spider 子类**。
 * JS 引擎能"零成本"接进现有的缓存 / 派发 / 释放链路，靠的正是 `JsSpider` 继承了同一个基类。
 * 它只把 `spider` 当成一个接口用，所以叫什么名字的爬虫都一样。
 *
 * 这里的活儿只有两件 —— **调用 spider**，以及**把返回的 JSON 交给统一解析器**。
 * 没有第三件事：协议规定 spider 的返回值与内建 JSON 源同构，所以拿到 JSON
 * 之后的路和 [JsonSiteClient] 完全一样。
 *
 * ─── spider 抛出来的东西必须全接住 ────────────────────────────────────
 * spider 是**外部代码**，且来源五花八门 —— 抛 `NullPointerException`、
 * 抛 `ExceptionInInitializerError`、甚至抛 `Error` 都是常态。
 * 这一层是它们与 App 之间唯一的闸门，漏出去一个就是一个崩溃。
 * 所以 [call] 里 catch 的是 `Throwable` 而不是 `Exception`。
 */
class SpiderSiteClient(
    override val site: SiteConfig,
    /**
     * 本地代理服务按 `siteKey` 派发时要能拿到它 —— 见 `CatVodProxyDispatcher`。
     * 其余地方一律只经 [SiteClient] 的接口访问，不直接碰这个字段。
     */
    internal val spider: Spider,
    flags: List<String>,
) : SiteClient {

    /**
     * `playerContent` 的第三个参数是 `List<String>`（顶层配置的 `flags`）。
     *
     * 类型必须跟权威签名一致 —— 原版是 `playerContent(String, String, List<String>)`，
     * 传 `List<String?>` 在 JVM 上虽然擦除后一样，但 Kotlin 编译期就过不去，
     * 而且会在读代码的人脑子里留下"这里可能有 null"的错觉。
     */
    private val vipFlags: List<String> = flags

    override suspend fun homeContent(): HomeContent = call("取首页内容") {
        /*
         * 首页是**两次**调用，要合并 —— 原版 `SiteApi.homeContent` 就是这么做的：
         * ```java
         * Result result = Result.fromJson(spider.homeContent(true));          // 分类 + 列表
         * List<Vod> list = Result.fromJson(spider.homeVideoContent()).getList();  // 推荐位
         * if (!list.isEmpty()) result.setList(list);
         * ```
         *
         * 只调 `homeContent` 的话，那些把轮播/精选单独放在 `homeVideoContent` 的站点
         * 首页会**没有精选**，而且不报任何错 —— 表现是"这个源没有推荐"，
         * 但源其实有，只是没被问。
         *
         * ⚠️ 故意与原版有一处不同：原版对 `homeVideoContent()` 的异常是**放任上抛**的，
         * 这里单独 `runCatching` 兜住。理由是这两次调用的**地位不对等** ——
         * `homeContent` 是主契约（分类都在里面），`homeVideoContent` 只是补充的推荐位。
         * 让一个可选推荐接口把整个首页搞成错误页，是明显更差的失败模式。
         */
        val home = CatVodResponse.parseHome(spider.homeContent(true), site.key)

        val video = runCatching {
            CatVodResponse.parseVods(spider.homeVideoContent(), site.key, categoryId = "")
        }.onFailure {
            Log.w("CatVodJar", "站点「${site.name}」homeVideoContent 失败，退回只用 homeContent：$it")
        }.getOrDefault(emptyList())

        if (video.isEmpty()) home else home.copy(featured = video)
    }

    override suspend fun categoryContent(tid: String, page: Int): List<Vod> =
        call("取分类内容") {
            val json = spider.categoryContent(
                tid = tid,
                pg = page.toString(),
                filter = false,
                extend = HashMap(),
            )
            CatVodResponse.parseVods(json, site.key, tid)
        }

    override suspend fun detailContent(sourceId: String): Vod? = call("取详情") {
        CatVodResponse.parseDetail(spider.detailContent(listOf(sourceId)), site.key)
    }

    override suspend fun searchContent(keyword: String): List<Vod> {
        if (keyword.isBlank()) return emptyList()
        return call("搜索") {
            /*
             * 调**两参**重载，不是三参。
             *
             * 原版 `SiteApi.searchContent` 的调度逻辑是：
             * ```java
             * boolean hasPage = !page.equals("1");
             * String s = hasPage ? spider.searchContent(key, quick, page)
             *                    : spider.searchContent(key, quick);
             * ```
             * 也就是**第一页走两参版**。这不是随手写的：三参版是后来才加的分页重载，
             * 而官方 SPIDER.md 里记载的、绝大多数现存 jar 实现的是两参版。
             * 无脑调三参版的话，只实现两参版的爬虫会落到基类空实现上
             * —— 搜索永远没结果，且一声不响。
             *
             * 本项目搜索暂不分页，所以恒等价于原版 `page == "1"` 这条分支。
             * 将来要翻页时，记得照原版：只有 `page != "1"` 才走三参版。
             *
             * `quick` 取配置里的 `quickSearch`（`site.quickSearch`），不写死 false：
             * 它是爬虫的语义开关（快速搜索通常只查标题、不翻详情），配置里标了
             * `quickSearch: 1` 就是想让它生效。之前恒传 false 等于把这个字段吞了
             * —— 解析了、存下来了、没有任何地方读它。
             */
            val json = spider.searchContent(keyword, site.quickSearch)
            CatVodResponse.parseVods(json, site.key, categoryId = "")
        }
    }

    override suspend fun playerContent(flag: String?, id: String): PlaySource? =
        call("取播放地址") {
            CatVodResponse.parsePlayer(spider.playerContent(flag, id, vipFlags))
        }

    /**
     * 站点被移除 / 换配置时调用。spider 的 `destroy` 里通常会关连接池、停线程。
     *
     * 实现 [SiteClient.close] 而不是自己另起一个名字，是为了让"释放"这件事
     * **留在 seam 上** —— 否则调用方（工厂）就得知道具体实现是谁。
     */
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
