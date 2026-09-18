package com.github.catvod.utils

import org.junit.Assert.assertEquals
import org.junit.Test
import java.util.Locale

/**
 * [Trans] 的简繁转换测试。
 *
 * ─── ⚠️ 方向先说清楚，这里很容易读反 ─────────────────────────────────
 * 参考实现的两张表是：
 * ```
 * S2T.put(简体, 繁体)   // 键是简体 → 查它得到繁体 = 简 → 繁
 * T2S.put(繁体, 简体)   // 键是繁体 → 查它得到简体 = 繁 → 简
 * ```
 * 所以 **`s2t` 的入参是简体、出参是繁体**（"simplified to traditional"），
 * `t2s` 反过来。输入里混着一个**已经是目标形态**的字时，它不在键里，
 * 于是**原样保留** —— 这不是 bug，是映射表的定义。
 *
 * ─── 最要紧的一条：两张表必须等长且位置对应 ───────────────────────────
 * `UTF8T` / `UTF8S` 是**位置对应**的字符对，第 i 位是一对。从参考实现逐字符
 * 搬过来时只错开一个字符，整张映射表就从那一处开始**全部错位** ——
 * 而错位的症状是"转换出来的字看着也是汉字，但根本不是那个字"，
 * 既不崩也不报错，比崩溃难查得多。
 *
 * 这个性质由 `Trans` 的静态初始化**隐式保证**：它按 `UTF8T.length` 逐位取
 * 两张表的下标，`UTF8S` 短一个字符就会在类初始化时抛
 * `StringIndexOutOfBoundsException`。所以下面**任何**一条断言能跑，
 * 就说明两张表是等长的。
 */
class TransTest {

    @Test
    fun `简转繁可用（能跑起来即证明两张表等长）`() {
        // 这条同时是"数据表长度一致"的哨兵：不等长会在 <clinit> 里先抛
        assertEquals("專業", Trans.s2t(false, "专业"))
    }

    @Test
    fun `简转繁 与 繁转简 互为逆`() {
        assertEquals("專業", Trans.s2t(false, "专业"))
        assertEquals("专业", Trans.t2s(false, "專業"))
        assertEquals("萬與醜", Trans.s2t(false, "万与丑"))
        assertEquals("万与丑", Trans.t2s(false, "萬與醜"))
    }

    /**
     * 表里没有的字符**原样保留** —— 包括 ASCII、数字，以及
     * **已经是目标形态**的汉字（它不在键里）。
     */
    @Test
    fun `没有映射的字符原样保留`() {
        assertEquals("abc123", Trans.s2t(false, "abc123"))
        assertEquals("abc123", Trans.t2s(false, "abc123"))
        assertEquals("a專b", Trans.s2t(false, "a专b"))
        // '专' 是简体，不在 T2S 的键里（T2S 的键是繁体）→ 原样返回
        assertEquals("a专b", Trans.t2s(false, "a专b"))
    }

    /**
     * `pass = true` 表示"不需要转换"，输入原样返回。
     *
     * ⚠️ 别把它当成"禁用开关"删掉：`pass()` 是按系统区域算出来的，
     * 简体地区的用户拿到繁体源时**不该**被转成繁体。
     */
    @Test
    fun `pass 为真时不做任何转换`() {
        assertEquals("专业", Trans.s2t(true, "专业"))
        assertEquals("專業", Trans.t2s(true, "專業"))
    }

    /** 空串走短路分支，不该去查表。 */
    @Test
    fun `空串原样返回`() {
        assertEquals("", Trans.s2t(false, ""))
        assertEquals("", Trans.t2s(false, ""))
    }

    /** 入参为 null 时返回 null（不是空串）—— 参考实现如此。 */
    @Test
    fun `null 进 null 出`() {
        assertEquals(null, Trans.s2t(null))
        assertEquals(null, Trans.t2s(null))
    }

    /**
     * `pass()` 的语义 = "当前区域不是台湾"。
     *
     * 钉住它是因为它**看起来像个可以随便改的开关**，而它其实是一个按
     * `Locale.getDefault().country` 算出来的值。断言写成"与区域判断一致"
     * 而不是写死 `true` —— 否则在台湾的机器上会假红。
     */
    @Test
    fun `pass 与系统区域一致`() {
        assertEquals("TW" != Locale.getDefault().country, Trans.pass())
    }
}
