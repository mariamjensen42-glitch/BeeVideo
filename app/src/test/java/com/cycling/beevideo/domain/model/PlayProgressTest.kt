package com.cycling.beevideo.domain.model

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * [resumePositionMs] 的单元测试。
 *
 * 这是「续播」的全部判定逻辑，也是这个 MVP 里最容易"看起来对、实际错"的一处：判错了
 * 的表现是**点进去从头播**或**点进去就播完**，两种现象从界面上看都像播放器的问题。
 * 所以五种返回 0 的情形和唯一的非 0 情形逐条钉住。
 */
class PlayProgressTest {

    private fun progress(
        lineName: String = "线路一",
        episodeIndex: Int = 2,
        positionMs: Long = 600_000L,
        durationMs: Long = 2_700_000L,
    ) = PlayProgress(
        vodId = "mock:1001",
        lineName = lineName,
        episodeIndex = episodeIndex,
        episodeName = "第 03 集",
        positionMs = positionMs,
        durationMs = durationMs,
        updatedAt = 1_700_000_000_000L,
    )

    @Test
    fun `没看过就从头播`() {
        assertEquals(0L, resumePositionMs(null, lineName = "线路一", episodeIndex = 0))
    }

    @Test
    fun `线路和集号都对得上就回到上次的位置`() {
        assertEquals(
            600_000L,
            resumePositionMs(progress(), lineName = "线路一", episodeIndex = 2),
        )
    }

    @Test
    fun `换了一条线路就不复用进度`() {
        // 集号只在同一条线路内可比。换线路还按 index 跳会跳到另一份地址的另一个位置，
        // 比从头播更糟 —— 用户不知道自己被带到哪了
        assertEquals(
            0L,
            resumePositionMs(progress(), lineName = "线路二", episodeIndex = 2),
        )
    }

    @Test
    fun `点的是别的一集就不复用进度`() {
        // 用户明确点了第 5 集，不该被拽回第 3 集的位置
        assertEquals(
            0L,
            resumePositionMs(progress(), lineName = "线路一", episodeIndex = 4),
        )
    }

    @Test
    fun `位置为 0 等于没有进度`() {
        assertEquals(
            0L,
            resumePositionMs(
                progress(positionMs = 0L),
                lineName = "线路一",
                episodeIndex = 2,
            ),
        )
    }

    @Test
    fun `上次看到结尾附近就从头上播`() {
        // 45 分钟的剧停在 44:55，恢复过去等于一进去就播完
        val nearEnd = progress(positionMs = 2_690_000L, durationMs = 2_700_000L)
        assertEquals(0L, resumePositionMs(nearEnd, lineName = "线路一", episodeIndex = 2))
    }

    @Test
    fun `结尾守卫的边界刚好在余量之外`() {
        // 距离结尾 10_001ms，比余量多 1ms —— 刚好落在守卫之外，该恢复
        val exactly = progress(positionMs = 2_690_000L - 1, durationMs = 2_700_000L)
        assertEquals(
            2_690_000L - 1,
            resumePositionMs(exactly, lineName = "线路一", episodeIndex = 2),
        )
    }

    @Test
    fun `来源没给时长时不做结尾判断`() {
        // durationMs = 0 表示来源没说时长（直链、部分 m3u8 都这样）。这时不能当成
        // "时长为 0、position 超过它"，那会把所有进度都判成看完了
        assertEquals(
            600_000L,
            resumePositionMs(
                progress(durationMs = 0L),
                lineName = "线路一",
                episodeIndex = 2,
            ),
        )
    }
}
