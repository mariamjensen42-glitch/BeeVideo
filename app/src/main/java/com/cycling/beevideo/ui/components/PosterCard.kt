package com.cycling.beevideo.ui.components

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import coil.request.ImageRequest
import com.cycling.beevideo.domain.model.Vod
import com.cycling.beevideo.ui.preview.PreviewVods
import com.cycling.beevideo.ui.theme.BeeBrandFont
import com.cycling.beevideo.ui.theme.BeeDimens
import com.cycling.beevideo.ui.theme.BeeMotion
import com.cycling.beevideo.ui.theme.BeeVideoTheme
import com.cycling.beevideo.ui.theme.MotionSpeed
import com.cycling.beevideo.ui.theme.PRESSED_SCALE
import com.cycling.beevideo.ui.theme.PosterScrim
import com.cycling.beevideo.ui.theme.PosterTextPrimary
import com.cycling.beevideo.ui.theme.PosterTextSecondary

// 渐变占位的色相参数。⚠️ 色域收敛到暖琥珀附近：不能铺满 360°（一屏主导色变随机噪声），
// 上沿停在 46°（过了会变土黄），暗端往红侧偏（往黄绿偏会变军绿）。
// 节奏主要靠明度拉开，不靠色相。
private const val HUE_BASE = 8f
private const val HUE_SPAN = 38f
private const val HUE_SHIFT = 8f
private const val SATURATION_TOP = 0.50f
private const val LIGHTNESS_TOP = 0.52f
private const val SATURATION_BOTTOM = 0.56f
private const val LIGHTNESS_BOTTOM = 0.28f

private const val SCORE_HIGH_THRESHOLD = 8.0f
private const val SCORE_MID_THRESHOLD = 6.5f

// 内圆角 = 卡片圆角 − 这个间距 = 12 − 8 = 4，正好落在刻度上
private val BADGE_PADDING_HORIZONTAL = 6.dp
private val BADGE_PADDING_VERTICAL = 2.dp

// 文字全在底部，所以上半部全透明。alpha 0.36 压暗后白字实测 3.3:1；末端停在 0.62
// 而不是 1.0，是为了让最底部仍是深酒红而非纯黑
private val CardScrim = Brush.verticalGradient(
    colorStops = arrayOf(
        0.00f to Color.Transparent,
        0.50f to Color.Transparent,
        0.78f to PosterScrim.copy(alpha = 0.36f),
        1.00f to PosterScrim.copy(alpha = 0.62f),
    ),
)

/** 演示期的海报占位：由条目 id 推导出稳定的渐变色。 */
fun posterBrush(id: String): Brush {
    // ⚠️ 必须先乘 Knuth 乘数再取模：相邻 id 的 hashCode 只差 1，直接 %1000 会让
    // 演示数据全挤在同一个色相（实测一整屏橄榄绿）。and 0x7FFFFFFF 保证非负
    val spread = (id.hashCode() * 2654435761L) and 0x7FFFFFFFL
    val t = (spread % 1000L) / 1000f
    val hue = HUE_BASE + t * HUE_SPAN
    return Brush.linearGradient(
        listOf(
            Color.hsl(hue, SATURATION_TOP, LIGHTNESS_TOP),
            // +360 再取模：t 接近 0 时 hue − SHIFT 是负数，hsl 不保证处理负角
            Color.hsl((hue - HUE_SHIFT + 360f) % 360f, SATURATION_BOTTOM, LIGHTNESS_BOTTOM),
        )
    )
}

/** 三档对应三个强调角色：高分 → primary、中分 → tertiary、低分 → 降为中性。 */
@Composable
fun scoreColors(score: String): Pair<Color, Color> {
    val value = score.toFloatOrNull() ?: 0f
    val scheme = MaterialTheme.colorScheme
    return when {
        value >= SCORE_HIGH_THRESHOLD -> scheme.primary to scheme.onPrimary
        value >= SCORE_MID_THRESHOLD -> scheme.tertiary to scheme.onTertiary
        else -> scheme.surfaceContainerHighest to scheme.onSurfaceVariant
    }
}

@Composable
fun ScoreBadge(score: String, modifier: Modifier = Modifier) {
    // ⚠️ 没有有效分数就什么都不画：少了这一句，Text("") 仍会画出背景与内边距，
    // 得到一个空方块，而 vod_score 空缺是常态，首页一屏会飘十几个白方块。
    // 判据用 > 0f 而不是"非空"：很多源用 "0" / "0.0" 表示没有评分
    if (score.toFloatOrNull()?.let { it > 0f } != true) return

    val (container, content) = scoreColors(score)
    Text(
        text = score,
        color = content,
        style = MaterialTheme.typography.labelSmallEmphasized,
        modifier = modifier
            .background(container, MaterialTheme.shapes.extraSmall)
            .padding(
                horizontal = BADGE_PADDING_HORIZONTAL,
                vertical = BADGE_PADDING_VERTICAL,
            ),
    )
}

/**
 * 海报卡 —— 首页网格的基本单元。**卡片 = 一张图**：片名与状态左对齐贴在左下角，
 * 评分印章在左上，形成对角平衡。
 *
 * 内圆角 = 外圆角 − 间距（12 − 8 = 4dp）；海报自己不裁圆角，由 Card 统一裁。
 * 片名走 brand 字体槽（衬线）但刻度仍是官方 titleMediumEmphasized，不动一个数。
 */
@Composable
fun PosterCard(
    vod: Vod,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    PosterCard(
        id = vod.id,
        name = vod.name,
        pic = vod.pic,
        score = vod.score,
        remarks = vod.remarks,
        onClick = onClick,
        modifier = modifier,
    )
}

