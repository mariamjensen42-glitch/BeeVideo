package com.cycling.beevideo.data.source.vod.catvod

import com.github.catvod.crawler.Spider
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * `JarSiteClient` 对 spider 的**调用约定**测试。
 *
 * ─── 为什么这些用例值得存在 ────────────────────────────────────────────
 * 这几条全部是"调错了也不报错、只是结果不对"的类型：
 *
 *  - 搜索调三参版而不是两参版 → 只实现两参版的爬虫落到基类空实现
 *    → **搜索永远没结果，一声不响**；
 *  - 首页只调 `homeContent` 不调 `homeVideoContent` → 那些把推荐位单独放的站点
 *    → **首页没有精选，看起来像"这个源没内容"**。
 *
 * 它们不会抛异常、不会崩，只会让用户觉得"这个源不好用"。
 * 靠人工在真机上一页页翻是发现不了的，只能靠这种把断言钉在**调用行为**上的测试。
 *
 * 测试用的是 [Spider] 的假实现（真实 jar 里那些爬虫的替身），
 * 记录"宿主到底调了哪个重载"，与爬虫内部实现无关。
 */
class JarSiteClientTest {

    /** 记录调用痕迹的假爬虫。只覆写我们关心的那几个方法。 */
    private class FakeSpider(
        private val home: String = "",
        private val homeVideo: String = "",
        private val throwOnHomeVideo: Boolean = false,
        private val searchTwoArg: String = "",
        private val searchThreeArg: String = "",
    ) : Spider() {

        var searchTwoArgCalls = 0
        var searchThreeArgCalls = 0
        var homeVideoCalls = 0

        override fun homeContent(filter: Boolean): String = home

        override fun homeVideoContent(): String {
            homeVideoCalls++
            if (throwOnHomeVideo) error("推荐位接口炸了")
            return homeVideo
        }

        override fun searchContent(key: String, quick: Boolean): String {
            searchTwoArgCalls++
            return searchTwoArg
        }

        override fun searchContent(key: String, quick: Boolean, pg: String): String {
            searchThreeArgCalls++
            return searchThreeArg
        }
    }

    private fun site() = SiteConfig(
        key = "test_key",
        name = "测试源",
        type = SiteType.SPIDER,
        api = "csp_Fake",
        ext = "",
        jar = "",
        searchable = true,
        quickSearch = false,
        categories = emptyList(),
    )

    /** 拼一个 `{"list":[{"vod_id":"…","vod_name":"…"}]}`。 */
    private fun vodList(vararg ids: String): String {
        val items = ids.joinToString(",") { "{\"vod_id\":\"$it\",\"vod_name\":\"片$it\"}" }
        return "{\"list\":[$items]}"
    }

    /** 拼一个带分类的首页响应。 */
    private fun homeJson(listItems: String): String =
        "{\"class\":[{\"type_id\":\"1\",\"type_name\":\"电影\"}],\"list\":[$listItems]}"

    private fun client(spider: Spider) = JarSiteClient(site(), spider, emptyList())

    /**
     * 解析出来的 id 是**带站点前缀**的（`test_key:h1`）。
     *
     * 这不是笔误：不同源里的 `vod_id` 大量重复（很多都是 "1"），
     * 不隔离的话跨站点跳转会串数据（见 `CatVodResponse.parseVods` 的说明）。
     * 断言写全名，顺便把这条约定钉住。
     */
    private fun ns(rawId: String) = "test_key:$rawId"

    // ── 搜索走哪个重载 ────────────────────────────────────────────────

    @Test
    fun `搜索调用两参重载而不是三参重载`() = runBlocking {
        val spider = FakeSpider(
            searchTwoArg = vodList("2arg"),
            searchThreeArg = vodList("3arg"),
        )

        val result = client(spider).searchContent("关键字")

        assertEquals("两参 searchContent 应该被调用一次", 1, spider.searchTwoArgCalls)
        assertEquals(
            "三参 searchContent 不该被调用（原版只在 page != 1 时才走它）",
            0,
            spider.searchThreeArgCalls,
        )
        // 结果必须来自两参那一份，否则就是"调对了但吃错了"
        assertEquals(listOf(ns("2arg")), result.map { it.id })
    }

    @Test
    fun `搜索关键字为空白时不去打扰爬虫`() = runBlocking {
        val spider = FakeSpider(searchTwoArg = vodList("x"))

        val result = client(spider).searchContent("   ")

        assertTrue(result.isEmpty())
        assertEquals(0, spider.searchTwoArgCalls)
        assertEquals(0, spider.searchThreeArgCalls)
    }

    // ── 首页两次调用与合并 ────────────────────────────────────────────

    @Test
    fun `首页会用 homeVideoContent 的结果覆盖推荐位`() = runBlocking {
        val spider = FakeSpider(
            home = homeJson(""),
            homeVideo = vodList("v1", "v2"),
        )

        val home = client(spider).homeContent()

        assertEquals(1, home.categories.size)
        assertEquals(
            "首页推荐位应该来自 homeVideoContent",
            listOf(ns("v1"), ns("v2")),
            home.featured.map { it.id },
        )
        assertEquals("homeVideoContent 必须被调用到", 1, spider.homeVideoCalls)
    }

    @Test
    fun `homeVideoContent 为空时沿用 homeContent 自带的列表`() = runBlocking {
        val spider = FakeSpider(
            home = homeJson("{\"vod_id\":\"h1\",\"vod_name\":\"首页片\"}"),
            homeVideo = "",
        )

        val home = client(spider).homeContent()

        assertEquals(
            "推荐位为空不该把首页列表清空",
            listOf(ns("h1")),
            home.featured.map { it.id },
        )
    }

    @Test
    fun `homeVideoContent 抛异常不该让整个首页失败`() = runBlocking {
        val spider = FakeSpider(
            home = homeJson("{\"vod_id\":\"h1\",\"vod_name\":\"首页片\"}"),
            throwOnHomeVideo = true,
        )

        val home = client(spider).homeContent()

        // 主契约（分类 + 列表）必须完好 —— 推荐位是补充，不该毁掉首页
        assertEquals(1, home.categories.size)
        assertEquals(listOf(ns("h1")), home.featured.map { it.id })
    }

    // ── 基类默认值 ───────────────────────────────────────────────────

    /**
     * 权威 `Spider.java` 的默认实现返回的是**空串**，不是 `"{}"`。
     * 宿主解析器必须把空串当"空结果"，不能当成 JSON 解析失败。
     */
    @Test
    fun `基类默认返回空串且解析器能容忍`() = runBlocking {
        val bare = object : Spider() {}

        assertEquals("", bare.homeContent(true))
        assertEquals("", bare.homeVideoContent())
        assertEquals("", bare.searchContent("k", false))
        assertEquals("", bare.searchContent("k", false, "1"))

        val home = client(bare).homeContent()
        assertTrue("空响应应解析为空列表而不是抛异常", home.categories.isEmpty())
        assertTrue(home.featured.isEmpty())
    }

    /**
     * `siteKey` 必须是**公开可写字段**（`@JvmField`）。这里只能验证 Kotlin 侧的读写语义；
     * 真正的考验是真实 jar 用 `putfield` 直接写它 —— 那要等真机跑起来才会以
     * `IllegalAccessError` 暴露，所以字段声明必须一次写对（见 `Spider.siteKey`）。
     */
    @Test
    fun `siteKey 默认空串且可被赋值`() {
        val spider = FakeSpider()
        assertTrue("默认应为空串", spider.siteKey.isEmpty())
        spider.siteKey = "abc"
        assertEquals("abc", spider.siteKey)
    }
}
