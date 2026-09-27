package com.cycling.beevideo.ui.player

/*
 * 手势的换算规则，全是纯函数 —— 用像素算百分比、用像素算时间。
 *
 * 抽出来的唯一理由是**可测**：真机上"滑一屏亮度涨多少"没法自动验证，而这几个
 * 函数在 JVM 单测里能钉住边界（滑出屏幕、时长为 0、宽度为 0）。
 */

/** 一整屏宽对应的滑动时长，占视频总长的几分之一。见 [seekSpanMs]。 */
private const val SEEK_SCREEN_FRACTION = 10f

/** 滑动跨度的下限。短视频一碰就到头的话，手势等于不能用。 */
internal const val MIN_SEEK_SPAN_MS = 30_000L

/** 滑动跨度的上限。两小时的片子按 1/10 算是 12 分钟，一屏扫过 12 分钟太粗糙。 */
internal const val MAX_SEEK_SPAN_MS = 600_000L

/**
 * 竖直滑动（亮度 / 音量）的换算：**整屏高 = 满量程**，向上滑变大。
 *
 * 用"当前值 + 位移"而不是"按下位置对应的绝对值"：后者在手指离开再落下时会让
 * 亮度跳一下（起点重新映射），而用户只是想接着往上滑。
 *
 * @param start 按下时的值，0f..1f
 * @param dragPx 相对按下点的竖直位移，向下为正（屏幕坐标系）
 * @return 0f..1f
 */
internal fun dragToLevel(start: Float, dragPx: Float, heightPx: Float): Float {
    if (heightPx <= 0f) return start.coerceIn(0f, 1f)
    return (start - dragPx / heightPx).coerceIn(0f, 1f)
}

/**
 * 水平滑动一整屏对应的时长。
 *
 * 按**比例**取而不是固定秒数：90 分钟的片子用固定 ±90 秒要滑十几次才到头，
 * 20 秒的片段用 ±10 分钟则一碰就到底。夹在两端之间兜住这两种极端。
 */
internal fun seekSpanMs(durationMs: Long): Long =
    (durationMs / SEEK_SCREEN_FRACTION.toLong()).coerceIn(MIN_SEEK_SPAN_MS, MAX_SEEK_SPAN_MS)

/**
 * 水平滑动的目标位置。向右滑变大。
 *
 * @param startMs 按下时的位置
 * @param dragPx 相对按下点的水平位移
 * @param widthPx 画面宽
 * @param durationMs 总时长；**0 表示还不知道时长**，此时不换算（见下）
 */
internal fun dragToPositionMs(
    startMs: Long,
    dragPx: Float,
    widthPx: Float,
    durationMs: Long,
): Long {
    // 时长未知时返回原位而不是 0：进度条此时也没有可拖的范围，跳回开头是纯粹的惊吓
    if (durationMs <= 0L) return startMs.coerceAtLeast(0L)
    if (widthPx <= 0f) return startMs.coerceIn(0L, durationMs)
    val offset = (dragPx / widthPx * seekSpanMs(durationMs)).toLong()
    return (startMs + offset).coerceIn(0L, durationMs)
}
