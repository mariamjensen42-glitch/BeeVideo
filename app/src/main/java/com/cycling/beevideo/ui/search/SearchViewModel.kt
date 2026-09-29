package com.cycling.beevideo.ui.search

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.cycling.beevideo.domain.repository.ContentRepository
import com.cycling.beevideo.domain.repository.SearchHistoryRepository

/**
 * [SearchState] 的 Android 宿主 —— 它只做一件 [SearchState] 做不到的事：
 * **跨 Activity 重建存活**（`configChanges` 不含 `uiMode`）。
 *
 * 这一页尤其需要它：用户打了字、搜出了结果，切一次主题回来不该从头开始。
 */
class SearchViewModel(
    content: ContentRepository,
    history: SearchHistoryRepository,
) : ViewModel() {

    private val state = SearchState(content, history, viewModelScope)

    val uiState = state.uiState
    val effect = state.effect

    fun onIntent(intent: SearchIntent) = state.onIntent(intent)
}
