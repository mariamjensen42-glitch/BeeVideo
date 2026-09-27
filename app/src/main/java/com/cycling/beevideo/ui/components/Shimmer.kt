package com.cycling.beevideo.ui.components

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.StartOffset
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.ButtonGroupDefaults
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.TileMode
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.cycling.beevideo.ui.theme.BeeDimens
import com.cycling.beevideo.ui.theme.BeeVideoTheme

/*
 * 骨架屏与微光。M3 没有 shimmer 规范 —— 下面所有周期 / 停顿 / 错峰数值都是本项目的取值。
 *
 * 三条不变量：
 * 1. 微光走线性，绝不用弹簧（弹簧会减速，光带看起来像卡顿）。
 * 2. 占位几何必须与真实内容逐项对齐，否则内容到达那一刻整页会跳一下。
 * 3. 骨架块里不放业务色 —— 那组渐变由条目 id 推出来，而加载中恰恰还没有 id。
 */

/** 一次扫过的周期。所有块共用同一个周期，错峰波才成立。 */
private const val SHIMMER_PERIOD_MS = 1_600

/** 周期末尾的静默比例。不留的话光带一出去就立刻从左边进来，像传送带。 */
private const val SHIMMER_HOLD_FRACTION = 0.18f

/** 相邻块的错峰间隔：周期相同、起始相位依次后移，波才推得起来。 */
private const val SHIMMER_STAGGER_MS = 90

/** 光带宽度按元素宽度的比例取（再夹上下限），这样不同尺寸上视觉比重一致。 */
private const val SHIMMER_BAND_RATIO = 0.5f
private val SHIMMER_BAND_MIN = 16.dp
private val SHIMMER_BAND_MAX = 120.dp

/**
 * 光带颜色为白色，透明度按 surface 亮度分两档 —— **不读 `isSystemInDarkTheme()`**，
 * 因为主题可以被显式覆盖，读系统设置会让浅色预览稿取到深色那一套。
 * 浅色单独给 50%：按容器刻度取"更亮的一档"会取到 surface，光带与页面同色就整块消失。
 */
private const val SHIMMER_GLINT_ALPHA_DARK = 0.07f
private const val SHIMMER_GLINT_ALPHA_LIGHT = 0.50f

/** 亮度剖面中间留一段平顶（0.40–0.60）：尖峰扫过去像一道硬边。 */
private const val SHIMMER_STOP_HEAD = 0.40f
private const val SHIMMER_STOP_TAIL = 0.60f

/**
 * 在已画好的内容之上扫过一道微光。**纯绘制**：不改尺寸、不参与测量，所以任何尺寸的节点都能用。
 * 光带宽度按元素自身宽度算，行程 = 宽度 + 带宽，没有一处写死宽高。
 *
 * ⚠️ 必须是线性动画：弹簧是朝目标值收敛的，而这里没有目标值；更要紧的是它会**减速**，
 * 光带会在元素中间慢下来 —— 那不是顺滑，是卡顿。
 *
 * @param shape 裁剪形状，光带必须跟着圆角走，否则会在圆角外露出直角
 * @param staggerIndex 错峰序号，同屏第 n 块传 n（网格上建议传 `行 + 列`）
 */
@Composable
fun Modifier.shimmerGlint(
    shape: Shape = RectangleShape,
    staggerIndex: Int = 0,
): Modifier {
    val transition = rememberInfiniteTransition(label = "shimmer")
    val progress = transition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = SHIMMER_PERIOD_MS, easing = LinearEasing),
            repeatMode = RepeatMode.Restart,
            // 起始相位后移 = 错峰。StartOffset 只在第一次迭代生效，之后每轮都是完整周期
            initialStartOffset = StartOffset(staggerIndex * SHIMMER_STAGGER_MS),
        ),
        label = "shimmerProgress",
    )

    val scheme = MaterialTheme.colorScheme
    val glint = Color.White.copy(
        alpha = if (scheme.surface.luminance() < 0.5f) {
            SHIMMER_GLINT_ALPHA_DARK
        } else {
            SHIMMER_GLINT_ALPHA_LIGHT
        },
    )

    return this
        .clip(shape)
        .drawWithCache {
            // ⚠️ 夹取必须先 AtMost 再 AtLeast，不能写 coerceIn：元素比下限还窄时
            // coerceIn(min, max) 会拿到 min > max 而抛异常（是崩溃，不是瑕疵）
            val maxBandPx = minOf(SHIMMER_BAND_MAX.toPx(), size.width)
            val bandPx = (size.width * SHIMMER_BAND_RATIO)
                .coerceAtMost(maxBandPx)
                .coerceAtLeast(SHIMMER_BAND_MIN.toPx())

            onDrawWithContent {
                drawContent()

                // ⚠️ progress 只在绘制阶段读：在组合阶段读（by 委托）会每帧重组整屏占位块
                val travel = size.width + bandPx
                val swept = (progress.value / (1f - SHIMMER_HOLD_FRACTION)).coerceAtMost(1f)
                val bandLeft = -bandPx + swept * travel

                drawRect(
                    brush = Brush.linearGradient(
                        colorStops = arrayOf(
                            0f to Color.Transparent,
                            SHIMMER_STOP_HEAD to glint,
                            SHIMMER_STOP_TAIL to glint,
                            1f to Color.Transparent,
                        ),
                        start = Offset(bandLeft, 0f),
                        end = Offset(bandLeft + bandPx, 0f),
                        // 显式写 Clamp：默认值若变了，光带会变成"整块铺满 + 一条缝"
                        tileMode = TileMode.Clamp,
                    ),
                    size = size,
                )
            }
        }
}

