package com.cycling.beevideo.ui.player

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 进度控件上那一对时钟文本。
 *
 * 看上去是"一行格式化"，但它的两个分支各自有一个真会咬人的地方：**满一小时要换写法**
 * （否则长片显示成 `63:07`，一眼读不出多久），以及 **0 要回 `00:00` 而不是空串**
 * （来源没给时长时 `durationMs` 就是 0）。两条都写进用例，改动时不会悄悄丢。
 */
class PlayerTimeTest {

    @Test
    fun `零与负数都回 00 00`() {
        assertEquals("00:00", formatClock(0L))
        assertEquals("00:00", formatClock(-1L))
        assertEquals("00:00", formatClock(-90_000L))
    }

    @Test
    fun `不足一秒向下取整`() {
        assertEquals("00:00", formatClock(999L))
        assertEquals("00:01", formatClock(1_000L))
    }

    @Test
    fun `不满一小时写两位分加两位秒`() {
        assertEquals("00:59", formatClock(59_000L))
        assertEquals("03:07", formatClock(187_000L))
        assertEquals("59:59", formatClock(3_599_000L))
    }

    @Test
    fun `满一小时起写 时 分 秒`() {
        assertEquals("1:00:00", formatClock(3_600_000L))
        assertEquals("1:03:07", formatClock(3_787_000L))
        assertEquals("12:00:00", formatClock(43_200_000L))
    }
}
