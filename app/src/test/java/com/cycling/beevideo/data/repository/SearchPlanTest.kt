package com.cycling.beevideo.data.repository

import com.cycling.beevideo.domain.model.ContentSource
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 跨源搜索的站点筛选与置顶排序。
 *
 * 这些判据以前只能靠"造一个完整的假站点客户端"来测 —— `SiteClient` 有六个方法，
 * 而这段逻辑一个网络请求都不需要。抽成纯函数之后，静默少搜、计数说谎这类
 * 只在真机上看得出来（且要数半天）的问题第一次可以被钉住。
 */
class SearchPlanTest {

    @Test
    fun `没有排除项时全部可搜`() {
        val plan = planSearch(listOf("a", "b", "c"), excluded = emptySet(), max = 10)

        assertEquals(listOf("a", "b", "c"), plan.keys)
        assertEquals(3, plan.searchable)
        assertEquals(0, plan.disabled)
        assertFalse(plan.truncated)
    }

    @Test
    fun `被排除的站点不进 keys`() {
        val plan = planSearch(listOf("a", "b", "c"), excluded = setOf("b"), max = 10)

        assertEquals(listOf("a", "c"), plan.keys)
    }

    /**
     * 计数必须如实：80 个站点被自己排除掉却显示"已搜索 10 / 10 个源"，
     * 用户只会得出"这个 App 搜索很烂"的结论。
     */
    @Test
    fun `可搜索计数不含被排除的，排除数单独如实上报`() {
        val plan = planSearch(listOf("a", "b", "c"), excluded = setOf("b", "c"), max = 10)

        assertEquals(1, plan.searchable)
        assertEquals(2, plan.disabled)
    }

    @Test
    fun `全部被排除时 keys 为空而排除数不为零`() {
        val plan = planSearch(listOf("a", "b"), excluded = setOf("a", "b"), max = 10)

        assertTrue(plan.keys.isEmpty())
        assertEquals(0, plan.searchable)
        assertEquals(2, plan.disabled)
    }

    /** 截断与排除是两回事：截断时 [SearchPlan.searchable] 仍要说全量，否则界面读不出"少搜了"。 */
    @Test
    fun `超过上限时 keys 截断，但可搜索总数如实`() {
        val keys = (1..25).map { "s$it" }

        val plan = planSearch(keys, excluded = emptySet(), max = 10)

        assertEquals(10, plan.keys.size)
        assertEquals(25, plan.searchable)
        assertTrue(plan.truncated)
    }

    @Test
    fun `置顶的排到最前，其余保持原序`() {
        val sources = listOf(
            ContentSource("a", "甲"),
            ContentSource("b", "乙"),
            ContentSource("c", "丙"),
        )

        val ordered = orderByPinned(sources, pinned = listOf("c"))

        assertEquals(listOf("c", "a", "b"), ordered.map { it.id })
    }

    /** 多个置顶项按置顶列表自己的先后排，而不是按原序。 */
    @Test
    fun `多个置顶项按置顶顺序排列`() {
        val sources = listOf(
            ContentSource("a", "甲"),
            ContentSource("b", "乙"),
            ContentSource("c", "丙"),
        )

        val ordered = orderByPinned(sources, pinned = listOf("c", "b"))

        assertEquals(listOf("c", "b", "a"), ordered.map { it.id })
    }

    /**
     * 置顶记录里可能有这份配置里已不存在的 id（换配置时清了，但恢复旧记录时未必同步）。
     * 丢掉它，不能让它把列表变成空的。
     */
    @Test
    fun `置顶里不存在的 id 被忽略`() {
        val sources = listOf(ContentSource("a", "甲"), ContentSource("b", "乙"))

        val ordered = orderByPinned(sources, pinned = listOf("zzz", "b"))

        assertEquals(listOf("b", "a"), ordered.map { it.id })
    }

    @Test
    fun `没有置顶时原序不动`() {
        val sources = listOf(ContentSource("a", "甲"), ContentSource("b", "乙"))

        assertEquals(sources, orderByPinned(sources, pinned = emptyList()))
    }
}
