package com.cycling.beevideo.domain.model

/**
 * 一部剧的观看进度。
 *
 * 一行 = 一部剧，只回答「上次看到哪」。逐集进度是另一张表的事，不在 MVP 范围。
 */
data class PlayProgress(
    /** 全局条目 id，形如 `站点key:源内id` */
    val vodId: String,
    /**
     * 记录时所在的线路号。
     *
     * 与 [lineName] 一起记是**有意的冗余**：线路名负责判定（集号只在同一线路内可比），
     * 线路号负责「一键继续播放」—— 没有它就得先做一次详情请求才能知道往哪条线路跳，
     * 而历史页的全部意义就是不走那一步。
     */
    val lineIndex: Int = 0,
    /** 记录时所在的线路名。集号只在同一线路内可比，恢复时两项必须都对上 */
    val lineName: String,
    val episodeIndex: Int,
    val episodeName: String,
    val positionMs: Long,
    /** 0 = 来源没给时长（部分直链与 m3u8 就是这样） */
    val durationMs: Long,
    val updatedAt: Long,
    /*
     * 下面四项是**快照**，理由同 `KeepItem`：历史页要能在来源不可用时仍然列出片名与封面。
     * 有默认值是为了不逼所有构造点都填 —— 但播放页必须填，否则历史页只有一串 id。
     */
    val name: String = "",
    val pic: String = "",
    val score: String = "",
    val remarks: String = "",
)

/** 距结尾不足这段就当看完了、从头播 —— 否则恢复过去等于一进去就播完。 */
const val RESUME_TAIL_GUARD_MS = 10_000L

/**
 * 是否已经看完。
 *
 * 判据与 [resumePositionMs] 的结尾守卫**必须同源**，否则会出现「首页说已看完、点进去
 * 却从头播」。时长未知时永远不算看完 —— 那条守卫本来就不成立。
 */
fun PlayProgress.isFinished(): Boolean =
    durationMs > 0L && positionMs >= durationMs - RESUME_TAIL_GUARD_MS

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
    if (progress.isFinished()) return 0L

    return progress.positionMs
}
