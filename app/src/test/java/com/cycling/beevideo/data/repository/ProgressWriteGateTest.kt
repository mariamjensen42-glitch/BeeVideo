package com.cycling.beevideo.data.repository

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [ProgressWriteGate] 的单元测试。
 *
 * "进度记不住"在界面上是一个症状，底下有四种互不相同的成因（第一次被吞 / 窗口算错 /
 * force 没生效 / 时间源不单调），纯函数 + 注入时钟才能把它们一条条分开钉住。
 */
class ProgressWriteGateTest {

    /** 可控时钟。手动推进，不依赖真实时间 —— 否则测试要么慢、要么随机失败。 */
    private class FakeClock(var at: Long = 0L) : () -> Long {
        override fun invoke(): Long = at
        fun advance(ms: Long) { at += ms }
    }

    private fun gate(clock: FakeClock, windowMs: Long = 5_000L) =
        ProgressWriteGate(windowMs = windowMs, now = clock)

    @Test
    fun `第一次写入必须放行`() {
        // 专门盯 Long.MIN_VALUE 那个溢出陷阱：初值写成最小整数的话，`now - lastAt`
        // 会溢出成负数、被判成"在窗口内"，首次写入就被吞掉
        val clock = FakeClock(at = 1_000_000L)
        assertTrue(gate(clock).allow(force = false))
    }

    @Test
    fun `窗口内的重复写入被拦下`() {
        val clock = FakeClock(at = 1_000_000L)
        val gate = gate(clock)

        assertTrue(gate.allow(force = false))
        clock.advance(4_999L)
        assertFalse(gate.allow(force = false))
    }

    @Test
    fun `窗口外放行`() {
        val clock = FakeClock(at = 1_000_000L)
        val gate = gate(clock)

        assertTrue(gate.allow(force = false))
        clock.advance(5_000L)
        // 边界取「>= 窗口」而不是「> 窗口」：5 秒整就该放行，否则实际间隔会稳定变成 10 秒
        assertTrue(gate.allow(force = false))
    }

    @Test
    fun `force 在窗口内也放行`() {
        val clock = FakeClock(at = 1_000_000L)
        val gate = gate(clock)

        gate.allow(force = false)
        clock.advance(10L)
        assertTrue(gate.allow(force = true))
    }

    @Test
    fun `强制保存之后窗口重新计时`() {
        val clock = FakeClock(at = 1_000_000L)
        val gate = gate(clock)

        gate.allow(force = false)
        clock.advance(10L)
        gate.allow(force = true)

        // 强制保存刚写过，紧接着的周期上报不该再写一次
        clock.advance(10L)
        assertFalse(gate.allow(force = false))

        clock.advance(5_000L)
        assertTrue(gate.allow(force = false))
    }

    @Test
    fun `连续 force 每次都放行`() {
        // force 的语义是"这次必须写进去"，不能因为上一次也是 force 就被挡（连续切集时会这样）
        val clock = FakeClock(at = 1_000_000L)
        val gate = gate(clock)

        repeat(5) {
            assertTrue(gate.allow(force = true))
            clock.advance(1L)
        }
    }
}
