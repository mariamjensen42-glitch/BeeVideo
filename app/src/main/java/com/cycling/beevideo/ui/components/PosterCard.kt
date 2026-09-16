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
import com.cycling.beevideo.data.demo.DemoContent
import com.cycling.beevideo.domain.model.Vod
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
 * 海报底色的色相推导参数。
 *
 * 演示期用 id 生成稳定渐变；接入真实来源后连同 posterBrush 一并删除。
 *
 * ─── 为什么把色相锁在暖色带 ──────────────────────────────────────────
 * 最初是 `(hash * 47) % 360` —— 360° 全色环均匀分布，于是首页那一屏的
 * 主导色是红/绿/蓝/紫随机噪声，而主题的中性色和 primary 都是暖琥珀。
 * 结果：**屏幕上一半的像素在用一套跟主题无关的色相**，整页看起来不像
 * 一个 App 的界面，像一堆撞在一起的色卡。
 *
 * M3 允许内容图自带任意颜色，但界面自身的色域要收敛。演示期没有真实封面，
 * 这层渐变就是在**扮演界面装饰**，所以要落在 primary（琥珀，sRGB ≈ 45°）
 * 的邻近暖色区。
 *
 * ─── 2026-09-15 第三轮：把「节奏」做出来 ─────────────────────────────
 * 上一版把色相收到 4°–34°、明度压在 0.18–0.46，结果是装机截图上 13 张卡
 * **全是同一片暖褐红**，只有明度在轻微浮动 —— 整面墙糊在一起，这是「看起来
 * 普通」最直接的来源。
 *
 * 这一轮不动色相带的上下限（越界会出橄榄绿，见下），改的是**带内的铺开程度**：
 *   - 色相跨度 30° → 38°（下沿 4° → 8°，避开 0° 附近的品红）
 *   - 明度差 0.28 → 0.24（0.52 ↔ 0.28），亮端到「古铜金」、暗端到「深酒红」
 *
 * 判断依据：人眼在低饱和的暖色区里，**分辨明度差远比分辨色相差容易**。
 * 同样两个色相相差 6° 的暗红，几乎看不出区别；但亮度差 0.2 就是两张卡。
 * 所以节奏主要靠明度拉开，色相只负责给这面墙一点色温变化。
 *
 * ─── ⚠️ 暗端为什么不压得更低（0.18 → 0.28 是往回抬的）──────────────
 * 第一版把暗端压到 L=0.16，**同时又叠了 0.96 的底部 scrim**，两重压暗叠加，
 * 装机量到的纵向剖面是：卡片走到 56% 高度就已经是 `#301611`（近乎全黑），
 * 整片黑尾占了卡高的 45%。
 *
 * 副作用不只是「黑」：**卡底那片黑是同一个颜色**，于是它自己变成了新的
 * 「统一底色」，刚拉开的色相节奏又被它抹平了 —— 这面墙看着还是闷。
 *
 * 现在两边一起放松：暗端抬到 0.28（读起来是深酒红，不是黑），scrim 末端
 * 从 0.96 降到 0.62。片名处的实测对比度仍有 3.3:1（M3 对大字的要求是 3:1）。
 *
 * ─── 为什么暗端往红侧偏、而不是往黄绿侧 ───────────────────────────────
 * `hue + 12` 会把暗端推到 78°–80°，也就是**黄绿色**；L 压在这个区间时，
 * 黄绿读出来就是军绿/橄榄绿。改成 `hue − HUE_SHIFT` 之后暗端走橙红方向
 * —— 同样压暗，色相不越界。
 *
 * 色带上沿也刻意停在 46°：在 S=0.50 这个中低饱和下，色相一旦过 50°，
 * G 通道追上 R 通道，输出就变成土黄/卡其，跟琥珀主题不搭。
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
 * 图上文字的压暗层。
 *
 * 文字全部集中在**底部**（片名 + 状态，从卡高 72% 处开始），所以压暗层也从
 * 偏下的位置才起步，上半部完全透明 —— 这样一张卡内部自带「上亮下暗」的光感，
 * 而不是整体蒙一层灰。
 *
 * ⚠️ 强度是**按「刚好够读」定的，不是越黑越好**。第一版一路压到 0.96，
 * 结果卡底那片近黑占掉 45% 的卡高，而且因为颜色统一，它反而把这面墙的
 * 色相节奏又抹平了（详见下方色相推导的注释）。
 *
 * 现在的分段是按片名处的实测对比度反推的（要让白字拿到 3:1 以上）：
 *   片名位置 ≈ 卡高 0.72 → 该处 alpha 0.28，压暗后的有效亮度 0.25，
 *   白字对比 3.3:1 ✓
 * 末端停在 0.62 而不是 1.0，是为了让最底部**仍然是深酒红**而不是纯黑 ——
 * 卡片的四角应当保住自己的色，那是卡片身份的一部分。
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
    // 先乘 Knuth 乘数再取模 —— 直接 `hash % 1000` 是**坏的**：
    // 相邻 id 的 hashCode 只差 1，演示数据的 v01..v13 的散列值挤在
    // 935–968 这么窄的一段里，取模后 t 全部落在 0.935–0.968，13 张海报
    // 共用同一个色相（装机实测：一整屏橄榄绿）。乘一个 32 位黄金比例
    // 常数把低位噪声搬到高位，才是真的散开。
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
 * 评分角标的配色。
 *
 * 三档分别对应 M3 的三个强调角色：
 * - 高分 → primary（最重要，最需要被看见）
 * - 中分 → tertiary（角标属于「小元素需要特别强调」的典型场景）
 * - 低分 → surface container highest + on surface variant（降为中性）
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
     * 没有有效分数就**什么都不画**。
     *
     * 少了这一句，`Text("")` 仍然会把 background 和内边距画出来，得到一个
     * 只有 12dp × 行高 的**空背景方块**。装机实测：真实源里 `vod_score`
     * 空缺是常态（尤其聚合类和 XML 类），首页一屏会飘十几个白方块 ——
     * 比干脆不显示评分难看得多，而且看起来像渲染坏了。
     *
     * 判据用 `> 0f` 而不是"非空"：很多源用 `"0.0"` / `"0"` 表示没有评分，
     * 那种情况显示一个 0 分角标同样是噪音。非数值（"暂无"之类）一并挡掉。
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
 * ─── 2026-09-15 改版：从「盒子」改成「海报」 ──────────────────────────
 * 上一版是一张 `surfaceContainerLow` 底托的卡：上面一块图、下面一条文字行，
 * 而且片名**在图上压了一遍、卡片底下又写了一遍**。三个后果：
 *
 *   1. 那条底托让每张卡读起来像「半个列表项」，13 张排在网格里就是 13 个
 *      扁盒子，而不是一面海报墙；
 *   2. 片名重复，卡下的那条纯属噪音 —— 它占掉卡片 12% 的高度，却零信息增量；
 *   3. 图上的片名是**居中**的。居中文字在没有版式支撑时会显得廉价，
 *      而且它和右下角的状态分踞两端，中间空出一大片，信息是散的。
 *
 * 现在：**卡片 = 一张图**。所有信息压在图内，并且**聚成一个块**贴在左下角
 * （片名 + 状态左对齐堆叠），左上是评分印章，形成对角平衡。
 * 卡内不再有第二个区块，不再有重复的片名。
 *
 * 附带收益：底托和重复文字行拿掉之后，同样的卡片高度下图**大了约 12%**
 * （比例同时从 3:4 改成 2:3 的经典海报比例，见 `BeeDimens.posterAspect`），
 * 一屏能看到的画面更多。
 *
 * ─── 形状与字体 ─────────────────────────────────────────────────────
 * 形状关系：**卡片 12dp（medium）→ 内部印章 4dp（extraSmall）**，
 * 中间隔了 8dp 留白，符合「内圆角 = 外圆角 − 间距」。印章取 extraSmall
 * 而不是随手给个 6dp，是为了让所有圆角都能在十档刻度上找到出处。
 *
 * 海报本身不单独裁圆角 —— 它贴着卡片边缘，由 Card 统一裁切，
 * 这样两者永远同步，不会出现「卡片改圆角、海报忘了改」的错位。
 *
 * 片名走 **brand 字体槽（衬线）**：跟 Hero 刊头的大片名同一族，一排海报
 * 读下来像一本刊物的目录页。这里只用 `copy(fontFamily = …)`，
 * **刻度仍然是官方的 titleMediumEmphasized**，不动一个数。
 *
 * 按压时整卡缩到 `PRESSED_SCALE`，用 fast spatial 弹簧（会回一下弹）；
 * 缩放属于空间变化，绝不能用 effects 弹簧。
 */
