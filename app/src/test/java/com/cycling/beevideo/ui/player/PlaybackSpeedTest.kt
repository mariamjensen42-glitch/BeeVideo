package com.cycling.beevideo.ui.player

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 倍速档位与它们的文案。
 *
 * 文案那条用例是**防回归**：把 `speedLabel` 改成 `String.format("%.1f")` 是最自然的
 * "顺手优化"，而它会静默把 `0.75×` 变成 `0.8×` —— 界面看着正常，只是档位标错了，
 * 用户会以为 0.75 那档失效了。
 */
class PlaybackSpeedTest {

    @Test
    fun `档位升序，且含原速`() {
        assertEquals(PLAYBACK_SPEEDS.sorted(), PLAYBACK_SPEEDS)
        assertTrue("没有 1.0x 这一档的话用户回不到原速", PLAYBACK_SPEEDS.contains(1.0f))
    }

    @Test
    fun `文案保留原小数位并带乘号`() {
        assertEquals("0.5×", speedLabel(0.5f))
        assertEquals("0.75×", speedLabel(0.75f))
        assertEquals("1.0×", speedLabel(1.0f))
        assertEquals("1.25×", speedLabel(1.25f))
        assertEquals("1.5×", speedLabel(1.5f))
        assertEquals("2.0×", speedLabel(2.0f))
    }

    @Test
    fun `每个档位都有对应文案，不会出现空白按钮`() {
        PLAYBACK_SPEEDS.forEach { speed ->
            assertTrue(speedLabel(speed).isNotBlank())
        }
    }
}
