package com.cycling.beevideo.data.source.vod.catvod

import com.cycling.beevideo.domain.model.Category
import com.cycling.beevideo.domain.model.Vod
import kotlinx.coroutines.runBlocking
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * HTTP 站点客户端的 **URL 构造契约**。
 *
 * ─── 为什么这一层最该有测试 ───────────────────────────────────────────
 * `HttpSiteClient` 里有三块逻辑，只有中间这块是"纯我们自己的决定"：
 *
 *   1. 解析响应 —— 有 `CatVodConfigParserTest` 那一脉的覆盖；
 *   2. **构造 URL** —— 没有覆盖，直到这份文件出现；
 *   3. 发网络 —— 不该在单测里测。
 *
 * 第 2 块是这三块里**唯一出错后不会报错**的：少发一个 `wd`、多发一个 `ac`、
 * 或者把 `ext` 当成 `extend` 又发了一遍，服务端要么返回一个空列表、要么返回
 * 一份不认识的数据，界面上的表现都是"这个源没有内容"。而它又是纯函数，
 * 没有理由靠真机一个源一个源地试。
 *
 * 用的是 [HttpSiteClient.fetch] 这个出口，所以整条
 * 「操作 → 参数 → URL → 解析 → 封面回填」都真的跑了一遍，**不发一个请求**。
 *
 * ─── ⚠️ 断言里写下的分歧，都是有意的 ─────────────────────────────────
 * 有几处我们和参考实现（FongMi `SiteApi`）**故意不一样**，逐一标在用例上 ——
 * 它们不是 typo，改之前先看 `docs/coverage-gaps.md` 第 1 节。
 */
class HttpSiteClientTest {

    // ------------------------------------------------------------ 首页 / 分类

    @Test
    fun `首页发 ac=list 且保留 api 里原有的鉴权参数`() = runBlocking {
        val client = client(api = "$BASE?token=abc")
        client.homeContent()

        val url = client.url()
        assertEquals(listOf("abc"), url.query()["token"])
        assertEquals(listOf("list"), url.query()["ac"])
        // 总数也要断言：只查 `ac` 的话，"ac 被加了两次"这种错抓不到
        assertEquals(2, url.querySize())
    }

    /**
     * `api` 里已经写死了 `ac=list&t=9&pg=1` 的真实形态（模板型配置）。
     *
     * 必须**先删再放**：否则 URL 上会同时出现两个 `ac`，而服务端只读第一个 ——
     * 症状是"参数改了但行为没变"，极难定位。
     */
    @Test
    fun `api 里原有的语义参数先删再放，不重复`() = runBlocking {
        val client = client(api = "$BASE?ac=list&t=9&pg=1&token=abc")
        client.categoryContent(tid = "5", page = 2)

        val url = client.url()
        assertEquals(listOf("videolist"), url.query()["ac"])
        assertEquals(listOf("5"), url.query()["t"])
        assertEquals(listOf("2"), url.query()["pg"])
        assertEquals(listOf("abc"), url.query()["token"])
        assertEquals(4, url.querySize())
    }

    @Test
    fun `分类发 ac=videolist 带 t 与 pg`() = runBlocking {
        val client = client()
        client.categoryContent(tid = "movie", page = 3)

        val url = client.url()
        assertEquals(listOf("videolist"), url.query()["ac"])
        assertEquals(listOf("movie"), url.query()["t"])
        assertEquals(listOf("3"), url.query()["pg"])
        assertEquals(3, url.querySize())
    }

    @Test
    fun `详情发 ac=detail 带 ids`() = runBlocking {
        val client = client()
        client.detailContent("/vod-detail-id-95012.html")

        val url = client.url()
        assertEquals(listOf("detail"), url.query()["ac"])
        assertEquals(listOf("/vod-detail-id-95012.html"), url.query()["ids"])
        assertEquals(2, url.querySize())
    }

    // ---------------------------------------------------------------- 搜索

