package com.cycling.beevideo.domain.model

import org.junit.Assert.assertEquals
import org.junit.Test

class CustomHeadersTest {

    // ---------------------------------------------------- parseCustomHeaders

    @Test
    fun `常规解析`() {
        assertEquals(
            mapOf("Referer" to "https://a.com", "User-Agent" to "x"),
            parseCustomHeaders("Referer: https://a.com\nUser-Agent: x"),
        )
    }

    @Test
    fun `值里的冒号不被切开`() {
        assertEquals(
            mapOf("Referer" to "https://a.com/b?c=d"),
            parseCustomHeaders("Referer: https://a.com/b?c=d"),
        )
    }

    @Test
    fun `空行与注释行跳过`() {
        assertEquals(
            mapOf("A" to "1"),
            parseCustomHeaders("\n  \n# 说明\nA: 1"),
        )
    }

    @Test
    fun `没有冒号的行算无效`() {
        assertEquals(emptyMap<String, String>(), parseCustomHeaders("Referer https://a.com"))
    }

    @Test
    fun `冒号开头或值为空的行算无效`() {
        assertEquals(emptyMap<String, String>(), parseCustomHeaders(": v\nA:"))
    }

    @Test
    fun `同名两行以最后一行为准`() {
        assertEquals(mapOf("A" to "2"), parseCustomHeaders("A: 1\nA: 2"))
    }

    @Test
    fun `名字与值两侧空白被清掉`() {
        assertEquals(mapOf("A" to "1"), parseCustomHeaders("  A :   1  "))
    }

    @Test
    fun `无效行数如实报告`() {
        assertEquals(2, invalidCustomHeaderLineCount("A: 1\n坏行\nB: 2\n\n#c\n: x"))
    }

    // ---------------------------------------------------- withCustomHeaders

    @Test
    fun `用户头只补空缺`() {
        assertEquals(
            mapOf("Referer" to "https://src", "UA" to "user"),
            withCustomHeaders(
                source = mapOf("Referer" to "https://src"),
                custom = mapOf("Referer" to "https://wrong", "UA" to "user"),
            ),
        )
    }

    @Test
    fun `补空缺按大小写不敏感比对`() {
        assertEquals(
            mapOf("referer" to "https://src"),
            withCustomHeaders(
                source = mapOf("referer" to "https://src"),
                custom = mapOf("Referer" to "https://wrong"),
            ),
        )
    }

    @Test
    fun `来源没有头时全部采用用户头`() {
        assertEquals(
            mapOf("UA" to "user"),
            withCustomHeaders(source = emptyMap(), custom = mapOf("UA" to "user")),
        )
    }

    @Test
    fun `用户没填时原样返回来源`() {
        val source = mapOf("Referer" to "https://src")
        assertEquals(source, withCustomHeaders(source, emptyMap()))
    }
}
