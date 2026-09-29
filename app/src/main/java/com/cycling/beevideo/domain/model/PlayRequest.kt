package com.cycling.beevideo.domain.model

/**
 * 一次起播需要的全部东西：播放目标、从哪接着播，以及**通知栏要显示的信息**。
 *
 * 元数据（片名 / 集名 / 封面）与地址一起进请求而不是分开两次调 —— 通知栏的标题
 * 必须和正在放的东西一致，拆成两次就会有「画面换了、通知还是上一集」的窗口。
 */
data class PlayRequest(
    val target: PlayTarget,
    /** 续播位置（毫秒）。0 = 从头。 */
    val resumeAtMs: Long,
    val nowPlaying: NowPlaying,
)

/** 通知栏与锁屏上展示的这条播放的名字。 */
data class NowPlaying(
    /** 主标题：片名。 */
    val title: String,
    /** 副标题：线路 + 剧集。空字符串 = 不显示。 */
    val subtitle: String = "",
    /** 封面地址。null = 不带封面（加载失败也只会是没图，不会出错）。 */
    val artworkUri: String? = null,
)
