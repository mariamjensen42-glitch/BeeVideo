package com.cycling.beevideo.ui.search

import com.cycling.beevideo.domain.repository.ContentRepository
import com.cycling.beevideo.ui.components.LoadState
import com.cycling.beevideo.ui.mvi.MviState
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/**
 * 搜索页的状态持有者。提交式：只有 [SearchIntent.OnSubmit] 会发请求，敲键盘不会。
 *
 * 搜索是**跨源**的，一次提交会同时打向所有可搜索站点，所以"边打边搜"的代价是
 * 每个字符一整轮跨源请求 —— 见 [SearchUiState] 里 `input` / `submitted` 的分工。
 */
class SearchState(
    private val content: ContentRepository,
    scope: CoroutineScope,
) : MviState<SearchUiState, SearchIntent, SearchEffect>(SearchUiState(), scope) {

    private var searchJob: Job? = null

    override fun onIntent(intent: SearchIntent) {
        when (intent) {
            is SearchIntent.OnInputChange -> setState { it.copy(input = intent.text) }

            SearchIntent.OnClearInput -> setState { it.copy(input = "") }

            SearchIntent.OnSubmit -> submit()

            is SearchIntent.OnOpenVod -> sendEffect(SearchEffect.OpenVod(intent.vod))

            SearchIntent.OnBack -> sendEffect(SearchEffect.Back)
        }
    }

    private fun submit() {
        val keyword = currentState.input.trim()
        if (keyword.isEmpty()) return

        setState { it.copy(submitted = keyword, result = LoadState.Loading) }
        // 取消上一次：连点两下提交时，先发的那个可能后到，把新词的结果盖回旧的
        searchJob?.cancel()
        searchJob = scope.launch {
            // load 是 suspend，必须先取值再 setState（setState 的 lambda 不是 suspend）
            val result = load { content.search(keyword) }
            setState { it.copy(result = result) }
        }
    }

    /**
     * 成败都在这里收敛。失败**只转成状态**、不抛出去：界面每一处都要处理失败；
     * 取消原样重抛，因为它不是失败，是"这次不算数"（被取消的那次不该写状态）。
     */
    private suspend fun <T> load(block: suspend () -> T): LoadState<T> = try {
        LoadState.Ready(block())
    } catch (e: CancellationException) {
        throw e
    } catch (e: Throwable) {
        LoadState.Failed(e.message ?: "加载失败")
    }
}
