package com.cycling.beevideo.ui.history

import com.cycling.beevideo.domain.repository.ContentSourceRepository
import com.cycling.beevideo.domain.repository.IncognitoMode
import com.cycling.beevideo.domain.repository.LibraryRepository
import com.cycling.beevideo.ui.preview.FakeIncognitoMode
import com.cycling.beevideo.ui.preview.FakeLibraryRepository
import com.cycling.beevideo.ui.preview.FakeSourceRepository
import com.cycling.beevideo.ui.preview.PreviewHistory
import com.cycling.beevideo.ui.preview.PreviewKeeps
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 观看历史页状态持有者。
 *
 * 以前四个写动作（收藏 / 删一条 / 清空）直接写在 composable 里，用 `rememberCoroutineScope()`
 * 起协程 —— 主题切换重建 Activity 之后那些协程连同状态一起没了，而且一条都测不到。
 *
 * ⚠️ 只用 `advanceTimeBy`，见 `KeepStateTest` 的说明。
 */
class HistoryStateTest {

    @Test
    fun `三条流各自落进状态`() = runTest {
        val state = historyState(
            library = FakeLibraryRepository(historyFromDemo = true, keepsFromDemo = true),
        )

        advanceTimeBy(1)

        val uiState = state.uiState.value
        assertFalse(uiState.loading)
        assertEquals(PreviewHistory.records().size, uiState.records.size)
        assertEquals(PreviewKeeps.items.size, uiState.keptIds.size)
        // 来源名靠 vodId 的前缀换 —— 换不到的话预览与真机上那一段都是空的
        assertEquals("示例来源", uiState.sourceNames["demo"])
    }

    @Test
    fun `删除一条后它就不再出现在列表里`() = runTest {
        val state = historyState(library = FakeLibraryRepository(historyFromDemo = true))
        advanceTimeBy(1)
        val target = state.uiState.value.records.first()

        state.onIntent(HistoryIntent.OnDeleteRecord(target.vodId))
        advanceTimeBy(1)

        assertTrue(state.uiState.value.records.none { it.vodId == target.vodId })
    }

    @Test
    fun `清空之后列表为空，但 loading 不再为真`() = runTest {
        val state = historyState(library = FakeLibraryRepository(historyFromDemo = true))
        advanceTimeBy(1)

        state.onIntent(HistoryIntent.OnClearAll)
        advanceTimeBy(1)

        assertTrue(state.uiState.value.records.isEmpty())
        assertFalse("清空是'读到了、是空的'，不是'还没读到'", state.uiState.value.loading)
    }

    /**
     * 长按菜单的收藏用记录里的快照拼 `KeepItem`，**不回源** ——
     * 所以这里能直接断言收藏表里有它，而不需要任何内容来源参与。
     */
    @Test
    fun `长按收藏会写进收藏表`() = runTest {
        val state = historyState(library = FakeLibraryRepository(historyFromDemo = true))
        advanceTimeBy(1)
        val target = state.uiState.value.records.first()

        state.onIntent(HistoryIntent.OnToggleKeep(target))
        advanceTimeBy(1)

        assertTrue(target.vodId in state.uiState.value.keptIds)
    }

    /** 三种离开页面的方式都是导航 —— 一次性事件，走 Effect。 */
    @Test
    fun `续播、看详情、返回各发一次事件`() = runTest {
        val state = historyState(library = FakeLibraryRepository(historyFromDemo = true))
        advanceTimeBy(1)
        val target = state.uiState.value.records.first()
        val effects = mutableListOf<HistoryEffect>()
        backgroundScope.launch { state.effect.collect { effects += it } }
        advanceTimeBy(1)

        state.onIntent(HistoryIntent.OnContinue(target))
        state.onIntent(HistoryIntent.OnOpenDetail(target.vodId))
        state.onIntent(HistoryIntent.OnBack)
        advanceTimeBy(1)

        assertEquals(
            listOf(
                HistoryEffect.Continue(target),
                HistoryEffect.OpenDetail(target.vodId),
                HistoryEffect.Back,
            ),
            effects,
        )
    }

    /**
     * 无痕状态是这一页换空态文案的**唯一依据**：同样是空列表，
     * 「还没有观看记录」与「无痕模式已开启」说的是两件事（记录变空那一步在仓储里，
     * 这里只钉住"开关能进来、能来回切"）。
     */
    @Test
    fun `无痕开关落进状态并可来回切换`() = runTest {
        val incognito = FakeIncognitoMode()
        val state = historyState(incognito = incognito)
        advanceTimeBy(1)
        assertFalse(state.uiState.value.incognito)

        incognito.set(true)
        advanceTimeBy(1)
        assertTrue(state.uiState.value.incognito)

        incognito.set(false)
        advanceTimeBy(1)
        assertFalse(state.uiState.value.incognito)
    }

    // ------------------------------------------------------------------ 夹具

    /** 基准时刻写死，好让收藏记录里的 `createdAt` 在断言里是确定的。 */
    private fun TestScope.historyState(
        library: LibraryRepository = FakeLibraryRepository(),
        sources: ContentSourceRepository = FakeSourceRepository.singleSourceReady(),
        incognito: IncognitoMode = FakeIncognitoMode(),
    ) = HistoryState(
        library = library,
        sources = sources,
        incognito = incognito,
        now = { 1_700_000_000_000L },
        scope = backgroundScope,
    )
}
