package com.cycling.beevideo.data.source.vod.catvod

import com.cycling.beevideo.domain.model.Category
import com.cycling.beevideo.domain.model.Vod
import org.json.JSONArray
import org.json.JSONObject

/** 播放源。`parse=true` 表示 `url` 是需要二次解析的网页地址，不是直链。 */
data class PlaySource(
    val url: String,
    val headers: Map<String, String>,
    val parse: Boolean,
)

/**
 * 首页内容。
 *
 * CatVod 的 `homeContent` 一次返回两样东西：**分类**，以及一批**推荐内容**。
 * 后者是关键 —— 没有它，首页第一屏就只能是一串筛选按钮，用户得先点一下
 * 才看得见内容。协议里这两样在同一个响应里，所以这里也一起返回。
 */
data class HomeContent(
    val categories: List<Category>,
    val featured: List<Vod>,
) {
    companion object {
        val Empty = HomeContent(emptyList(), emptyList())
    }
}

/**
 * CatVod 响应的解析。
 *
 * ─── 为什么一套解析器能同时吃 JSON 源和 spider ────────────────────────
 * 这不是巧合：CatVod 协议**就是**要求 spider 的返回值与内建 JSON 源的返回值同构 ——
 * `categoryContent` 一律返回 `{list:[Vod], page, pagecount}`，`detailContent` 一律返回
 * `{list:[Vod]}`，`homeContent` 一律返回 `{class:[{type_id,type_name}]}`。
 * 所以 JSON 源、XML 源、jar 源三者在"响应长什么样"这一层是统一的，
 * 差别只在"怎么把响应拿到手"。
 *
 * ─── 字段值可能是数字也可能是字符串 ──────────────────────────────────
 * `vod_id` 在有些源里是 `123`（数字），有些是 `"123"`（字符串），还有的是
 * `"123-1-1"` 这种复合 id。统一按字符串取，别做数值转换 —— 复合 id 转不动。
 */
object CatVodResponse {

    /** 分类列表。响应形如 `{"class":[{"type_id":"1","type_name":"电影"}]}` */
    fun parseCategories(json: String): List<Category> {
        val root = optObject(json) ?: return emptyList()
        return parseCategories(root)
    }

    /** 同一个解析，但输入已经是对象（spider 源与 JSON 源共用同一条路径）。 */
    private fun parseCategories(root: JSONObject): List<Category> {
        // 有的源把 class 写成 "class"，有的在列表响应里同样用这个键
        val arr = root.optJSONArray("class") ?: return emptyList()
        return buildList {
            for (i in 0 until arr.length()) {
                val o = arr.optJSONObject(i) ?: continue
                val id = o.str("type_id")
                val name = o.str("type_name")
                if (id.isEmpty() && name.isEmpty()) continue
                add(Category(id = id.ifEmpty { name }, name = name.ifEmpty { id }))
            }
        }
    }

    /**
     * 首页内容：`{"class":[…],"list":[Vod]}`。
     *
     * `list` 缺失是正常的（有些源只在首页给分类），这时 [HomeContent.featured]
     * 为空，界面退回"只有筛选行"的形态。
     */
    fun parseHome(json: String, siteKey: String): HomeContent {
        val root = optObject(json) ?: return HomeContent.Empty
        return HomeContent(
            categories = parseCategories(root),
            featured = parseVods(root.optJSONArray("list"), siteKey, categoryId = ""),
        )
    }

    /**
     * 内容列表。响应形如 `{"list":[Vod], "page":1, "pagecount":100}`。
     *
     * @param siteKey    站点标识。**会拼进 `Vod.id`** —— 不同站点的 `vod_id` 完全可能撞车
     *                   （都是 "1"），不隔离的话跨站点跳转就会串数据。
     * @param categoryId 本次请求的分类，列表项里通常不带这个信息，由调用方给。
     */
    fun parseVods(json: String, siteKey: String, categoryId: String): List<Vod> {
        val root = optObject(json) ?: return emptyList()
        return parseVods(root.optJSONArray("list"), siteKey, categoryId)
    }

    private fun parseVods(arr: JSONArray?, siteKey: String, categoryId: String): List<Vod> {
        if (arr == null) return emptyList()
        return buildList {
            for (i in 0 until arr.length()) {
                val o = arr.optJSONObject(i) ?: continue
                toVod(o, siteKey, categoryId)?.let { add(it) }
            }
        }
    }

    /** 详情。响应形如 `{"list":[{完整 Vod}]}`，通常只有一条。 */
    fun parseDetail(json: String, siteKey: String): Vod? {
        val root = optObject(json) ?: return null
        val arr = root.optJSONArray("list") ?: return null
        val o = arr.optJSONObject(0) ?: return null
        return toVod(o, siteKey, categoryId = o.str("type_id"))
    }