    /**
     * ⚠️ **有意与参考实现分歧**：参考 `SiteApi.searchContent` 走 HTTP 时一个
     * `ac` 都不发，只发 `wd` / `quick` / `extend` / `pg`。
     *
     * 我们显式发 `ac=videolist`，与 [HttpSiteClient.categoryContent] 一致
     * （`videolist` 返回完整字段集）。两种写法理论上都成立，但没有真实
     * type=0/1 站点上的对照实验 —— 见 `docs/coverage-gaps.md` 第 1 节。
     * **要改先做对照实验，别凭"参考没发"就删掉这条断言。**
     */
    @Test
    fun `搜索发 ac=videolist + wd + quick`() = runBlocking {
        val client = client(quickSearch = true)
        client.searchContent("庆余年")

        val url = client.url()
        assertEquals(listOf("videolist"), url.query()["ac"])
        assertEquals(listOf("庆余年"), url.query()["wd"])
        assertEquals(listOf("true"), url.query()["quick"])
        assertEquals(3, url.querySize())
    }

    /**
     * `quickSearch` 曾经被解析出来却**从不使用**（jar 路径恒传 `false`，
     * HTTP 路径干脆不发 `quick`）—— 配了 `quickSearch: 1` 的源拿不到任何
     * 快速搜索语义。这条用例就是那次修复的回归锁。
     */
    @Test
    fun `quickSearch 为假时发 quick=false`() = runBlocking {
        val client = client(quickSearch = false)
        client.searchContent("庆余年")

        assertEquals(listOf("false"), client.url().query()["quick"])
    }

    @Test
    fun `空关键词不发请求`() = runBlocking {
        val client = client()
        assertTrue(client.searchContent("   ").isEmpty())
        assertTrue(client.urls.isEmpty())
    }

    // ---------------------------------------------------------------- ext

    /** `ext` 是 token / 过滤串时，按参考实现**每次**请求都带 `extend`。 */
    @Test
    fun `opaque ext 作为 extend 透传`() = runBlocking {
        val client = client(ext = "noproxy")
        client.categoryContent(tid = "1", page = 1)

        assertEquals(listOf("noproxy"), client.url().query()["extend"])
    }

    /**
     * ⚠️ **有意与参考实现分歧**：参考 `SiteApi.call` 无条件把 `ext` 塞进 `extend`。
     *
     * 而 `ext` 是 `{"class":[…]}` 时，那份分类表已经被**本地**消费掉了
     * （见 [parseCategoriesFromExt]），再发给站点就是喂错东西。
     *
     * 「能解析成分类映射的就不透传」—— 这是本项目自己加的一条规则，
     * 没有真站对照。下面两条用例把它钉死在两个方向上。
     */
    @Test
    fun `ext 是分类映射时不透传 extend`() = runBlocking {
        val client = client(ext = """{"class":[{"type_id":"1","type_name":"电影"}]}""")
        client.categoryContent(tid = "1", page = 1)

        assertNull(client.url().query()["extend"])
    }

    /** `{"1":"电影"}` 这种键值对形态同样是分类映射，同样不透传。 */
    @Test
    fun `ext 是键值对分类映射时不透传 extend`() = runBlocking {
        val client = client(ext = """{"1":"电影","2":"剧集"}""")
        client.categoryContent(tid = "1", page = 1)

        assertNull(client.url().query()["extend"])
    }

    /**
     * 能解析成分类映射时，首页分类**以 ext 为准**，不看响应里的 `class`。
     *
     * 这是 `ext` 被当分类映射用的全部意义：有些源（尤其中转/聚合）的 `ac=list`
     * 根本不吐 `class`，只靠响应就一个分类都没有。
     */
    @Test
    fun `ext 分类映射覆盖响应里的分类`() = runBlocking {
        val client = client(ext = """{"1":"电影"}""")
        client.home = HomeContent(
            categories = listOf(Category(id = "99", name = "来自响应")),
            featured = emptyList(),
        )

        val home = client.homeContent()

        assertEquals(listOf(Category(id = "1", name = "电影")), home.categories)
    }

    /** 解析不出分类时用响应里的。 */
    @Test
    fun `ext 没有分类时用响应里的分类`() = runBlocking {
        val client = client(ext = "")
        client.home = HomeContent(
            categories = listOf(Category(id = "1", name = "电影")),
            featured = emptyList(),
        )

        assertEquals(listOf(Category(id = "1", name = "电影")), client.homeContent().categories)
    }

    // ------------------------------------------------------------ 封面回填

