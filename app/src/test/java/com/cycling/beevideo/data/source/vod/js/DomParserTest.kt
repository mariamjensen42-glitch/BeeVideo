package com.cycling.beevideo.data.source.vod.js

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [DomParser] 的规则语义测试
 * —— 逐条钉住参考宿主 `com.github.catvod.js.utils.Parser` 的行为。
 *
 * ─── 为什么值得单独写一轮用例 ────────────────────────────────────────
 * `pdfh` / `pdfa` / `pd` 是 drpy 系源的**唯一取数通道**，而规则串是源作者写的
 * 方言（`.item&&a&&Text`、`:eq(1)`、`--` 排除、`href||data-src`）。
 * 这里错一条，症状是"某个源列表为空"或"图片地址少了个域名" ——
 * 既不抛异常，也看不出是哪一层的问题。所以用可执行的断言把语义固定下来。
 *
 * ⚠️ 用例里的 HTML 一律是**手写的最小结构**：不要改成抓回来的真实页面，
 * 那会让"哪条断言在保什么"变得不可读。
 */
class DomParserTest {

    private val parser = DomParser()

    private val BASE = "https://site.com/list/index.html"

    private val HTML = """
        <html><body>
        <div class="list">
          <div class="item">
            <a class="title" href="/v/1">第一部</a>
            <span class="pic" style="background:url('/img/1.jpg')"></span>
            <em>备注1</em>
          </div>
          <div class="item">
            <a class="title" href="http://other.com/v/2">第二部</a>
            <span class="pic" style="background:url(/img/2.jpg)"></span>
            <em>备注2</em>
          </div>
          <div class="ad">广告位</div>
        </div>
        </body></html>
    """.trimIndent()

    // ── pdfh：取单个值 ────────────────────────────────────────────────

    /** 选择器链 + `&&` 取值分段是全套规则里最常出现的一形态。 */
    @Test
    fun `选择器链取文本`() {
        assertEquals("第一部", parser.parseDomForUrl(HTML, ".item&&a&&Text", ""))
        assertEquals("第二部", parser.parseDomForUrl(HTML, ".item:eq(1)&&a&&Text", ""))
    }

    @Test
    fun `取属性`() {
        assertEquals("/v/1", parser.parseDomForUrl(HTML, "a&&href", ""))
        assertEquals("title", parser.parseDomForUrl(HTML, ".item&&a&&class", ""))
    }

    /** `||` 是属性候选（第一个非空胜出），不是"或"运算 —— 与 jsoup 选择器无关。 */
    @Test
    fun `属性候选用竖线分隔`() {
        assertEquals("/v/1", parser.parseDomForUrl(HTML, "a&&data-src||href", ""))
    }

    /** `Text` / `Html` 不带选择器时作用在整页上，且走的是特例分支。 */
    @Test
    fun `整页文本与整页 HTML`() {
        assertTrue(parser.parseDomForUrl(HTML, "Text", "").contains("第一部"))
        assertTrue(parser.parseDomForUrl(HTML, "Text", "").contains("广告位"))
        assertTrue(parser.parseDomForUrl(HTML, "body&&Html", "").contains("class=\"item\""))
    }

    /** 没有 `&&` 时返回**元素自身的 outerHtml**（列表项之间传递的形态）。 */
    @Test
    fun `只给选择器时返回 outerHtml`() {
        val html = parser.parseDomForUrl(HTML, ".item", "")
        assertTrue(html.contains("class=\"item\""))
        assertTrue(html.contains("第一部"))
    }

    /**
     * `style` 属性要从 `url(...)` 里抠出地址并去掉引号。
     *
     * ⚠️ 这段逻辑在参考实现里是**两条**（`parseDomForUrl` 与 JS 侧 `pdfh2` 各一份），
     * 这里保留 Java 侧那份：抠完再走 URL 拼接判断。
     */
    @Test
    fun `style 里抠 url 并去掉引号`() {
        assertEquals("/img/1.jpg", parser.parseDomForUrl(HTML, ".pic&&style", ""))
        assertEquals("/img/2.jpg", parser.parseDomForUrl(HTML, ".item:eq(1)&&.pic&&style", ""))
    }

    /** 找不到就是空串，**不抛** —— JS 侧对空串的处理路径是通的，异常不是。 */
    @Test
    fun `无匹配返回空串`() {
        assertEquals("", parser.parseDomForUrl(HTML, ".不存在&&Text", ""))
        assertEquals("", parser.parseDomForUrl(HTML, ".item&&.不存在&&Text", ""))
    }