    /** 播放地址。响应形如 `{"parse":0,"url":"http://…","header":"{…}"}` */
    fun parsePlayer(json: String): PlaySource? {
        val root = optObject(json) ?: return null
        val url = root.str("url").ifEmpty { root.str("playUrl") }
        if (url.isEmpty()) return null
        return PlaySource(
            url = url,
            headers = readHeaders(root.opt("header")),
            // parse 缺省按 0 处理：直接当直链试，失败了播放器自己会报错，
            // 比反过来（把直链当网页去嗅探）体面得多
            parse = root.optInt("parse", 0) == 1,
        )
    }

    // ------------------------------------------------------------------

    private fun toVod(o: JSONObject, siteKey: String, categoryId: String): Vod? {
        val sourceId = o.str("vod_id")
        if (sourceId.isEmpty()) {
            // 没有 id 就既不能进详情也不能播放，留着只会变成一张点了没反应的卡
            return null
        }
        return Vod(
            id = vodId(siteKey, sourceId),
            name = o.str("vod_name"),
            categoryId = categoryId,
            year = o.str("vod_year"),
            area = o.str("vod_area"),
            genre = o.str("type_name"),
            score = o.str("vod_score"),
            remarks = o.str("vod_remarks"),
            director = o.str("vod_director"),
            actors = o.str("vod_actor"),
            intro = o.str("vod_content").let(::stripHtml),
            pic = normalizePic(o.str("vod_pic")),
            lines = PlayUrlCodec.decode(o.str("vod_play_from"), o.str("vod_play_url")),
        )
    }

    /**
     * 封面地址的清洗。
     *
     * 三种脏数据都真实见过：
     *   - 多个地址用 `$$$` 拼在一起（源站没决定用哪张）
     *   - 前面带 `//`（协议相对地址，OkHttp 会当成相对路径）
     *   - 干脆是个空格或 `null` 字面量
     *
     * 统一处理掉，界面层拿到空串就画占位、拿到就画图，不需要再判断。
     */
    private fun normalizePic(raw: String): String {
        val first = raw.split("\$\$\$").firstOrNull().orEmpty().trim()
        if (first.isEmpty()) return ""
        return if (first.startsWith("//")) "https:$first" else first
    }

    /** 全局 id：`站点key:源内id`。仓储反解它来定位站点。 */
    fun vodId(siteKey: String, sourceId: String): String = "$siteKey:$sourceId"

    /** 反解 [vodId]；解析不出返回 null。 */
    fun splitVodId(vodId: String): Pair<String, String>? {
        val i = vodId.indexOf(':')
        if (i <= 0) return null
        return vodId.substring(0, i) to vodId.substring(i + 1)
    }

    /**
     * `header` 有两种写法：JSON 字符串，或者直接是个对象。
     * 解析失败一律当"没有自定义头"，不要因此让整个播放流程挂掉。
     */
    private fun readHeaders(raw: Any?): Map<String, String> {
        if (raw == null || raw == JSONObject.NULL) return emptyMap()
        val obj = when (raw) {
            is JSONObject -> raw
            is String -> {
                val s = raw.trim()
                if (s.isEmpty() || !s.startsWith("{")) return emptyMap()
                runCatching { JSONObject(s) }.getOrNull() ?: return emptyMap()
            }
            else -> return emptyMap()
        }
        return buildMap {
            obj.keys().forEach { k ->
                val v = obj.optString(k)
                if (v.isNotEmpty()) put(k, v)
            }
        }
    }

    /** 简介里常带 HTML 标签和 `&nbsp;`，直接显示会给用户看到一堆尖括号。 */
    private fun stripHtml(s: String): String {
        if (s.isEmpty()) return s
        return s.replace(Regex("<[^>]+>"), "")
            .replace("&nbsp;", " ")
            .replace("&amp;", "&")
            .replace("&lt;", "<")
            .replace("&gt;", ">")
            .replace(Regex("\\s+"), " ")
            .trim()
    }

    private fun optObject(json: String): JSONObject? {
        val s = json.trim()
        if (s.isEmpty()) return null
        return runCatching { JSONObject(s) }.getOrNull()
    }

    /**
     * 取字符串字段，**把 `null` 和缺字段都归一成空串**。
     *
     * 不能直接用 `optString`：`org.json` 遇到 JSON 的 `null` 会返回字面量 `"null"`，
     * 于是界面就会理直气壮地显示四个字母。
     */
    private fun JSONObject.str(key: String): String {
        if (!has(key) || isNull(key)) return ""
        return optString(key).trim()
    }
}

/** 供 XPath 源复用：把一串同名字段拼成 JSONObject 之外的结构时不至于重复代码。 */
internal fun JSONArray.objects(): List<JSONObject> =
    (0 until length()).mapNotNull { optJSONObject(it) }
