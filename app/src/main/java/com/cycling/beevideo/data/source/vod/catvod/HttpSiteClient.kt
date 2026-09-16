package com.cycling.beevideo.data.source.vod.catvod

import com.cycling.beevideo.domain.model.Category
import com.cycling.beevideo.domain.model.Vod
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import org.json.JSONObject

/**
 * 基于 HTTP 的站点客户端基类（type=1 JSON / type=0 XML）。
 *
 * 两者的**流程完全一致**：替换 URL 占位符 → 发请求 → 解析文本。
 * 差别只在最后一步「怎么从文本里取字段」。所以这里把流程固定住，
 * 把解析留给子类 —— 否则 `homeContent` 那套「先看 ext、再请求」的逻辑要写两遍。
 *
 * ─── URL 怎么来的 ────────────────────────────────────────────────────
 * 配置里的 `api` 形态很不统一，实测两类都有：
 *   a) 完整模板：`http://host/api.php/provide/vod/?ac=videolist&t={cateId}&pg={catePg}`
 *   b) 纯 base  ：`http://host/api.php/provide/vod/`
 *
 * 策略是：**保留 `api` 里已有的 query 参数**（很多源靠一个 token 参数鉴权，
 * 清掉就全废了），只把 MacCMS 那几个语义参数（`ac` / `t` / `pg` / `wd` / `ids`）
 * 换成当前操作需要的值。
 */
abstract class HttpSiteClient(override val site: SiteConfig) : SiteClient {

    /**
     * 首页补全封面时最多带多少个 id。
     *
     * MacCMS 的 `ac=list` 默认 pagesize 是 20，设 40 留一倍余量。
     * 再往上加没有意义：多出来的 id 只会把 URL 撑长（`ids=1,2,…,100`
     * 有几百字符，而首页一屏根本展示不了那么多），换来的封面没人看得到。
     */
    private companion object {
        const val MAX_BACKFILL = 40
    }

    /** 解析首页（要同时给出分类与推荐内容） */
    protected abstract fun parseHome(body: String): HomeContent

    /** 解析内容列表（回应 `ac=videolist`） */
    protected abstract fun parseVods(body: String, categoryId: String): List<Vod>

    /** 解析详情（回应 `ac=detail`） */
    protected abstract fun parseDetail(body: String): Vod?

    // ------------------------------------------------------------------

    override suspend fun homeContent(): HomeContent {
        // 有些源（尤其中转/聚合类）不吐 class，而是靠配置里写死分类。先看配置。
        val fromExt = parseCategoriesFromExt(site.ext)
        /*
         * 这里用 `ac=list` 而不是 `ac=videolist`。
         *
         * 两个理由，第二个是硬的：
         *   1. MacCMS 的 `ac=list` 是**首页接口** —— 一次返回 `class`（全部分类）
         *      + `list`（一页内容），正是首页需要的那两样。
         *   2. `class` **只在 `ac != videolist/detail` 时才返回**
         *      （Provide.php 第 246 行）。想拿分类就必须走这条，没有别的选择。
         *
         * 代价见 [withFullFields]：这条路径的条目字段被砍到只剩 8 个，
         * 封面得另外补一次。
         */
        val body = CatVodHttp.getText(buildUrl(mapOf("ac" to "list")))
        val parsed = parseHome(body)
        return HomeContent(
            categories = fromExt ?: parsed.categories,
            featured = withFullFields(parsed.featured),
        )
    }

    /**
     * 给首页条目补全封面等信息。
     *
     * ─── 为什么非补不可 ──────────────────────────────────────────────────
     * MacCMS 的 `ac=list` 查询字段是**写死的精简集**（Provide.php 第 125 行）：
     *
     *     vod_id, vod_name, type_id, "" as type_name, vod_en, vod_time,
     *     vod_remarks, vod_play_from
     *
     * **没有 vod_pic，也没有 vod_year / vod_area / vod_score。**
     * 而首页最需要的就是封面 —— 不补的话，真实站上首页一张图都没有，
     * 刊头也会退化成只有「更新至 N」（实测：远程源上一屏渐变占位）。
     *
     * 补法是 `ac=detail&ids=1,2,3…` —— detail 走 `$field = '*'` 那个分支，
     * 而且 MacCMS 的 ids 支持逗号分隔批量查（Provide.php 对 ids 按逗号切分），
     * 所以整页只要一次请求。
     *
     * ─── ⚠️ 只在"一条封面都没拿到"时才补 ─────────────────────────────────
     * XML 源、spider 源、以及一些改造过的 JSON 源在首页接口里本来就带图。
     * 判据放在**数据**上而不是站点类型上，这样未知来源也能自适应：
     * 只要有任意一条带了封面，就认为这个源不砍字段，不再多打一次请求。
     *
     * ─── ⚠️ 失败必须降级，不能连坐 ───────────────────────────────────────
     * 补全失败（超时、站点不支持多 id）顶多是没有封面 —— 而"没有封面"
     * 本来就是被设计过的路径（见 `PosterCard` 里渐变底的注释）。
     * 所以整体兜住，异常时原样返回。
     */
    private suspend fun withFullFields(vods: List<Vod>): List<Vod> {
        if (vods.isEmpty() || vods.any { it.pic.isNotEmpty() }) return vods
        return runCatching {
            val ids = vods
                .mapNotNull { CatVodResponse.splitVodId(it.id)?.second }
                .take(MAX_BACKFILL)
            if (ids.isEmpty()) return@runCatching vods

            val body = CatVodHttp.getText(
                buildUrl(mapOf("ac" to "detail", "ids" to ids.joinToString(",")))
            )
            val full = parseVods(body, categoryId = "").associateBy { it.id }
            if (full.isEmpty()) return@runCatching vods

            // 逐字段回填而不是整条替换：categoryId 这类由调用方指定的值
            // 在 detail 路径里会变成响应里的 type_id，整体替换会把它改掉。
            vods.map { v ->
                val f = full[v.id] ?: return@map v
                v.copy(
                    pic = f.pic.ifEmpty { v.pic },
                    year = f.year.ifEmpty { v.year },
                    area = f.area.ifEmpty { v.area },
                    score = f.score.ifEmpty { v.score },
                    genre = f.genre.ifEmpty { v.genre },
                )
            }
        }.getOrDefault(vods)
    }

