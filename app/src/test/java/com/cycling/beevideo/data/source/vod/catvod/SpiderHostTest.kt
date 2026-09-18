package com.cycling.beevideo.data.source.vod.catvod

import com.cycling.beevideo.domain.model.Vod
import com.github.catvod.crawler.Spider
import com.github.catvod.crawler.SpiderApi
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 爬虫装配的顺序契约：`siteKey` → `init` → `initApi`。
 *
 * ─── 为什么这几条值得存在 ──────────────────────────────────────────────
 * 这三步都是"顺序错了也不报错、只是结果不对"的类型：
 *
 *  - `siteKey` 晚于 `init` → 一个 jar 里的同一个类被配成十几个站点时
 *    （`csp_AppYs` 配了"南府追剧""HG影视""瑞丰资源"…），爬虫拿到的是**别的站点**
 *    那套 ext / header → **结果不对，一声不响**；
 *  - 只调一参 `init` → 真实爬虫的两参覆写变成没人调用的普通方法 →
 *    **站点照常加载、结果永远为空**；
 *  - 给 `initApi` 传 `null` → 失败点跑到若干秒后的某次查询里，表现为
 *    `NullPointerException`，完全指不到这里。
 *
 * 这些以前只有注释担保，而且 jar / JS 两条链各写一遍。现在顺序只写在一处
 * （[SpiderHost]），并且能被钉住 —— 靠的是它**故意不认识 `Context`**：
 * 宿主把 `spider.init(context, ext)` 关进 lambda 交进来，于是单测可以直接观察
 * "`init` 被调用的那一刻，`siteKey` 是什么"。
 */
class SpiderHostTest {

    @Test
    fun `siteKey 在 init 之前赋值`() {
        val spider = RecordingSpider()

        host().host(SITE, spider, emptyList(), LOG_TAG)

        assertEquals(
            "init 时 siteKey 必须已经就位 —— 爬虫靠它区分自己这次该用哪套 ext / header",
            SITE.key,
            spider.siteKeyAtInit,
        )
    }

    @Test
    fun `init 收到的是配置里那串 ext 原文`() {
        val spider = RecordingSpider()

        host().host(SITE, spider, emptyList(), LOG_TAG)

        assertEquals(SITE.ext, spider.extAtInit)
    }

    @Test
    fun `init 抛异常时包成 CatVodException 并带上站点名与原因`() {
        val spider = RecordingSpider(initError = IllegalStateException("boom"))

        val error = assertThrows(CatVodException::class.java) {
            host().host(SITE, spider, emptyList(), LOG_TAG)
        }

        assertTrue(
            "报错必须指向是哪个站点，否则多站点配置里无从下手：${error.message}",
            error.message.orEmpty().contains(SITE.name),
        )
        assertTrue(error.message.orEmpty().contains("boom"))
    }

    /** 这只是个**可选钩子**：爬虫不认它不该导致站点建不起来。 */
    @Test
    fun `initApi 失败不阻断站点`() {
        val spider = RecordingSpider(initApiError = IllegalStateException("api 不可用"))

        val client = host().host(SITE, spider, emptyList(), LOG_TAG)

        assertEquals(SITE.key, client.site.key)
    }

    @Test
    fun `代理没起来时给空实现而不是 null`() {
        val spider = RecordingSpider()

        host(proxyReady = false).host(SITE, spider, emptyList(), LOG_TAG)

        assertSame(
            "给 null 会让失败点跑到若干秒后的某次查询里；空实现至少失败点在同一处",
            SpiderApi.noop,
            spider.apiAtInitApi,
        )
    }

    @Test
    fun `代理起来了就给真实现`() {
        val spider = RecordingSpider()
        val real = object : SpiderApi() {}

        host(api = real, proxyReady = true).host(SITE, spider, emptyList(), LOG_TAG)

        assertSame(real, spider.apiAtInitApi)
    }

    /**
     * 「释放」现在留在 seam 上（`SiteClient.close()`），工厂因此不必再把缓存里的值
     * 向下转型成具体实现 —— 以前那个转型就是"seam 上缺这个能力"的证据。
     */
    @Test
    fun `close 会 destroy 掉 spider`() {
        val spider = RecordingSpider()
        val client = host().host(SITE, spider, emptyList(), LOG_TAG)

        client.close()

        assertTrue("不 destroy 的话 spider 的连接池与线程会一直留着", spider.destroyed)
    }

    /**
     * 默认空实现：JSON / XML 源是无状态的。这条把"接口上必须能给个默认"钉住 ——
     * 谁要是去掉默认实现，这个测试文件会编译不过，而不是留下 4 个空方法让人猜。
     */
    @Test
    fun `没覆写 close 的实现也能被释放`() {
        StatelessClient(SITE).close()
    }

    // ------------------------------------------------------------------ 夹具

    private fun host(
        api: SpiderApi = object : SpiderApi() {},
        proxyReady: Boolean = true,
    ) = SpiderHost(
        spiderApi = api,
        proxyReady = { proxyReady },
        /*
         * 宿主那支 lambda 是 `{ spider, ext -> spider.init(appContext, ext) }`；
         * 单测里换成"记录一次"，因为这里没有 `Context` 也不需要它 ——
         * 要断言的只是"回调被调用的那一刻 siteKey 是什么"。
         */
        initSpider = { spider, ext -> (spider as RecordingSpider).recordInit(ext) },
    )

    private companion object {
        const val LOG_TAG = "CatVodTest"

        val SITE = SiteConfig(
            key = "test_key",
            name = "测试源",
            type = SiteType.SPIDER,
            api = "csp_Fake",
            ext = "{\"a\":1}",
            jar = "",
            searchable = true,
            quickSearch = false,
            categories = emptyList(),
        )
    }
}

/**
 * 记录调用痕迹的假爬虫。
 *
 * `init` 走 [recordInit] 而不是覆写 `init(Context, String?)`：那样需要一个 `Context`，
 * 而单测里没有也不该有（见 [SpiderHostTest] 的类注释）。
 */
private class RecordingSpider(
    private val initError: Throwable? = null,
    private val initApiError: Throwable? = null,
) : Spider() {

    var siteKeyAtInit: String? = null
        private set

    var extAtInit: String? = null
        private set

    var apiAtInitApi: SpiderApi? = null
        private set

    var destroyed = false
        private set

    fun recordInit(extend: String?) {
        siteKeyAtInit = siteKey
        extAtInit = extend
        initError?.let { throw it }
    }

    override fun initApi(api: SpiderApi?) {
        apiAtInitApi = api
        initApiError?.let { throw it }
    }

    override fun destroy() {
        destroyed = true
    }
}

/** 无状态的站点客户端（JSON / XML 那样），用来钉住 `close()` 有默认实现。 */
private class StatelessClient(override val site: SiteConfig) : SiteClient {

    override suspend fun homeContent(): HomeContent = HomeContent.Empty

    override suspend fun categoryContent(tid: String, page: Int): List<Vod> = emptyList()

    override suspend fun detailContent(sourceId: String): Vod? = null

    override suspend fun searchContent(keyword: String): List<Vod> = emptyList()

    override suspend fun playerContent(flag: String?, id: String): PlaySource? = null
}
