package com.cycling.beevideo.ui.history

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.cycling.beevideo.R
import com.cycling.beevideo.domain.model.PlayProgress
import com.cycling.beevideo.domain.model.isFinished
import com.cycling.beevideo.ui.components.BeePosterImage
import com.cycling.beevideo.ui.components.ContainmentBlock
import com.cycling.beevideo.ui.components.metaLine
import com.cycling.beevideo.ui.components.posterBrush
import com.cycling.beevideo.ui.theme.BeeDimens
import com.cycling.beevideo.ui.theme.BeeMotion
import com.cycling.beevideo.ui.theme.MotionSpeed
import com.cycling.beevideo.ui.theme.PRESSED_SCALE
import java.time.ZoneId

/**
 * 历史行 —— 观看历史页与首页「继续观看」**共用同一个组件**。
 *
 * 两处的差别只有容器：竖排列表里占满整宽，横滑里给一个固定宽度。版式共用是关键 ——
 * 两个实现的话，"首页显示 35%、历史页显示 34%"这种差异迟早会出现，而且没人会去对。
 *
 * 形状直接复用 [ContainmentBlock]（28dp + `surface container low` + 16dp 内边距），
 * 于是它与详情页 / 播放页的块是同一套刻度，包括「内圆角 = 外圆角 − 间距」那条同心规则。
 *
 * @param sourceName 来源（站点）名。来源已从配置里删掉、或名字对不上时给空串，整段不显示。
 * @param now 「今天 / 昨天」的参照时刻。传进来而不是内部取，预览与测试才能有确定的样子。
 * @param menu 长按弹出的菜单槽位。首页那份不传 —— 它只负责"接着看"，管理动作在历史页。
 */
@Composable
internal fun HistoryRow(
    progress: PlayProgress,
    sourceName: String,
    now: Long,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    onLongClick: (() -> Unit)? = null,
    menu: (@Composable () -> Unit)? = null,
) {
    val shape = MaterialTheme.shapes.extraLarge
    val interactionSource = remember { MutableInteractionSource() }
    val pressed by interactionSource.collectIsPressedAsState()
    val scale by animateFloatAsState(
        targetValue = if (pressed) PRESSED_SCALE else 1f,
        animationSpec = BeeMotion.floatSpatial(MotionSpeed.FAST),
        label = "historyRowScale",
    )

    Box(modifier = modifier) {
        Box(
            modifier = Modifier
                // clip 打在点击层外面：指示器的水波纹按节点形状裁，不裁的话圆角块上会露出直角涟漪
                .clip(shape)
                .graphicsLayer {
                    scaleX = scale
                    scaleY = scale
                }
                .combinedClickable(
                    interactionSource = interactionSource,
                    onClick = onClick,
                    onLongClick = onLongClick,
                ),
        ) {
            HistoryRowContent(
                progress = progress,
                sourceName = sourceName,
                now = now,
            )
        }
        menu?.invoke()
    }
}

@Composable
private fun HistoryRowContent(
    progress: PlayProgress,
    sourceName: String,
    now: Long,
) {
    // 老记录（v2 之前写进去的）没有快照，退回用 vodId —— 难看，但比"空白一行"诚实
    val title = progress.name.ifBlank { progress.vodId }
    val episode = progress.episodeName.ifBlank {
        stringResource(R.string.history_episode, progress.episodeIndex + 1)
    }

    val fraction = progressFraction(progress.positionMs, progress.durationMs)
    val finished = progress.isFinished()
    val whenText = watchedAtLabel(watchedAt(progress.updatedAt, now, ZoneId.systemDefault()))

    ContainmentBlock {
        Row {
            Box(
                modifier = Modifier
                    .width(BeeDimens.historyPosterWidth)
                    .aspectRatio(BeeDimens.detailPosterAspect)
                    .clip(MaterialTheme.shapes.medium)
                    .background(posterBrush(progress.vodId)),
            ) {
                // 渐变垫在底下：相当一部分来源不给封面，那是被设计过的一条路径（见 BeePosterImage）
                BeePosterImage(pic = progress.pic)
            }

            Spacer(Modifier.width(BeeDimens.gapSmall))

            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = title,
                    color = MaterialTheme.colorScheme.onSurface,
                    style = MaterialTheme.typography.titleMediumEmphasized,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    // 集数 · 线路 · 来源。空片段连同分隔符一起被 metaLine 丢掉
                    text = metaLine(episode, progress.lineName, sourceName),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.labelMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )

                if (fraction != null) {
                    Spacer(Modifier.height(BeeDimens.gapTiny))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        LinearProgressIndicator(
                            progress = { fraction },
                            modifier = Modifier.weight(1f),
                        )
                        Spacer(Modifier.width(BeeDimens.gapTiny))
                        Text(
                            text = if (finished) {
                                stringResource(R.string.history_finished)
                            } else {
                                stringResource(R.string.history_progress_percent, progressPercent(fraction))
                            },
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            style = MaterialTheme.typography.labelSmallEmphasized,
                        )
                    }
                }

                Spacer(Modifier.height(BeeDimens.gapTight))
                Text(
                    text = metaLine(
                        whenText,
                        // 时长未知时就只给位置，不编一个分母出来
                        progressClock(progress.positionMs, progress.durationMs),
                    ),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.labelSmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

/** [WatchedAt] 的文案。判据在 [watchedAt]，词在这里 —— 见 `HistoryText.kt` 的说明。 */
@Composable
internal fun watchedAtLabel(at: WatchedAt): String = when (at) {
    is WatchedAt.Today -> stringResource(R.string.history_today, at.clock)
    is WatchedAt.Yesterday -> stringResource(R.string.history_yesterday, at.clock)
    is WatchedAt.DaysAgo -> stringResource(R.string.history_days_ago, at.days)
    is WatchedAt.Date -> stringResource(R.string.history_date_this_year, at.month, at.day)
    is WatchedAt.FullDate ->
        stringResource(R.string.history_date, at.year, at.month, at.day)
}
