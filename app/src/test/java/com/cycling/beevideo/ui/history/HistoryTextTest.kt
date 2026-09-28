package com.cycling.beevideo.ui.history

import java.time.LocalDateTime
import java.time.ZoneId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * 历史行文案的判据。
 *
 * 这些判据以前只能靠"改一下系统时间再进历史页看看"来验，而那是**最容易看错**的一类：
 * 「昨天 23:50」和「今天 00:10」在屏幕上只差一个字，判错了没人看得出来。
 *
 * 时区**显式钉死**而不是用 `systemDefault()`：跨零点那几条用例的结果依赖时区，
 * 跟着跑测机器走的话，同一个用例在两个地方会给出两种结论。
 */
class HistoryTextTest {

    @Test
    fun `同一天就是今天带时钟`() {
        assertEquals(
            WatchedAt.Today("20:15"),
            watchedAt(at(2026, 9, 27, 20, 15), now = at(2026, 9, 27, 22, 0), zone = ZONE),
        )
    }

    /**
     * 判据是**日历天**不是"过了 24 小时"：凌晨 1 点看昨晚 23:50 看的那条，该是「昨天」
     * 而不是「今天」（只差 70 分钟）。反过来，今天 00:01 与今天 23:59 之间差了近 24 小时，
     * 仍然是「今天」。
     */
    @Test
    fun `跨过零点算昨天，哪怕只差一个多小时`() {
        assertEquals(
            WatchedAt.Yesterday("23:50"),
            watchedAt(at(2026, 9, 26, 23, 50), now = at(2026, 9, 27, 1, 0), zone = ZONE),
        )
        assertEquals(
            WatchedAt.Today("00:01"),
            watchedAt(at(2026, 9, 27, 0, 1), now = at(2026, 9, 27, 23, 59), zone = ZONE),
        )
    }

    @Test
    fun `前天到六天前给天数`() {
        assertEquals(
            WatchedAt.DaysAgo(3),
            watchedAt(at(2026, 9, 24, 10, 0), now = at(2026, 9, 27, 22, 0), zone = ZONE),
        )
        // 六天是"天前"这一档的上界
        assertEquals(
            WatchedAt.DaysAgo(6),
            watchedAt(at(2026, 9, 21, 10, 0), now = at(2026, 9, 27, 22, 0), zone = ZONE),
        )
    }

    @Test
    fun `七天以上就是日期，今年不写年份`() {
        assertEquals(
            WatchedAt.Date(9, 20),
            watchedAt(at(2026, 9, 20, 10, 0), now = at(2026, 9, 27, 22, 0), zone = ZONE),
        )
    }

    @Test
    fun `跨年必须写年份`() {
        // 14 天前 —— 已经超出「N 天前」那一档，而且年份不同，"12月20日"会有歧义
        assertEquals(
            WatchedAt.FullDate(2025, 12, 20),
            watchedAt(at(2025, 12, 20, 23, 0), now = at(2026, 1, 3, 9, 0), zone = ZONE),
        )
    }

    /**
     * 跨年但只差几天时**仍然是「N 天前」**：这一档比日期好读，年份在这里不构成歧义。
     */
    @Test
    fun `跨年但只差三天还是给天数`() {
        assertEquals(
            WatchedAt.DaysAgo(3),
            watchedAt(at(2025, 12, 31, 23, 0), now = at(2026, 1, 3, 9, 0), zone = ZONE),
        )
    }

    /** 时间戳落在未来（用户改过系统时间、或来源给的时间不准）时说"今天"，而不是"-1 天前"。 */
    @Test
    fun `未来的时间戳按今天算`() {
        assertEquals(
            WatchedAt.Today("23:30"),
            watchedAt(at(2026, 9, 27, 23, 30), now = at(2026, 9, 27, 22, 0), zone = ZONE),
        )
    }

    @Test
    fun `时长已知时给出 位置 加 分母`() {
        assertEquals("12:30 / 45:00", progressClock(750_000L, 2_700_000L))
    }

    /** 来源没给时长（0）时只给位置 —— 不编一个分母出来。 */
    @Test
    fun `时长未知时只给位置`() {
        assertEquals("12:30", progressClock(750_000L, 0L))
    }

    /** 时长未知时整条进度条不画，所以比例也得是 null 而不是 0。 */
    @Test
    fun `时长未知时没有比例`() {
        assertNull(progressFraction(750_000L, 0L))
    }

    @Test
    fun `比例夹在 0 到 1 之间`() {
        assertEquals(0f, progressFraction(-1_000L, 2_700_000L)!!, FLOAT_DELTA)
        assertEquals(1f, progressFraction(9_999_999L, 2_700_000L)!!, FLOAT_DELTA)
    }

    @Test
    fun `百分比四舍五入`() {
        assertEquals(35, progressPercent(0.3524f))
        assertEquals(36, progressPercent(0.3551f))
    }

    private companion object {
        const val FLOAT_DELTA = 1e-6f
        val ZONE: ZoneId = ZoneId.of("Asia/Shanghai")

        fun at(year: Int, month: Int, day: Int, hour: Int, minute: Int): Long =
            LocalDateTime.of(year, month, day, hour, minute)
                .atZone(ZONE)
                .toInstant()
                .toEpochMilli()
    }
}
