package com.cycling.beevideo.ui.player

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material.icons.filled.Brightness6
import androidx.compose.material.icons.filled.FastForward
import androidx.compose.material.icons.filled.FastRewind
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.cycling.beevideo.R
import com.cycling.beevideo.ui.theme.BeeDimens
import com.cycling.beevideo.ui.theme.BeeMotion
import com.cycling.beevideo.ui.theme.BeeVideoTheme
import com.cycling.beevideo.ui.theme.MotionSpeed

private val HUD_BAR_WIDTH = 120.dp
private val HUD_BAR_HEIGHT = 4.dp
private val HUD_GLYPH_SIZE = 24.dp
private const val HUD_CONTAINER_ALPHA = 0.78f
private const val HUD_TRACK_ALPHA = 0.3f
private const val HUD_SECONDARY_ALPHA = 0.7f

/**
 * 手势 HUD —— 滑动时浮在画面**正中**的那一块。没有手势时整块不存在。
 *
 * 位置取正中而不是靠边：三种手势的反馈都要"一眼看见但不挡着看内容的注意力"，
 * 靠边会跟控件那两个角挤在一起。
 *
 * [feedback] 传 `null` 表示手势结束。退出动画期间还要有东西可画，所以最后一次
 * 反馈被记在 [last] 里 —— 直接按 `feedback` 渲染的话，收场那一帧会一闪空白。
 */
@Composable
internal fun PlayerGestureOverlay(
    feedback: PlayerGestureFeedback?,
    modifier: Modifier = Modifier,
) {
    var last by remember { mutableStateOf<PlayerGestureFeedback?>(null) }
    if (feedback != null) last = feedback

    AnimatedVisibility(
        visible = feedback != null,
        modifier = modifier,
        enter = fadeIn(BeeMotion.floatEffects(MotionSpeed.FAST)),
        exit = fadeOut(BeeMotion.floatEffects(MotionSpeed.FAST)),
    ) {
        // HUD 压在画面正中：不给它全屏背景，只让这块浮着，别把画面压灰
        Box {
            last?.let { PlayerGestureCard(it) }
        }
    }
}

@Composable
private fun PlayerGestureCard(feedback: PlayerGestureFeedback) {
    Surface(
        shape = MaterialTheme.shapes.large,
        // 和控件同一套：底是 scrim、字是纯白，这一层压在媒体内容上，不取主题角色色
        color = MaterialTheme.colorScheme.scrim.copy(alpha = HUD_CONTAINER_ALPHA),
        contentColor = Color.White,
    ) {
        Row(
            modifier = Modifier.padding(
                horizontal = BeeDimens.gapMedium,
                vertical = BeeDimens.gapSmall,
            ),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(BeeDimens.gapSmall),
        ) {
            when (feedback) {
                is PlayerGestureFeedback.Seek -> SeekHud(feedback)
                is PlayerGestureFeedback.Brightness -> LevelHud(
                    icon = Icons.Filled.Brightness6,
                    label = stringResource(R.string.player_gesture_brightness),
                    level = feedback.level,
                )
                is PlayerGestureFeedback.Volume -> LevelHud(
                    icon = Icons.AutoMirrored.Filled.VolumeUp,
                    label = stringResource(R.string.player_gesture_volume),
                    level = feedback.level,
                )
            }
        }
    }
}

@Composable
private fun SeekHud(feedback: PlayerGestureFeedback.Seek) {
    // 往前滑是快进、往后是快退。符号直接写在偏移量上，图标只表示方向
    val forward = feedback.deltaMs >= 0L
    Icon(
        imageVector = if (forward) Icons.Filled.FastForward else Icons.Filled.FastRewind,
        contentDescription = null,
        modifier = Modifier.size(HUD_GLYPH_SIZE),
    )
    Column {
        Text(
            text = formatClock(feedback.targetMs),
            style = MaterialTheme.typography.titleMediumEmphasized,
        )
        Text(
            text = signedClock(feedback.deltaMs),
            style = MaterialTheme.typography.labelMedium,
            color = Color.White.copy(alpha = HUD_SECONDARY_ALPHA),
        )
    }
}

@Composable
private fun LevelHud(icon: ImageVector, label: String, level: Float) {
    Icon(
        imageVector = icon,
        contentDescription = label,
        modifier = Modifier.size(HUD_GLYPH_SIZE),
    )
    Column {
        Text(text = label, style = MaterialTheme.typography.labelMedium)
        Box(
            modifier = Modifier
                .padding(top = BeeDimens.gapTight)
                .width(HUD_BAR_WIDTH)
                .height(HUD_BAR_HEIGHT)
                .clip(CircleShape)
                .background(Color.White.copy(alpha = HUD_TRACK_ALPHA)),
        ) {
            Box(
                Modifier
                    .fillMaxWidth(level.coerceIn(0f, 1f))
                    .fillMaxHeight()
                    .background(Color.White),
            )
        }
    }
}

/** `+01:23` / `-00:45`。偏移量为 0 时不带符号 —— `+00:00` 看着像出错了。 */
private fun signedClock(deltaMs: Long): String {
    val sign = if (deltaMs > 0L) "+" else if (deltaMs < 0L) "-" else ""
    return sign + formatClock(if (deltaMs < 0L) -deltaMs else deltaMs)
}

@Preview(
    name = "手势 HUD · 快进",
    group = "组件",
    showBackground = true,
    backgroundColor = 0xFF0B0A08,
    widthDp = 411,
    heightDp = 231,
)
@Composable
private fun PlayerGestureSeekPreview() {
    BeeVideoTheme(darkTheme = true) {
        Box(Modifier.fillMaxHeight(), contentAlignment = Alignment.Center) {
            PlayerGestureOverlay(
                feedback = PlayerGestureFeedback.Seek(targetMs = 192_000L, deltaMs = 15_000L),
            )
        }
    }
}

@Preview(
    name = "手势 HUD · 音量",
    group = "组件",
    showBackground = true,
    backgroundColor = 0xFF0B0A08,
    widthDp = 411,
    heightDp = 231,
)
@Composable
private fun PlayerGestureVolumePreview() {
    BeeVideoTheme(darkTheme = false) {
        Box(Modifier.fillMaxHeight(), contentAlignment = Alignment.Center) {
            PlayerGestureOverlay(feedback = PlayerGestureFeedback.Volume(level = 0.62f))
        }
    }
}
