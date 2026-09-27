package com.cycling.beevideo.ui.history

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.cycling.beevideo.domain.model.PlayProgress
import com.cycling.beevideo.domain.repository.ContentSourceRepository
import com.cycling.beevideo.domain.repository.LibraryRepository

/**
 * 观看历史页的连接层：取持有者、订阅状态、消费一次性事件。
 *
 * 「继续播放」少一次网络往返这件事落在宿主侧：路线直接用记录里存的**线路号**，
 * 不先查详情，所以来源挂掉时这个按钮照样跳得过去。
 */
@Composable
fun HistoryRoute(
    library: LibraryRepository,
    sources: ContentSourceRepository,
    onBack: () -> Unit,
    onContinue: (PlayProgress) -> Unit,
    onOpenDetail: (String) -> Unit,
) {
    val viewModel: HistoryViewModel = viewModel(
        factory = viewModelFactory {
            initializer { HistoryViewModel(library, sources) }
        },
    )
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()

    LaunchedEffect(viewModel) {
        viewModel.effect.collect { effect ->
            when (effect) {
                is HistoryEffect.Continue -> onContinue(effect.progress)
                is HistoryEffect.OpenDetail -> onOpenDetail(effect.vodId)
                HistoryEffect.Back -> onBack()
            }
        }
    }

    HistoryScreen(uiState = uiState, onIntent = viewModel::onIntent)
}
