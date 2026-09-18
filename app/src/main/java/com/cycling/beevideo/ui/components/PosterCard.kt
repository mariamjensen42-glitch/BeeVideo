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

/*
 * 海报底色（演示期的渐变占位）的色相推导参数。演示期没有真实封面，这层渐变实际上在
 * 扮演界面装饰，所以色域要收敛到主题的暖琥珀附近。
 *
 * 三条边界，都是装机实测定的：
 *   - **不能铺满 360°**：最初用 `(hash * 47) % 360`，结果一屏主导色是随机噪声，
 *     一半像素用着跟主题无关的色相，看起来不像一个 App 的界面。
 *   - **上沿停在 46°**：S=0.50 的中低饱和下，色相过 50° 后 G 通道追上 R，输出变土黄/卡其。
 *   - **暗端往红侧偏**（`hue − HUE_SHIFT`）：往黄绿侧偏会得到军绿/橄榄绿。
 *
 * 节奏主要靠**明度**拉开而不是色相 —— 低饱和暖色区里，人眼分辨明度差远比分辨色相差容易。
 */
private const val HUE_BASE = 8f
private const val HUE_SPAN = 38f

/** 暗端在亮端色相基础上往红侧的回偏量（度）。 */
private const val HUE_SHIFT = 8f
private const val SATURATION_TOP = 0.50f
private const val LIGHTNESS_TOP = 0.52f
private const val SATURATION_BOTTOM = 0.56f
private const val LIGHTNESS_BOTTOM = 0.28f

/** 评分分档阈值 */
private const val SCORE_HIGH_THRESHOLD = 8.0f
private const val SCORE_MID_THRESHOLD = 6.5f

/** 角标内部留白：inner 圆角 = 卡片圆角 − 这个间距 = 12 − 8 = 4，正好落在刻度上 */
private val BADGE_PADDING_HORIZONTAL = 6.dp
private val BADGE_PADDING_VERTICAL = 2.dp

/**
 * 图上文字的压暗层。文字全在底部（从卡高 ~72% 处开始），所以上半部完全透明 ——
 * 一张卡内部自带「上亮下暗」的光感，而不是整体蒙一层灰。
 *
 * 强度是**按「刚好够读」定的**：片名处 alpha 0.36，压暗后白字实测对比度 3.3:1
 * （M3 对大字的要求是 3:1）。末端停在 0.62 而不是 1.0，是为了让最底部仍是深酒红
 * 而非纯黑 —— 第一版压到 0.96，那片近黑占掉 45% 卡高，而且颜色统一，反而把整面墙的
 * 色相节奏又抹平了。
 */
private val CardScrim = Brush.verticalGradient(
    colorStops = arrayOf(
        0.00f to Color.Transparent,
        0.50f to Color.Transparent,
        0.78f to PosterScrim.copy(alpha = 0.36f),
        1.00f to PosterScrim.copy(alpha = 0.62f),
    ),
)

/**
 * 演示期的海报占位：由条目 id 推导出一组稳定的渐变色。
 * 接入真实来源后换成来源提供的封面地址。
 */
fun posterBrush(id: String): Brush {
    // 先乘 Knuth 乘数再取模。直接 `hash % 1000` 是坏的：相邻 id 的 hashCode 只差 1，
    // 演示数据的 v01..v13 会挤在 935–968 这一段，13 张海报共用同一个色相（实测一整屏
    // 橄榄绿）。乘一个 32 位黄金比例常数把低位噪声搬到高位才是真的散开。
    // and 0x7FFFFFFF 保证非负：Int.MIN_VALUE 乘完仍是负数，会让色相跑出暖色带。
    val spread = (id.hashCode() * 2654435761L) and 0x7FFFFFFFL
    val t = (spread % 1000L) / 1000f
    val hue = HUE_BASE + t * HUE_SPAN
    return Brush.linearGradient(
        listOf(
            Color.hsl(hue, SATURATION_TOP, LIGHTNESS_TOP),
            // +360 再取模：t 接近 0 时 hue − SHIFT 会是负数，hsl 不保证处理负角
            Color.hsl((hue - HUE_SHIFT + 360f) % 360f, SATURATION_BOTTOM, LIGHTNESS_BOTTOM),
        )
    )
}