/** 骨架块：中性容器色 + 一道微光。**尺寸完全由 [modifier] 决定**，内部不设宽高。 */
@Composable
fun SkeletonBlock(
    modifier: Modifier = Modifier,
    shape: Shape = MaterialTheme.shapes.small,
    staggerIndex: Int = 0,
) {
    Box(
        modifier = modifier
            // High 这一档：Low 系从背景里分离不出来，Highest 是"可点元素"那一档
            .background(color = MaterialTheme.colorScheme.surfaceContainerHigh, shape = shape)
            .shimmerGlint(shape = shape, staggerIndex = staggerIndex),
    )
}

/** 让一屏骨架对读屏"说出"它在加载 —— 骨架块是纯绘制，一个文字节点都没有。 */
fun Modifier.skeletonSemantics(description: String): Modifier =
    semantics { contentDescription = description }

/**
 * 海报墙骨架。列数、间距、卡片比例照抄真实网格，内容到达时占位是被"填上"而不是"换掉"。
 *
 * 卡片是整块同色、不画内部灰条：真实卡片本来就是"一张图 + 压在图上的字"，
 * 画几条灰条等于承诺了一个并不存在的版式。
 * 用 Column + Row 手排而不用 LazyVerticalGrid —— 它是外层网格里的一个整行项，同方向嵌套会抛异常。
 */
@Composable
fun SkeletonPosterGrid(
    modifier: Modifier = Modifier,
    rows: Int = 2,
    columns: Int = BeeDimens.posterColumns,
    startStaggerIndex: Int = 0,
) {
    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(BeeDimens.gapMedium),
    ) {
        repeat(rows) { row ->
            Row(horizontalArrangement = Arrangement.spacedBy(BeeDimens.gapSmall)) {
                repeat(columns) { column ->
                    SkeletonBlock(
                        modifier = Modifier
                            .weight(1f)
                            .aspectRatio(BeeDimens.posterAspect),
                        shape = MaterialTheme.shapes.medium,
                        // 行 + 列：光带斜着推过海报墙，而不是一行一行地跳
                        staggerIndex = startStaggerIndex + row + column,
                    )
                }
            }
        }
    }
}

/**
 * 分类按钮组的骨架。32dp 高度是**算准的**（`ToggleButtonDefaults.MinHeight`），
 * 因为它是这里唯一会推动下方布局的维度 —— 高度错了下面的版块标题和海报墙会跟着跳。
 */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun SkeletonChipRow(
    modifier: Modifier = Modifier,
    staggerIndex: Int = 0,
) {
    Row(
        modifier = modifier,
        horizontalArrangement = Arrangement.spacedBy(ButtonGroupDefaults.ConnectedSpaceBetween),
    ) {
        CHIP_SKELETON_WIDTHS.forEachIndexed { index, chipWidth ->
            SkeletonBlock(
                modifier = Modifier
                    .width(chipWidth)
                    .height(CHIP_SKELETON_HEIGHT),
                shape = MaterialTheme.shapes.small,
                staggerIndex = staggerIndex + index,
            )
        }
    }
}

private val CHIP_SKELETON_HEIGHT = 32.dp

/** 三个差档，只为不排成三条等长的灰条（横向不推动任何东西，宽度无所谓）。 */
private val CHIP_SKELETON_WIDTHS = listOf(72.dp, 56.dp, 80.dp)

// ---------------------------------------------------------------- 预览

/** 从 48dp 到 200dp 排在一起，验证微光真的按节点尺寸缩放（带宽占比应接近 50%，而不是等宽）。 */
@Preview(
    name = "骨架 · 尺寸自适应",
    group = "组件",
    showBackground = true,
    backgroundColor = 0xFF0B0A08,
    widthDp = 411,
)
@Composable
private fun SkeletonBlockPreview() {
    val side = BeeDimens.screenMargin
    BeeVideoTheme(darkTheme = true) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = side),
            verticalArrangement = Arrangement.spacedBy(BeeDimens.gapMedium),
        ) {
            SkeletonBlock(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(200.dp),
                shape = MaterialTheme.shapes.extraLarge,
                staggerIndex = 0,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(BeeDimens.gapSmall)) {
                SkeletonBlock(
                    modifier = Modifier
                        .weight(1f)
                        .aspectRatio(BeeDimens.posterAspect),
                    shape = MaterialTheme.shapes.medium,
                    staggerIndex = 1,
                )
                Column(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(BeeDimens.gapTiny),
                ) {
                    SkeletonBlock(
                        modifier = Modifier
                            .width(BeeDimens.detailPosterWidth * 0.5f)
                            .aspectRatio(BeeDimens.detailPosterAspect),
                        shape = MaterialTheme.shapes.medium,
                        staggerIndex = 2,
                    )
                    SkeletonBlock(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(BeeDimens.episodeCellHeight),
                        shape = MaterialTheme.shapes.medium,
                        staggerIndex = 3,
                    )
                }
            }
            SkeletonChipRow(staggerIndex = 4)
            SkeletonPosterGrid(rows = 1, startStaggerIndex = 6)
        }
    }
}

@Preview(
    name = "骨架 · 浅色",
    group = "组件",
    showBackground = true,
    backgroundColor = 0xFFFFF9EF,
    widthDp = 411,
)
@Composable
private fun SkeletonBlockLightPreview() {
    BeeVideoTheme(darkTheme = false) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = BeeDimens.screenMargin),
            verticalArrangement = Arrangement.spacedBy(BeeDimens.gapMedium),
        ) {
            SkeletonBlock(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(200.dp),
                shape = MaterialTheme.shapes.extraLarge,
            )
            SkeletonChipRow(staggerIndex = 1)
            SkeletonPosterGrid(rows = 1, startStaggerIndex = 4)
        }
    }
}
