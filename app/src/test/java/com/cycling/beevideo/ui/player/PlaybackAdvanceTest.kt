package com.cycling.beevideo.ui.player

import com.cycling.beevideo.domain.model.PlaybackFailure
import com.cycling.beevideo.domain.model.PlaybackState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * 自动下一集的判据。
 *
 * 这一段以前只能靠真机等到片尾：判据写错的表现是一个**越界的集号** ——
 * 界面会短暂跳到"无可播放剧集"，看起来像源坏了，而不是像判断错了。
 */
class PlaybackAdvanceTest {

    @Test
    fun `播完且不是最后一集时跳下一集`() {
        assertEquals(
            4,
            autoNextEpisode(
                state = PlaybackState.Ended,
                currentIndex = 3,
                episodeCount = 10,
                enabled = true,
            ),
        )
    }

    @Test
    fun `最后一集播完不跳`() {
        assertNull(
            "跳出去会得到一个不存在的集号，界面上表现为'无可播放剧集'",
            autoNextEpisode(
                state = PlaybackState.Ended,
                currentIndex = 9,
                episodeCount = 10,
                enabled = true,
            ),
        )
    }

    @Test
    fun `只有一集时播完不跳`() {
        assertNull(
            autoNextEpisode(
                state = PlaybackState.Ended,
                currentIndex = 0,
                episodeCount = 1,
                enabled = true,
            ),
        )
    }

    @Test
    fun `剧集列表为空时不跳`() {
        assertNull(
            autoNextEpisode(
                state = PlaybackState.Ended,
                currentIndex = 0,
                episodeCount = 0,
                enabled = true,
            ),
        )
    }

    @Test
    fun `关掉连播时不跳`() {
        assertNull(
            autoNextEpisode(
                state = PlaybackState.Ended,
                currentIndex = 0,
                episodeCount = 10,
                enabled = false,
            ),
        )
    }

    /**
     * 四种"没有在播"的状态都不能触发跳集。
     *
     * 特别是 [PlaybackState.Idle]：`STATE_ENDED` 曾经和它并成一种，那时这条判据
     * 根本无从下手 —— 也就没法把"播完了"和"还没起播"分开。
     */
    @Test
    fun `非播完状态一律不跳`() {
        val others = listOf(
            PlaybackState.Idle,
            PlaybackState.Buffering,
            PlaybackState.Playing,
            PlaybackState.Paused,
            PlaybackState.Failed(PlaybackFailure.Kernel, detail = null),
        )

        others.forEach { state ->
            assertNull(
                "$state 不该触发自动下一集",
                autoNextEpisode(
                    state = state,
                    currentIndex = 0,
                    episodeCount = 10,
                    enabled = true,
                ),
            )
        }
    }
}
