package com.cycling.beevideo.ui.detail

import com.cycling.beevideo.domain.model.Category
import com.cycling.beevideo.domain.model.PlayProgress
import com.cycling.beevideo.domain.model.PlayTarget
import com.cycling.beevideo.domain.model.SearchOutcome
import com.cycling.beevideo.domain.model.Vod
import com.cycling.beevideo.domain.repository.ContentRepository
import com.cycling.beevideo.ui.components.LoadState
import com.cycling.beevideo.ui.preview.FakeContentRepository
import com.cycling.beevideo.ui.preview.FakeLibraryRepository
import com.cycling.beevideo.ui.preview.PreviewVods
import java.io.IOException
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 详情页状态持有者的加载与编排。
 *
 * 这些判据以前都只以注释形式存在（"进度是进来时读一次、不订阅"、"线路号要记住"），
 * 因为它们写在 composable 的函数体里，唯一能观察它们的方式是在真机上点。
 *
 * 用 `TestScope.backgroundScope` 提供加载作用域：`runTest` 结束时自动取消，
 * 不必替换 `Dispatchers.Main`。
 */
class DetailStateTest {

    @Test
    fun `详情加载成功进入 Ready`() = runTest {
        val state = detailState()

        // ⚠️ 必须用 advanceTimeBy 而不是 advanceUntilIdle：后者**有意跳过**
        // backgroundScope 里的任务（正是为了不被长跑的后台作业拖住），
        // 而持有者的加载全在 backgroundScope 上 —— 用它的话状态一直停在 Loading。
        advanceTimeBy(1)

        val loaded = state.vod.value
        assertTrue("应该是就绪而不是失败：$loaded", loaded is LoadState.Ready)
        assertEquals(VOD_NAME, (loaded as LoadState.Ready).value?.name)
    }

    /**
     * 「源站确认不存在这条」是**正常结果**，不是失败 —— 界面要显示"条目不存在"，
     * 而不是"加载失败"加一个重试按钮。
     */
    @Test
    fun `源站没有这条时是 Ready null 而不是 Failed`() = runTest {
        val state = detailState(vodId = "not-exist")

        // ⚠️ 必须用 advanceTimeBy 而不是 advanceUntilIdle：后者**有意跳过**
        // backgroundScope 里的任务（正是为了不被长跑的后台作业拖住），
        // 而持有者的加载全在 backgroundScope 上 —— 用它的话状态一直停在 Loading。
        advanceTimeBy(1)

        val loaded = state.vod.value
        assertTrue("应该走正常结果分支：$loaded", loaded is LoadState.Ready)
        assertEquals(null, (loaded as LoadState.Ready).value)
    }

    @Test
    fun `加载失败进入 Failed 并带上原因`() = runTest {
        val state = detailState(content = FailingContentRepository(IOException("boom")))

        // ⚠️ 必须用 advanceTimeBy 而不是 advanceUntilIdle：后者**有意跳过**
        // backgroundScope 里的任务（正是为了不被长跑的后台作业拖住），
        // 而持有者的加载全在 backgroundScope 上 —— 用它的话状态一直停在 Loading。
        advanceTimeBy(1)

        val failed = state.vod.value
        assertTrue("应该是失败：$failed", failed is LoadState.Failed)
        assertEquals("boom", (failed as LoadState.Failed).message)
    }

    /**
     * 「进度进来读一次、不订阅」这条取舍的代价是：**要在正确的时刻主动重读一次**。
     * 从播放页返回就是那个时刻 —— 那一页刚往库里写过。
     */
    @Test
    fun `刷新进度会重新读一次，而不是记住进来时那一份`() = runTest {
        val library = FakeLibraryRepository()
        val state = detailState(library = library)

        library.progress = progressAt(1_000L)
        state.refreshProgress()
        // ⚠️ 必须用 advanceTimeBy 而不是 advanceUntilIdle：后者**有意跳过**
        // backgroundScope 里的任务（正是为了不被长跑的后台作业拖住），
        // 而持有者的加载全在 backgroundScope 上 —— 用它的话状态一直停在 Loading。
        advanceTimeBy(1)
        assertEquals(1_000L, readyProgress(state)?.positionMs)

        // 从播放页返回：库里已经变了
        library.progress = progressAt(90_000L)
        state.refreshProgress()
        // ⚠️ 必须用 advanceTimeBy 而不是 advanceUntilIdle：后者**有意跳过**
        // backgroundScope 里的任务（正是为了不被长跑的后台作业拖住），
        // 而持有者的加载全在 backgroundScope 上 —— 用它的话状态一直停在 Loading。
        advanceTimeBy(1)

        assertEquals(
            "返回详情页必须重读，否则进度条停在上次进来时的位置",
            90_000L,
            readyProgress(state)?.positionMs,
        )
    }

