package com.cycling.beevideo.ui.detail

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import com.cycling.beevideo.ui.theme.BeeDimens
import com.cycling.beevideo.ui.theme.BeeMotion
import com.cycling.beevideo.ui.theme.MotionSpeed
import com.cycling.beevideo.ui.theme.PRESSED_SCALE

/** 剧集格。用 Card 而不是自己画 Box：卡片自带点击语义、状态层和无障碍角色。 */
@Composable
internal fun EpisodeCell(
    label: String,
    /** 非空时画进度条，表示"这一集上次看到这里"。 */
    progressFraction: Float?,
    onClick: () -> Unit,
) {
    val interactionSource = remember { MutableInteractionSource() }
    val pressed by interactionSource.collectIsPressedAsState()
    val scale by animateFloatAsState(
        targetValue = if (pressed) PRESSED_SCALE else 1f,
        animationSpec = BeeMotion.floatSpatial(MotionSpeed.FAST),
        label = "episodeScale",
    )

    Card(
        onClick = onClick,
        modifier = Modifier
            .height(BeeDimens.episodeCellHeight)
            .graphicsLayer {
                scaleX = scale
                scaleY = scale
            },
        interactionSource = interactionSource,
        shape = MaterialTheme.shapes.medium,
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerHighest,
        ),
    ) {
        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text(
                text = label,
                color = MaterialTheme.colorScheme.onSurface,
                style = MaterialTheme.typography.labelLarge,
                maxLines = 1,
            )

            if (progressFraction != null) {
                EpisodeProgressBar(
                    fraction = progressFraction,
                    modifier = Modifier.align(Alignment.BottomCenter),
                )
            }
        }
    }
}

/** 格底的进度条。左右各让出 12dp（= 卡片圆角半径），不让的话两端会顶出圆角外。 */
@Composable
private fun EpisodeProgressBar(fraction: Float, modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = BeeDimens.episodeProgressInset)
            .height(BeeDimens.episodeProgressHeight)
            .background(
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = EPISODE_TRACK_ALPHA),
                shape = MaterialTheme.shapes.extraSmall,
            ),
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth(fraction)
                .fillMaxHeight()
                .background(
                    color = MaterialTheme.colorScheme.primary,
                    shape = MaterialTheme.shapes.extraSmall,
                ),
        )
    }
}

/** 轨道用 onSurface 的低透明度而不是 outlineVariant —— 后者是为分割线设计的，压在这里会糊掉。 */
private const val EPISODE_TRACK_ALPHA = 0.20f