/**
 * 评分角标的配色，三档对应 M3 的三个强调角色：高分 → primary、中分 → tertiary、
 * 低分 → 降为中性（surface container highest）。
 */
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
    /*
     * 没有有效分数就**什么都不画**。少了这一句，`Text("")` 仍会把 background 和内边距
     * 画出来，得到一个 12dp × 行高 的空背景方块 —— 真实源里 vod_score 空缺是常态
     * （聚合类和 XML 类尤其），首页一屏会飘十几个白方块，看起来像渲染坏了。
     *
     * 判据用 `> 0f` 而不是"非空"：很多源用 `"0.0"` / `"0"` 表示没有评分，显示一个
     * 0 分角标同样是噪音。非数值（"暂无"之类）一并挡掉。
     */
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
 * 海报卡 —— 首页网格的基本单元。
 *
 * **卡片 = 一张图**：所有信息压在图内，片名 + 状态左对齐堆叠贴在左下角，评分印章在左上，
 * 形成对角平衡。上一版是「图 + 底托 + 卡片下方又写一遍片名」的扁盒子，片名重复占掉 12%
 * 卡高却零信息增量，13 张排在一起读起来是 13 个盒子而不是一面海报墙。
 *
 * 形状：卡片 12dp（medium）→ 内部印章 4dp（extraSmall），中间 8dp 留白，符合
 * 「内圆角 = 外圆角 − 间距」。海报自己不裁圆角，由 Card 统一裁，两者永远同步。
 *
 * 片名走 **brand 字体槽（衬线）**跟 Hero 刊头同族，但**刻度仍是官方的
 * titleMediumEmphasized**，不动一个数。按压缩到 `PRESSED_SCALE`，走 spatial 弹簧
 * （缩放是空间变化，不能用 effects 弹簧）。
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
 * 海报卡的**基本形态** —— 只要这五个字段就能画出来。
 *
 * 会有第二个重载，是因为卡片真正用到 `Vod` 的地方只有这五个字段（`id` 决定渐变底色，
 * 其余四个上图），而收藏页手里没有 `Vod`：收藏夹存的是快照，回源凑一个完整的 `Vod`
 * 是刻意的设计选择之外的事。硬造 `Vod` 就得给 `categoryId` / `intro` / `lines` 这些
 * 卡片根本不看的字段编假值；让收藏页自己抄一份布局则会让同一个视觉元素有两个实现。
 *
 * 所以把"卡片要什么"如实写成参数，`Vod` 那份变成一层薄薄的适配。
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
        // 容器色只是图的兜底（图铺满时看不见），取跟页面同族的一档，免得某张图没铺满时露方块
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
        ),
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(BeeDimens.posterAspect)
                .background(posterBrush(id)),
        ) {
            /*
             * 真实封面。渐变**不撤**，它是图漏出来时的底 —— 相当一部分来源不给封面
             * （聚合类源尤其常见），所以"没有图"是被设计过的一条路径，不是异常分支。
             * 用 Coil 的 placeholder 也能做，但那要再维护一套状态，把渐变留在底下更少一层。
             *
             * Crop：海报比例千奇百怪，统一裁切才能让一面墙的卡片边缘对齐。
             */
            BeePosterImage(pic)

            // 图上文字必须压一层 scrim，否则遇到浅色封面就读不出来了
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

            // 片名与状态左对齐堆叠：给这一块一条明确的版轴，视线扫过网格时始终落在同一条线上
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
                    // 跟片名同属「这一条是什么」，分居两端就成了两个孤立的碎片
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

// ------------------------------------------------------------------ 预览

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

/**
 * 三列网格实况。这张稿是用来判断**整面墙的节奏**的 ——
 * 单个卡片好不好看，跟 13 张排在一起好不好看是两件事。
 */
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
