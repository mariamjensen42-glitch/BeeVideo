package com.cycling.beevideo.ui.history

import com.cycling.beevideo.domain.model.KeepItem
import com.cycling.beevideo.domain.model.PlayProgress
import com.cycling.beevideo.domain.repository.ContentSourceRepository
import com.cycling.beevideo.domain.repository.LibraryRepository
import com.cycling.beevideo.ui.mvi.MviState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/**
 * 观看历史页的状态持有者：三条订阅，加四个写动作。
 *
 * 三条流**分开订阅**而不是 `combine` 成一条：合并的话，"收藏变了"这件事也会重算一遍
 * 来源名映射 —— 而三者各自只影响状态里的一段。
 */
class HistoryState(
    private val library: LibraryRepository,
    sources: ContentSourceRepository,
    /** 取当前时间。注入是为了让收藏记录的 `createdAt` 在测试里确定。 */
    private val now: () -> Long = System::currentTimeMillis,
    scope: CoroutineScope,
) : MviState<HistoryUiState, HistoryIntent, HistoryEffect>(HistoryUiState(), scope) {

    init {
        scope.launch {
            library.progressList.collect { records ->
                setState { it.copy(loading = false, records = records) }
            }
        }

        scope.launch {
            library.keeps.collect { keeps ->
                setState { it.copy(keptIds = keeps.mapTo(mutableSetOf()) { it.vodId }) }
            }
        }

        scope.launch {
            sources.status.collect { status ->
                setState { it.copy(sourceNames = status.sources.associate { it.id to it.name }) }
            }
        }
    }

    override fun onIntent(intent: HistoryIntent) {
        when (intent) {
            is HistoryIntent.OnContinue -> sendEffect(HistoryEffect.Continue(intent.progress))

            is HistoryIntent.OnOpenDetail -> sendEffect(HistoryEffect.OpenDetail(intent.vodId))

            is HistoryIntent.OnToggleKeep -> scope.launch {
                library.toggleKeep(intent.progress.toKeepItem(now()))
            }

            is HistoryIntent.OnDeleteRecord -> scope.launch {
                library.deleteProgress(intent.vodId)
            }

            HistoryIntent.OnClearAll -> scope.launch { library.clearProgress() }

            HistoryIntent.OnBack -> sendEffect(HistoryEffect.Back)
        }
    }
}

/**
 * 收藏用记录里的快照拼，**不回源**：能一站收藏是这一页的价值之一，而回源在来源挂掉时
 * 必然失败。快照缺字段（v2 之前的老记录）时退回用 vodId 当片名 —— 收藏页至少还认得出。
 */
private fun PlayProgress.toKeepItem(createdAt: Long) = KeepItem(
    vodId = vodId,
    name = name.ifBlank { vodId },
    pic = pic,
    score = score,
    remarks = remarks,
    createdAt = createdAt,
)
