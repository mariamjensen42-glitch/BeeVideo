package com.cycling.beevideo.ui.preview

import com.cycling.beevideo.data.settings.SearchHistoryStore
import com.cycling.beevideo.domain.repository.SearchHistoryRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow

/**
 * 预览与 JVM 测试用的假 [SearchHistoryRepository]，不在任何业务路径上
 * （真机跑的是 `PrefsSearchHistoryRepository`）。
 *
 * 上限与去重复用 [SearchHistoryStore] 的纯函数，而不是在这里再写一遍 ——
 * 两份实现迟早会出现"预览里去重了、真机上没去"这种只在对照两屏时才看得出的差异。
 */
class FakeSearchHistoryRepository(
    initial: List<String> = emptyList(),
) : SearchHistoryRepository {

    private val state = MutableStateFlow(initial)

    override val keywords: Flow<List<String>> = state

    override fun record(keyword: String) {
        val trimmed = keyword.trim()
        if (trimmed.isEmpty()) return
        state.value = SearchHistoryStore.record(state.value, trimmed)
    }

    override fun remove(keyword: String) {
        state.value = SearchHistoryStore.remove(state.value, keyword)
    }

    override fun clear() {
        state.value = emptyList()
    }
}
