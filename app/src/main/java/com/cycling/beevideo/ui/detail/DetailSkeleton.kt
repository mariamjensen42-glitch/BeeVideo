package com.cycling.beevideo.ui.detail

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.cycling.beevideo.ui.components.ContainmentBlock
import com.cycling.beevideo.ui.components.SkeletonBlock
import com.cycling.beevideo.ui.components.SkeletonChipRow
import com.cycling.beevideo.ui.theme.BeeDimens

/**
 * 详情页骨架。结构要逐块对齐 [DetailBody]：信息块 / 线路块 / 剧集网格。
 * 容器块复用 [ContainmentBlock] —— 骨架的块感本来就该来自真实那个组件。
 */
@Composable
internal fun DetailSkeleton(modifier: Modifier = Modifier) {
    Column(
        modifier = modifier
            .verticalScroll(rememberScrollState())
            .padding(
                start = BeeDimens.screenMargin,
                end = BeeDimens.screenMargin,
                top = BeeDimens.gapSmall,
                bottom = BeeDimens.gapHuge,
            ),
        verticalArrangement = Arrangement.spacedBy(BeeDimens.gapTiny),
    ) {
        ContainmentBlock {
            Row {
                SkeletonBlock(
                    modifier = Modifier
                        .width(BeeDimens.detailPosterWidth)
                        .aspectRatio(BeeDimens.detailPosterAspect),
                    shape = MaterialTheme.shapes.medium,
                    staggerIndex = 0,
                )
                Spacer(Modifier.width(BeeDimens.gapMedium))
                Column(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(BeeDimens.gapTiny),
                ) {
                    // 四行元信息，末行（主演）短一截
                    repeat(4) { index ->
                        SkeletonBlock(
                            modifier = Modifier
                                .fillMaxWidth(if (index == 3) 0.62f else 1f)
                                .height(SKELETON_TEXT_BAR),
                            shape = MaterialTheme.shapes.extraSmall,
                            staggerIndex = 1 + index,
                        )
                    }
                }
            }
            Spacer(Modifier.height(BeeDimens.gapMedium))
            SKELETON_INTRO_FRACTIONS.forEachIndexed { index, fraction ->
                SkeletonBlock(
                    modifier = Modifier
                        .fillMaxWidth(fraction)
                        .height(SKELETON_TEXT_BAR),
                    shape = MaterialTheme.shapes.extraSmall,
                    staggerIndex = 5 + index,
                )
                if (index != SKELETON_INTRO_FRACTIONS.lastIndex) {
                    Spacer(Modifier.height(BeeDimens.gapTiny))
                }
            }
        }

        ContainmentBlock {
            // 两个区块标签（「播放线路」「选集 · 共 N 集」）
            SkeletonBlock(
                modifier = Modifier
                    .fillMaxWidth(0.28f)
                    .height(SKELETON_TEXT_BAR),
                shape = MaterialTheme.shapes.extraSmall,
                staggerIndex = 8,
            )
            Spacer(Modifier.height(BeeDimens.gapTiny))
            SkeletonChipRow(staggerIndex = 9)
            Spacer(Modifier.height(BeeDimens.gapSmall))
            SkeletonBlock(
                modifier = Modifier
                    .fillMaxWidth(0.42f)
                    .height(SKELETON_TEXT_BAR),
                shape = MaterialTheme.shapes.extraSmall,
                staggerIndex = 12,
            )
        }

        // 剧集网格：列数、格高、间距照抄 DetailBody
        repeat(SKELETON_EPISODE_ROWS) { row ->
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(BeeDimens.gapTiny),
            ) {
                repeat(BeeDimens.episodeColumns) { column ->
                    SkeletonBlock(
                        modifier = Modifier
                            .weight(1f)
                            .height(BeeDimens.episodeCellHeight),
                        shape = MaterialTheme.shapes.medium,
                        // 行 + 列：光带斜着推过剧集网格
                        staggerIndex = 14 + row + column,
                    )
                }
            }
        }
    }
}

/** 骨架里一条"文字"的高度：比真字略高才像"这里有字"，比真字矮会读成"这里有线"。 */
private val SKELETON_TEXT_BAR = 12.dp

private val SKELETON_INTRO_FRACTIONS = listOf(1f, 1f, 0.68f)

/** 剧集骨架行数。两行 8 格，加上信息块正好一屏。 */
private const val SKELETON_EPISODE_ROWS = 2
