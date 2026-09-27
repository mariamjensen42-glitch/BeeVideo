package com.cycling.beevideo.ui.preview

import com.cycling.beevideo.domain.model.PlayProgress

/**
 * 演示用的观看历史：越靠前越"新"，最后一条已看完。
 *
 * 时间**相对当下**取而不是写死一个绝对时间：这个列表存在的意义就是让人看见
 * 「今天 / 昨天 / 3 天前」这三种文案长什么样，写死时间戳的话过几天预览里就全是日期了。
 *
 * id 带上站点前缀 —— 真实 vodId 就是 `站点key:源内id` 的形状，不带前缀的话预览稿里
 * 「来源」那一段永远是空的，等于这条路径没人看过。
 */
object PreviewHistory {

    fun records(now: Long = System.currentTimeMillis()): List<PlayProgress> {
        val hour = 3_600_000L
        val day = 24 * hour
        val offsets = listOf(2 * hour, 26 * hour, 3 * day, 9 * day, 40 * day)
        return PreviewVods.vods.take(offsets.size).mapIndexed { index, vod ->
            val line = vod.lines.firstOrNull()
            val episodeCount = line?.episodes?.size ?: 1
            val episodeIndex = (index * 2) % episodeCount
            // 最后一条压到结尾，好让「已看完」这个状态也在预览里出现
            val positionMs = if (index == offsets.lastIndex) {
                EPISODE_DURATION_MS
            } else {
                EPISODE_DURATION_MS * (index + 2) / 10
            }
            PlayProgress(
                vodId = "$DEMO_SITE_KEY:${vod.id}",
                lineIndex = 0,
                lineName = line?.name.orEmpty(),
                episodeIndex = episodeIndex,
                episodeName = line?.episodes?.getOrNull(episodeIndex)?.name.orEmpty(),
                positionMs = positionMs,
                durationMs = EPISODE_DURATION_MS,
                updatedAt = now - offsets[index],
                name = vod.name,
                pic = vod.pic,
                score = vod.score,
                remarks = vod.remarks,
            )
        }
    }
}

/** 与 `FakeSourceRepository.singleSourceReady()` 里的来源 id 一致，预览里才显示得出「来源」。 */
private const val DEMO_SITE_KEY = "demo"

private const val EPISODE_DURATION_MS = 2_700_000L
