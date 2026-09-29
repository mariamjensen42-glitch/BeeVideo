package com.cycling.beevideo.ui.player

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.cycling.beevideo.R
import com.cycling.beevideo.domain.model.PlaybackState
import com.cycling.beevideo.ui.theme.BeeDimens
import com.cycling.beevideo.ui.theme.PlayerSurface

/**
 * 应用内小窗：退出播放页之后画面**留在 App 里**继续放。
 *
 * ─── 为什么不是系统画中画 ──────────────────────────────────────────────
 * 系统 PiP 会把整个 App 缩成一块浮在**桌面上**的窗口 —— 于是"按返回"变成"被踢出 App"。
 * 这里要的是"还在 App 里，只是不在播放页"，所以只能自己画一块。
 *
 * ⚠️ 内核只有一个画面出口，同一时刻只能有一个 `PlayerView` 绑着那个会话：本窗只在
 * **离开播放页**时出现，播放页一出现它就被移出组合，两者不会并存。
 *
 * ⚠️ 点击的优先级靠"子节点先消费"：画面区整块点击回播放页，两个按钮压在它上面各收自己的。
 */
@Composable
internal fun MiniPlayerWindow(
    state: PlayerPlaybackState,
    /** 用户开了画中画**且**设备支持。小窗放着的时候按 Home 也该缩成系统小窗。 */
    pipEnabled: Boolean,
    onOpen: () -> Unit,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val playback by state.state.collectAsStateWithLifecycle()
    val current = playback

    // 什么都还没在放（含起不来）：小窗没有存在的意义，不占版面
    if (current is PlaybackState.Idle || current is PlaybackState.Failed) return

    PipAutoEnterEffect(shouldAutoEnterPip(current, pipEnabled))

    val isPlaying =
        current is PlaybackState.Playing || current is PlaybackState.Buffering

    Surface(
        modifier = modifier.width(BeeDimens.miniPlayerWidth),
        shape = RoundedCornerShape(BeeDimens.gapSmall),
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        // 浮在内容之上，边界要看得见：色差不做足，这块在深色下就是糊上去的一块
        shadowElevation = BeeDimens.gapTiny,
        tonalElevation = BeeDimens.gapTight,
    ) {
        Column {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .aspectRatio(BeeDimens.videoAspect)
                    .clickable(onClick = onOpen),
            ) {
                PlaybackSurface(
                    session = state.session,
                    // 小窗不画缓冲转圈：那个圈在小窗里比画面本身还大
                    isBuffering = false,
                    modifier = Modifier.fillMaxSize(),
                ) {}

                MiniButton(
                    icon = if (isPlaying) Icons.Filled.Pause else Icons.Filled.PlayArrow,
                    label = stringResource(
                        if (isPlaying) R.string.player_action_pause else R.string.player_action_play,
                    ),
                    onClick = state::togglePlayPause,
                    modifier = Modifier
                        .align(Alignment.BottomStart)
                        .padding(BeeDimens.gapTight),
                )
                MiniButton(
                    icon = Icons.Filled.Close,
                    label = stringResource(R.string.player_mini_close),
                    // 关小窗 = 用户收场。落进度再暂停，与系统小窗被关掉同一条口径
                    onClick = onClose,
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .padding(BeeDimens.gapTight),
                )
            }

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable(onClick = onOpen)
                    .padding(horizontal = BeeDimens.gapSmall, vertical = BeeDimens.gapTiny),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = state.nowPlayingLabel,
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

/**
 * 压在画面上的小按钮。
 *
 * ⚠️ 颜色写死不走主题角色色（同 `PlayerControls`）：底下永远是视频画面，
 * 浅色主题下拿 `colorScheme` 那套会白底白图标。
 */
@Composable
private fun MiniButton(
    icon: ImageVector,
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier = modifier
            .size(MINI_BUTTON_SIZE)
            .background(PlayerSurface.copy(alpha = MINI_BUTTON_SCRIM_ALPHA), CircleShape)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = icon,
            contentDescription = label,
            tint = Color.White,
            modifier = Modifier.size(MINI_BUTTON_ICON_SIZE),
        )
    }
}

/** 比 M3 的 48dp 小：它压在小窗画面角上，48dp 会盖掉半个画面。 */
private val MINI_BUTTON_SIZE = 32.dp
private val MINI_BUTTON_ICON_SIZE = 18.dp

/** 按钮底那一层黑。0.6 是"图标够清楚"与"还看得见画面"之间的取中值。 */
private const val MINI_BUTTON_SCRIM_ALPHA = 0.6f
