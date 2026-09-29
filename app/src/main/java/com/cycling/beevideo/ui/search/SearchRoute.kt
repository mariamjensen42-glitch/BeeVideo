package com.cycling.beevideo.ui.search

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.cycling.beevideo.domain.model.Vod
import com.cycling.beevideo.domain.repository.ContentRepository
import com.cycling.beevideo.domain.repository.SearchHistoryRepository

/** 搜索页的连接层：取持有者、订阅状态、消费一次性事件。 */
@Composable
fun SearchRoute(
    content: ContentRepository,
    history: SearchHistoryRepository,
    onVodClick: (Vod) -> Unit,
    onBack: () -> Unit,
) {
    val viewModel: SearchViewModel = viewModel(
        factory = viewModelFactory { initializer { SearchViewModel(content, history) } },
    )
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()

    LaunchedEffect(viewModel) {
        viewModel.effect.collect { effect ->
            when (effect) {
                is SearchEffect.OpenVod -> onVodClick(effect.vod)
                SearchEffect.Back -> onBack()
            }
        }
    }

    SearchScreen(uiState = uiState, onIntent = viewModel::onIntent)
}