    override suspend fun categoryContent(tid: String, page: Int): List<Vod> {
        val url = buildUrl(
            mapOf(
                // videolist 而不是 list：`ac=list` 只回精简字段（没有 vod_play_url），
                // 拿它做列表会导致详情页还得再请求一次才能拿到线路
                "ac" to "videolist",
                "t" to tid,
                "pg" to page.toString(),
            )
        )
        return parseVods(CatVodHttp.getText(url), tid)
    }

    override suspend fun detailContent(sourceId: String): Vod? =
        parseDetail(CatVodHttp.getText(buildUrl(mapOf("ac" to "detail", "ids" to sourceId))))

    override suspend fun searchContent(keyword: String): List<Vod> {
        if (keyword.isBlank()) return emptyList()
        val url = buildUrl(mapOf("ac" to "videolist", "wd" to keyword))
        return parseVods(CatVodHttp.getText(url), categoryId = "")
    }

    override suspend fun playerContent(flag: String?, id: String): PlaySource? {
        if (id.isEmpty()) return null
        // 已经是可播地址（m3u8 / mp4 / 直链），不需要再问站点
        if (id.startsWith("http://") || id.startsWith("https://")) {
            return PlaySource(url = id, headers = emptyMap(), parse = false)
        }
        // 不是 URL 就说明这家用了自定义兑换接口，只能走 detail 再取一次
        val detail = detailContent(id) ?: return null
        val episodeUrl = detail.lines
            .firstOrNull { flag == null || it.name == flag }
            ?.episodes
            ?.firstOrNull()
            ?.url
            ?: return null
        return PlaySource(url = episodeUrl, headers = emptyMap(), parse = false)
    }

    // ------------------------------------------------------------------

    /**
     * 只覆盖 MacCMS 的语义参数，其余查询参数原样保留。
     *
     * 用 `HttpUrl` 而不是手拼字符串：手拼很容易在 `?` 和 `&` 上出错
     * （base 里已有 query 时再加 `?` 就成了第二个问号，服务端只读第一个）。
     */
    protected fun buildUrl(overrides: Map<String, String>): String {
        val url = site.api.toHttpUrlOrNull() ?: return site.api
        val builder = url.newBuilder()
        // 这些键由本次操作决定，先全删再放，避免出现 `ac=list&ac=detail`
        listOf("ac", "t", "pg", "wd", "ids").forEach { builder.removeAllQueryParameters(it) }
        overrides.forEach { (k, v) -> builder.addQueryParameter(k, v) }
        return builder.build().toString()
    }
}

/**
 * 从 `ext` 里读分类。
 *
 * 支持两种写法（都真实出现过）：
 *   - `{"class":[{"type_id":"1","type_name":"电影"}]}` —— 与响应同构
 *   - `{"1":"电影","2":"剧集"}` / `{"电影":"1"}` —— 键值对，哪边像数字哪边是 id
 *
 * 解析不出来返回 `null`（表示"没有配置分类"），而不是空表 ——
 * 空表会让 `homeContent` 以为"配了但一个都没有"，从此不再去请求接口。
 */
internal fun parseCategoriesFromExt(ext: String): List<Category>? {
    val s = ext.trim()
    if (!s.startsWith("{")) return null
    val obj = runCatching { JSONObject(s) }.getOrNull() ?: return null

    obj.optJSONArray("class")?.let { arr ->
        if (arr.length() > 0) return CatVodResponse.parseCategories(obj.toString())
    }

    val mapped = buildList {
        obj.keys().forEach { k ->
            val v = obj.optString(k).trim()
            if (v.isEmpty()) return@forEach
            if (k.toIntOrNull() != null && v.toIntOrNull() == null) {
                add(Category(id = k, name = v))
            } else if (v.toIntOrNull() != null && k.toIntOrNull() == null) {
                add(Category(id = v, name = k))
            }
        }
    }
    return mapped.ifEmpty { null }
}
