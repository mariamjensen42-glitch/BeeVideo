package com.cycling.beevideo.data.repository

import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.fail
import org.junit.Test

/**
 * [MergeWindow] 的行为约束。
 *
 * 时钟是**注入**的，所以「窗口过期」这条分支能被真正测到 —— 默认的
 * `SystemClock.elapsedRealtime()` 在纯 JVM 测试里是 android.jar 的桩、
 * 恒返回 0，靠它永远走不出窗口。
 */
class MergeWindowTest {

    @Test
    fun `窗口内同一个 key 只取一次`() = runBlocking {
        var calls = 0
        val window = MergeWindow<String>(1_000) { 0L }

        val first = window.get("k") { calls++; "a" }
        val second = window.get("k") { calls++; "b" }

        assertEquals("第二次应命中快照，取到的是第一次的结果", "a", first)
        assertEquals("a", second)
        assertEquals(1, calls)
    }

    @Test
    fun `窗口过期后重新取`() = runBlocking {
        var now = 0L
        var calls = 0
        val window = MergeWindow<String>(1_000) { now }

        window.get("k") { calls++; "a" }
        assertEquals(1, calls)

        // 窗口是 [at, at+window)：999 还在里面
        now = 999
        window.get("k") { calls++; "b" }
        assertEquals("边界内不该重新取", 1, calls)

        now = 1_000
        assertEquals("恰好到窗口长度就算出窗", "c", window.get("k") { calls++; "c" })
        assertEquals(2, calls)
    }

    @Test
    fun `key 不同不命中`() = runBlocking {
        var calls = 0
        val window = MergeWindow<String>(1_000) { 0L }

        window.get("a") { calls++; "va" }
        assertEquals("vb", window.get("b") { calls++; "vb" })
        assertEquals(2, calls)
    }

    /**
     * 这条是**在途合并**：第一个请求还在飞的时候第二个进来，它不该再发一次，
     * 而应该在锁上等第一发写完、然后命中快照。首页那两个成对的调用
     * （`categories` + `listByCategory`）经常就是这个时序。
     */
    @Test
    fun `在途的同一个 key 只会真正取一次`() = runBlocking {
        var calls = 0
        val window = MergeWindow<String>(1_000) { 0L }
        val got = mutableListOf<String>()

        val jobs = (1..5).map {
            launch {
                val v = window.get("k") {
                    calls++
                    delay(50)
                    "v"
                }
                synchronized(got) { got += v }
            }
        }
        jobs.forEach { it.join() }

        assertEquals(1, calls)
        assertEquals(5, got.size)
        assertEquals(listOf("v", "v", "v", "v", "v"), got)
    }

    /**
     * 失败**不写快照**：写进去的话，用户在界面上点「重试」拿到的还是同一个
     * 错误，而窗口有 3 秒 —— 表现得像「重试没用」。
     */
    @Test
    fun `fetch 抛异常不写快照且锁会释放`() = runBlocking {
        var calls = 0
        val window = MergeWindow<String>(1_000) { 0L }

        runCatching { window.get("k") { calls++; error("boom") } }
            .onSuccess { fail("fetch 抛出的异常应该原样抛出") }
        assertEquals(1, calls)

        // 锁释放了 + 没有脏快照，所以同一个 key 还能再取一次
        assertEquals("ok", window.get("k") { calls++; "ok" })
        assertEquals(2, calls)
    }

    @Test
    fun `invalidate 之后重新取`() = runBlocking {
        var calls = 0
        val window = MergeWindow<String>(1_000) { 0L }

        window.get("k") { calls++; "a" }
        window.invalidate()
        assertEquals("b", window.get("k") { calls++; "b" })
        assertEquals(2, calls)
    }
}
