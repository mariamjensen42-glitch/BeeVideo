package com.cycling.beevideo.ui.player

import com.cycling.beevideo.domain.model.PlaybackState

/**
 * 这一集播完之后该自动跳到哪一集；`null` = 不跳。
 *
 * 抽成纯函数是为了**能测**：它住在 composable 里的时候，"最后一集播完会不会试图跳出去"
 * 只能靠真机等到片尾才知道，而那个判据写错的表现是一个越界的集号 ——
 * 界面会短暂显示"无可播放剧集"，看起来像源坏了。
 */
internal fun autoNextEpisode(
    state: PlaybackState,
    currentIndex: Int,
    episodeCount: Int,
    enabled: Boolean,
): Int? {
    if (!enabled) return null
    if (state !is PlaybackState.Ended) return null
    // 最后一集（以及空列表）没有下一集；越界的集号也要挡住，否则会跳到一个不存在的项
    if (currentIndex !in 0 until episodeCount - 1) return null
    return currentIndex + 1
}
