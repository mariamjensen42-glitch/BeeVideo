package com.cycling.beevideo.data.source.vod.catvod

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * `CatVodConfigParser` 的回归测试。
 *
 * 这里的每个用例都对应**一个真实配置里出现过的形态** —— 不是凭空构造的边界。
 * 语料来自公开的配置合集（只当格式样本用，不涉及任何具体源）。
 *
 * ⚠️ 本测试需要**真实**的 `org.json`：android.jar 里那份是桩，一调用就抛 "Stub!"。
 * 见 app/build.gradle.kts 的 testImplementation(libs.org.json)。
 */
class CatVodConfigParserTest {

    private val base = "https://example.com/dir/config.json"

    private fun parse(json: String) = CatVodConfigParser.parse(json, base)

    private fun failure(json: String): String =
        runCatching { parse(json) }
            .exceptionOrNull()
            ?.let { assertTrue("期望 CatVodException，实际 ${it::class}", it is CatVodException); it.message.orEmpty() }
            ?: error("期望抛异常，但解析成功了")

    // ---------- 三种「是合法 JSON、但不是站点配置」的形态 ----------

    @Test
    fun `配置合集 urls 要报明确的错`() {
        // 真实样本就长这样：元素是一串配置地址，不是 sites
        val msg = failure("""{"urls":["https://a.com/1.json","https://b.com/2.json"]}""")
        assertTrue("应指出这是配置合集，实际：$msg", msg.contains("配置合集"))
    }

    @Test
    fun `msg 提示要原样带给用户`() {
        val msg = failure("""{"msg":"该配置已停止维护，请更换"}""")
        assertTrue("应带上原始提示，实际：$msg", msg.contains("该配置已停止维护，请更换"))
    }

    @Test
    fun `空 msg 不当成提示`() {
        // msg 存在但是空串 —— 不能因此判定为"服务端提示"，得走后面的逻辑
        val msg = failure("""{"msg":"   ","spider":""}""")
        assertFalse("空 msg 不该被当成提示：$msg", msg.contains("返回了提示"))
        assertTrue("应落到「没有可用站点」，实际：$msg", msg.contains("没有任何可用站点"))
    }

    @Test
    fun `没有 sites 要报没有可用站点`() {
        val msg = failure("""{"spider":""}""")
        assertTrue("实际：$msg", msg.contains("没有任何可用站点"))
    }

    @Test
    fun `站点全部缺 key 等同于没有站点`() {
        // key 是唯一标识，没有 key 的条目留着也无法寻址，解析阶段就该丢
        val msg = failure("""{"sites":[{"name":"甲","api":"https://a.com"},{"name":"乙","api":"https://b.com"}]}""")
        assertTrue("实际：$msg", msg.contains("没有任何可用站点"))
    }

    @Test
    fun `urls 和 sites 同时存在时按配置解析`() {
        // 少数配置两者都有 —— 有 sites 就是真配置，不该被合集那条拦下
        val cfg = parse("""{"urls":["https://x.com"],"sites":[{"key":"k","api":"https://a.com"}]}""")
        assertEquals(1, cfg.sites.size)
    }

    // ---------- api 的语义随 type 变 ----------

    @Test
    fun `spider 源的 api 是类名 绝不能当路径解析`() {
        val cfg = parse("""{"sites":[{"key":"k","type":3,"api":"csp_MockSite","name":"甲"}]}""")
        val s = cfg.sites.single()
        assertEquals("csp_MockSite", s.api)
        assertEquals("MockSite", s.spiderClassName)
    }

    @Test
    fun `json 源的相对 api 要解析成绝对地址`() {
        val cfg = parse("""{"sites":[{"key":"k","type":1,"api":"./api.php/provide/vod/"}]}""")
        assertEquals("https://example.com/dir/api.php/provide/vod/", cfg.sites.single().api)
    }

    @Test
    fun `xml 源的相对 api 同样要解析`() {
        val cfg = parse("""{"sites":[{"key":"k","type":0,"api":"./xml.php"}]}""")
        assertEquals("https://example.com/dir/xml.php", cfg.sites.single().api)
    }

    @Test
    fun `绝对 api 保持原样`() {
        val cfg = parse("""{"sites":[{"key":"k","type":1,"api":"http://a.com/api.php"}]}""")
        assertEquals("http://a.com/api.php", cfg.sites.single().api)
    }

    @Test
    fun `缺 type 时按 JSON 处理`() {
        // 大多数"没写 type"的真实站点就是 MacCMS，按 JSON 走最不容易崩
        val cfg = parse("""{"sites":[{"key":"k","api":"./api.php"}]}""")
        assertEquals(SiteType.JSON, cfg.sites.single().type)
    }

    @Test
    fun `未知 type 也退化成 JSON`() {
        val cfg = parse("""{"sites":[{"key":"k","type":99,"api":"./api.php"}]}""")
        assertEquals(SiteType.JSON, cfg.sites.single().type)
    }

    // ---------- ext 的形态 ----------

    @Test
    fun `缺 ext 时是空串`() {
        val cfg = parse("""{"sites":[{"key":"k","api":"https://a.com"}]}""")
        assertEquals("", cfg.sites.single().ext)
    }

