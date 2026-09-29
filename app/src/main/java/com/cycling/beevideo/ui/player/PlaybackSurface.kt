package com.cycling.beevideo.ui.player

import android.view.ViewGroup
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.LoadingIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.media3.ui.PlayerView
import com.cycling.beevideo.domain.repository.PlaybackSession
import com.cycling.beevideo.player.MediaControllerPlaybackSession

/**
 * 画面槽：把会话接到 Media3 的 PlayerView 上。
 * 这里是唯一还认识 Media3 的界面代码；控制器自绘，所以 `useController = false`
 * （自带控制器会把亮度 / 音量 / 进度手势要的触摸事件吃掉）。
 */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
internal fun PlaybackSurface(
    session: PlaybackSession,
    /** 判据里含"控件已收起"：同时显示的话转圈会被中央播放按钮盖住。 */
    isBuffering: Boolean,
    modifier: Modifier,
    overlay: @Composable BoxScope.() -> Unit,
) {
    // 控制器连接是异步的，没连上就先空着 —— MediaController 本身就是 Player，
    // 连上那一刻这条流发出值，PlayerView 才绑得上
    val player = (session as? MediaControllerPlaybackSession)
        ?.playerFlow
        ?.collectAsStateWithLifecycle()
        ?.value
        ?: return

    Box(modifier) {
        AndroidView(
            factory = { ctx ->
                PlayerView(ctx).apply {
                    this.player = player
                    useController = false
                    layoutParams = ViewGroup.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.MATCH_PARENT,
                    )
                }
            },
            update = { it.player = player },
            modifier = Modifier.fillMaxSize(),
        )
        if (isBuffering) {
            LoadingIndicator(
                modifier = Modifier.align(Alignment.Center),
                color = MaterialTheme.colorScheme.primary,
            )
        }
        overlay()
    }
}
