package com.cycling.beevideo.data.source.vod.catvod

import org.json.JSONArray
import org.json.JSONObject
import java.net.URI

/**
 * CatVod 站点类型。
 *
 * 数值是**协议的一部分**（配置文件里就是这么写的），不要改。
 */
enum class SiteType(val raw: Int) {
    /** 0：XML 接口，响应用 XPath / 字符串匹配取字段 */
    XML(0),

    /** 1：JSON 接口，事实标准是 MacCMS（苹果 CMS v10）的 `/api.php/provide/vod/` */
    JSON(1),

    /** 3：spider —— 需要加载 jar 里的 `csp_*` 类并反射调用 */
    SPIDER(3),

    /** 4：自定义 api，同样需要 jar，走 spider 的自定义实现 */
    API(4),
    ;

    companion object {
        /** 未知类型一律按 JSON 处理：至少不会崩，且大多数"没写 type"的站点就是 JSON */
        fun of(raw: Int): SiteType = entries.firstOrNull { it.raw == raw } ?: JSON
    }
}

/**
 * 一个站点。
 *
 * 字段名对齐 CatVod 配置规范，**不要重命名** —— 解析就是按这些名字读的。
 */
data class SiteConfig(
    val key: String,
    val name: String,
    val type: SiteType,
    /** JSON/XML 源是 URL 模板；spider 源是 `csp_XXX`（后半段是 jar 里的类名） */
    val api: String,
    /** 站点扩展参数。多数源是 JSON 字符串，也有的源用它传分类映射 */
    val ext: String,
    /** 站点专属 jar；为空时用顶层 `spider` 指的那个 */
    val jar: String,
    val searchable: Boolean,
    val quickSearch: Boolean,
    /** 只显示这些分类；为空表示全部 */
    val categories: List<String>,
) {
    /** spider 源要加载的类名（`csp_MySite` → `MySite`） */
    val spiderClassName: String get() = api.removePrefix("csp_")
}

/** 顶层配置。 */
data class CatVodConfig(
    /** 主 jar，格式 `url;md5;hash`（后两段可选） */
    val spider: String,
    val sites: List<SiteConfig>,
    /** 标志位，如 `youku` / `qq`，供 playerContent 的 vipFlags 用 */
    val flags: List<String>,
    /** 配置原文，留着给"要重新解析"或排查用 */
    val raw: String,
    /** 配置来源 URL，用来把相对路径解析成绝对的 */
    val sourceUrl: String,
)

/**
 * 配置解析。
 *
 * 只认 CatVod 的字段名，**不做任何"猜"** —— 猜字段名的解析器在真实配置上
 * 会静默丢数据，比直接报错难查得多。
 *
 * 直播（`lives`）**不解析**：本项目范围是视频点播 + 本地播放，不做直播。
 * 配置里有这个字段是正常的，忽略即可。
 */
object CatVodConfigParser {

