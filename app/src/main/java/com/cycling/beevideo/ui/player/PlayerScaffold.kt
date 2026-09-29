package com.cycling.beevideo.ui.player

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.ScaffoldDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.cycling.beevideo.R
import com.cycling.beevideo.domain.model.Episode
import com.cycling.beevideo.domain.model.PlayLine
import com.cycling.beevideo.ui.components.BeeBackButton
import com.cycling.beevideo.ui.components.BeeChipRow
import com.cycling.beevideo.ui.components.ContainmentBlock
import com.cycling.beevideo.ui.components.beeTopAppBarColors
import com.cycling.beevideo.ui.theme.BeeDimens
import com.cycling.beevideo.ui.theme.BeeMotion
import com.cycling.beevideo.ui.theme.MotionSpeed
import com.cycling.beevideo.ui.theme.PlayerSurface

/** 播放页布局要看的东西。回调与画面槽不在里面（塞进来会破坏相等性，Preview 也没法换画面）。 */
internal data class PlayerUiState(
    val title: String,
    val lineName: String,
    val episodeName: String,
    val episodes: List<Episode>,
    val currentIndex: Int,
    // 放末尾：上面那行解构是位置式的，插在中间就得跟着改
    val lines: List<PlayLine> = emptyList(),
    val currentLineIndex: Int = 0,
)

/**
 * 播放页布局，不含播放器实现（画面由 [player] 槽位注入）。
 * 顶栏故意不用 MediumFlexibleTopAppBar：竖向空间要留给画面和选集。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun PlayerScaffold(
    state: PlayerUiState,
    onSelectLine: (Int) -> Unit,
    onSelectEpisode: (Int) -> Unit,
    onPrev: () -> Unit,
    onNext: () -> Unit,
    onBack: () -> Unit,
    isFullscreen: Boolean,
    player: @Composable (Modifier) -> Unit,
) {
    val (title, lineName, episodeName, episodes, currentIndex) = state
    val scrollBehavior = TopAppBarDefaults.enterAlwaysScrollBehavior()

    // ⚠️ 全屏与竖屏共用同一棵树：两棵子树会让 AndroidView 重建、SurfaceView 重挂 = 黑闪一帧
    Scaffold(
        containerColor = if (isFullscreen) PlayerSurface else MaterialTheme.colorScheme.surface,
        // 全屏时系统栏已隐藏，再按 systemBars 留边会把画面从屏幕边上推开
        contentWindowInsets = if (isFullscreen) {
            WindowInsets(0, 0, 0, 0)
        } else {
            ScaffoldDefaults.contentWindowInsets
        },
        modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        topBar = {
            // 全屏是不渲染顶栏，不是藏起来 —— 藏起来仍然占着高度
            if (!isFullscreen) {
                TopAppBar(
                    title = {
                        Text(
                            text = title,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    },
                    subtitle = {
                        if (lineName.isNotEmpty()) {
                            Text(text = stringResource(R.string.player_line, lineName))
                        }
                    },
                    navigationIcon = { BeeBackButton(onBack) },
                    colors = beeTopAppBarColors(),
                    scrollBehavior = scrollBehavior,
                )
            }
        },
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                // 全屏不能挂滚动容器：fillMaxSize 会落进无穷高度约束，画面撑不开
                .then(
                    if (isFullscreen) Modifier else Modifier.verticalScroll(rememberScrollState()),
                ),
        ) {
            Box(
                modifier = if (isFullscreen) {
                    Modifier
                        .fillMaxSize()
                        .background(PlayerSurface)
                } else {
                    // 画面不裁切：媒体保持直角，与周围的圆角形成张力
                    Modifier
                        .fillMaxWidth()
                        .aspectRatio(BeeDimens.videoAspect)
                        .background(PlayerSurface)
                },
            ) {
                player(Modifier.fillMaxSize())
            }

            if (!isFullscreen) {
                Spacer(Modifier.height(BeeDimens.gapSmall))

                ContainmentBlock(Modifier.padding(horizontal = BeeDimens.screenMargin)) {
                    Text(
                        text = episodeName,
                        style = MaterialTheme.typography.headlineSmallEmphasized,
                        color = MaterialTheme.colorScheme.onSurface,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Spacer(Modifier.height(BeeDimens.gapTight))
                    Text(
                        text = stringResource(R.string.player_line, lineName),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        style = MaterialTheme.typography.labelMedium,
                    )
                    Spacer(Modifier.height(BeeDimens.gapSmall))

                    Row(horizontalArrangement = Arrangement.spacedBy(BeeDimens.gapTiny)) {
                        FilledTonalButton(
                            onClick = onPrev,
                            enabled = currentIndex > 0,
                        ) {
                            Text(
                                text = stringResource(R.string.player_prev),
                                style = MaterialTheme.typography.labelLarge,
                            )
                        }
                        Button(
                            onClick = onNext,
                            enabled = currentIndex < episodes.lastIndex,
                        ) {
                            Text(
                                text = stringResource(R.string.player_next),
                                style = MaterialTheme.typography.labelLargeEmphasized,
                            )
                        }
                    }
                }

                Spacer(Modifier.height(BeeDimens.gapSmall))

                // 只有一条线路时整块不出现：单项按钮组既占地方又在暗示"可以选"
                if (state.lines.size > 1) {
                    ContainmentBlock(Modifier.padding(horizontal = BeeDimens.screenMargin)) {
                        Text(
                            text = stringResource(R.string.player_section_lines),
                            style = MaterialTheme.typography.titleSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Spacer(Modifier.height(BeeDimens.gapTiny))
                        BeeChipRow(
                            items = state.lines,
                            selectedIndex = state.currentLineIndex,
                            onSelect = onSelectLine,
                        ) { item ->
                            Text(text = item.name, style = MaterialTheme.typography.labelLarge)
                        }
                    }

                    Spacer(Modifier.height(BeeDimens.gapSmall))
                }

                ContainmentBlock(Modifier.padding(horizontal = BeeDimens.screenMargin)) {
                    Text(
                        text = stringResource(R.string.player_section_episodes),
                        style = MaterialTheme.typography.titleSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.height(BeeDimens.gapTiny))
                    LazyRow(
                        horizontalArrangement = Arrangement.spacedBy(BeeDimens.gapTiny),
                    ) {
                        // key 里带下标：换集 / 切线路时 episodes 整体换掉，源给出重名剧集时
                        // 光用短标签当 key 会撞车（LazyList 的 key 重复同样是崩溃）
                        itemsIndexed(
                            items = episodes,
                            key = { index, episode -> "$index:${episode.short}" },
                        ) { index, episode ->
                            EpisodeChip(
                                label = episode.short,
                                selected = index == currentIndex,
                                onClick = { onSelectEpisode(index) },
                            )
                        }
                    }
                }

                Spacer(Modifier.height(BeeDimens.gapHuge))
            }
        }
    }
}

/** 48dp 见方时 full 圆角 = 尺寸的一半 */
private val EPISODE_CELL_FULL = 24.dp

