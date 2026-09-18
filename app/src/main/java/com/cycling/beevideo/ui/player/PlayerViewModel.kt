package com.cycling.beevideo.ui.player

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.cycling.beevideo.domain.repository.LibraryRepository
import com.cycling.beevideo.domain.repository.PlaybackSession

/**
 * [PlayerPlaybackState] 的 Android 宿主 —— 它只做一件 [PlayerPlaybackState] 做不到的事：
 * **跨 Activity 重建存活**。
 *
 * 为什么必须活下来：`AndroidManifest` 的 `configChanges` 不含 `uiMode`，
 * 所以这个 App 自己的主题切换会重建 Activity。会话与集号要是在组合里，
 * 重建就意味着回到路由参数那一集 0:00。
 *
 * 收场（落进度 + 释放内核）由 [onCleared] 负责 —— 导航栈把播放页弹掉时触发。
 * **界面不要自己调 `release()`**：配置变化同样会让组合销毁，而那一刻不该收场。
 */
class PlayerViewModel(
    session: PlaybackSession,
    library: LibraryRepository,
    vodId: String,
    initialEpisodeIndex: Int,
) : ViewModel() {

    val playback = PlayerPlaybackState(
        session = session,
        library = library,
        vodId = vodId,
        initialEpisodeIndex = initialEpisodeIndex,
        scope = viewModelScope,
    )

    override fun onCleared() {
        playback.release()
    }
}
