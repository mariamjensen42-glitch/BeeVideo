package com.cycling.beevideo.ui.keep

import com.cycling.beevideo.domain.model.KeepItem

/**
 * 收藏页的界面状态。
 *
 * 字段都有默认值，所以预览与单测都不必造夹具 —— `KeepUiState(loading = false, keeps = …)`
 * 就能画满一屏，界面从此不认识仓储。
 */
data class KeepUiState(
    /**
     * 「还没读到」与「读到了、是空的」必须分开。合成"列表为空就显示空态"的话，
     * 进这一页第一帧会闪一下「还没有收藏」再冒出内容 —— 本地库只要几毫秒，
     * 但恰恰是用户盯着屏幕的那几毫秒，看起来像"收藏丢了"。
     */
    val loading: Boolean = true,
    val keeps: List<KeepItem> = emptyList(),
)

sealed interface KeepIntent {
    /** 点一张卡。进详情是**导航** —— 一次性事件，由 [KeepEffect] 承载。 */
    data class OnOpenVod(val vodId: String) : KeepIntent

    /** 长按菜单里的「取消收藏」。 */
    data class OnRemoveKeep(val vodId: String) : KeepIntent
}

/**
 * 本页的一次性事件。
 *
 * 导航走 Effect 而不是 Route 直接传回调：界面因此只认 `(KeepUiState, (KeepIntent) -> Unit)`
 * 两个参数，而"点了卡会跳详情"这件事也变成可断言的（见 `KeepStateTest`）。
 */
sealed interface KeepEffect {
    data class OpenVod(val vodId: String) : KeepEffect
}