/** 选中收成 medium —— 圆 → 方，Expressive 的形状变化 */
private val EPISODE_CELL_SELECTED = 12.dp

/** 选集格：未选中是正圆，选中收成圆角方。两种动画都用 fast（M3 里「小组件」那一档）。 */
@Composable
private fun EpisodeChip(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
) {
    val corner by animateDpAsState(
        targetValue = if (selected) EPISODE_CELL_SELECTED else EPISODE_CELL_FULL,
        animationSpec = BeeMotion.dpSpatial(MotionSpeed.FAST),
        label = "episodeCorner",
    )
    val scheme = MaterialTheme.colorScheme
    val container by animateColorAsState(
        targetValue = if (selected) scheme.primaryContainer else scheme.surfaceContainerHighest,
        animationSpec = BeeMotion.colorEffects(MotionSpeed.FAST),
        label = "episodeContainer",
    )
    val content by animateColorAsState(
        targetValue = if (selected) scheme.onPrimaryContainer else scheme.onSurfaceVariant,
        animationSpec = BeeMotion.colorEffects(MotionSpeed.FAST),
        label = "episodeContent",
    )

    Surface(
        onClick = onClick,
        modifier = Modifier.size(BeeDimens.playerCellSize),
        shape = RoundedCornerShape(corner),
        color = container,
        contentColor = content,
    ) {
        Box(contentAlignment = Alignment.Center) {
            Text(
                text = label,
                style = if (selected) {
                    MaterialTheme.typography.labelMediumEmphasized
                } else {
                    MaterialTheme.typography.labelMedium
                },
                maxLines = 1,
            )
        }
    }
}