    /**
     * MacCMS 的 `ac=list` 查询字段是写死的精简集，**没有 `vod_pic`** ——
     * 首页最需要的恰恰就是封面。所以缺封面时要补一次 `ac=detail&ids=…`。
     *
     * `ids` 走的是**源内 id**（`splitVodId` 的右半边），不是全局 id。
     */
    @Test
    fun `首页缺封面时补一次 ac=detail 批量取`() = runBlocking {
        val client = client()
        client.home = HomeContent(
            categories = emptyList(),
            featured = listOf(vod("site:/vod/1.html"), vod("site:/vod/2.html")),
        )
        /*
         * ⚠️ 队列里**只有一项**。
         *
         * 首页的主列表来自 `parseHome`（它直接返回 `home`，**不消耗**队列），
         * 所以 `parseVods` 在首页这条路径上只会被调用一次 —— 就是封面回填那一次。
         * 早先这里写的是 `listOf(emptyList(), listOf(带图的…))`（按"第 1 次主列表、
         * 第 2 次回填"的直觉），结果回填拿到的是那个占位的空列表，
         * 覆盖回填的断言全落空。
         */
        client.vodsQueue = listOf(
            listOf(
                vod("site:/vod/1.html", pic = "https://img/1.jpg"),
                vod("site:/vod/2.html", pic = "https://img/2.jpg"),
            ),
        )

        val home = client.homeContent()

        assertEquals("主请求 + 回填请求", 2, client.urls.size)
        val backfill = client.urls[1].query()
        assertEquals(listOf("detail"), backfill["ac"])
        assertEquals(listOf("/vod/1.html,/vod/2.html"), backfill["ids"])
        assertEquals("https://img/1.jpg", home.featured[0].pic)
    }

    /** 已经有封面就**不再多打一次请求** —— 这条很容易被顺手改掉。 */
    @Test
    fun `首页已有封面时不再补全`() = runBlocking {
        val client = client()
        client.home = HomeContent(
            categories = emptyList(),
            featured = listOf(vod("site:/vod/1.html", pic = "https://img/1.jpg")),
        )

        client.homeContent()

        assertEquals(1, client.urls.size)
    }

    /**
     * 判据是"**任意一条**有封面就认为这个源不砍字段"。
     *
     * ⚠️ 这意味着混排（有的有图有的没图）时不会补全，那几条没图的就一直没图。
     * 这是**有意的**：MacCMS 的字段集是写死的，一个源要么全有要么全无；
     * 为混排去补一次请求，是拿每次首页加载的延迟换一个不该出现的场景。
     * 这条用例把它记成"已知取舍"，而不是让它在将来被当成 bug 顺手改掉。
     */
    @Test
    fun `只要有一条带封面就不补全（已知取舍）`() = runBlocking {
        val client = client()
        client.home = HomeContent(
            categories = emptyList(),
            featured = listOf(
                vod("site:/vod/1.html", pic = "https://img/1.jpg"),
                vod("site:/vod/2.html", pic = ""),
            ),
        )

        client.homeContent()

        assertEquals(1, client.urls.size)
    }

    /**
     * ⚠️ 搜索**必须**和首页一样补封面。
     *
     * 这条曾经是缺失的：同一个源"首页有图、搜索没图"，两个页面看起来像两个
     * 不相干的问题。修法就是在 `searchContent` 的返回上套同一个 `withFullFields`。
     */
    @Test
    fun `搜索同样补封面`() = runBlocking {
        val client = client()
        client.vodsQueue = listOf(
            listOf(vod("site:/vod/1.html")),
            listOf(vod("site:/vod/1.html", pic = "https://img/1.jpg")),
        )

        val vods = client.searchContent("庆余年")

        assertEquals(2, client.urls.size)
        assertEquals(listOf("detail"), client.urls[1].query()["ac"])
        assertEquals("https://img/1.jpg", vods[0].pic)
    }

    /**
     * 回填失败（超时 / 站点不支持多 id）**不能连坐** —— 顶多是没有封面，
     * 而"没有封面"本来就是被设计过的路径（`PosterCard` 有渐变占位底）。
     */
    @Test
    fun `回填抛异常时原样返回主列表`() = runBlocking {
        val client = client()
        client.home = HomeContent(
            categories = emptyList(),
            featured = listOf(vod("site:/vod/1.html", name = "原名")),
        )
        client.failOnBackfill = true

        val home = client.homeContent()

        assertEquals(1, home.featured.size)
        assertEquals("原名", home.featured[0].name)
    }

