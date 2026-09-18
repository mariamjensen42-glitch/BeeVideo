package com.cycling.beevideo.ui.preview

import com.cycling.beevideo.domain.model.PlayTarget
import com.cycling.beevideo.domain.model.PlaybackState
import com.cycling.beevideo.domain.repository.PlaybackSession
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * 预览与 JVM 测试用的假播放会话。
 *
 * 真实现要 `Context` + ExoPlayer，预览和单测里都跑不起来。有了它，
 * 播放页的**宿主**（状态持有者）第一次能在纯 JVM 上被测：它什么时候上报进度、
 * 切集前读的是哪一刻的位置、离开时有没有 `close()`，全都能断言。
 *
 * 位置与时长是**可写字段**而不是固定值：用例需要表达"播到 90 秒"这类场景。
 */
class FakePlaybackSession : PlaybackSession {

    private val _state = MutableStateFlow<PlaybackState>(PlaybackState.Idle)
    override val state: StateFlow<PlaybackState> = _state.asStateFlow()

    /** 当前"播放位置"。宿主保存进度时读的就是它。 */
    var currentPositionMs: Long = 0L

    /** 当前"媒体时长"；0 = 来源没给。 */
    var currentDurationMs: Long = 0L

    /** 每次 [open] 的记录，按调用顺序。用来断言"切集前先读了位置"这类编排。 */
    val opens = mutableListOf<OpenCall>()

    var closeCount = 0
        private set

    data class OpenCall(val target: PlayTarget, val resumeAtMs: Long)

    override fun positionMs(): Long = currentPositionMs

    override fun durationMs(): Long = currentDurationMs

    override fun open(target: PlayTarget, resumeAtMs: Long) {
        opens += OpenCall(target, resumeAtMs)
        currentPositionMs = resumeAtMs
        _state.value = PlaybackState.Playing
    }

    override fun close() {
        closeCount++
        _state.value = PlaybackState.Idle
    }

    /** 把状态推到某个值（模拟缓冲 / 失败 / 暂停）。 */
    fun emit(state: PlaybackState) {
        _state.value = state
    }
}
