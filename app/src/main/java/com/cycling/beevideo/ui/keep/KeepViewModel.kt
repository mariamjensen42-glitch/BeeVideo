package com.cycling.beevideo.ui.keep

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.cycling.beevideo.domain.repository.LibraryRepository

/**
 * [KeepState] 的 Android 宿主 —— 它只做一件 [KeepState] 做不到的事：
 * **跨 Activity 重建存活**（`AndroidManifest` 的 `configChanges` 不含 `uiMode`，
 * 这个 App 自己的主题切换会重建 Activity）。理由与分工见 `docs/adr/0004`。
 */
class KeepViewModel(library: LibraryRepository) : ViewModel() {

    private val state = KeepState(library, viewModelScope)

    val uiState = state.uiState
    val effect = state.effect

    fun onIntent(intent: KeepIntent) = state.onIntent(intent)
}