    // ── pd：带 base 的取地址 ──────────────────────────────────────────

    /**
     * 相对地址要按 `addUrl` 补成绝对地址 —— 少了这一步，播放器拿到的是
     * `/v/1` 这种没有域名的地址。
     */
    @Test
    fun `相对地址按 base 补全`() {
        assertEquals("https://site.com/v/1", parser.parseDomForUrl(HTML, "a&&href", BASE))
    }

    /** 已经是绝对地址（含 http）时只做裁剪，不重复拼接。 */
    @Test
    fun `绝对地址原样保留`() {
        val url = parser.parseDomForUrl(HTML, ".item:eq(1)&&a&&href", BASE)
        assertEquals("http://other.com/v/2", url)
    }

    /** `magnet:` / `ftp:` 这类不是相对路径，不能被拼上 base。 */
    @Test
    fun `特殊协议不拼 base`() {
        val html = """<a href="magnet:?xt=urn:btih:aaa">x</a>"""
        assertEquals("magnet:?xt=urn:btih:aaa", parser.parseDomForUrl(html, "a&&href", BASE))
    }

    /** 非地址属性（如 `Text`）不参与拼接。 */
    @Test
    fun `文本属性不拼 base`() {
        assertEquals("第一部", parser.parseDomForUrl(HTML, "a&&Text", BASE))
    }

    // ── pdfa：取列表 ─────────────────────────────────────────────────

    @Test
    fun `取元素数组`() {
        val items = parser.parseDomForArray(HTML, ".item")
        assertEquals(2, items.size)
        assertTrue(items[0].contains("第一部"))
        assertTrue(items[1].contains("第二部"))
        // 每项是 outerHtml，能被下一次 pdfh 继续解析
        assertEquals("第一部", parser.parseDomForUrl(items[0], "a&&Text", ""))
    }

    @Test
    fun `无匹配返回空列表`() {
        assertTrue(parser.parseDomForArray(HTML, ".不存在").isEmpty())
    }

    /**
     * `--` 是排除：把被选中的子元素从结果里删掉。
     *
     * ⚠️ 实现里必须先 `clone()` 再 remove —— 直接删改的是**缓存的那棵 DOM**，
     * 下一次查询就少了一块，而症状是"第二次调用结果不对"。
     */
    @Test
    fun `双横线排除子元素`() {
        val list = parser.parseDomForArray(HTML, ".list--.ad")
        assertEquals(1, list.size)
        assertTrue(list[0].contains("第一部"))
        assertFalse("被排除的元素不该出现在结果里", list[0].contains("广告位"))
    }

    /** 排除作用于缓存：做过一次 `--` 之后，后续查询仍能看到完整 DOM。 */
    @Test
    fun `排除不影响后续查询`() {
        parser.parseDomForArray(HTML, ".list--.ad")
        assertEquals("广告位", parser.parseDomForUrl(HTML, ".ad&&Text", ""))
    }

    // ── pdfl：文本 + 链接一起取 ──────────────────────────────────────

    @Test
    fun `取文本与链接组合`() {
        val rows = parser.parseDomForList(HTML, ".item", "a&&Text", "a&&href", BASE)
        assertEquals(2, rows.size)
        // ⚠️ `$` 是分隔符，在 Kotlin 字符串里要转义，否则 `$https` 会被当插值
        assertEquals("第一部\$https://site.com/v/1", rows[0])
        assertEquals("第二部\$http://other.com/v/2", rows[1])
    }

    // ── 缓存 ─────────────────────────────────────────────────────────

    /**
     * 缓存按 html 值判定，不能"粘住上一次的页面"
     * —— 列表页与详情页交替查询时会串数据。
     */
    @Test
    fun `不同 html 不共用缓存`() {
        val other = """<div class="item"><a href="/v/9">第九部</a></div>"""
        assertEquals("第一部", parser.parseDomForUrl(HTML, ".item&&a&&Text", ""))
        assertEquals("第九部", parser.parseDomForUrl(other, ".item&&a&&Text", ""))
        assertEquals("第一部", parser.parseDomForUrl(HTML, ".item&&a&&Text", ""))
    }

    /** [DomParser.clear] 之后仍然可用（销毁路径不能把实例弄成不可用）。 */
    @Test
    fun `clear 之后仍能继续解析`() {
        assertEquals("第一部", parser.parseDomForUrl(HTML, ".item&&a&&Text", ""))
        parser.clear()
        assertEquals("第一部", parser.parseDomForUrl(HTML, ".item&&a&&Text", ""))
    }
}
