package com.cycling.beevideo.data.source.vod.js

import com.github.catvod.utils.UriUtil
import org.jsoup.nodes.Document
import org.jsoup.select.Elements
import java.util.regex.Pattern

/**
 * `pdfh` / `pdfa` / `pd` / `pdfl` 的规则引擎
 * —— 逐行对齐参考宿主的 `com.github.catvod.js.utils.Parser`（三个正则、`:eq(0)`
 * 补全、`--` 排除、单槽 Document 缓存都照搬）。
 *
 * ⚠️ 为什么必须宿主自己实现：`lib/drpy2.min.js` 顶层就写
 * `const defaultParser = { pdfh: pdfh, … }`，裸全局名缺席时模块求值当场抛
 * `'pdfh' is not defined` —— **所有** `.js` 源在初始化阶段一起报废。
 * 参考宿主从 spider.jar 的 `com.github.catvod.js.Function` 拿这几个函数，
 * 而实测本配置那份 jar 里没有这个类（`ClassNotFoundException`），没有别的来源。
 *
 * ⚠️ 规则语法是几万个源共同依赖的**方言**：`a&&Text`、`.x:eq(1)&&href`、
 * `#list--.ad`、`style` 里抠 `url(...)`。任何"顺手简化"的后果都是静默取不到数据。
 *
 * ⚠️ 只在 [JsSpider] 的单线程 executor 上调用（它与 JS 侧串行）。
 */
class DomParser {

    /*
     * 单槽缓存。pdfh 的调用模式是"同一段 html 连查十几个字段"，留一份解析结果能省掉
     * 十几次 jsoup.parse。两个槽分别服务两条入口，不合并（对齐参考实现）。
     */
    private var pdfhKey: String? = null
    private var pdfhDoc: Document? = null
    private var pdfaKey: String? = null
    private var pdfaDoc: Document? = null

    /** 取属性值（`html` 里按 `rule` 找到元素，再取其文本 / HTML / 属性）。 */
    fun parseDomForUrl(html: String, rule: String, addUrl: String): String {
        val doc = pdfhDocument(html)
        if (rule == "body&&Text" || rule == "Text") return doc.text()
        if (rule == "body&&Html" || rule == "Html") return doc.html()

        // 最后一段是"取值方式"（Text / Html / 属性名），前面的才是选择器链
        var selector = rule
        var option = ""
        if (selector.contains("&&")) {
            val parts = selector.split("&&")
            option = parts.last()
            selector = parts.dropLast(1).joinToString("&&")
        }
        selector = parseHikerToJq(selector, true)

        var elements = Elements()
        for (part in selector.split(" ")) {
            elements = parseOneRule(doc, part, elements)
            if (elements.isEmpty()) return ""
        }

        if (option.isEmpty()) return elements.outerHtml()
        if (option == "Text") return elements.text()
        if (option == "Html") return elements.html()

        var result = ""
        // `[||]` 是字符类，等价于按 `|` 切 —— 写法照抄参考实现，别"简化"成 | 转义
        for (attr in option.split(Pattern.compile("[||]"))) {
            result = elements.attr(attr)
            if (attr.lowercase().contains("style") && result.contains("url(")) {
                val matcher = URL.matcher(result)
                if (matcher.find()) result = matcher.group(1) ?: ""
                result = result.replace(QUOTE_TRIM, "${'$'}1")
            }
            if (result.isNotEmpty() && addUrl.isNotEmpty()) {
                if (JOIN_URL.matcher(attr).find() && !SPEC_URL.matcher(result).find()) {
                    result = if (result.contains("http")) {
                        result.substring(result.indexOf("http"))
                    } else {
                        UriUtil.resolve(addUrl, result)
                    }
                }
            }
            if (result.isNotEmpty()) return result
        }
        return result
    }

    /** 取元素列表，每项是它的 `outerHtml()`（drpy 的"列表"形态）。 */
    fun parseDomForArray(html: String, rule: String): List<String> {
        val doc = pdfaDocument(html)
        var elements = Elements()
        for (part in parseHikerToJq(rule, false).split(" ")) {
            elements = parseOneRule(doc, part, elements)
            if (elements.isEmpty()) return emptyList()
        }
        return elements.map { it.outerHtml() }
    }

    /** 取"文本$链接"列表（`pdfl`）。 */
    fun parseDomForList(
        html: String,
        rule: String,
        texts: String,
        urls: String,
        urlKey: String,
    ): List<String> {
        var elements = Elements()
        for (part in parseHikerToJq(rule, false).split(" ")) {
            elements = parseOneRule(pdfaDocument(html), part, elements)
            if (elements.isEmpty()) return emptyList()
        }
        return elements.map { element ->
            // ⚠️ 两个子规则都作用在**该元素自己的 html** 上，不是整页
            val item = element.outerHtml()
            parseDomForUrl(item, texts, "").trim() + '$' + parseDomForUrl(item, urls, urlKey)
        }
    }

