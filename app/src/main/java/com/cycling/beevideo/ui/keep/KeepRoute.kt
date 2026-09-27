package com.cycling.beevideo.ui.keep

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.cycling.beevideo.domain.repository.LibraryRepository

/**
 * 收藏页的连接层：取持有者、订阅状态、消费一次性事件。
 *
 * 界面（[KeepScreen]）因此只认 [KeepUiState] 与 [KeepIntent] —— 不碰仓储，也不认识
 * `NavController`。导航在这里被翻译成回调，宿主（`BeeNavHost`）接上真正的路由。
 */
@Composable
fun KeepRoute(
    library: LibraryRepository,
    onVodClick: (String) -> Unit,
) {
    val viewModel: KeepViewModel = viewModel(
        factory = viewModelFactory { initializer { KeepViewModel(library) } },
    )
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()

    LaunchedEffect(viewModel) {
        viewModel.effect.collect { effect ->
            when (effect) {
                is KeepEffect.OpenVod -> onVodClick(effect.vodId)
            }
        }
    }

    KeepScreen(uiState = uiState, onIntent = viewModel::onIntent)
}
