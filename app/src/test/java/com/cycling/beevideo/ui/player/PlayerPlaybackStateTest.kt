package com.cycling.beevideo.ui.player

import com.cycling.beevideo.domain.model.PlayTarget
import com.cycling.beevideo.domain.model.PlaybackState
import com.cycling.beevideo.ui.preview.FakeLibraryRepository
import com.cycling.beevideo.ui.preview.FakePlaybackSession
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 播放页状态持有者的**策略**：什么时候上报进度。
 *
 * 这一层以前住在 813 行的 composable 里，只能靠真机一集一集试 ——
 * "切集前先落库"这种顺序判据写错了的表现是"进度偶尔倒退"，看日志看不出来。
 * 拆出假会话之后，三件事第一次能被钉住：上报**时机**、上报时用的是**哪一刻**的
 * 位置、以及收场时**有没有**关掉会话。
 *
 * ─── 为什么不需要 `Dispatchers.setMain` ────────────────────────────────
 * 持有者**不是** ViewModel，周期上报的作用域由外面给。所以这里直接把
 * `TestScope.backgroundScope` 交给它：虚拟时间归 `runTest` 管，
 * 而 `backgroundScope` 会在用例结束时被自动取消 —— 那个
 * `while (isActive) delay(…)` 的无限循环因此不会把调度器拖住。
 * （这正是把作用域做成参数的收益：换成 `viewModelScope` 就得替换 Main 调度器，
 * 而且无限循环会让 `runTest` 一直推进虚拟时间直到整轮测试超时。）
 */
class PlayerPlaybackStateTest {

    @Test
    fun `正在播放时按间隔上报，且不强制`() = runTest {
        val session = FakePlaybackSession()
        val library = FakeLibraryRepository()
        val holder = holder(session, library)

        holder.open(TARGET, resumeAtMs = 0L)
        session.currentPositionMs = 30_000L
        session.currentDurationMs = 60_000L

        advanceTimeBy(1_001)

        val saved = library.savedProgress.single()
        assertEquals(30_000L, saved.progress.positionMs)
        assertEquals(60_000L, saved.progress.durationMs)
        assertTrue("周期上报不带 force —— 去重是仓储的事，界面只管如实汇报", !saved.force)
        assertEquals(FIXED_NOW, saved.progress.updatedAt)
    }

    @Test
    fun `暂停时不上报`() = runTest {
        val session = FakePlaybackSession()
        val library = FakeLibraryRepository()
        val holder = holder(session, library)

        holder.open(TARGET, resumeAtMs = 0L)
        session.currentPositionMs = 30_000L
        session.emit(PlaybackState.Paused)

        advanceTimeBy(3_001)

        assertTrue("暂停时位置不动，写进去的是一模一样的行", library.savedProgress.isEmpty())
    }

    @Test
    fun `还没起播时位置为 0，不写库`() = runTest {
        val session = FakePlaybackSession()
        val library = FakeLibraryRepository()
        holder(session, library)

        // 状态是"在播"，但位置还是 0 —— 这一条专门测位置守卫，不靠状态挡
        session.emit(PlaybackState.Playing)
        advanceTimeBy(2_001)

        assertTrue("位置为 0 表示还没起播，没有可记的东西", library.savedProgress.isEmpty())
    }

    /**
     * **先落库再切集** —— 反过来的话，切完会话已经在新集上，
     * 那时读位置拿到的是新集的 0，旧集的进度就永久丢了。
     */
    @Test
    fun `切集前先用旧集号强制落一次进度`() = runTest {
        val session = FakePlaybackSession()
        val library = FakeLibraryRepository()
        val holder = holder(session, library, initialEpisodeIndex = 2)
        holder.bindContext(lineName = "线路一", episodeName = "第 03 集")
        holder.open(TARGET, resumeAtMs = 0L)
        session.currentPositionMs = 12_000L

        holder.selectEpisode(3)

        val saved = library.savedProgress.single()
        assertEquals("记的必须是刚刚在看的那一集", 2, saved.progress.episodeIndex)
        assertEquals("第 03 集", saved.progress.episodeName)
        assertEquals(12_000L, saved.progress.positionMs)
        assertTrue("切集那一刻的位置必须落库，不被节流窗口吞掉", saved.force)
        assertEquals(3, holder.episodeIndex)
    }

    @Test
    fun `退到后台强制落一次`() = runTest {
        val session = FakePlaybackSession()
        val library = FakeLibraryRepository()
        val holder = holder(session, library)
        holder.open(TARGET, resumeAtMs = 0L)
        session.currentPositionMs = 5_000L

        holder.onStop()

        assertTrue(library.savedProgress.single().force)
    }

    /**
     * 集号在持有者里夹取，而不是在界面里：界面夹取的话持有者记账用的还是越界那个，
     * 落进库里的就是一条指向不存在剧集的进度。
     */
    @Test
    fun `剧集变少时集号被夹取`() = runTest {
        val holder = holder(initialEpisodeIndex = 11)

        holder.onEpisodesChanged(count = 6)

        assertEquals(5, holder.episodeIndex)
    }

    @Test
    fun `收场时先落进度再关闭会话`() = runTest {
        val session = FakePlaybackSession()
        val library = FakeLibraryRepository()
        val holder = holder(session, library)
        holder.bindContext(lineName = "线路一", episodeName = "第 01 集")
        holder.open(TARGET, resumeAtMs = 0L)
        session.currentPositionMs = 8_000L

        holder.release()

        assertEquals(8_000L, library.savedProgress.single().progress.positionMs)
        assertTrue(library.savedProgress.single().force)
        assertEquals("收场必须释放内核，否则 ExoPlayer 会一直握着解码器", 1, session.closeCount)
    }

    // ------------------------------------------------------------------ 夹具

    private fun TestScope.holder(
        session: FakePlaybackSession = FakePlaybackSession(),
        library: FakeLibraryRepository = FakeLibraryRepository(),
        initialEpisodeIndex: Int = 0,
    ) = PlayerPlaybackState(
        session = session,
        library = library,
        vodId = VOD_ID,
        initialEpisodeIndex = initialEpisodeIndex,
        // backgroundScope：用例结束时由 runTest 自动取消，无限上报循环不会拖住调度器
        scope = backgroundScope,
        now = { FIXED_NOW },
    )

    private companion object {
        const val VOD_ID = "site:v01"
        const val FIXED_NOW = 1_700_000_000_000L

        val TARGET = PlayTarget(url = "https://example.com/ep01.m3u8", headers = emptyMap())
    }
}
