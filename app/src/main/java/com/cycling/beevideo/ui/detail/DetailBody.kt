package com.cycling.beevideo.ui.detail

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import com.cycling.beevideo.R
import com.cycling.beevideo.domain.model.PlayProgress
import com.cycling.beevideo.domain.model.Vod
import com.cycling.beevideo.ui.components.BeeChipRow
import com.cycling.beevideo.ui.components.ContainmentBlock
import com.cycling.beevideo.ui.theme.BeeDimens

/** 详情页正文：整页一个网格，信息块 / 线路块 / 剧集格都是它的项。 */
@Composable
internal fun DetailBody(
    vod: Vod,
    progress: PlayProgress?,
    lineIndex: Int,
    onSelectLine: (Int) -> Unit,
    onPlay: (lineIndex: Int, episodeIndex: Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    val line = vod.lines.getOrNull(lineIndex)
    val episodes = line?.episodes.orEmpty()

    // 「上次看到」的判据与 domain 的 resumePositionMs 同一条：线路名 + 集号同时对上。
    // 两处各写一套会出现"标记在第 3 集、点进去却从头播"
    val markedIndex = progress
        ?.takeIf { it.lineName == line?.name }
        ?.episodeIndex
        ?.takeIf { it in episodes.indices }

    // 来源没给时长时不给比例：画一条永远 0% 的条比不画更像加载失败
    val markedFraction = progress
        ?.takeIf { it.durationMs > 0L && it.episodeIndex == markedIndex }
        ?.let { (it.positionMs.toFloat() / it.durationMs).coerceIn(0f, 1f) }

    LazyVerticalGrid(
        columns = GridCells.Fixed(BeeDimens.episodeColumns),
        modifier = modifier,
        contentPadding = PaddingValues(
            start = BeeDimens.screenMargin,
            end = BeeDimens.screenMargin,
            top = BeeDimens.gapSmall,
            bottom = BeeDimens.gapHuge,
        ),
        horizontalArrangement = Arrangement.spacedBy(BeeDimens.gapTiny),
        verticalArrangement = Arrangement.spacedBy(BeeDimens.gapTiny),
    ) {
        item(key = "info", span = { GridItemSpan(maxLineSpan) }, contentType = "info") {
            DetailInfoBlock(vod = vod)
        }

        item(key = "lines", span = { GridItemSpan(maxLineSpan) }, contentType = "lines") {
            ContainmentBlock {
                SectionLabel(stringResource(R.string.detail_section_lines))
                BeeChipRow(
                    items = vod.lines,
                    selectedIndex = lineIndex,
                    onSelect = onSelectLine,
                    modifier = Modifier.padding(vertical = BeeDimens.gapTiny),
                ) { line ->
                    Text(line.name, style = MaterialTheme.typography.labelLarge)
                }
                Spacer(Modifier.height(BeeDimens.gapSmall))
                SectionLabel(
                    stringResource(R.string.detail_section_episodes, episodes.size)
                )
                // 进度条标了位置，但"哪一格"要一格格看过去才知道
                if (markedIndex != null) {
                    Text(
                        text = stringResource(
                            R.string.detail_last_watched,
                            progress?.episodeName?.takeIf { it.isNotBlank() }
                                ?: stringResource(R.string.history_episode, markedIndex + 1),
                        ),
                        color = MaterialTheme.colorScheme.primary,
                        style = MaterialTheme.typography.labelMedium,
                        modifier = Modifier.padding(bottom = BeeDimens.gapTiny),
                    )
                }
            }
        }

        // ⚠️ key 里带上下标：切线路时 `episodes` 整个换掉，光用集名当 key 会在源给出重名
        // 剧集时直接崩（LazyGrid 的 key 撞车是崩溃，不是显示两张）；纯下标又等于没 key
        itemsIndexed(
            items = episodes,
            key = { index, episode -> "$index:${episode.name}" },
            contentType = { _, _ -> "episode" },
        ) { index, episode ->
            EpisodeCell(
                label = episode.name,
                // 只有"上次看到的那一集"带进度条
                progressFraction = if (index == markedIndex) markedFraction else null,
                onClick = { onPlay(lineIndex, index) },
            )
        }
    }
}

/** 区块标签。强调留给标题和行动，不层层加粗。 */
@Composable
private fun SectionLabel(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(vertical = BeeDimens.gapTiny),
    )
}
