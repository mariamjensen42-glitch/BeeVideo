package com.cycling.beevideo.ui.keep

import com.cycling.beevideo.ui.preview.FakeLibraryRepository
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
 * 收藏页状态持有者。
 *
 * 以前这一页的界面直接订阅仓储，于是两件事都没有落点：「第一帧该画骨架还是空态」
 * 只在注释里，"点一张卡会跳详情"藏在 `onVodClick` 回调里 —— 两个都只能靠真机点。
 *
 * ⚠️ 只用 `advanceTimeBy`，不用 `advanceUntilIdle`：持有者的订阅跑在 `backgroundScope`
 * 上，后者有意跳过它，状态会一直停在初始值。见 `docs/adr/0004`。
 */
class KeepStateTest {

    @Test
    fun `读到收藏之后 loading 结束`() = runTest {
        val state = keepState(keepsFromDemo = true)

        advanceTimeBy(1)

        assertFalse("第一次发射就算读到了", state.uiState.value.loading)
        assertEquals(PreviewKeeps.items.size, state.uiState.value.keeps.size)
    }

    /**
     * 空列表 + `loading` 已经结束，才是"真的没有收藏"。
     *
     * 这两件事在界面上是两句话（骨架 vs 空态），合成一句就会在进页面时闪一下
     * 「还没有收藏」—— 看着像收藏丢了。
     */
    @Test
    fun `没有收藏时 loading 也会结束`() = runTest {
        val state = keepState()

        advanceTimeBy(1)

        assertFalse(state.uiState.value.loading)
        assertTrue(state.uiState.value.keeps.isEmpty())
    }

    @Test
    fun `从别处取消收藏，这一页立刻少一张卡`() = runTest {
        val library = FakeLibraryRepository(keepsFromDemo = true)
        val state = KeepState(library, backgroundScope)
        advanceTimeBy(1)
        val target = PreviewKeeps.items.first()

        library.toggleKeep(target)
        advanceTimeBy(1)

        assertTrue(state.uiState.value.keeps.none { it.vodId == target.vodId })
    }

    /** 导航是**一次性事件**：变成状态的话，旋转屏回来会再跳一次详情页。 */
    @Test
    fun `点一张卡发一次导航事件`() = runTest {
        val state = keepState()
        val effects = mutableListOf<KeepEffect>()
        backgroundScope.launch { state.effect.collect { effects += it } }
        advanceTimeBy(1)

        state.onIntent(KeepIntent.OnOpenVod("demo:1"))
        advanceTimeBy(1)

        assertEquals(listOf(KeepEffect.OpenVod("demo:1")), effects)
    }

    @Test
    fun `菜单里取消收藏，这一页立刻少一张卡`() = runTest {
        val library = FakeLibraryRepository(keepsFromDemo = true)
        val state = KeepState(library, backgroundScope)
        advanceTimeBy(1)
        val target = PreviewKeeps.items.first()

        state.onIntent(KeepIntent.OnRemoveKeep(target.vodId))
        advanceTimeBy(1)

        assertTrue(state.uiState.value.keeps.none { it.vodId == target.vodId })
    }

    /**
     * 删除**不存在的条目**是无操作。
     *
     * 这条钉住"为什么不复用 toggleKeep"：翻转遇到不同步的状态会把条目又加回来，
     * 而菜单里写的是「取消收藏」，用户看到它出现在列表里会以为是自己点错了。
     */
    @Test
    fun `取消收藏不存在的条目不会把它加回来`() = runTest {
        val state = keepState()

        state.onIntent(KeepIntent.OnRemoveKeep("demo:1"))
        advanceTimeBy(1)

        assertTrue(state.uiState.value.keeps.isEmpty())
    }

    // ------------------------------------------------------------------ 夹具

    private fun TestScope.keepState(keepsFromDemo: Boolean = false) =
        KeepState(FakeLibraryRepository(keepsFromDemo = keepsFromDemo), backgroundScope)
}
