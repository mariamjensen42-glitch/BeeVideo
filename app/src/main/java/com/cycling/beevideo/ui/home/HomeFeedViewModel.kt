package com.cycling.beevideo.ui.home

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.cycling.beevideo.domain.repository.ContentRepository

/**
 * [HomeFeedState] 的 Android 宿主 —— 它只做一件 [HomeFeedState] 做不到的事：
 * **跨 Activity 重建存活**。
 *
 * 为什么必须活下来：`AndroidManifest` 的 `configChanges` 不含 `uiMode`，
 * 所以这个 App 自己的主题切换会重建 Activity。选中的分类要是住在 `remember` 里，
 * 回来就归零 —— 而分类 id 在不同站点之间会撞车，归零不是回到「推荐」，
 * 是回到第一个分类。
 */
class HomeFeedViewModel(content: ContentRepository) : ViewModel() {

    val state = HomeFeedState(content = content, scope = viewModelScope)
}
