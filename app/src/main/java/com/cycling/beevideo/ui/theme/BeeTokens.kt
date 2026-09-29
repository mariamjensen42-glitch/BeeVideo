package com.cycling.beevideo.ui.theme

import androidx.compose.ui.unit.dp

/**
 * 尺寸常量。要调布局就改这里，不要在页面里写死数字。
 *
 * ⚠️ 这里**不定义圆角** —— 圆角一律取 `MaterialTheme.shapes` 的十档刻度。
 * ⚠️ 本项目只做手机端：只有一套竖屏布局，没有断点、没有平板列数与留白分支、也没有侧边导航轨。
 */
object BeeDimens {

    // 间距：全部是 4dp 的整数倍
    val gapTight = 4.dp
    val gapTiny = 8.dp
    val gapSmall = 12.dp
    val gapMedium = 16.dp
    val gapLarge = 24.dp
    val gapHuge = 32.dp

    val screenMargin = 16.dp
    /** 首页海报列数 */
    const val posterColumns = 3
    /** 详情页剧集列数 */
    const val episodeColumns = 4

    val detailPosterWidth = 116.dp
    /** 48dp 是 M3 的最小可点目标，剧集格不能比它矮 */
    val episodeCellHeight = 48.dp
    /** 再细在 440dpi 上会被抗锯齿抹成灰边，像格子有描边 */
    val episodeProgressHeight = 3.dp
    /** 让出的距离 = 剧集格自己的圆角半径，让出后才是真的"贴在格子底部" */
    val episodeProgressInset = 12.dp
    val playerCellSize = 48.dp

    /**
     * 应用内小窗的宽度。横屏视频 + 一行标题，16:9 下高约 117dp。
     * 按 392dp 宽的竖屏算占一半多一点 —— 再大就挡内容，再小标题只能显示两个字。
     */
    val miniPlayerWidth = 208.dp

    // 观看历史行 3:4 → 72×96，加容器块上下各 16dp 内边距，整行 128dp。
    // 不用海报墙的 2:3：这一行右侧有五行信息要排，封面再拉长会把整行撑高
    val historyPosterWidth = 72.dp

    /** 一屏（411dp）露出约一张半 —— 露出半张才是在说"这里能横滑" */
    val resumeCardWidth = 300.dp
    /** 「继续观看」最多几条。再多就不是"接着看"而是一份列表了，那份列表在历史页 */
    const val resumeMaxCount = 5

    /**
     * 海报内浮层距卡片边缘的留白。8dp 同时承担两个职责：视觉留白，以及**同心圆角的间距**
     * —— 卡片 12dp(medium) − 8 = 4dp(extraSmall)，正好是印章那一档。
     */
    val posterInset = 8.dp

    /** 经典电影海报比例。3:4 偏方，铺下去像九宫格图片列表 */
    const val posterAspect = 2f / 3f
    /** 详情页信息块横向并排布局，用更紧凑的 3:4（那边右侧还有一整列文字） */
    const val detailPosterAspect = 3f / 4f
    const val videoAspect = 16f / 9f
}