    fun parse(json: String, sourceUrl: String): CatVodConfig {
        val root = JSONObject(json)

        /*
         * 两种"是合法 JSON、但不是站点配置"的形态。真实的配置合集里非常常见，
         * 而且都会走到"配置里没有站点"这个终点 —— 但那个报错完全指不了路。
         *
         *   `{"urls": [...]}` —— 配置**合集**（depot），元素是一串配置地址。
         *       用户常把合集的地址当成单个配置填进来。参照实现遇到它会取第一个地址
         *       递归加载（FongMi `VodConfig.parseDepot`）—— 那是替用户做了选择，
         *       本项目不做：选哪个源是用户的事，我们只负责说清楚。
         *
         *   `{"msg": "..."}` —— 服务端提示，通常是"配置已失效/已停止维护"。
         *       参照实现会把它抛成异常（`VodConfig.checkJson`）。这条提示往往就是
         *       用户唯一能拿到的线索，不该被吞掉。
         */
        root.optString("msg").trim().takeIf { it.isNotEmpty() }?.let { msg ->
            throw CatVodException("配置地址返回了提示：$msg")
        }
        if (root.has("urls") && !root.has("sites")) {
            throw CatVodException(
                "这是一个配置合集（内含 urls 列表），不是单个配置。" +
                    "请改成填其中一个具体的配置地址。",
            )
        }

        val sitesJson = root.optJSONArray("sites") ?: JSONArray()

        val sites = buildList {
            for (i in 0 until sitesJson.length()) {
                val o = sitesJson.optJSONObject(i) ?: continue
                val key = o.optString("key").trim()
                // key 是站点的唯一标识，空 key 的条目直接丢 —— 留着也无法寻址
                if (key.isEmpty()) continue
                val type = SiteType.of(o.optInt("type", 1))
                add(
                    SiteConfig(
                        key = key,
                        name = o.optString("name").trim().ifEmpty { key },
                        type = type,
                        /*
                         * `api` 的语义**随 type 变**，不能一律当路径解析：
                         *   - JSON(1) / XML(0)：URL 模板，可能是相对路径（`./api.php`），
                         *     要按配置文件的 URL 解析成绝对地址；
                         *   - SPIDER(3) / API(4)：**jar 里的类名**（`csp_Xxx`）。
                         *
                         * 踩过一次：`csp_MockSite` 被当成相对路径 resolve 成了
                         * `http://host/csp_MockSite`，随后 `spiderClassName` 剥前缀剥不掉，
                         * 拼出来的类名变成 `com.github.catvod.spider.http://host/csp_MockSite`，
                         * 报错是「jar 里找不到类 ……」—— 这类报错完全没有指向性，
                         * 所以必须在解析阶段就拦住。
                         */
                        api = when (type) {
                            SiteType.SPIDER, SiteType.API -> o.optString("api").trim()
                            SiteType.JSON, SiteType.XML ->
                                absolutize(sourceUrl, o.optString("api").trim())
                        },
                        ext = readExt(o),
                        jar = absolutize(sourceUrl, o.optString("jar").trim()),
                        searchable = o.optInt("searchable", 0) == 1,
                        quickSearch = o.optInt("quickSearch", 0) == 1,
                        categories = readStringList(o, "categories"),
                    )
                )
            }
        }

        /*
         * 一个站点都没解析出来 —— 与其让上层显示一个空列表（用户只会以为"软件坏了"），
         * 不如在这里说清楚。真实配置里这条路径的成因通常是 `sites` 缺失、或者
         * 每个条目都没有 `key`（key 是唯一标识，没有就没法寻址，见上面的 continue）。
         */
        if (sites.isEmpty()) {
            throw CatVodException("这个配置里没有任何可用站点（缺少 sites，或每个站点都没有 key）。")
        }

        return CatVodConfig(
            spider = absolutizeJarSpec(sourceUrl, root.optString("spider").trim()),
            sites = sites,
            flags = readStringList(root, "flags"),
            raw = json,
            sourceUrl = sourceUrl,
        )
    }

    /**
     * 顶层 `spider` 的格式是 `地址;md5;hash`，**只有第一段是路径**。
     *
     * 真实配置里写成相对路径的很多（`./jar/spider.jar;md5;…`）。不解析的话
     * 下载必然失败，而失败信息只说"下载 jar 失败"——它指向不了
     * "这个路径其实是相对的"这个真正的原因。
     */
    private fun absolutizeJarSpec(base: String, spec: String): String {
        if (spec.isEmpty()) return spec
        val parts = spec.split(";")
        val url = absolutize(base, parts[0].trim())
        return (listOf(url) + parts.drop(1)).joinToString(";")
    }

    /**
     * `ext` 在真实配置里**两种形态都出现过**：字符串，或者内联的对象/数组。
     * 对象形态直接 `toString()` 化成字符串，后面统一按字符串处理。
     */
    private fun readExt(o: JSONObject): String {
        if (!o.has("ext")) return ""
        val v = o.opt("ext") ?: return ""
        return when (v) {
            is String -> v
            JSONObject.NULL -> ""
            else -> v.toString()
        }
    }

    private fun readStringList(o: JSONObject, key: String): List<String> {
        val arr = o.optJSONArray(key) ?: return emptyList()
        return buildList {
            for (i in 0 until arr.length()) {
                val s = arr.optString(i).trim()
                if (s.isNotEmpty()) add(s)
            }
        }
    }

    /**
     * 把相对路径按配置文件的 URL 解析成绝对地址。
     *
     * 配置文件里 `jar` 和 `api` 写成相对路径是很常见的（`./spider.jar`），
     * 不解析的话后面下载会直接失败。
     */
    private fun absolutize(base: String, ref: String): String {
        if (ref.isEmpty()) return ref
        if (ref.startsWith("http://") || ref.startsWith("https://") || ref.startsWith("file")) return ref
        if (base.isEmpty()) return ref
        return try {
            URI(base.replace("\\", "/")).resolve(ref).toString()
        } catch (_: Exception) {
            ref
        }
    }
}
