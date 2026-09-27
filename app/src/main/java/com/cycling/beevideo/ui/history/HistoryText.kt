package com.cycling.beevideo.ui.history

import com.cycling.beevideo.ui.player.formatClock
import java.time.Instant
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.temporal.ChronoUnit
import kotlin.math.roundToInt

/**
 * 历史行的文案计算 —— 全是纯函数。
 *
 * 判据（是今天吗、看完没有、百分比是多少）留在这里，**文案本身留在资源里**：
 * 一个是算术，一个是语言。混在一起的话「昨天」这类词就没法测，而
 * `isReturnDefaultValues` 下 `android.*` 返回 null 而非抛异常，测试会静默算错（见 MEMORY）。
 */
internal sealed interface WatchedAt {

    /** 今天（含时间戳落在未来：时钟被往回校时，说"今天"比说"-1 天前"诚实）。 */
    data class Today(val clock: String) : WatchedAt

    data class Yesterday(val clock: String) : WatchedAt

    /** 前天到六天前。七天以上就是一周前了，给日期比给天数好读。 */
    data class DaysAgo(val days: Int) : WatchedAt

    /** 今年内的更早日子，不写年份。 */
    data class Date(val month: Int, val day: Int) : WatchedAt

    /** 跨年了，年份必须写 —— "9月25日"在跨年时是有歧义的。 */
    data class FullDate(val year: Int, val month: Int, val day: Int) : WatchedAt
}

/** 七天往上就该给日期了。 */
private const val DAYS_AGO_LIMIT = 6L

/**
 * 最后观看时间落在"什么时候"。
 *
 * [zone] 必须显式传而不是内部取 `ZoneId.systemDefault()`：那样测试就只能依赖跑测机器的时区，
 * 而"昨天 23:50 看成前天"这类错误恰恰只在一个时区里出现。
 */
internal fun watchedAt(updatedAt: Long, now: Long, zone: ZoneId): WatchedAt {
    val at = Instant.ofEpochMilli(updatedAt).atZone(zone)
    val today = Instant.ofEpochMilli(now).atZone(zone).toLocalDate()
    val days = ChronoUnit.DAYS.between(at.toLocalDate(), today)

    return when {
        // days < 0 = 时间戳在未来（改过系统时间、或来源给的时间不准）
        days <= 0L -> WatchedAt.Today(clockOf(at))
        days == 1L -> WatchedAt.Yesterday(clockOf(at))
        days <= DAYS_AGO_LIMIT -> WatchedAt.DaysAgo(days.toInt())
        // "是不是今年"在这里判完，而不是留给界面去问系统时钟 —— 那样文案就不可测了
        at.year == today.year -> WatchedAt.Date(at.monthValue, at.dayOfMonth)
        else -> WatchedAt.FullDate(at.year, at.monthValue, at.dayOfMonth)
    }
}

private fun clockOf(at: ZonedDateTime): String = "%02d:%02d".format(at.hour, at.minute)

/**
 * 「看到多少」的时钟文本，如 `12:34 / 45:00`。
 *
 * 时长未知（0，见 `PlayProgress.durationMs`）时只给位置 —— 不编一个分母出来。
 */
internal fun progressClock(positionMs: Long, durationMs: Long): String =
    if (durationMs > 0L) {
        "${formatClock(positionMs)} / ${formatClock(durationMs)}"
    } else {
        formatClock(positionMs)
    }

/**
 * 进度比例，0..1。**时长未知时返回 null**：历史行据此整条不画进度条 ——
 * 画一条永远 0% 的轨道比不画更像"加载失败"。
 */
internal fun progressFraction(positionMs: Long, durationMs: Long): Float? =
    if (durationMs > 0L) {
        (positionMs.toFloat() / durationMs).coerceIn(0f, 1f)
    } else {
        null
    }

/** `0.35f` → `35`，供 `history_progress_percent` 用。 */
internal fun progressPercent(fraction: Float): Int =
    (fraction * 100f).roundToInt().coerceIn(0, 100)
