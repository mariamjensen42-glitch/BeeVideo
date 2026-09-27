package com.cycling.beevideo.domain.model

/**
 * 播放内核当前的样子。
 *
 * ─── 为什么要有类型，而不是让界面去问内核 ──────────────────────────────
 * 以前这些判断散在播放页的 composable 里：`isBuffering`、`playerError`、
 * `target.parse` 各是一个 `remember`，最后拼成一行状态文案。表现层因此必须
 * **认识内核的类型**（`PlaybackException`、`Player.STATE_*`），换内核就得改界面。
 * 收成这几个状态之后，界面只按状态分支，[PlaybackFailure] 到中文的映射留在界面
 * （那是文案，不是内核知识）。
 */
sealed interface PlaybackState {

    /** 没载入任何媒体，或者已经释放。 */
    data object Idle : PlaybackState

    /** 已交给内核，正在等首帧或重新缓冲。 */
    data object Buffering : PlaybackState

    /** 正在播 —— **周期上报进度只看它**。 */
    data object Playing : PlaybackState

    /** 已就绪但没在播（用户暂停，或内核停在 READY 还没起播）。 */
    data object Paused : PlaybackState

    /**
     * 这一集播完了。
     *
     * ⚠️ **必须与 [Idle] 分开**：两者都表示"现在没有画面在动"，但只有 Ended 能推出
     * "该接下一集了"。以前内核的 `STATE_ENDED` 被并进 [Idle]，于是"自动下一集"这件事
     * 在状态层面根本无从判断 —— 界面分不清"播完了"和"还没起播"。
     */
    data object Ended : PlaybackState

    /** 起不来。 */
    data class Failed(
        val reason: PlaybackFailure,
        /** 内核给的原始说明，可能没有。界面只在 [PlaybackFailure.Kernel] 时展示它。 */
        val detail: String?,
    ) : PlaybackState
}

/**
 * 起不来的原因。分成枚举而不是直接塞一段字符串，是因为界面要按原因选**不同的文案**，
 * 而"这个地址需要站外解析"和"内核报错"对用户是两件完全不同的事。
 */
enum class PlaybackFailure {

    /** 来源没给出可播地址（空串）。 */
    NoAddress,

    /**
     * 地址是**网页**而不是媒体流，需要站外的解析服务。
     *
     * 本项目**不内置**这类服务（那等于替用户决定用谁），所以标记出来让播放页
     * 能说清楚"为什么放不出来" —— 硬塞给内核的结果是黑屏 + 一条无解的报错，
     * 用户会以为是播放器坏了。
     */
    RequiresExternalParser,

    /** 内核自己报的错（解码、网络、格式）。[PlaybackState.Failed.detail] 里有原文。 */
    Kernel,
}
