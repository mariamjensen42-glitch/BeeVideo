package com.cycling.beevideo.data.source.vod.catvod

import com.cycling.beevideo.domain.model.Category
import com.cycling.beevideo.domain.model.Vod
import com.cycling.beevideo.domain.model.VodPage
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import org.json.JSONObject

/**
 * HTTP 站点客户端基类（type=1 JSON / type=0 XML）：流程一致（替换 URL 占位符 → 请求 →
 * 解析文本），只把最后一步的字段解析留给子类。
 *
 * 配置里的 `api` 形态不统一（完整模板 / 纯 base），策略是**保留已有的 query 参数**
 * （很多源靠一个 token 鉴权），只把 MacCMS 的语义参数换成当前操作需要的值。
 */
abstract class HttpSiteClient(override val site: SiteConfig) : SiteClient {

    // MacCMS 的 `ac=list` 默认 pagesize 是 20，设 40 留一倍余量。再往上只会撑长 URL
    private companion object {
        const val MAX_BACKFILL = 40
    }

    protected abstract fun parseHome(body: String): HomeContent

    protected abstract fun parseVods(body: String, categoryId: String): List<Vod>

    protected abstract fun parseDetail(body: String): Vod?

    /**
     * 响应里声明的总页数，源没给返回 null。默认 null —— JSON 与 XML 的读法不同，
     * 由子类各自实现。
     */
    protected open fun parseTotalPages(body: String): Int? = null

    /**
     * 取正文，全类唯一的网络出口。留成可覆盖的方法是为了让单测把
     * 「操作 → 参数 → URL → 解析 → 封面回填」整条跑一遍而不发请求。
     */
    protected open suspend fun fetch(url: String): String = CatVodHttp.getText(url)

    override suspend fun homeContent(): HomeContent {
        // 有些源不吐 class，靠配置里写死分类，先看配置
        val fromExt = parseCategoriesFromExt(site.ext)
        // ⚠️ 必须用 ac=list：class 只在 ac != videolist/detail 时才返回，没有别的选择
        val body = fetch(buildUrl(mapOf("ac" to "list")))
        val parsed = parseHome(body)
        return HomeContent(
            categories = fromExt ?: parsed.categories,
            featured = withFullFields(parsed.featured),
        )
    }

    /**
     * 给条目补全封面等信息，首页与搜索共用。做法是 `ac=detail&ids=1,2,3…` —— MacCMS 的
     * ids 支持逗号批量查，整页只要一次请求；`ac=list` 的查询字段是写死的精简集，没有 vod_pic。
     *
     * ⚠️ 判据放在**数据**上而不是站点类型上：只要有任意一条带了封面就认为这个源不砍字段，
     * 不再多打一次请求（XML 源、spider 源本来就带图）。
     *
     * ⚠️ 失败必须降级不能连坐：补全失败顶多没封面，而"没有封面"本来就是被设计过的路径。
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

            // 逐字段回填而不是整条替换：detail 路径会把 categoryId（=响应里的 type_id）改掉
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

    override suspend fun categoryContent(tid: String, page: Int): VodPage {
        val url = buildUrl(
            mapOf(
                // videolist 而不是 list：list 只回精简字段、没有 vod_play_url
                "ac" to "videolist",
                "t" to tid,
                "pg" to page.toString(),
            )
        )
        // ⚠️ 只 fetch 一次：页数和列表在同一个响应体里，分两次取就得多打一次网络
        val body = fetch(url)
        // 这里是把非空 categoryId 传进 parseVods 的唯一场景，逐字段回填的理由靠它支撑。
        // 代价可控：videolist 本来就带 vod_pic，真实站点上几乎不会触发第二次请求
        return VodPage(
            vods = withFullFields(parseVods(body, tid)),
            totalPages = parseTotalPages(body),
        )
    }

    override suspend fun detailContent(sourceId: String): Vod? =
        parseDetail(fetch(buildUrl(mapOf("ac" to "detail", "ids" to sourceId))))

    override suspend fun searchContent(keyword: String): List<Vod> {
        if (keyword.isBlank()) return emptyList()

        // ⚠️ 有意与参考实现分歧：它走 HTTP 时一个 ac 都不发。这里显式发 ac=videolist 与
        // categoryContent 保持一致（完整字段集）。两种写法都成立，但没有真实站点对照实验
        val url = buildUrl(
            mapOf(
                "ac" to "videolist",
                "wd" to keyword,
                // 参考在 HTTP 路径上无条件发 quick
                "quick" to site.quickSearch.toString(),
            )
        )
        // 封面补全与首页同源，别只补首页
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

    /**
     * 用 `HttpUrl` 而不是手拼字符串：手拼很容易在 `?` 和 `&` 上出错。
     * `extend` 由 [extendOf] 决定，放在这里而不是各调用点。
     */
    protected fun buildUrl(overrides: Map<String, String>): String =
        buildMacCmsQuery(site.api, overrides + extendOf(site.ext))
}

/**
 * MacCMS 的语义参数。先全删再放 —— 否则 base 里写死的 `ac=list` 和新加的 `ac=detail`
 * 会同时出现在 URL 上，服务端只读第一个，症状是「参数改了但行为没变」。
 */
internal val MAC_CMS_QUERY_KEYS =
    listOf("ac", "t", "pg", "wd", "ids", "quick", "extend")

/**
 * 把 [overrides] 覆盖到 [api] 上，保留其它 query（很多源靠一个 token 参数鉴权）。
 * **纯函数**，单测直接打这里。
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
 * ⚠️ 有意与参考实现分歧：它无条件透传，而本项目额外把 `ext` 当分类映射用
 * （见 [parseCategoriesFromExt]）。那份分类表已被本地消费掉，再发出去就是喂错东西。
 */
internal fun extendOf(ext: String): Map<String, String> =
    if (ext.isBlank() || parseCategoriesFromExt(ext) != null) {
        emptyMap()
    } else {
        mapOf("extend" to ext)
    }

/**
 * 从 `ext` 里读分类，支持 `{"class":[…]}`（与响应同构）与 `{"1":"电影"}` / `{"电影":"1"}`
 * 两种写法（哪边像数字哪边是 id）。
 *
 * ⚠️ 解析不出来返回 `null` 而不是空表 —— 空表会让 `homeContent` 以为"配了但一个都没有"。
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
