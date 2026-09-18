package com.cycling.beevideo.domain.model

/**
 * 一部剧的观看进度。
 *
 * 一行 = 一部剧，只回答「上次看到哪」。逐集进度是另一张表的事，不在 MVP 范围。
 */
data class PlayProgress(
    /** 全局条目 id，形如 `站点key:源内id` */
    val vodId: String,
    /** 记录时所在的线路名。集号只在同一线路内可比，恢复时两项必须都对上 */
    val lineName: String,
    val episodeIndex: Int,
    val episodeName: String,
    val positionMs: Long,
    /** 0 = 来源没给时长（部分直链与 m3u8 就是这样） */
    val durationMs: Long,
    val updatedAt: Long,
)

/** 距结尾不足这段就当看完了、从头播 —— 否则恢复过去等于一进去就播完。 */
const val RESUME_TAIL_GUARD_MS = 10_000L

/**
 * 本次应从第几毫秒开始播。
 *
 * 返回 0（从头播）的四种情况都是有意的：没看过 / 线路对不上 / 点的是别的一集 /
 * 上次已接近结尾。纯函数，因为判错的表现是"进度记不住"，从界面上看不出是哪条失效。
 */
fun resumePositionMs(
    progress: PlayProgress?,
    lineName: String,
    episodeIndex: Int,
): Long {
    if (progress == null) return 0L
    if (progress.lineName != lineName) return 0L
    if (progress.episodeIndex != episodeIndex) return 0L
    if (progress.positionMs <= 0L) return 0L

    val duration = progress.durationMs
    if (duration > 0L && progress.positionMs >= duration - RESUME_TAIL_GUARD_MS) return 0L

    return progress.positionMs
}
