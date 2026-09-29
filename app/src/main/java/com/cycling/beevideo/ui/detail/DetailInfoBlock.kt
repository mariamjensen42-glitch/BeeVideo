package com.cycling.beevideo.ui.detail

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import com.cycling.beevideo.R
import com.cycling.beevideo.domain.model.Vod
import com.cycling.beevideo.ui.components.BeePosterImage
import com.cycling.beevideo.ui.components.ContainmentBlock
import com.cycling.beevideo.ui.components.ScoreBadge
import com.cycling.beevideo.ui.components.metaLine
import com.cycling.beevideo.ui.components.posterBrush
import com.cycling.beevideo.ui.theme.BeeDimens
import com.cycling.beevideo.ui.theme.PosterScrim
import com.cycling.beevideo.ui.theme.PosterTextPrimary

/** 信息块：海报 + 元数据 + 简介。片名不在这里重复 —— 它已经在顶栏的展开态里。 */
@Composable
internal fun DetailInfoBlock(vod: Vod) {
    ContainmentBlock {
        Row {
            Box(
                modifier = Modifier
                    .width(BeeDimens.detailPosterWidth)
                    .aspectRatio(BeeDimens.detailPosterAspect)
                    .background(
                        posterBrush(vod.id),
                        MaterialTheme.shapes.medium,
                    ),
            ) {
                // 渐变当底、图盖在上面：图没加载出来或源不给封面时，渐变就是占位
                BeePosterImage(pic = vod.pic, contentDescription = vod.name)

                // 有真实封面时才压一层 scrim 保住文字（只压暗底部 45%）
                if (vod.pic.isNotEmpty()) {
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .background(DetailPosterScrim),
                    )
                }

                Text(
                    text = vod.name,
                    color = PosterTextPrimary,
                    style = MaterialTheme.typography.titleMediumEmphasized,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier
                        .align(Alignment.BottomStart)
                        .padding(BeeDimens.posterInset),
                )
            }

            Spacer(Modifier.width(BeeDimens.gapMedium))

            Column(modifier = Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    ScoreBadge(vod.score)
                    Spacer(Modifier.width(BeeDimens.gapTiny))
                    Text(
                        text = metaLine(vod.year, vod.area, vod.genre),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        style = MaterialTheme.typography.labelMedium,
                    )
                }
                Spacer(Modifier.height(BeeDimens.gapTiny))
                Text(
                    text = vod.remarks,
                    color = MaterialTheme.colorScheme.onSurface,
                    style = MaterialTheme.typography.labelLargeEmphasized,
                )
                Spacer(Modifier.height(BeeDimens.gapTight))
                Text(
                    text = stringResource(R.string.detail_director, vod.director),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.labelMedium,
                )
                Spacer(Modifier.height(BeeDimens.gapTight))
                Text(
                    text = stringResource(R.string.detail_actors, vod.actors),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.labelMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }

        Spacer(Modifier.height(BeeDimens.gapMedium))
        Text(
            text = vod.intro,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            style = MaterialTheme.typography.bodyMedium,
        )
    }
}

/** 封面压暗层：只在底部 45% 起作用。 */
private val DetailPosterScrim = Brush.verticalGradient(
    colorStops = arrayOf(
        0.55f to Color.Transparent,
        1.00f to PosterScrim.copy(alpha = 0.72f),
    ),
)
