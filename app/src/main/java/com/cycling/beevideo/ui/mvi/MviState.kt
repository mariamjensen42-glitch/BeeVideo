package com.cycling.beevideo.ui.mvi

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow

/**
 * 单向数据流的底座：State 单一、Intent 单入口、一次性事件走 Effect。
 *
 * ⚠️ 这是**状态持有者**的基类，不是 ViewModel 的 —— 分工见
 * `docs/adr/0004-state-holder-vs-viewmodel.md`：逻辑住在普通类里（作用域由宿主注入，
 * JVM 可测），ViewModel 只是它的 Android 宿主。所以这里不碰 `viewModelScope`。
 *
 * 不抽 ViewModel 基类：那需要基类在构造期读子类的 `abstract val`（拿到 null），
 * 而每页省下的只有三行转发，见 `docs/adr/0009-mvi-layer.md`。
 */
abstract class MviState<S : Any, I : Any, E : Any>(
    initialState: S,
    /** 加载与写入用的作用域，**由宿主提供**（生产是 `viewModelScope`）。 */
    protected val scope: CoroutineScope,
) {

    private val _uiState = MutableStateFlow(initialState)
    val uiState: StateFlow<S> = _uiState.asStateFlow()

    /*
     * 一次性事件用 Channel 而不是 StateFlow：StateFlow 会**重放**最后一帧，
     * 而"导航"重放一次就是多跳一层 —— 旋转屏回来会再跳一次详情页。
     * Channel 里的东西只被取走一次。
     */
    private val _effect = Channel<E>(Channel.BUFFERED)
    val effect: Flow<E> = _effect.receiveAsFlow()

    /** 读当前状态。给 `onIntent` 里的同步判断用（例如空关键词直接忽略）。 */
    protected val currentState: S get() = _uiState.value

    /** 改状态只有这一条路：`setState { it.copy(...) }`。 */
    protected fun setState(reducer: (S) -> S) {
        _uiState.value = reducer(_uiState.value)
    }

    /** 发一次性事件。非 suspend —— 它常从同步的 `onIntent` 分支里发。 */
    protected fun sendEffect(effect: E) {
        _effect.trySend(effect)
    }

    /** 子类唯一的公开入口。界面只发 Intent，不直接调动作。 */
    abstract fun onIntent(intent: I)
}
