package com.cycling.beevideo.ui.detail

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.cycling.beevideo.domain.repository.ContentRepository
import com.cycling.beevideo.domain.repository.LibraryRepository

/**
 * [DetailState] 的 Android 宿主 —— 它只做一件 [DetailState] 做不到的事：
 * **跨 Activity 重建存活**。
 *
 * 为什么必须活下来：`AndroidManifest` 的 `configChanges` 不含 `uiMode`，
 * 所以这个 App 自己的主题切换会重建 Activity。线路号要是住在 `remember` 里，
 * 回来就归零 —— 而进度是按「线路名 + 集号」定位的，线路没了进度条也就对不上了。
 */
class DetailViewModel(
    content: ContentRepository,
    library: LibraryRepository,
    vodId: String,
) : ViewModel() {

    val state = DetailState(
        content = content,
        library = library,
        vodId = vodId,
        scope = viewModelScope,
    )
}
