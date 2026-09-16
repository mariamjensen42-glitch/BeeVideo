package com.cycling.beevideo.data.source.vod.catvod

import com.cycling.beevideo.domain.model.Episode
import com.cycling.beevideo.domain.model.PlayLine

/**
 * CatVod 协议里「线路 / 剧集」两段字符串的编解码。
 *
 * ─── 协议原文 ────────────────────────────────────────────────────────
 * ```
 * vod_play_from = "线路一$$$线路二$$$线路三"
 * vod_play_url  = "第01集$http://a.m3u8#第02集$http://b.m3u8$$$第01集$http://c.m3u8"
 * ```
 * 也就是说：**`$$$` 分线路、`#` 分剧集、`$` 分「剧集名 + 播放地址」**。
 * 两个字段的线路顺序一一对应，靠下标配起来。
 *
 * ─── ⚠️ 但线路名的分隔符是**两种都真实存在的** ──────────────────────
 * `vod_play_url` 恒用 `$$$`，`vod_play_from` 却不一定。MacCMS 的
 * `application/api/controller/Provide.php` 里有两处会把它改成逗号：
 *
 *   - 第 240 行，`ac=list` 时 `str_replace('$$$', ',')`
 *   - 第 219 行，站点配了 `api.vod.from` 白名单（只暴露指定播放组）时，
 *     **`ac=videolist` / `ac=detail` 也会走逗号那条路**
 *
 * 后一种是真会踩的：白名单是很常见的站点配置。只认 `$$$` 的话，
 * "线路一,线路二" 会被当成**一个**名字，于是第二条线路显示成「线路2」。
 * 详见 [splitLineNames]。
 */
object PlayUrlCodec {

    private const val LINE_SEP = "\$\$\$"
    private const val EPISODE_SEP = "#"

    /**
     * 解码出全部线路。
     *
     * 容错策略：
     * - `vod_play_url` 为空 → 返回空表（详情页据此显示"暂无播放地址"）
     * - 线路名数量少于地址组时，缺的用「线路 N」补位 —— 有些源只给地址不给名字
     * - 某条线路解析出 0 集 → 整条丢掉。留着它，选集栏会多出一个点了没反应的 chip
     */
    fun decode(playFrom: String?, playUrl: String?): List<PlayLine> {
        if (playUrl.isNullOrBlank()) return emptyList()

        val groups = playUrl.split(LINE_SEP)
        val names = splitLineNames(playFrom.orEmpty(), groups.size)

        return groups.mapIndexedNotNull { index, group ->
            val episodes = decodeEpisodes(group)
            if (episodes.isEmpty()) return@mapIndexedNotNull null
            val rawName = names.getOrNull(index)?.trim().orEmpty()
            PlayLine(
                name = rawName.ifEmpty { "线路${index + 1}" },
                episodes = episodes,
            )
        }
    }

    /**
     * 切线路名。`$$$` 优先，其次逗号 —— 两种都是真实格式，理由见文件头。
     *
     * ⚠️ 逗号那一路**必须校验段数**。线路名自己就可能含逗号
     * （"线路一,高清"），无脑 split 会把一条线路的名字劈成两条，
     * 于是名字和地址组**错位** —— 这比名字显示不全严重得多，因为错位之后
     * 每一条线路挂的都不是自己的剧集。
     *
     * 判据：逗号切出来的段数**正好等于**地址组数，才采信。
     * 数量对不上就退回"整串当一条名字"（那样至少下标还是从 0 开始对齐的）。
     */
    private fun splitLineNames(raw: String, expected: Int): List<String> {
        if (raw.isBlank()) return emptyList()
        if (raw.contains(LINE_SEP)) return raw.split(LINE_SEP)
        if (expected > 1) {
            val byComma = raw.split(",")
            if (byComma.size == expected) return byComma
        }
        return listOf(raw)
    }

    private fun decodeEpisodes(group: String): List<Episode> =
        group.split(EPISODE_SEP).mapIndexedNotNull { index, raw ->
            val item = raw.trim()
            if (item.isEmpty()) return@mapIndexedNotNull null

            val sep = item.indexOf('$')
            val namePart: String
            val url: String
            if (sep < 0) {
                // 只有地址没有名字。真实源里出现过（尤其是单集资源）
                namePart = ""
                url = item
            } else {
                namePart = item.substring(0, sep).trim()
                url = item.substring(sep + 1).trim()
            }
            if (url.isEmpty()) return@mapIndexedNotNull null

            val display = namePart.ifEmpty { "第${index + 1}集" }
            Episode(
                name = display,
                short = shortLabel(display, index),
                url = url,
            )
        }

    private val DIGITS = Regex("\\d+")

    /**
     * 选集栏用的短标签。
     *
     * ⚠️ 这一步**故意放在数据层**，不是界面层。
     *
     * domain 的 `Episode.short` 注释写着「由来源直接提供，不由界面解析推导」——
     * 那条约束是说**界面**不许拿 `name` 做字符串替换。但 CatVod 协议的 `vod_play_url`
     * 里根本没有独立的短标签字段，能推导的唯一时机就是这里：一次推导、存进模型，
     * 之后所有使用方拿到的都是确定值。放到界面层才是灾难 —— 每个展示选集的地方
     * 都要重做一遍，而且各写各的规则。
     */
    private fun shortLabel(name: String, index: Int): String {
        DIGITS.find(name)?.let { return it.value }
        if (name.length <= 4) return name
        return (index + 1).toString()
    }

    /**
     * 填 URL 模板里的占位符。
     *
     * 各家的写法不统一，实测出现过 `{cateId}` / `{catePg}` / `{cateName}` /
     * `{keyword}` / `{pg}` / `{wd}` 六种，所以每种都认。
     */
    fun fillTemplate(
        template: String,
        cateId: String? = null,
        page: Int? = null,
        keyword: String? = null,
    ): String {
        var s = template
        if (cateId != null) {
            s = s.replace("{cateId}", cateId).replace("{cateName}", cateId)
        }
        if (page != null) {
            s = s.replace("{catePg}", page.toString()).replace("{pg}", page.toString())
        }
        if (keyword != null) {
            s = s.replace("{keyword}", keyword).replace("{wd}", keyword)
        }
        return s
    }
}
