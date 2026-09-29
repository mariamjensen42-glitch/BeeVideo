package com.cycling.beevideo.ui.player

import com.cycling.beevideo.domain.model.PlaybackFailure
import com.cycling.beevideo.domain.model.PlaybackState
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 「按 Home 时该不该自动缩成**系统**小窗」的判据。
 *
 * ⚠️ 它**不管返回键** —— 返回键一律退回 App 内的上一页，画面交给应用内小窗。
 *
 * 写错这一条的表现：暂停着按 Home 冒出一块小窗（用户明明已经停下来了），
 * 或者开关关着还冒小窗（默认档下不该出现系统小窗）。
 */
class PlayerPipTest {

    @Test
    fun `在播或缓冲时自动进小窗`() {
        assertTrue(shouldAutoEnterPip(PlaybackState.Playing, pipEnabled = true))
        assertTrue(
            "缓冲中画面正常在转圈，掐掉比留着突兀",
            shouldAutoEnterPip(PlaybackState.Buffering, pipEnabled = true),
        )
    }

    @Test
    fun `暂停后不自动进小窗`() {
        assertFalse(shouldAutoEnterPip(PlaybackState.Paused, pipEnabled = true))
    }

    @Test
    fun `播完与没起播都不自动进小窗`() {
        assertFalse(shouldAutoEnterPip(PlaybackState.Ended, true))
        assertFalse(shouldAutoEnterPip(PlaybackState.Idle, true))
        assertFalse(shouldAutoEnterPip(PlaybackState.Failed(PlaybackFailure.Kernel, null), true))
    }

    /** 默认档：用户没开画中画，退到后台就只是退到后台，音频由通知栏管。 */
    @Test
    fun `开关关着时一律不进`() {
        assertFalse(shouldAutoEnterPip(PlaybackState.Playing, pipEnabled = false))
        assertFalse(shouldAutoEnterPip(PlaybackState.Buffering, pipEnabled = false))
    }
}
