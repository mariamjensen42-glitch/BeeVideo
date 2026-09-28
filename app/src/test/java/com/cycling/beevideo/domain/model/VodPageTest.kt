package com.cycling.beevideo.domain.model

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 翻页判据。
 *
 * 这里错的两个方向都不报错：判早了 —— 内容被截在用户看不见的地方（就是首页只有 30 部那个问题的成因）；
 * 判晚了 —— 触底无限拉空页，源拿重复项充数时尤其明显。
 */
class VodPageTest {

    @Test
    fun `源给了页数就按页数判`() {
        val page = VodPage(vods = listOf(vod("a"), vod("b")), totalPages = 3)

        assertTrue(page.hasMoreAfter(1))
        assertTrue(page.hasMoreAfter(2))
        assertFalse("第 3 页之后没有了", page.hasMoreAfter(3))
    }

    @Test
    fun `源没给页数时这一页有内容就还能往下拉`() {
        assertTrue(VodPage(vods = listOf(vod("a")), totalPages = null).hasMoreAfter(1))
        assertFalse("空页就是到底", VodPage(vods = emptyList(), totalPages = null).hasMoreAfter(7))
    }

    @Test
    fun `只有一页的源无论怎么问都没有下一页`() {
        val page = VodPage(vods = listOf(vod("a")), totalPages = 1)

        assertFalse(page.hasMoreAfter(1))
        assertFalse(page.hasMoreAfter(2))
    }

    private fun vod(id: String) = Vod(
        id = id,
        name = id,
        categoryId = "",
        year = "",
        area = "",
        genre = "",
        score = "",
        remarks = "",
        director = "",
        actors = "",
        intro = "",
        pic = "",
        lines = emptyList(),
    )
}
