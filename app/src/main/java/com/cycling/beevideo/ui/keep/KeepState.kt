package com.cycling.beevideo.ui.keep

import com.cycling.beevideo.domain.repository.LibraryRepository
import com.cycling.beevideo.ui.mvi.MviState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/**
 * 收藏页的状态持有者。
 *
 * 订阅 [`LibraryRepository.keeps`] 而不是读一次：用户可能在这一页停留时从别处改了收藏，
 * 靠 Flow 那张卡才会立刻消失，不需要下拉刷新。
 */
class KeepState(
    private val library: LibraryRepository,
    scope: CoroutineScope,
) : MviState<KeepUiState, KeepIntent, KeepEffect>(KeepUiState(), scope) {

    init {
        scope.launch {
            library.keeps.collect { keeps ->
                // 第一次发射即"读到了"，loading 从此为假 —— 空列表这时才等于"真的没有"
                setState { it.copy(loading = false, keeps = keeps) }
            }
        }
    }

    override fun onIntent(intent: KeepIntent) {
        when (intent) {
            is KeepIntent.OnOpenVod -> sendEffect(KeepEffect.OpenVod(intent.vodId))

            // 不在这里改列表：`keeps` 是 Flow，删完库自己会重发一帧
            is KeepIntent.OnRemoveKeep -> scope.launch { library.removeKeep(intent.vodId) }
        }
    }
}
