package com.cycling.beevideo.data.settings

import com.cycling.beevideo.data.settings.SearchHistoryStore.Companion.MAX
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 搜索历史的纯逻辑：上限、去重上移、编解码往返。
 *
 * 这一层没有 `Context`，所以能直测 —— 而 `SharedPreferences` 那半边（读写、无痕拦截）
 * 测不了，项目没有 robolectric，与 `RoomLibraryRepository` 的处境一样。
 */
class SearchHistoryStoreTest {

    @Test
    fun `新词排在最前`() {
        assertEquals(listOf("b", "a"), SearchHistoryStore.record(listOf("a"), "b"))
    }

    /** 重复搜索同一个词是常态，历史里不该出现两条。 */
    @Test
    fun `重复词上移而不是追加`() {
        val result = SearchHistoryStore.record(listOf("a", "b", "c"), "c")

        assertEquals(listOf("c", "a", "b"), result)
    }

    @Test
    fun `超过上限时丢掉最旧的`() {
        val full = (1..MAX).map { "k$it" }

        val result = SearchHistoryStore.record(full, "new")

        assertEquals(MAX, result.size)
        assertEquals("new", result.first())
        assertTrue("最旧的那条该被挤出去", "k$MAX" !in result)
        assertTrue("第二旧的还在", "k${MAX - 1}" in result)
    }

    @Test
    fun `删除只去掉那一条`() {
        assertEquals(listOf("a", "c"), SearchHistoryStore.remove(listOf("a", "b", "c"), "b"))
    }

    @Test
    fun `删除不存在的词是无操作`() {
        assertEquals(listOf("a"), SearchHistoryStore.remove(listOf("a"), "zzz"))
    }

    /** 编解码必须能往返 —— 分隔符选错的话含分隔符的关键词会被切成两条。 */
    @Test
    fun `编解码往返`() {
        val list = listOf("庆余年", "繁花 第2季", "a/b?c=1", "带 空格")

        assertEquals(list, SearchHistoryStore.decode(SearchHistoryStore.encode(list)))
    }

    /** 空串解出来是空列表，而不是一个含空字符串的列表。 */
    @Test
    fun `空串解出空列表`() {
        assertEquals(emptyList<String>(), SearchHistoryStore.decode(""))
        assertEquals(emptyList<String>(), SearchHistoryStore.decode("\n\n"))
    }
}
