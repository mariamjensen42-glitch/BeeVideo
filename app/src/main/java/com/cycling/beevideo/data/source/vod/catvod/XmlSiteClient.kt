package com.cycling.beevideo.data.source.vod.catvod

import com.cycling.beevideo.domain.model.Category
import com.cycling.beevideo.domain.model.Vod
import org.w3c.dom.Document
import org.w3c.dom.Element
import java.io.ByteArrayInputStream
import javax.xml.parsers.DocumentBuilderFactory

/**
 * type=0：XML 接口（MacCMS 的 XML 输出）。
 *
 * 结构长这样：
 * ```xml
 * <rss version="5.1">
 *   <class><ty id="1">电影</ty></class>
 *   <list page="1" pagecount="100" recordcount="2000">
 *     <video>
 *       <id>1</id><name>…</name><type>电影</type><pic>…</pic>
 *       <note>更新至06</note><actor>…</actor><director>…</director>
 *       <dl><dd flag="线路一"><![CDATA[第01集$http://a.m3u8#第02集$http://b.m3u8]]></dd></dl>
 *       <des><![CDATA[简介]]></des>
 *     </video>
 *   </list>
 * </rss>
 * ```
 *
 * 线路同样藏在 `<dl><dd flag="线路名">` 里，内容还是 `$$$` / `#` / `$` 那套编码 ——
 * 所以拼回 `vod_play_from` / `vod_play_url` 之后可以直接复用 [PlayUrlCodec]。
 *
 * ⚠️ 已知限制：响应按 UTF-8 解码（走 OkHttp 的 Content-Type charset）。
 * 少数老站返回 GBK 编码的 XML 且不在响应头里声明，那类站点会出现乱码。
 * 真遇到再加"按 XML 声明里的 encoding 重新解码"的处理 —— 现在加是过度设计。
 */
class XmlSiteClient(site: SiteConfig) : HttpSiteClient(site) {

    override fun parseHome(body: String): HomeContent {
        val doc = parseXml(body) ?: return HomeContent.Empty
        return HomeContent(
            categories = parseCategories(doc),
            // XML 源的 <list> 里就是最近更新的一批，正好当推荐位用
            featured = parseVods(doc, categoryId = ""),
        )
    }

    override fun parseVods(body: String, categoryId: String): List<Vod> {
        val doc = parseXml(body) ?: return emptyList()
        return parseVods(doc, categoryId)
    }

    override fun parseDetail(body: String): Vod? {
        val doc = parseXml(body) ?: return null
        val el = doc.getElementsByTagName("video").item(0) as? Element ?: return null
        return toVod(el, categoryId = el.text("tid"))
    }

    // ------------------------------------------------------------------

    private fun parseCategories(doc: Document): List<Category> {
        val nodes = doc.getElementsByTagName("ty")
        return buildList {
            for (i in 0 until nodes.length) {
                val el = nodes.item(i) as? Element ?: continue
                val id = el.getAttribute("id").trim()
                val name = el.textContent.trim()
                if (id.isEmpty() && name.isEmpty()) continue
                add(Category(id = id.ifEmpty { name }, name = name.ifEmpty { id }))
            }
        }
    }

    private fun parseVods(doc: Document, categoryId: String): List<Vod> {
        val nodes = doc.getElementsByTagName("video")
        return buildList {
            for (i in 0 until nodes.length) {
                val el = nodes.item(i) as? Element ?: continue
                toVod(el, categoryId)?.let { add(it) }
            }
        }
    }

    private fun toVod(el: Element, categoryId: String): Vod? {
        val sourceId = el.text("id")
        if (sourceId.isEmpty()) return null

        // 多条线路各是一个 <dd flag="线路名">，正好拼成 CatVod 那两个字段
        val dds = el.getElementsByTagName("dd")
        val from = buildList {
            for (i in 0 until dds.length) {
                val dd = dds.item(i) as? Element ?: continue
                add(dd.getAttribute("flag").trim())
            }
        }
        val urls = buildList {
            for (i in 0 until dds.length) {
                add(dds.item(i).textContent.trim())
            }
        }

        return Vod(
            id = CatVodResponse.vodId(site.key, sourceId),
            name = el.text("name"),
            categoryId = categoryId,
            year = el.text("year"),
            area = el.text("area"),
            genre = el.text("type"),
            /*
             * 评分：XML 输出**有没有 `<score>` 两类都真实存在**，所以有就读、
             * 没有给空串，交给界面按「这部没有评分」降级（不画印章、不画「 分 ·」）。
             *
             * 别拿 `<state>` 顶替 —— 那是连载状态（0=完结 / 1=连载），不是评分。
             */
            score = el.text("score"),
            remarks = el.text("note"),
            director = el.text("director"),
            actors = el.text("actor"),
            intro = el.text("des"),
            // XML 侧的多地址同样用 `$$$` 分隔，清洗规则与 JSON 侧保持一致
            pic = el.text("pic").split("\$\$\$").firstOrNull().orEmpty().trim(),
            lines = PlayUrlCodec.decode(
                from.joinToString("\$\$\$"),
                urls.joinToString("\$\$\$"),
            ),
        )
    }

    /** 取子元素文本。[Element.getElementsByTagName] 会连后代一起找，这正是想要的。 */
    private fun Element.text(tag: String): String =
        getElementsByTagName(tag).item(0)?.textContent?.trim().orEmpty()

    private fun parseXml(xml: String): Document? {
        val s = xml.trim()
        if (!s.startsWith("<")) return null
        return runCatching {
            val factory = DocumentBuilderFactory.newInstance().apply {
                isNamespaceAware = false
                setFeatureSafe("http://apache.org/xml/features/disallow-doctype-decl", true)
                setFeatureSafe("http://xml.org/sax/features/external-general-entities", false)
                setFeatureSafe("http://xml.org/sax/features/external-parameter-entities", false)
            }
            factory.newDocumentBuilder()
                .parse(ByteArrayInputStream(s.toByteArray(Charsets.UTF_8)))
        }.getOrNull()
    }

    /**
     * 安全开关不是所有解析器实现都支持，不支持时 `setFeature` 会抛异常。
     * 抛了就跳过 —— 这些 feature 是**加固**，不是功能必需。
     */
    private fun DocumentBuilderFactory.setFeatureSafe(name: String, value: Boolean) {
        runCatching { setFeature(name, value) }
    }
}
