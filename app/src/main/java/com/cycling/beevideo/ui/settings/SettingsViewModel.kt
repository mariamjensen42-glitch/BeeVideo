package com.cycling.beevideo.ui.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.cycling.beevideo.domain.repository.ContentSourceRepository
import com.cycling.beevideo.domain.repository.MediaCache

/**
 * [SettingsState] 的 Android 宿主 —— 它只做一件 [SettingsState] 做不到的事：
 * **跨 Activity 重建存活**（`applying` 因此不会因为切主题被清掉）。
 */
class SettingsViewModel(
    sources: ContentSourceRepository,
    cache: MediaCache,
) : ViewModel() {

    val state = SettingsState(sources = sources, cache = cache, scope = viewModelScope)
}