/**
 * 卡片的基本形态 —— 只要这五个字段就能画出来。
 *
 * 有第二个重载是因为收藏页手里没有 `Vod`（存的是快照），硬造一个就得给
 * `categoryId` / `intro` / `lines` 编假值；让收藏页自己抄一份布局则会有两个实现。
 */
@Composable
fun PosterCard(
    id: String,
    name: String,
    pic: String,
    score: String,
    remarks: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val interactionSource = remember { MutableInteractionSource() }
    val pressed by interactionSource.collectIsPressedAsState()
    val scale by animateFloatAsState(
        targetValue = if (pressed) PRESSED_SCALE else 1f,
        animationSpec = BeeMotion.floatSpatial(MotionSpeed.FAST),
        label = "posterScale",
    )

    Card(
        onClick = onClick,
        modifier = modifier.graphicsLayer {
            scaleX = scale
            scaleY = scale
        },
        interactionSource = interactionSource,
        shape = MaterialTheme.shapes.medium,
        // 容器色只是图的兜底，取跟页面同族的一档，免得某张图没铺满时露方块
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
        ),
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(BeeDimens.posterAspect)
                // remember：要算两个 hsl 颜色，滚动时没必要每次重组都重算
                .background(remember(id) { posterBrush(id) }),
        ) {
            // ⚠️ 渐变不撤：相当一部分来源不给封面（聚合类尤其），"没有图"是被设计过的
            // 一条路径，留着它当底比再维护一套 Coil placeholder 状态少一层
            BeePosterImage(pic)

            // 图上文字必须压一层 scrim，否则遇到浅色封面就读不出来
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(CardScrim),
            )

            ScoreBadge(
                score = score,
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .padding(BeeDimens.posterInset),
            )

            // 片名与状态左对齐堆叠：给这一块一条明确的版轴
            Column(
                modifier = Modifier
                    .align(Alignment.BottomStart)
                    .fillMaxWidth()
                    .padding(BeeDimens.posterInset),
            ) {
                Text(
                    text = name,
                    color = PosterTextPrimary,
                    style = MaterialTheme.typography.titleMediumEmphasized.copy(
                        fontFamily = BeeBrandFont,
                    ),
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = remarks,
                    color = PosterTextSecondary,
                    style = MaterialTheme.typography.labelSmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

@Preview(
    name = "海报卡片 · 三档评分",
    group = "组件",
    showBackground = true,
    backgroundColor = 0xFF0B0A08,
    widthDp = 420,
)
@Composable
private fun PosterCardPreview() {
    BeeVideoTheme(darkTheme = true) {
        Row(
            modifier = Modifier.padding(BeeDimens.gapMedium),
            horizontalArrangement = Arrangement.spacedBy(BeeDimens.gapSmall),
        ) {
            PreviewVods.vods.take(3).forEach { vod ->
                PosterCard(
                    vod = vod,
                    onClick = {},
                    modifier = Modifier.width(BeeDimens.detailPosterWidth),
                )
            }
        }
    }
}

@Preview(
    name = "海报卡片 · 浅色",
    group = "组件",
    showBackground = true,
    backgroundColor = 0xFFFFF9EF,
    widthDp = 420,
)
@Composable
private fun PosterCardLightPreview() {
    BeeVideoTheme(darkTheme = false) {
        Row(
            modifier = Modifier.padding(BeeDimens.gapMedium),
            horizontalArrangement = Arrangement.spacedBy(BeeDimens.gapSmall),
        ) {
            PreviewVods.vods.take(3).forEach { vod ->
                PosterCard(
                    vod = vod,
                    onClick = {},
                    modifier = Modifier.width(BeeDimens.detailPosterWidth),
                )
            }
        }
    }
}

@Preview(
    name = "评分角标 · 三档",
    group = "组件",
    showBackground = true,
    backgroundColor = 0xFF0B0A08,
)
@Composable
private fun ScoreBadgePreview() {
    BeeVideoTheme(darkTheme = true) {
        Row(
            modifier = Modifier.padding(BeeDimens.gapMedium),
            horizontalArrangement = Arrangement.spacedBy(BeeDimens.gapTiny),
        ) {
            listOf("9.1", "7.2", "5.8").forEach { score ->
                ScoreBadge(score)
            }
        }
    }
}

/** 三列网格实况：单个卡片好不好看，跟 13 张排在一起好不好看是两件事。 */
@Preview(
    name = "海报卡片 · 三列网格",
    group = "组件",
    showBackground = true,
    backgroundColor = 0xFFFFF9EF,
    widthDp = 393,
)
@Composable
private fun PosterGridPreview() {
    BeeVideoTheme(darkTheme = false) {
        Column(
            modifier = Modifier.padding(horizontal = BeeDimens.screenMargin),
            verticalArrangement = Arrangement.spacedBy(BeeDimens.gapSmall),
        ) {
            PreviewVods.vods.take(9).chunked(BeeDimens.posterColumns).forEach { row ->
                Row(horizontalArrangement = Arrangement.spacedBy(BeeDimens.gapSmall)) {
                    row.forEach { vod ->
                        PosterCard(
                            vod = vod,
                            onClick = {},
                            modifier = Modifier.weight(1f),
                        )
                    }
                    // 末行不满时补空位，免得剩下的卡片被拉宽
                    repeat(BeeDimens.posterColumns - row.size) {
                        Box(modifier = Modifier.weight(1f))
                    }
                }
            }
        }
    }
}