@Composable
fun PosterCard(
    vod: Vod,
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
        // 容器色只是图的兜底（图正常铺满时看不见），取跟页面同族的一档，
        // 万一某张图没铺满也不会露出突兀的方块。
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
        ),
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(BeeDimens.posterAspect)
                .background(posterBrush(vod.id)),
        ) {
            /*
             * 真实封面。
             *
             * 渐变**不撤**，它是这张图漏出来时的底 —— 相当一部分来源不给封面
             * （聚合类源尤其常见），所以"没有图"是被设计过的一条路径，
             * 不是异常分支。Coil 的 placeholder 也能做这件事，但那需要为它
             * 再写一套状态；把渐变留在底下更少一层。
             *
             * `ContentScale.Crop`：海报比例千奇百怪，统一裁切才能让一面墙
             * 的卡片边缘对齐。
             */
            if (vod.pic.isNotEmpty()) {
                val context = LocalContext.current
                AsyncImage(
                    model = remember(vod.pic) {
                        ImageRequest.Builder(context)
                            .data(vod.pic)
                            .crossfade(true)
                            .build()
                    },
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize(),
                )
            }

            // 图上文字必须压一层 scrim，否则遇到浅色封面就读不出来了
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(CardScrim),
            )

            ScoreBadge(
                score = vod.score,
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .padding(BeeDimens.posterInset),
            )

            /*
             * 底部信息组。片名与状态**左对齐堆叠**，而不是一个居中一个靠右 ——
             * 左对齐给这一块一条明确的版轴，视线扫过网格时始终落在同一条线上，
             * 这是「一面墙」和「一堆卡片」的区别。
             */
            Column(
                modifier = Modifier
                    .align(Alignment.BottomStart)
                    .fillMaxWidth()
                    .padding(BeeDimens.posterInset),
            ) {
                Text(
                    text = vod.name,
                    color = PosterTextPrimary,
                    style = MaterialTheme.typography.titleMediumEmphasized.copy(
                        fontFamily = BeeBrandFont,
                    ),
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    // 状态放这里而不是卡片右下角：跟片名同属「这一条是什么」，
                    // 分居两端就成了两个孤立的碎片。
                    text = vod.remarks,
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
            DemoContent.vods.take(3).forEach { vod ->
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
            DemoContent.vods.take(3).forEach { vod ->
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
            DemoContent.vods.take(9).chunked(BeeDimens.posterColumns).forEach { row ->
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
