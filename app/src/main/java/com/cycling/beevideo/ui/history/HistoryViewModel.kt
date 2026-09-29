package com.cycling.beevideo.ui.history

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.cycling.beevideo.domain.repository.ContentSourceRepository
import com.cycling.beevideo.domain.repository.IncognitoMode
import com.cycling.beevideo.domain.repository.LibraryRepository

/**
 * [HistoryState] 的 Android 宿主 —— 它只做一件 [HistoryState] 做不到的事：
 * **跨 Activity 重建存活**（`configChanges` 不含 `uiMode`）。理由见 `docs/adr/0004`。
 */
class HistoryViewModel(
    library: LibraryRepository,
    sources: ContentSourceRepository,
    incognito: IncognitoMode,
) : ViewModel() {

    private val state = HistoryState(library, sources, incognito, scope = viewModelScope)

    val uiState = state.uiState
    val effect = state.effect

    fun onIntent(intent: HistoryIntent) = state.onIntent(intent)
}