    /** 丢开缓存的 DOM 树（[JsSpider] 销毁时调，否则最后一个 Document 会跟着站点一直挂着）。 */
    fun clear() {
        pdfhKey = null
        pdfhDoc = null
        pdfaKey = null
        pdfaDoc = null
    }

    /**
     * 给每个选择器段补 `:eq(0)`（= 只要第一个）。`NO_ADD` 里的伪类自带定位，
     * 补了反而会选错 —— 参考实现用同一条正则决定补不补。
     */
    private fun parseHikerToJq(parse: String, first: Boolean): String {
        if (!parse.contains("&&")) {
            val split = parse.split(" ")
            if (!NO_ADD.matcher(split.last()).find() && first) return "$parse:eq(0)"
            return parse
        }
        val parses = parse.split("&&")
        val items = ArrayList<String>(parses.size)
        for (i in parses.indices) {
            val split = parses[i].split(" ")
            if (NO_ADD.matcher(split.last()).find()) {
                items.add(parses[i])
            } else {
                // 最后一段在非 first 模式下不补（它是"取值"那一段的宿主元素）
                if (!first && i >= parses.size - 1) items.add(parses[i])
                else items.add(parses[i] + ":eq(0)")
            }
        }
        return items.joinToString(" ")
    }

    private fun parseOneRule(doc: Document, parse: String, elements: Elements): Elements {
        val info = RuleInfo(parse)
        if (parse.contains(":eq")) {
            val parts = parse.split(":")
            info.rule = parts[0]
            info.setPosition(parts.getOrElse(1) { "" })
        } else if (parse.contains("--")) {
            val rules = parse.split("--")
            info.excludeFrom(rules)
            info.rule = rules[0]
        }

        var current = if (elements.isEmpty()) doc.select(info.rule) else elements.select(info.rule)
        if (parse.contains(":eq")) {
            current = if (info.index < 0) current.eq(current.size + info.index) else current.eq(info.index)
        }
        val excludes = info.excludes
        if (excludes != null && !current.isEmpty()) {
            // ⚠️ 必须先 clone：select().remove() 改的是 DOM 本体，
            // 直接删会把缓存里那棵树改掉，后续查询全部错位
            current = current.clone()
            for (i in excludes.indices) current.select(excludes[i]).remove()
        }
        return current
    }

    private fun pdfhDocument(html: String): Document {
        val cached = pdfhDoc
        if (cached != null && html == pdfhKey) return cached
        return org.jsoup.Jsoup.parse(html).also {
            pdfhDoc = it
            pdfhKey = html
        }
    }

    private fun pdfaDocument(html: String): Document {
        val cached = pdfaDoc
        if (cached != null && html == pdfaKey) return cached
        return org.jsoup.Jsoup.parse(html).also {
            pdfaDoc = it
            pdfaKey = html
        }
    }

    private class RuleInfo(var rule: String) {

        var index: Int = 0
        var excludes: List<String>? = null

        fun setPosition(position: String) {
            var pos = position
            if (rule.contains("--")) {
                val rules = rule.split("--")
                excludeFrom(rules)
                rule = rules[0]
            } else if (pos.contains("--")) {
                val rules = pos.split("--")
                excludeFrom(rules)
                pos = rules[0]
            }
            index = pos.replace("eq(", "").replace(")", "").toIntOrNull() ?: 0
        }

        /**
         * ⚠️ 不能叫 `setExcludes`：`excludes` 是属性，它的 setter 在 JVM 上就是
         * `setExcludes(List)`，同名方法会撞成 "Platform declaration clash"。
         * （参考实现是 Java，那边一个是字段一个是 `String[]` 参数，签名不同才没撞。）
         */
        fun excludeFrom(rules: List<String>) {
            excludes = rules.drop(1)
        }
    }

    private companion object {

        val URL: Pattern = Pattern.compile("url\\((.*?)\\)", Pattern.MULTILINE or Pattern.DOTALL)

        /**
         * 已经自带定位的伪类 / 特殊选择器 —— 见到它们就不再补 `:eq(0)`。
         * ⚠️ 末尾的 `^body$|^#` 是**锚点**（整段就是 body、或整段以 # 开头），
         * 不是"包含"。
         */
        val NO_ADD: Pattern = Pattern.compile(
            ":eq|:lt|:gt|:first|:last|:not|:even|:odd|:has|:contains|:matches|:empty|^body$|^#",
        )

        /** 哪些属性名算"地址"，需要跟 `addUrl` 拼绝对路径。 */
        val JOIN_URL: Pattern = Pattern.compile(
            "(url|src|href|-original|-src|-play|-url|style)$|^(data-|url-|src-)",
            Pattern.MULTILINE or Pattern.CASE_INSENSITIVE,
        )

        /** `ftp:` / `magnet:` 这类本来就不是相对路径，不拼。 */
        val SPEC_URL: Pattern = Pattern.compile(
            "^(ftp|magnet|thunder|ws):",
            Pattern.MULTILINE or Pattern.CASE_INSENSITIVE,
        )

        val QUOTE_TRIM: Regex = Regex("^['|\"](.*)['|\"]${'$'}")
    }
}
