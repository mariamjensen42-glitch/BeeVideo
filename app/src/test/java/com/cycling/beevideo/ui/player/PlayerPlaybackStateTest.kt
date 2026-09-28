package com.cycling.beevideo.ui.player

import com.cycling.beevideo.domain.model.PlayTarget
import com.cycling.beevideo.domain.model.PlaybackState
import com.cycling.beevideo.domain.model.Vod
import com.cycling.beevideo.ui.preview.FakeLibraryRepository
import com.cycling.beevideo.ui.preview.FakePlaybackSession
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
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
@OptIn(ExperimentalCoroutinesApi::class)
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
        holder.bindContext(vod = VOD, lineName = "线路一", episodeName = "第 03 集")
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
     * 换线路也是**先落库再切**，而且比切集更要紧：进度记录带的是**线路名**，
     * 而线路名是重组之后才由 `bindContext` 推过来的。顺序反了的话，刚看的那 12 分钟
     * 会被记到新线路名下 —— 那条记录随后就成了新线路的续播点，等于凭空空降一个进度。
     */
    @Test
    fun `换线路前先用旧线路名强制落一次进度`() = runTest {
        val session = FakePlaybackSession()
        val library = FakeLibraryRepository()
        val holder = holder(session, library, initialLineIndex = 0)
        holder.bindContext(vod = VOD, lineName = "线路一", episodeName = "第 03 集")
        holder.open(TARGET, resumeAtMs = 0L)
        session.currentPositionMs = 12_000L

        holder.selectLine(1)

        val saved = library.savedProgress.single()
        assertEquals("记的必须是刚刚在看的那条线路", "线路一", saved.progress.lineName)
        assertEquals(12_000L, saved.progress.positionMs)
        assertTrue("换线路那一刻的位置必须落库，不被节流窗口吞掉", saved.force)
        assertEquals(1, holder.lineIndex)
    }

    /** 集号不跟着线路走：第 3 集换条线路还是第 3 集，越界交给 `onEpisodesChanged` 夹。 */
    @Test
    fun `换线路不动集号`() = runTest {
        val holder = holder(initialEpisodeIndex = 3)

        holder.selectLine(2)

        assertEquals(3, holder.episodeIndex)
        assertEquals(2, holder.lineIndex)
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
        holder.bindContext(vod = VOD, lineName = "线路一", episodeName = "第 01 集")
        holder.open(TARGET, resumeAtMs = 0L)
        session.currentPositionMs = 8_000L

        holder.release()

        assertEquals(8_000L, library.savedProgress.single().progress.positionMs)
        assertTrue(library.savedProgress.single().force)
        assertEquals("收场必须释放内核，否则 ExoPlayer 会一直握着解码器", 1, session.closeCount)
    }

    // -------------------------------------------------------- 播放期控件

    /**
     * 快照比上报密：进度条要跟手，落库 1 秒一次已经够。两者用**两个**循环，
     * 所以这里按 250ms 断言 —— 如果哪天被合并成一个循环，这条会先红。
     */
    @Test
    fun `进度快照按 tick 刷新，比上报密`() = runTest {
        val session = FakePlaybackSession()
        val library = FakeLibraryRepository()
        val holder = holder(session, library)
        holder.open(TARGET, resumeAtMs = 0L)
        session.currentPositionMs = 42_000L
        session.currentDurationMs = 90_000L

        advanceTimeBy(251)

        assertEquals(42_000L, holder.positionMs)
        assertEquals(90_000L, holder.durationMs)
        assertTrue("这个点还没到上报间隔，不该写库", library.savedProgress.isEmpty())
    }

    @Test
    fun `拖进度条立刻改快照，且只向前端发一次 seek`() = runTest {
        val session = FakePlaybackSession()
        val holder = holder(session)
        holder.open(TARGET, resumeAtMs = 0L)

        holder.seekTo(120_000L)

        assertEquals(
            "快照必须当场跟上 —— 等下一个 tick 才纠正的话，滑块会先弹回原位再跳过去",
            120_000L,
            holder.positionMs,
        )
        assertEquals(listOf(120_000L), session.seeks)
    }

    @Test
    fun `改倍速同时更新快照与会话`() = runTest {
        val session = FakePlaybackSession()
        val holder = holder(session)
        holder.open(TARGET, resumeAtMs = 0L)

        holder.selectSpeed(1.5f)

        assertEquals(1.5f, holder.speed, 0f)
        assertEquals("必须落到内核上，否则界面显示变了、声音没变", 1.5f, session.speed(), 0f)
    }

    @Test
    fun `倍速初值从会话读回来`() = runTest {
        val session = FakePlaybackSession().apply { setSpeed(2.0f) }

        val holder = holder(session)

        assertEquals("倍速是内核级设置，会话比本类活得久（换集不重置）", 2.0f, holder.speed, 0f)
    }

    @Test
    fun `播放暂停透传到会话`() = runTest {
        val session = FakePlaybackSession()
        val holder = holder(session)
        holder.open(TARGET, resumeAtMs = 0L)

        holder.togglePlayPause()

        assertEquals(1, session.toggleCount)
    }

    @Test
    fun `收场后不再刷新快照`() = runTest {
        val session = FakePlaybackSession()
        val holder = holder(session)
        holder.open(TARGET, resumeAtMs = 0L)
        session.currentPositionMs = 10_000L
        advanceTimeBy(251)
        assertEquals(10_000L, holder.positionMs)

        holder.release()
        session.currentPositionMs = 99_000L
        advanceTimeBy(1_000)

        assertEquals("release 必须把快照循环一起停掉", 10_000L, holder.positionMs)
    }

    // -------------------------------------------------------- 自动下一集

    /**
     * 播完接下一集，且**先把这一集的位置落下来**再改集号 ——
     * 顺序反了的话，片尾那段位置会被记到下一集名下。
     */
    @Test
    fun `一集播完自动接下一集并先落进度`() = runTest {
        val session = FakePlaybackSession()
        val library = FakeLibraryRepository()
        val holder = holder(session, library, initialEpisodeIndex = 0)
        holder.bindContext(vod = VOD, lineName = "线路一", episodeName = "第 01 集")
        holder.open(TARGET, resumeAtMs = 0L)
        holder.onEpisodesChanged(count = 3)
        session.currentPositionMs = 100_000L

        session.emit(PlaybackState.Ended)
        runCurrent()

        assertEquals(1, holder.episodeIndex)
        val saved = library.savedProgress.single()
        assertEquals("落的是刚刚播完那一集", 0, saved.progress.episodeIndex)
        assertEquals(100_000L, saved.progress.positionMs)
        assertTrue(saved.force)
    }

    /**
     * 跳完之后状态**仍然**是 Ended（新集还在解析地址），这时候再推调度器不该接着跳。
     *
     * 这条守着的是"一次播完只跳一次"这个性质 —— 不管状态流是不是会重复投递
     * 同一个 Ended，结论都该一样。
     */
    @Test
    fun `跳完之后不会继续往前跳`() = runTest {
        val session = FakePlaybackSession()
        val holder = holder(session, initialEpisodeIndex = 0)
        holder.open(TARGET, resumeAtMs = 0L)
        holder.onEpisodesChanged(count = 5)

        session.emit(PlaybackState.Ended)
        runCurrent()
        // 状态仍是 Ended（新集还没起播），再推几次调度器也不该接着跳
        runCurrent()
        runCurrent()

        assertEquals("只跳一集", 1, holder.episodeIndex)
    }

    /** 新集起播（状态离开 Ended）之后要重新武装，否则第二集播完就停在那里了。 */
    @Test
    fun `起播下一集后能继续连播`() = runTest {
        val session = FakePlaybackSession()
        val holder = holder(session, initialEpisodeIndex = 0)
        holder.open(TARGET, resumeAtMs = 0L)
        holder.onEpisodesChanged(count = 5)

        session.emit(PlaybackState.Ended)
        runCurrent()
        session.emit(PlaybackState.Buffering)
        runCurrent()
        session.emit(PlaybackState.Ended)
        runCurrent()

        assertEquals(2, holder.episodeIndex)
    }

    @Test
    fun `最后一集播完不跳，但仍落一次进度`() = runTest {
        val session = FakePlaybackSession()
        val library = FakeLibraryRepository()
        val holder = holder(session, library, initialEpisodeIndex = 2)
        holder.bindContext(vod = VOD, lineName = "线路一", episodeName = "第 03 集")
        holder.open(TARGET, resumeAtMs = 0L)
        holder.onEpisodesChanged(count = 3)
        session.currentPositionMs = 88_000L

        session.emit(PlaybackState.Ended)
        runCurrent()

        assertEquals(2, holder.episodeIndex)
        assertEquals(
            "周期上报只在'在播'时跑，片尾那一段得靠这一下补上",
            88_000L,
            library.savedProgress.single().progress.positionMs,
        )
    }

    @Test
    fun `关掉连播时播完不动`() = runTest {
        val session = FakePlaybackSession()
        val library = FakeLibraryRepository()
        val holder = holder(session, library, autoPlayNext = false)
        holder.bindContext(vod = VOD, lineName = "线路一", episodeName = "第 01 集")
        holder.open(TARGET, resumeAtMs = 0L)
        holder.onEpisodesChanged(count = 5)
        session.currentPositionMs = 60_000L

        session.emit(PlaybackState.Ended)
        runCurrent()

        assertEquals(0, holder.episodeIndex)
    }

    // ------------------------------------------------------------------ 夹具

    /**
     * 落库要带上**线路号**与**影片快照**。
     *
     * 历史页靠线路号做"一键继续播放"（有它才不必先请求一次详情），靠快照在来源不可用时
     * 也能列出片名与封面。少写任何一项都不会报错 —— 只会让历史页少一列，
     * 而"少一列"看起来永远像"这条记录本来就没有"。
     */
    @Test
    fun `进度里带上线路号与影片快照`() = runTest {
        val session = FakePlaybackSession()
        val library = FakeLibraryRepository()
        val holder = holder(session, library, initialLineIndex = 1)
        holder.bindContext(vod = VOD, lineName = "线路二", episodeName = "第 03 集")
        holder.open(TARGET, resumeAtMs = 0L)
        session.currentPositionMs = 12_000L

        holder.save(force = true)

        val saved = library.savedProgress.single().progress
        assertEquals(1, saved.lineIndex)
        assertEquals(VOD.name, saved.name)
        assertEquals(VOD.pic, saved.pic)
        assertEquals(VOD.score, saved.score)
        assertEquals(VOD.remarks, saved.remarks)
    }

    private fun TestScope.holder(
        session: FakePlaybackSession = FakePlaybackSession(),
        library: FakeLibraryRepository = FakeLibraryRepository(),
        initialEpisodeIndex: Int = 0,
        initialLineIndex: Int = 0,
        autoPlayNext: Boolean = true,
    ) = PlayerPlaybackState(
        session = session,
        library = library,
        vodId = VOD_ID,
        initialEpisodeIndex = initialEpisodeIndex,
        initialLineIndex = initialLineIndex,
        autoPlayNext = autoPlayNext,
        // backgroundScope：用例结束时由 runTest 自动取消，无限上报循环不会拖住调度器
        scope = backgroundScope,
        now = { FIXED_NOW },
    )

    private companion object {
        const val VOD_ID = "site:v01"
        const val FIXED_NOW = 1_700_000_000_000L

        val TARGET = PlayTarget(url = "https://example.com/ep01.m3u8", headers = emptyMap())

        /** 只用来验"快照被原样搬进进度"，字段值是随便取的，但都非空 —— 空值会让断言形同虚设。 */
        val VOD = Vod(
            id = VOD_ID,
            name = "长风渡海",
            categoryId = "tv",
            year = "2024",
            area = "大陆",
            genre = "剧情",
            score = "8.6",
            remarks = "全 12 集",
            director = "——",
            actors = "——",
            intro = "——",
            pic = "https://example.com/poster.jpg",
            lines = emptyList(),
        )
    }
}
