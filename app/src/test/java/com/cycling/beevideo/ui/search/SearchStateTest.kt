package com.cycling.beevideo.ui.search

import com.cycling.beevideo.domain.model.Category
import com.cycling.beevideo.domain.model.PlayTarget
import com.cycling.beevideo.domain.model.SearchOutcome
import com.cycling.beevideo.domain.model.Vod
import com.cycling.beevideo.domain.model.VodPage
import com.cycling.beevideo.domain.repository.ContentRepository
import com.cycling.beevideo.domain.repository.SearchHistoryRepository
import com.cycling.beevideo.ui.components.LoadState
import com.cycling.beevideo.ui.preview.FakeSearchHistoryRepository
import com.cycling.beevideo.ui.preview.PreviewVods
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 搜索页状态持有者。
 *
 * 这里钉住的是一条**代价很高**的取舍：跟随输入的即时搜索会在每个字符上发起一整轮
 * 跨源请求（打"庆余年"三个字就是三轮，前两轮注定被丢掉）。所以"敲键盘不发请求、
 * 只有提交才发"必须可断言 —— 光写在注释里，下次改的人一顺手就退回边打边搜了。
 *
 * ⚠️ 只用 `advanceTimeBy`，见 `KeepStateTest` 的说明。
 */
class SearchStateTest {

    @Test
    fun `初始是还没搜过，而不是搜了没结果`() = runTest {
        val state = searchState()

        advanceTimeBy(1)

        assertEquals(SearchUiState().result, state.uiState.value.result)
        assertEquals(LoadState.Ready(null), state.uiState.value.result)
    }

    @Test
    fun `敲键盘只改输入，不发任何请求`() = runTest {
        val content = RecordingContentRepository()
        val state = searchState(content)

        "庆余年".forEach { state.onIntent(SearchIntent.OnInputChange(state.uiState.value.input + it)) }
        advanceTimeBy(1)

        assertEquals("庆余年", state.uiState.value.input)
        assertEquals(emptyList<String>(), content.searchedKeywords)
    }

    @Test
    fun `提交才发请求，且关键词去掉首尾空白`() = runTest {
        val content = RecordingContentRepository()
        val state = searchState(content)

        state.onIntent(SearchIntent.OnInputChange("  庆余年  "))
        state.onIntent(SearchIntent.OnSubmit)
        advanceTimeBy(1)

        assertEquals(listOf("庆余年"), content.searchedKeywords)
        assertEquals("庆余年", state.uiState.value.submitted)
        assertTrue(state.uiState.value.result is LoadState.Ready)
    }

    /** 空白词提交是**空操作**：发一次也就罢了，还占着一整屏骨架。 */
    @Test
    fun `空白词提交不发请求也不改状态`() = runTest {
        val content = RecordingContentRepository()
        val state = searchState(content)

        state.onIntent(SearchIntent.OnInputChange("   "))
        state.onIntent(SearchIntent.OnSubmit)
        advanceTimeBy(1)

        assertEquals(emptyList<String>(), content.searchedKeywords)
        assertEquals(LoadState.Ready(null), state.uiState.value.result)
    }

    /** 清空输入框只是换个词重搜，**结果先留着** —— 清掉的话屏幕会白一帧。 */
    @Test
    fun `清空输入不动已有结果`() = runTest {
        val state = searchState()
        state.onIntent(SearchIntent.OnInputChange("示例"))
        state.onIntent(SearchIntent.OnSubmit)
        advanceTimeBy(1)
        val resultBefore = state.uiState.value.result

        state.onIntent(SearchIntent.OnClearInput)
        advanceTimeBy(1)

        assertEquals("", state.uiState.value.input)
        assertEquals(resultBefore, state.uiState.value.result)
    }

    @Test
    fun `搜索中状态是 Loading，回来后是结果`() = runTest {
        val content = RecordingContentRepository()
        val gate = CompletableDeferred<Unit>()
        content.gate = gate
        val state = searchState(content)

        state.onIntent(SearchIntent.OnInputChange("示例"))
        state.onIntent(SearchIntent.OnSubmit)
        advanceTimeBy(1)
        assertEquals(LoadState.Loading, state.uiState.value.result)

        gate.complete(Unit)
        advanceTimeBy(1)
        assertTrue(state.uiState.value.result is LoadState.Ready)
    }

    /**
     * 失败**只转成状态、不抛出去**：界面每一处都要处理失败，抛出去等于逼每个调用点
     * 写一遍 try。这里同时钉住"抛了也不会炸穿持有者"。
     */
    @Test
    fun `搜索抛异常时转成 Failed 而不是崩溃`() = runTest {
        val content = RecordingContentRepository().apply {
            error = IllegalStateException("网络断了")
        }
        val state = searchState(content)

        state.onIntent(SearchIntent.OnInputChange("示例"))
        state.onIntent(SearchIntent.OnSubmit)
        advanceTimeBy(1)

        val result = state.uiState.value.result
        assertTrue(result is LoadState.Failed)
        assertEquals("网络断了", (result as LoadState.Failed).message)
    }

