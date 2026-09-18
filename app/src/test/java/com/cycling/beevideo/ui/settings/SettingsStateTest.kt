package com.cycling.beevideo.ui.settings

import com.cycling.beevideo.domain.model.SourceStatus
import com.cycling.beevideo.domain.repository.ContentSourceRepository
import com.cycling.beevideo.domain.repository.MediaCache
import com.cycling.beevideo.ui.preview.FakeMediaCache
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 设置页状态持有者：占用读写的时机，以及 `applying` 的真假。
 *
 * 这些以前要么写在 composable 里（`applying` 用裸 `remember`，主题切换就被清掉），
 * 要么靠一个自增的 `usageTick` 计数器刷新 —— 错了的表现都是"按钮状态不对"或
 * "占用数字不动"，而这两种都不报错。
 */
class SettingsStateTest {

    @Test
    fun `进页面就读一次缓存占用`() = runTest {
        val cache = FakeMediaCache(usage = 1_234L)
        val state = settingsState(cache = cache)

        advanceTimeBy(1)

        assertEquals(1_234L, state.usageBytes.value)
    }

    /**
     * 清空之后必须**重读**：界面上的数字来自文件系统遍历，
     * 不重读的话"已用 1.2 GB"会一直挂在那里，用户以为没清掉。
     */
    @Test
    fun `清空缓存会清掉并立刻重读占用`() = runTest {
        val cache = FakeMediaCache(usage = 500L)
        val state = settingsState(cache = cache)
        advanceTimeBy(1)

        state.clearCache()
        advanceTimeBy(1)

        assertEquals(1, cache.clearCount)
        assertEquals(0L, state.usageBytes.value)
    }

    @Test
    fun `装载期间 applying 为真，结束后为假`() = runTest {
        val sources = RecordingSourceRepository()
        val gate = CompletableDeferred<Unit>()
        sources.gate = gate
        val state = settingsState(sources = sources)

        state.applyConfig("https://example.com/config.json")
        advanceTimeBy(1)
        assertTrue("装载还没回来，按钮该是禁用的", state.applying.value)

        gate.complete(Unit)
        advanceTimeBy(1)
        assertTrue("回来了就该复位，否则按钮永久禁用", !state.applying.value)
        assertEquals(listOf("https://example.com/config.json"), sources.applyCalls)
    }

    /**
     * 装载**被取消**时 `applying` 也要复位。
     *
     * 复位走 `finally` 而不是顺序执行：真实路径是"用户点完加载、装载还没回来就离开了页面"
     * （持有者的作用域随之取消）。顺序执行的话那一次 `applying` 会永远停在 true，
     * 按钮永久禁用。
     *
     * ⚠️ 这里**不测**"仓储抛异常"：`ContentSourceRepository.applyConfig` 的契约是
     * **不抛异常**（失败时返回给用户看的原因，见它的说明）。真抛了就是实现违约 ——
     * 那该让它在测试里炸出来，而不是被持有者吞掉。
     */
    @Test
    fun `装载被取消也要复位 applying`() = runTest {
        val sources = RecordingSourceRepository().apply {
            applyError = CancellationException("页面走了")
        }
        val state = settingsState(sources = sources)

        state.applyConfig("https://example.com/config.json")
        advanceTimeBy(1)

        assertTrue("取消路径同样要复位，否则按钮永久禁用", !state.applying.value)
    }

    @Test
    fun `清空来源会调仓储`() = runTest {
        val sources = RecordingSourceRepository()
        val state = settingsState(sources = sources)

        state.clearSource()
        advanceTimeBy(1)

        assertEquals(1, sources.clearCount)
    }

    // ------------------------------------------------------------------ 夹具

    private fun TestScope.settingsState(
        sources: ContentSourceRepository = RecordingSourceRepository(),
        cache: MediaCache = FakeMediaCache(),
    ) = SettingsState(sources = sources, cache = cache, scope = backgroundScope)
}

/** 记录调用的假来源仓储；`gate` 用来把一次装载卡在半途。 */
private class RecordingSourceRepository : ContentSourceRepository {

    override val status = MutableStateFlow(SourceStatus.Initial)

    val applyCalls = mutableListOf<String>()

    var clearCount = 0
        private set

    /** 非 null 时 `applyConfig` 会在这里挂住，直到调用方放行。 */
    var gate: CompletableDeferred<Unit>? = null

    var applyError: Throwable? = null

    override suspend fun restore() = Unit

    override suspend fun applyConfig(url: String): String? {
        applyCalls += url
        gate?.await()
        applyError?.let { throw it }
        return null
    }

    override fun selectSource(sourceId: String) = Unit

    override suspend fun clear() {
        clearCount++
    }
}
