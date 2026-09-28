package com.cycling.beevideo.ui.player

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 手势换算的边界。
 *
 * 这些数字在真机上没法自动验证（"滑一屏亮度涨多少"要靠手感），但**越界与零值**能钉住：
 * 高度为 0、时长为 0、宽度为 0 这三个退化输入都不是假设 —— 时长为 0 是常态
 * （来源没给时长），宽高为 0 出现在画面还没测量完的第一帧。
 */
class PlayerGestureMathTest {

    @Test
    fun `竖直滑动 整屏高等于满量程`() {
        // 向上滑（dragPx 为负）变大，整屏正好走完 0..1
        assertEquals(1f, dragToLevel(start = 0.5f, dragPx = -500f, heightPx = 1_000f))
        assertEquals(0f, dragToLevel(start = 0.5f, dragPx = 500f, heightPx = 1_000f))
        assertEquals(0.75f, dragToLevel(start = 0.5f, dragPx = -250f, heightPx = 1_000f))
    }

    @Test
    fun `竖直滑动 越界夹到两端`() {
        assertEquals(1f, dragToLevel(0.9f, -10_000f, 1_000f))
        assertEquals(0f, dragToLevel(0.1f, 10_000f, 1_000f))
    }

    @Test
    fun `竖直滑动 高度为 0 时原样返回`() {
        // 第一帧的画面高度是 0，这时按 0 除会得到 Infinity/NaN
        assertEquals(0.5f, dragToLevel(0.5f, -300f, 0f))
        assertEquals(0.5f, dragToLevel(0.5f, -300f, -1f))
    }

    @Test
    fun `滑动跨度 按总时长的十分之一`() {
        assertEquals(120_000L, seekSpanMs(1_200_000L))
    }

    @Test
    fun `滑动跨度 两端被夹住`() {
        // 1 分钟 → 6 秒太细，抬到下限
        assertEquals(MIN_SEEK_SPAN_MS, seekSpanMs(60_000L))
        // 2 小时 → 12 分钟太粗，压到上限
        assertEquals(MAX_SEEK_SPAN_MS, seekSpanMs(7_200_000L))
    }

    @Test
    fun `水平滑动 一屏宽等于一个跨度`() {
        val duration = 1_200_000L // 跨度是 2 分钟
        assertEquals(220_000L, dragToPositionMs(100_000L, 1_080f, 1_080f, duration))
        // 往回滑一屏：100 秒 − 120 秒，夹到 0
        assertEquals(0L, dragToPositionMs(100_000L, -1_080f, 1_080f, duration))
    }

    @Test
    fun `水平滑动 越界夹到两端`() {
        val duration = 1_200_000L
        assertEquals(duration, dragToPositionMs(600_000L, 10_800f, 1_080f, duration))
        assertEquals(0L, dragToPositionMs(600_000L, -10_800f, 1_080f, duration))
    }

    @Test
    fun `时长未知时水平滑动不动`() {
        // durationMs = 0 是"来源没给时长"的常态。这时滑到 0 是纯粹的惊吓
        assertEquals(5_000L, dragToPositionMs(5_000L, 1_080f, 1_080f, 0L))
    }

    @Test
    fun `宽度为 0 时水平滑动夹回时长范围内`() {
        assertEquals(1_200_000L, dragToPositionMs(9_999_999L, 100f, 0f, 1_200_000L))
        assertEquals(0L, dragToPositionMs(-9_999_999L, 100f, 0f, 1_200_000L))
    }
}