    @Test
    fun `点结果与返回各发一次事件`() = runTest {
        val state = searchState()
        val effects = mutableListOf<SearchEffect>()
        backgroundScope.launch { state.effect.collect { effects += it } }
        advanceTimeBy(1)
        val vod = PreviewVods.vods.first()

        state.onIntent(SearchIntent.OnOpenVod(vod))
        state.onIntent(SearchIntent.OnBack)
        advanceTimeBy(1)

        assertEquals(listOf(SearchEffect.OpenVod(vod), SearchEffect.Back), effects)
    }

    // ------------------------------------------------------------------ 历史

    /**
     * 记账发生在**发请求之前**：历史记的是"我搜过什么"，不是"什么搜到了"。
     * 网络失败不该让关键词消失。
     */
    @Test
    fun `提交就写历史，不等结果`() = runTest {
        val content = RecordingContentRepository().apply { error = IllegalStateException("断了") }
        val history = FakeSearchHistoryRepository()
        val state = searchState(content, history)

        state.onIntent(SearchIntent.OnInputChange("庆余年"))
        state.onIntent(SearchIntent.OnSubmit)
        advanceTimeBy(1)

        assertEquals(listOf("庆余年"), state.uiState.value.history)
        assertTrue("失败也不该把词撤回去", state.uiState.value.result is LoadState.Failed)
    }

    @Test
    fun `重复的词上移而不是产生两条`() = runTest {
        val history = FakeSearchHistoryRepository(listOf("庆余年", "繁花"))
        val state = searchState(history = history)

        state.onIntent(SearchIntent.OnInputChange("繁花"))
        state.onIntent(SearchIntent.OnSubmit)
        advanceTimeBy(1)

        assertEquals(listOf("繁花", "庆余年"), state.uiState.value.history)
    }

    /** 空白词连请求都不发，自然也不该进历史。 */
    @Test
    fun `空白词不写历史`() = runTest {
        val history = FakeSearchHistoryRepository()
        val state = searchState(history = history)

        state.onIntent(SearchIntent.OnInputChange("   "))
        state.onIntent(SearchIntent.OnSubmit)
        advanceTimeBy(1)

        assertTrue(state.uiState.value.history.isEmpty())
    }

    /** 点历史等同"填进输入框再提交"—— 否则结果有了而输入框空着，像上次的残留。 */
    @Test
    fun `点历史即用该词提交且输入框同步`() = runTest {
        val content = RecordingContentRepository()
        val state = searchState(content, FakeSearchHistoryRepository(listOf("繁花")))

        state.onIntent(SearchIntent.OnUseHistory("繁花"))
        advanceTimeBy(1)

        assertEquals("繁花", state.uiState.value.input)
        assertEquals(listOf("繁花"), content.searchedKeywords)
        assertEquals("繁花", state.uiState.value.submitted)
    }

    @Test
    fun `删一条历史不动别的`() = runTest {
        val state = searchState(history = FakeSearchHistoryRepository(listOf("繁花", "庆余年")))

        state.onIntent(SearchIntent.OnRemoveHistory("繁花"))
        advanceTimeBy(1)

        assertEquals(listOf("庆余年"), state.uiState.value.history)
    }

    @Test
    fun `清空历史后界面上的列表也空`() = runTest {
        val state = searchState(history = FakeSearchHistoryRepository(listOf("繁花")))

        state.onIntent(SearchIntent.OnClearHistory)
        advanceTimeBy(1)

        assertTrue(state.uiState.value.history.isEmpty())
    }

    // ------------------------------------------------------------------ 夹具

    private fun TestScope.searchState(
        content: ContentRepository = RecordingContentRepository(),
        history: SearchHistoryRepository = FakeSearchHistoryRepository(),
    ) = SearchState(content, history, backgroundScope)
}

/** 记录搜索过的词，并允许把一次搜索卡在半途（看 `Loading` 那一帧）。 */
private class RecordingContentRepository : ContentRepository {

    val searchedKeywords = mutableListOf<String>()

    /** 非 null 时 `search` 会在这里挂住，直到调用方放行。 */
    var gate: CompletableDeferred<Unit>? = null

    var error: Throwable? = null

    override suspend fun categories(): List<Category> = emptyList()

    override suspend fun listByCategory(categoryId: String, page: Int): VodPage = VodPage(emptyList(), 1)

    override suspend fun detail(vodId: String): Vod? = null

    override suspend fun search(keyword: String): SearchOutcome {
        searchedKeywords += keyword
        gate?.await()
        error?.let { throw it }
        return SearchOutcome(vods = PreviewVods.vods, searchedSources = 1, searchableSources = 1)
    }

    override suspend fun playTarget(
        vodId: String,
        lineName: String,
        episodeId: String,
    ): PlayTarget? = null
}
