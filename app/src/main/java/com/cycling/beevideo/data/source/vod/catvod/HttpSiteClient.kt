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

    /**
     * 取正文。**全类唯一的网络出口**，四个操作都从它走。
     *
     * 单独留一个可覆盖的方法**不是为了好看**：URL 构造（`ac` / `t` / `pg` / `wd`
     * / `ids` / `quick` / `extend` 的取舍）是本类里**唯一不属于解析、也不属于
     * 网络**的逻辑，而它此前完全没有覆盖 —— 只有真机上一个源一个源地试。
     *
     * 有了这个出口，`HttpSiteClientTest` 就能把「操作 → 参数 → URL → 解析 →
     * 封面回填」整条跑一遍而**不发一个请求**，包括那条最容易回归的：
     * 「首页有封面就不再多打一次 `ac=detail`」。
     *
     * 生产实现就是一行转发，没有任何分支。
     */
    protected open suspend fun fetch(url: String): String = CatVodHttp.getText(url)

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
        val body = fetch(buildUrl(mapOf("ac" to "list")))
        val parsed = parseHome(body)
        return HomeContent(
            categories = fromExt ?: parsed.categories,
            featured = withFullFields(parsed.featured),
        )
    }

    /**
     * 给条目补全封面等信息。
     *
     * **首页与搜索共用这一条** —— 两处的成因是同一个：MacCMS 的非 `detail`
     * 分支查询字段是精简集，本来就可能不含 `vod_pic`。搜索曾经漏了这一步，
     * 于是同一个源「首页有图、搜索没图」，看起来像两个不同的问题。
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

            val body = fetch(
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
        /*
         * 与 [searchContent] / [homeContent] 一样过一遍 [withFullFields]。
         *
         * ⚠️ 这条**不是**多余的：`withFullFields` 的"逐字段回填"这个做法，
         * 它的唯一理由就是"`categoryId` 是调用方（分类页）指定的，整条替换会把它改掉"
         * —— 而全项目里**只有这里**会把一个非空的 `categoryId`（`tid`）传进 `parseVods`。
         * 也就是说：不加这一句，`withFullFields` 里那段逐字段合并的理由就没有任何场景
         * 在支撑它（首页与搜索传的都是空串）。
         *
         * 代价可控：`withFullFields` 在**任意一条已有封面**时直接返回，而本方法用的是
         * `ac=videolist`（完整字段集，本来就带 `vod_pic`），所以真实站点上几乎不会
         * 触发第二次请求；真触发时，说明这个源确实没给封面 —— 那正是它要解决的场景。
         */
        return withFullFields(parseVods(fetch(url), tid))
    }

    override suspend fun detailContent(sourceId: String): Vod? =
        parseDetail(fetch(buildUrl(mapOf("ac" to "detail", "ids" to sourceId))))

    override suspend fun searchContent(keyword: String): List<Vod> {
        if (keyword.isBlank()) return emptyList()

        /*
         * `ac=videolist` 是**有意与参考实现分歧**的一处，记在这里免得后人当 typo 改掉：
         *
         * 参考 `SiteApi.searchContent` 走 HTTP 时**一个 `ac` 都不发**，只发
         * `wd` / `quick` / `extend` / `pg` —— MacCMS 的 `provide/vod` 在只带 `wd`
         * 时会落到默认分支，效果等价。
         *
         * 我们显式发 `ac=videolist`，与 [categoryContent] 保持一致：`videolist`
         * 返回的是完整字段集（含 `vod_play_url`），而 `list` 只回精简字段。
         * 两种写法理论上都成立，**但没有真实 type=0/1 站点上的对照实验**
         * —— 见 `docs/coverage-gaps.md` 第 1 节。要改先做对照，别凭"参考没发"就删。
         */
        val url = buildUrl(
            mapOf(
                "ac" to "videolist",
                "wd" to keyword,
                // 参考在 HTTP 路径上**无条件**发 quick（`SiteApi.java:204`）。
                "quick" to site.quickSearch.toString(),
            )
        )
        // 封面补全与首页同源，别只补首页 —— 否则同一个源「首页有图、搜索没图」。
        return withFullFields(parseVods(fetch(url), categoryId = ""))
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
     *
     * `extend` 由 [extendOf] 决定要不要带上 —— 参考实现是**每次**请求都带
     * （`SiteApi.call` 第一行），所以放在这里而不是各个调用点，只有一处要维护。
     */
    protected fun buildUrl(overrides: Map<String, String>): String =
        buildMacCmsQuery(site.api, overrides + extendOf(site.ext))
}

/**
 * MacCMS 的语义参数。每次操作由调用方重新决定，所以先全删再放 ——
 * 否则 base 里写死的 `ac=list` 和新加的 `ac=detail` 会同时出现在 URL 上，
 * 服务端只读第一个，症状是「参数改了但行为没变」。
 */
internal val MAC_CMS_QUERY_KEYS =
    listOf("ac", "t", "pg", "wd", "ids", "quick", "extend")

/**
 * 把 [overrides] 覆盖到 [api] 上，保留其它 query（很多源靠一个 token 参数鉴权，
 * 清掉就全废了）。**纯函数**，单测直接打这里，不用起网络。
 */
internal fun buildMacCmsQuery(api: String, overrides: Map<String, String>): String {
    val url = api.toHttpUrlOrNull() ?: return api
    val builder = url.newBuilder()
    MAC_CMS_QUERY_KEYS.forEach { builder.removeAllQueryParameters(it) }
    overrides.forEach { (k, v) -> builder.addQueryParameter(k, v) }
    return builder.build().toString()
}

/**
 * `ext` 要不要作为 `extend` 透传给站点。
 *
 * ⚠️ **本项目与参考实现有意分歧的一处。** 参考 `SiteApi.call` 在 `ext` 非空时
 * 无条件把它塞进 `extend`；而本项目额外把 `ext` 当作**分类映射**用
 * （见 [parseCategoriesFromExt]：`{"class":[…]}` 或 `{"1":"电影"}`，
 * 应对某些源的 `ac=list` 不吐 `class` 的情况）。
 *
 * 那份分类表已经被本地消费掉了，再原样发给站点就是**喂错东西** ——
 * 服务端拿到一个 `{"class":[…]}` 并不认识。所以判据是：
 * **能解析成分类映射的就不透传，其余（token / 过滤串）照常透传。**
 */
internal fun extendOf(ext: String): Map<String, String> =
    if (ext.isBlank() || parseCategoriesFromExt(ext) != null) {
        emptyMap()
    } else {
        mapOf("extend" to ext)
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
