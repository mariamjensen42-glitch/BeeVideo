package com.cycling.beevideo.ui.theme

import androidx.compose.ui.unit.dp

/**
 * 尺寸常量。
 *
 * 这里**不定义圆角** —— 圆角一律取 `MaterialTheme.shapes` 的十档刻度，不允许出现
 * 「这个格子圆角 6dp」这种手调值。
 *
 * 本项目**只做手机端**：只有一套竖屏布局，没有断点、没有平板/宽屏的列数与留白分支、
 * 也没有侧边导航轨。要调布局就改这里，不要在页面里写死数字。
 */
object BeeDimens {

    // 间距：全部是 4dp 的整数倍
    val gapTight = 4.dp
    val gapTiny = 8.dp
    val gapSmall = 12.dp
    val gapMedium = 16.dp
    val gapLarge = 24.dp
    val gapHuge = 32.dp

    // 手机端栅格（唯一一套）
    /** 屏幕左右留白 */
    val screenMargin = 16.dp
    /** 首页海报列数 */
    const val posterColumns = 3
    /** 详情页剧集列数 */
    const val episodeColumns = 4

    // 组件尺寸
    val detailPosterWidth = 116.dp
    /** 48dp 是 M3 的最小可点目标，剧集格不能比它矮 */
    val episodeCellHeight = 48.dp
    /** 剧集格底部进度条的高度。再细在 440dpi 上会被抗锯齿抹成灰边，像格子有描边。 */
    val episodeProgressHeight = 3.dp
    /** 进度条左右让出的距离 = 剧集格自己的圆角半径，让出后才是真的"贴在格子底部"。 */
    val episodeProgressInset = 12.dp
    val playerCellSize = 48.dp

    // 精选横滑（M3 carousel · Hero 布局）
    /**
     * 右侧「预告条」的宽度。M3 把 Hero 布局里那个小的 peek 元素定义为 **small carousel
     * item（40–56dp）**，这里取 52dp。
     *
     * 大项宽度不用按比例算：`PageSize.Fill` 会自动吃掉「屏宽 − 左留白 − 预告条宽度 −
     * 项间距」，露出量恒等于这个值，与屏宽无关。
     */
    val heroPeekWidth = 52.dp
    /**
     * 高度固定 —— 宽度随屏宽变、高度跟着变的话，这一屏的垂直节奏就没法预期了。
     *
     * 200dp = 「刊头行 28 + 片名一行 52 + 间隔 4 + 元信息 24 + 内边距 40」再加约 52dp
     * 呼吸空间；片名折成两行时（52×2）正好填满。数字是本项目取值，M3 只规定圆角与元素间距。
     */
    val heroCardHeight = 200.dp
    /** 卡片内边距。28（卡圆角） − 20 = 8，卡内元素的圆角必须是 8dp 才同心 */
    val heroCardPadding = 20.dp
    /** M3 carousel 规范：item 之间的间距 8dp */
    val heroCardGap = 8.dp
    /** 进入精选的门槛与上限。演示期按评分排序取前几张，接真实源后由来源给。 */
    const val featuredMaxCount = 5

    // 海报内部的固定偏移
    /**
     * 海报内所有浮层（评分印章、片名、状态）距卡片边缘的留白。
     *
     * 8dp 同时承担两个职责：既是视觉留白，也是**同心圆角的间距** ——
     * 卡片 12dp(medium) − 8 = 4dp(extraSmall)，正好是印章那一档。
     */
    val posterInset = 8.dp

    // 宽高比
    /** 海报卡用 2:3 的经典电影海报比例。3:4 偏方，铺下去像九宫格图片列表；2:3 才有"海报墙"的样子。 */
    const val posterAspect = 2f / 3f
    /**
     * 详情页信息块的封面：横向并排布局，用更紧凑的 3:4。不跟网格共用 2:3 是因为那边右侧
     * 还有一整列文字，封面再拉长会把整块撑高出一截空白。同一份内容在「墙」和「块」里的
     * 裁剪比例可以不同。
     */
    const val detailPosterAspect = 3f / 4f
    const val videoAspect = 16f / 9f
}