    /**
     * 回填**逐字段**回填而不是整条替换。
     *
     * `categoryId` 是调用方（分类页传入的 `tid`）指定的，而 detail 响应里
     * 那个位置是 `type_id` —— 整条替换会把它改掉，后果是详情页返回后
     * 列表的选中分类被复位。
     */
    @Test
    fun `回填保留调用方给的 categoryId`() = runBlocking {
        val client = client()
        client.vodsQueue = listOf(
            listOf(vod("site:/vod/1.html", categoryId = "movie")),
            listOf(vod("site:/vod/1.html", categoryId = "1", pic = "https://img/1.jpg")),
        )

        val vods = client.categoryContent(tid = "movie", page = 1)

        assertEquals("movie", vods[0].categoryId)
        assertEquals("https://img/1.jpg", vods[0].pic)
    }

    /** 回填最多带 [MAX_BACKFILL] 个 id —— 首页一屏根本展示不了更多。 */
    @Test
    fun `回填最多带 40 个 id`() = runBlocking {
        val client = client()
        client.home = HomeContent(
            categories = emptyList(),
            featured = (1..60).map { vod("site:/vod/$it.html") },
        )

        client.homeContent()

        val ids = client.urls[1].query()["ids"]!!.single().split(",")
        assertEquals(40, ids.size)
    }

    /** 没有可回填的 id（id 不含 `:`）时不该白发一次请求。 */
    @Test
    fun `id 反解不出源内 id 时不回填`() = runBlocking {
        val client = client()
        client.home = HomeContent(
            categories = emptyList(),
            featured = listOf(vod("没有冒号的id")),
        )

        client.homeContent()

        assertEquals(1, client.urls.size)
    }

    // ------------------------------------------------------------------ 辅助

    private fun FakeHttpClient.url(): String = urls.last()

    private fun String.query(): Map<String, List<String>> = toHttpUrl().let { url ->
        // ⚠️ `queryParameterValues` 在 OkHttp 4.12 的 Kotlin 签名里是
        // `List<String?>?`（还有"参数不存在"这一路 null）。直接交给
        // `associateWith` 会得到 `Map<String, List<String?>?>`，和这里的
        // 返回类型对不上 —— 而报错是 "Return type mismatch: expected
        // 'List<String>', actual 'List<String?>'"，看着像哪里传错了值，
        // 其实是 OkHttp 的空值标注。过滤掉即可。
        url.queryParameterNames.associateWith { name ->
            url.queryParameterValues(name).orEmpty().filterNotNull()
        }
    }

    private fun String.querySize(): Int = toHttpUrl().querySize

    private fun client(
        api: String = BASE,
        ext: String = "",
        quickSearch: Boolean = false,
    ) = FakeHttpClient(
        SiteConfig(
            key = SITE_KEY,
            name = "测试源",
            type = SiteType.JSON,
            api = api,
            ext = ext,
            jar = "",
            searchable = true,
            quickSearch = quickSearch,
            categories = emptyList(),
        )
    )

    private fun vod(
        id: String,
        pic: String = "",
        name: String = "片名",
        categoryId: String = "",
    ) = Vod(
        id = id,
        name = name,
        categoryId = categoryId,
        year = "",
        area = "",
        genre = "",
        score = "",
        remarks = "",
        director = "",
        actors = "",
        intro = "",
        pic = pic,
        lines = emptyList(),
    )

    private companion object {
        const val SITE_KEY = "site"
        const val BASE = "http://127.0.0.1:18080/api.php/provide/vod/"
    }
}

/**
 * 假的客户端：把唯一一个网络出口换成"记账 + 按次序喂数据"。
 *
 * 三个解析方法都不真解析 —— 本文件测的是 **URL 怎么造**，
 * 解析的正确性由 `CatVodConfigParserTest` 那一脉负责。
 */
private class FakeHttpClient(site: SiteConfig) : HttpSiteClient(site) {

    val urls = mutableListOf<String>()

    var home: HomeContent = HomeContent.Empty

    /** `parseVods` 的返回值队列，**按调用次序**取（每次调用取一项，取完给空表）。 */
    var vodsQueue: List<List<Vod>> = emptyList()

    /** 模拟回填请求失败。 */
    var failOnBackfill = false

    private var vodsIndex = 0

    override suspend fun fetch(url: String): String {
        // 第 2 次取正文必然是回填（见 withFullFields 的调用点）
        if (failOnBackfill && urls.isNotEmpty()) throw IllegalStateException("回填失败")
        urls += url
        return "{}"
    }

    override fun parseHome(body: String): HomeContent = home

    override fun parseVods(body: String, categoryId: String): List<Vod> =
        vodsQueue.getOrElse(vodsIndex++) { emptyList() }

    override fun parseDetail(body: String): Vod? = null
}