    @Test
    fun `选中的线路被记住`() = runTest {
        val state = detailState()

        state.selectLine(2)

        assertEquals(2, state.lineIndex)
    }

    /**
     * 收藏状态是**订阅**的（与进度相反）：本页的按钮会改它，必须立刻反映到图标上。
     */
    @Test
    fun `收藏切换会翻转状态，再点一次回到未收藏`() = runTest {
        val library = FakeLibraryRepository()
        val state = detailState(library = library)
        val vod = PreviewVods.vodById(VOD_ID) ?: error("夹具里必须有 $VOD_ID")
        // ⚠️ 必须用 advanceTimeBy 而不是 advanceUntilIdle：后者**有意跳过**
        // backgroundScope 里的任务（正是为了不被长跑的后台作业拖住），
        // 而持有者的加载全在 backgroundScope 上 —— 用它的话状态一直停在 Loading。
        advanceTimeBy(1)
        assertTrue("初始应是未收藏", !state.isKept.value)

        state.toggleKeep(vod)
        // ⚠️ 必须用 advanceTimeBy 而不是 advanceUntilIdle：后者**有意跳过**
        // backgroundScope 里的任务（正是为了不被长跑的后台作业拖住），
        // 而持有者的加载全在 backgroundScope 上 —— 用它的话状态一直停在 Loading。
        advanceTimeBy(1)
        assertTrue("点一次之后图标要变实心", state.isKept.value)

        state.toggleKeep(vod)
        // ⚠️ 必须用 advanceTimeBy 而不是 advanceUntilIdle：后者**有意跳过**
        // backgroundScope 里的任务（正是为了不被长跑的后台作业拖住），
        // 而持有者的加载全在 backgroundScope 上 —— 用它的话状态一直停在 Loading。
        advanceTimeBy(1)
        assertTrue("再点一次回到未收藏", !state.isKept.value)
    }

    // ------------------------------------------------------------------ 夹具

    private fun TestScope.detailState(
        content: ContentRepository = FakeContentRepository(),
        library: FakeLibraryRepository = FakeLibraryRepository(),
        vodId: String = VOD_ID,
    ) = DetailState(
        content = content,
        library = library,
        vodId = vodId,
        scope = backgroundScope,
        now = { FIXED_NOW },
    )

    private fun readyProgress(state: DetailState): PlayProgress? =
        (state.progress.value as? LoadState.Ready)?.value

    private fun progressAt(positionMs: Long) = PlayProgress(
        vodId = VOD_ID,
        lineName = "线路一 · 演示",
        episodeIndex = 2,
        episodeName = "第 03 集",
        positionMs = positionMs,
        durationMs = 2_700_000L,
        updatedAt = FIXED_NOW,
    )

    private companion object {
        const val VOD_ID = "v01"
        const val FIXED_NOW = 1_700_000_000_000L
        val VOD_NAME = PreviewVods.vodById(VOD_ID)?.name
    }
}

/** [ContentRepository] 里除详情外都不参与这些用例，所以只有详情会抛。 */
private class FailingContentRepository(private val error: Throwable) : ContentRepository {

    override suspend fun categories(): List<Category> = emptyList()

    override suspend fun listByCategory(categoryId: String, page: Int): List<Vod> = emptyList()

    override suspend fun detail(vodId: String): Vod? = throw error

    override suspend fun search(keyword: String): SearchOutcome = SearchOutcome.EMPTY

    override suspend fun playTarget(
        vodId: String,
        lineName: String,
        episodeId: String,
    ): PlayTarget? = null
}
