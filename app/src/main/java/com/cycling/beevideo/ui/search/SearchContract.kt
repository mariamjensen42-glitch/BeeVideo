package com.cycling.beevideo.ui.search

import com.cycling.beevideo.domain.model.SearchOutcome
import com.cycling.beevideo.domain.model.Vod
import com.cycling.beevideo.ui.components.LoadState

/**
 * 搜索页的界面状态。
 *
 * ─── 为什么 `input` 与 `submitted` 分开 ────────────────────────────────
 * 这是**跨源**搜索：一次提交会同时打向当前来源里所有可搜索的站点（上限见
 * `VodContentRepository`）。跟随输入的即时搜索会在每个字符上发起一整轮跨源请求 ——
 * 打"庆余年"三个字就是三轮，而前两轮的结果注定被丢掉。
 *
 * 所以只有提交才改变 [submitted]，而 [submitted] 才是发请求的键。
 *
 * `input` 住在这里而不是界面的 `rememberSaveable`：它跨 Activity 重建也要在
 * （主题切换会重建 Activity），而 ViewModel 天然给到这一点。
 */
data class SearchUiState(
    /** 输入框里的字。 */
    val input: String = "",
    /** 真正搜过的词。它变了才发请求。 */
    val submitted: String = "",
    /**
     * 三态：`Ready(null)` = 还没搜过 / `Loading` = 搜索中 / `Ready(outcome)` 或
     * `Failed` = 有结局。
     *
     * 用 null 表达"还没搜"而不是空列表：空列表会被界面读成"搜了，没有结果"，
     * 而那是**两句不同的话** —— 前者要告诉用户怎么开始，后者要说清搜的是哪个词。
     */
    val result: LoadState<SearchOutcome?> = LoadState.Ready(null),
    /**
     * 最近搜过的词，新的在前。
     *
     * 它不进除空态之外的任何判断 —— 界面只在"还没搜过"那一支用它。
     * 无痕模式下它恒为空（拦截在仓储，见 `PrefsSearchHistoryRepository`），
     * 所以界面不需要知道无痕这回事。
     */
    val history: List<String> = emptyList(),
)

sealed interface SearchIntent {
    data class OnInputChange(val text: String) : SearchIntent

    /** 清空输入框。**不动结果** —— 用户只是想换个词重搜。 */
    data object OnClearInput : SearchIntent

    /** 提交（点键盘的搜索键）。空白词直接忽略。 */
    data object OnSubmit : SearchIntent

    /** 点一条历史。**等同于把那个词填进输入框再提交**，否则结果有了而输入框空着。 */
    data class OnUseHistory(val keyword: String) : SearchIntent

    /** 删一条历史。 */
    data class OnRemoveHistory(val keyword: String) : SearchIntent

    /** 清空历史。界面负责先二次确认。 */
    data object OnClearHistory : SearchIntent

    data class OnOpenVod(val vod: Vod) : SearchIntent

    data object OnBack : SearchIntent
}

sealed interface SearchEffect {
    data class OpenVod(val vod: Vod) : SearchEffect

    data object Back : SearchEffect
}