    @Test
    fun `ext 显式 null 也是空串`() {
        val cfg = parse("""{"sites":[{"key":"k","api":"https://a.com","ext":null}]}""")
        assertEquals("", cfg.sites.single().ext)
    }

    @Test
    fun `ext 是对象时序列化成 JSON 文本`() {
        // 参照实现的 ExtAdapter 就是这么做的：对象 → json.toString()
        val cfg = parse("""{"sites":[{"key":"k","api":"https://a.com","ext":{"url":"https://a.com","n":1}}]}""")
        val ext = cfg.sites.single().ext
        val o = JSONObject(ext)
        assertEquals("https://a.com", o.getString("url"))
        assertEquals(1, o.getInt("n"))
    }

    @Test
    fun `ext 是数组时同样序列化`() {
        val cfg = parse("""{"sites":[{"key":"k","api":"https://a.com","ext":[{"a":1}]}]}""")
        assertTrue(cfg.sites.single().ext.startsWith("["))
    }

    @Test
    fun `http 形态的 ext 原样保留 由宿主去下载`() {
        val cfg = parse("""{"sites":[{"key":"k","api":"https://a.com","ext":"https://a.com/rules.json"}]}""")
        assertEquals("https://a.com/rules.json", cfg.sites.single().ext)
    }

    @Test
    fun `复合 ext 必须逐字节原样透传`() {
        // 真实样本形态：`./lib/token.json$$$http://…$$$noproxy$$$1$$$./json/wogg.json$$$MOGG`
        // 它**不以 http 开头**，所以宿主不能去下载；也不能因为内部含 http 就被 absolutize。
        // 实测（真机）：96 字符全部原样到达爬虫的 init。
        val compound = "./lib/token.json\$\$\$https://a.com/rules.json\$\$\$noproxy\$\$\$1\$\$\$./json/wogg.json\$\$\$MOGG"
        val cfg = parse("""{"sites":[{"key":"k","api":"csp_X","ext":"$compound"}]}""")
        assertEquals(compound, cfg.sites.single().ext)
    }

    @Test
    fun `相对路径形态的 ext 不被改写成绝对地址`() {
        // 参照实现的 UrlUtil.convert 只处理 assets:/proxy:/file:，不做 absolutize。
        // 我们跟着原样传 —— 擅自改写会让爬虫拿到一个它解析不了的路径。
        val cfg = parse("""{"sites":[{"key":"k","api":"csp_X","ext":"./json/bili.json"}]}""")
        assertEquals("./json/bili.json", cfg.sites.single().ext)
    }

    // ---------- 顶层 spider 的 url;md5;hash ----------

    @Test
    fun `顶层 spider 只有第一段是路径`() {
        val cfg = parse("""{"spider":"./jar/spider.jar;md5;abc123","sites":[{"key":"k","api":"https://a.com"}]}""")
        assertEquals("https://example.com/dir/jar/spider.jar;md5;abc123", cfg.spider)
    }

    @Test
    fun `顶层 spider 为绝对地址时不变`() {
        val cfg = parse("""{"spider":"http://a.com/s.jar;md5;d","sites":[{"key":"k","api":"https://a.com"}]}""")
        assertEquals("http://a.com/s.jar;md5;d", cfg.spider)
    }

    @Test
    fun `站点专属 jar 要解析成绝对地址`() {
        val cfg = parse("""{"sites":[{"key":"k","type":3,"api":"csp_X","jar":"./jar/x.jar"}]}""")
        assertEquals("https://example.com/dir/jar/x.jar", cfg.sites.single().jar)
    }

    // ---------- 其它字段 ----------

    @Test
    fun `name 缺失时回退成 key`() {
        val cfg = parse("""{"sites":[{"key":"mykey","api":"https://a.com"}]}""")
        assertEquals("mykey", cfg.sites.single().name)
    }

    @Test
    fun `searchable 与 quickSearch 只在等于 1 时为真`() {
        val cfg = parse(
            """{"sites":[
                 {"key":"a","api":"https://a.com","searchable":1,"quickSearch":1},
                 {"key":"b","api":"https://b.com","searchable":0,"quickSearch":2}
               ]}""",
        )
        assertEquals(true, cfg.sites[0].searchable)
        assertEquals(true, cfg.sites[0].quickSearch)
        assertEquals(false, cfg.sites[1].searchable)
        assertEquals(false, cfg.sites[1].quickSearch)
    }

    @Test
    fun `categories 与 flags 解析成列表并丢掉空项`() {
        val cfg = parse(
            """{"flags":["youku","qq"],
                "sites":[{"key":"k","api":"https://a.com","categories":["电影","  ","剧集"]}]}""",
        )
        assertEquals(listOf("电影", "剧集"), cfg.sites.single().categories)
        assertEquals(listOf("youku", "qq"), cfg.flags)
    }

    @Test
    fun `缺 flags 时是空列表而不是 null`() {
        val cfg = parse("""{"sites":[{"key":"k","api":"https://a.com"}]}""")
        assertEquals(emptyList<String>(), cfg.flags)
    }

    @Test
    fun `原文与来源地址被保留下来`() {
        val json = """{"sites":[{"key":"k","api":"https://a.com"}]}"""
        val cfg = parse(json)
        assertEquals(json, cfg.raw)
        assertEquals(base, cfg.sourceUrl)
    }
}
