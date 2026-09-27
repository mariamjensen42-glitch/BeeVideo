package com.cycling.beevideo.data.source.vod.catvod

import com.cycling.beevideo.domain.model.Episode
import com.cycling.beevideo.domain.model.PlayLine

/**
 * CatVod 协议里「线路 / 剧集」两段字符串的编解码：
 * `$$$` 分线路、`#` 分剧集、`$` 分「剧集名 + 播放地址」，两个字段靠下标配对。
 *
 * ⚠️ 线路名的分隔符是**两种都真实存在的**：`vod_play_url` 恒用 `$$$`，
 * 但 `vod_play_from` 在 MacCMS 的 `Provide.php` 里有两处会被改成逗号 ——
 * `ac=list` 时，以及站点配了播放组白名单时（后者连 `ac=detail` 也会走逗号那条路）。
 * 只认 `$$$` 的话，"线路一,线路二" 会被当成**一个**名字。详见 [splitLineNames]。
 */
object PlayUrlCodec {

    private const val LINE_SEP = "\$\$\$"
    private const val EPISODE_SEP = "#"

    /**
     * 解码出全部线路。容错：`vod_play_url` 为空返回空表；线路名数量少于地址组时
     * 缺的用「线路 N」补位；某条线路解析出 0 集则整条丢掉（否则选集栏会多出一个
     * 点了没反应的 chip）。
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
     * 切线路名。`$$$` 优先，其次逗号。
     *
     * ⚠️ 逗号那一路**必须校验段数**：线路名自己就可能含逗号（"线路一,高清"），
     * 无脑 split 会让名字和地址组**错位** —— 那比名字显示不全严重得多，因为错位之后
     * 每条线路挂的都不是自己的剧集。判据：段数正好等于地址组数才采信。
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
                // 只有地址没有名字，真实源里出现过（尤其单集资源）
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
     * ⚠️ **故意放在数据层**，不是界面层：domain 的 `Episode.short` 那条"不由界面解析
     * 推导"的约束是说界面不许拿 `name` 做字符串替换。而 CatVod 协议里根本没有独立的
     * 短标签字段，能推导的唯一时机就是这里 —— 一次推导、存进模型。放界面层才是灾难：
     * 每个展示选集的地方都要重做一遍，各写各的规则。
     */
    private fun shortLabel(name: String, index: Int): String {
        DIGITS.find(name)?.let { return it.value }
        if (name.length <= 4) return name
        return (index + 1).toString()
    }

}
