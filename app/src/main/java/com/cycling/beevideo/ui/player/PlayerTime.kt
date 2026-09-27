package com.cycling.beevideo.ui.player

/**
 * 位置 / 时长的时钟文本。
 *
 * 不满一小时写 `MM:SS`（`03:07`），超过写 `H:MM:SS`（`1:03:07`）——
 * 电影级长度下 `63:07` 这种"分钟一直涨"的写法没人能一眼读出是多久。
 *
 * `0`（来源没给时长）和负数一律回 `00:00`：控件里那个位置显示的是"末尾"，
 * 留空会让人以为控件坏了，而 `--:--` 又挡不住用户反复来问。
 */
internal fun formatClock(ms: Long): String {
    val totalSeconds = (ms / 1_000L).coerceAtLeast(0L)
    val hours = totalSeconds / 3_600L
    val minutes = totalSeconds % 3_600L / 60L
    val seconds = totalSeconds % 60L
    return if (hours > 0L) {
        "$hours:${pad2(minutes)}:${pad2(seconds)}"
    } else {
        "${pad2(minutes)}:${pad2(seconds)}"
    }
}

private fun pad2(value: Long): String = value.toString().padStart(2, '0')
