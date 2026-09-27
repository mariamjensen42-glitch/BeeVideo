package com.cycling.beevideo.ui.history

import com.cycling.beevideo.domain.model.PlayProgress

/**
 * 观看历史页的界面状态。
 *
 * 「还没读到」与「读到了、是空的」必须分开（同收藏页）：合成一个的话，进这一页第一帧
 * 会闪一下「还没有观看记录」再冒出内容 —— 本地库只要几毫秒，而恰恰是用户盯着屏幕的那
 * 几毫秒，看起来像"记录丢了"。
 */
data class HistoryUiState(
    val loading: Boolean = true,
    val records: List<PlayProgress> = emptyList(),
    /** 已收藏的 vodId。长按菜单靠它决定画实心还是描边。 */
    val keptIds: Set<String> = emptySet(),
    /** 站点 key（vodId 的前缀）→ 给人看的来源名。换不到就不显示那一段。 */
    val sourceNames: Map<String, String> = emptyMap(),
)

sealed interface HistoryIntent {
    /** 从记录里的线路号直接续播 —— 不先查详情，见 `HistoryScreen` 的说明。 */
    data class OnContinue(val progress: PlayProgress) : HistoryIntent

    data class OnOpenDetail(val vodId: String) : HistoryIntent

    /** 长按菜单里的收藏 / 取消收藏。 */
    data class OnToggleKeep(val progress: PlayProgress) : HistoryIntent

    /** 只删这一条记录，与收藏无关。 */
    data class OnDeleteRecord(val vodId: String) : HistoryIntent

    /** 清空全部记录。不可撤销，二次确认由界面负责。 */
    data object OnClearAll : HistoryIntent

    data object OnBack : HistoryIntent
}

sealed interface HistoryEffect {
    data class Continue(val progress: PlayProgress) : HistoryEffect

    data class OpenDetail(val vodId: String) : HistoryEffect

    data object Back : HistoryEffect
}
