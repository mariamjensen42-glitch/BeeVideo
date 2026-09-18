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
 * 骨架屏与微光（shimmer）。
 *
 * ─── 这一层解决什么 ──────────────────────────────────────────────────
 * 在它之前，"数据还没到"时画的是三种东西：一句居中的「正在读取…」、一个转圈的
 * `LoadingIndicator`、或者干脆什么都不画。三者都只说了「忙」，都没说
 * 「马上会出来什么形状的东西」。骨架屏的用处正在这里：它把**即将出现的版式**
 * 先画出来，于是内容到达时不是"凭空长出一屏"，而是"占位被填上"。
 *
 * ─── ⚠️ M3 没有 shimmer 规范 ────────────────────────────────────────
 * 官方文档里跟加载有关的条目只有 `progress indicator` 和
 * `loading indicator`（feedback.md），**没有任何关于骨架屏微光的定义**。
 * 所以本文件里的周期、停顿比例、错峰间隔、光带宽度比例、光带透明度，
 * 全部是**本项目的设计取值，不是 M3 的数值**。任何一条都可以按观感调整 ——
 * 但请把注释一起改，别留下对不上的说明。
 *
 * ─── 三条不变量（改动时别破） ────────────────────────────────────────
 * 1. **微光走线性，绝不用弹簧。** 理由见 [shimmerGlint]。
 * 2. **占位的几何必须和真实内容逐项对齐**（高度、圆角、列数、间距）。
 *    对不齐的话，内容到达那一刻整页会跳一下 —— 那比不画骨架更难看。
 * 3. **骨架块里不放业务色**（比如 `posterBrush(id)`）。那组渐变由条目 id 推出来，
 *    而加载中恰恰还没有 id。骨架一律取主题的中性容器色。
 */

/**
 * 一次扫过的周期。
 *
 * 所有占位块**共用同一个周期**，这是 [SHIMMER_STAGGER_MS] 那条错峰能成立的前提
 * （周期不同的话相位差会变，相邻两块会越扫越近、最后并到一起）。
 */
private const val SHIMMER_PERIOD_MS = 1_600

/**
 * 周期末尾留出的静默比例。
 *
 * 不留的话，光带从右边出去、立刻又从左边进来，一屏占位块看起来像一条传送带。
 * 留 18%（约 290ms）让它像"扫一次、歇一下"，这更接近人对手势的感知节奏。
 *
 * （本条为项目取值；Facebook/Shimmer 一类实现是不留停顿的，M3 无规定。）
 */
private const val SHIMMER_HOLD_FRACTION = 0.18f

/**
 * 相邻占位块的错峰间隔。
 *
 * 这是把「一块一块地各扫各的」变成「一道波推过去」的唯一办法：周期相同、
 * 起始相位依次后移。网格上按 `行 + 列` 编号，波就是斜着推过去的。
 *
 * 90ms 是按 1.6 秒周期定的 —— 太大（>200ms）会读成各扫各的，太小（<40ms）
 * 相邻块的差别看不出来，等于白错峰。
 *
 * （本条为项目取值，M3 没有公开任何 stagger 数值。）
 */
private const val SHIMMER_STAGGER_MS = 90

/**
 * 光带宽度 = 元素宽度的这个比例，再按下面的上下限夹一次。
 *
 * 按**比例**而不是固定 dp 取，正是「适配不同尺寸」的落点：48dp 的剧集格与
 * 361dp 的刊头卡上，光带都占同一个视觉比重。
 */
private const val SHIMMER_BAND_RATIO = 0.5f
private val SHIMMER_BAND_MIN = 16.dp
private val SHIMMER_BAND_MAX = 120.dp

/**
 * 光带颜色：白色，靠透明度叠加。透明度按 surface 的亮度分两档，跟
 * `HeroCarousel.heroFills()` 用同一个判据 —— **不读 `isSystemInDarkTheme()`**，
 * 因为 `BeeVideoTheme(darkTheme = false)` 是可以显式覆盖的，读系统设置会让
 * 浅色预览稿取到深色那一套。
 *
 *   深色：底色 `surfaceContainerHigh` = #272420，叠 7% 白 → #363330，
 *         相当于顺着容器刻度往上抬一档。
 *   浅色：底色 #EFE7D7，但**浅色下容器刻度是越靠前越暗**，按刻度取"更亮的一档"
 *         会取到 surface(#FFF9EF) —— 那等于光带和页面同色，整块消失。
 *         所以浅色单独给 50% 白，压出 #F7F3EA：比底色亮、又比 surface 暗一档，
 *         读起来是"这块正在发亮"，而不是"这块是空的"。
 */
private const val SHIMMER_GLINT_ALPHA_DARK = 0.07f
private const val SHIMMER_GLINT_ALPHA_LIGHT = 0.50f

/**
 * 光带的亮度剖面：0 → 透明，0.40 → 满，0.60 → 满，1 → 透明。
 *
 * 中间留 0.40–0.60 的**平顶**而不是尖峰：尖峰扫过去像一道硬边，
 * 平顶是一抹，跟圆角块放在一起更服帖。
 *
 * （本条为项目取值。）
 */
private const val SHIMMER_STOP_HEAD = 0.40f
private const val SHIMMER_STOP_TAIL = 0.60f

/**
 * 在节点已经画好的内容之上扫过一道微光。**纯绘制**：不改尺寸、不参与测量、
 * 不产生任何布局 —— 所以给多大尺寸的节点都能用。
 *
 * ─── 为什么是线性动画，不是弹簧 ─────────────────────────────────────
 * `BeeMotion` 那两套弹簧（spatial / effects）都是**朝一个目标值收敛**的，
 * 而这里根本没有目标值，微光是一个无限循环。更要紧的是弹簧会**减速**：
 * 光带会在元素中间慢下来甚至停住，那不是"顺滑"，是卡顿。
 * 匀速扫过才是这道光的物理直觉 —— 它就是一束在移动的光。
 *
 * ─── 尺寸无关是怎么做到的 ───────────────────────────────────────────
 * 光带宽度按**元素自身的宽度**算（比例 + 上下限夹取），行程 = 元素宽度 + 光带宽度，
 * 相位 0..1 线性映射到行程上。于是：
 *   48dp 的剧集格 → 光带 24dp，行程 72dp
 *   116dp 的详情封面 → 光带 58dp，行程 174dp
 *   361dp 的刊头卡 → 光带 120dp（被上限夹住），行程 481dp
 * 没有任何一处写死宽高。
 *
 * **有意的取舍**：所有块共用同一个周期，于是大块上的光带 dp/s 更快
 * （小格约 45dp/s，刊头卡约 300dp/s）。若改成"速度恒定"（周期 = 行程 / 速度），
 * 48dp 的格子就只剩 170ms 一次 —— 那个速度下人眼只看到闪烁，看不到"扫过"；
 * 而且上面说了，错峰波要求周期一致。所以按「每个元素都用 1.6 秒扫完自己」定，
 * 观感上反而更统一：用户感知的是"这道光扫过这块用了多久"，不是"它每秒走几 dp"。
 *
 * @param shape 裁剪形状。光带必须跟着圆角走，否则会在圆角外露出直角。
 * @param staggerIndex 错峰相位序号。同屏第 n 块传 n；网格上建议传 `行 + 列`。
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
            /*
             * 起始相位后移 = 错峰。`StartOffset` 只在第一次迭代生效，
             * 之后每轮都是完整周期 —— 正因为周期相同，相邻块的相位差恒定，
             * 波束才不会散。
             */
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
            /*
             * 这里算的是**像素**：bandPx 与行程都跟着 size 走，而 size 是节点
             * 实测出来的 —— 这就是"适配不同尺寸"的落点。
             *
             * 夹取顺序必须是 AtMost 再 AtLeast，不能写 coerceIn：
             * 元素宽度小于下限时 coerceIn(min, max) 会拿到 min > max 而**抛异常**
             * （IllegalArgumentException，只在极窄的元素上碰到，但那是崩溃不是瑕疵）。
             */
            val maxBandPx = minOf(SHIMMER_BAND_MAX.toPx(), size.width)
            val bandPx = (size.width * SHIMMER_BAND_RATIO)
                .coerceAtMost(maxBandPx)
                .coerceAtLeast(SHIMMER_BAND_MIN.toPx())

            onDrawWithContent {
                drawContent()

                /*
                 * progress **只在绘制阶段读**。若在组合阶段读（`by` 委托），
                 * 每一帧都会重组整个占位块 —— 一屏二十几个块，那是白烧的 CPU。
                 * 读在这里，快照系统只会让它重画，不重组。
                 */
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
                        /*
                         * Clamp：区间外的像素取两端颜色（两端都是透明）。
                         * 不传也是 Clamp，但这里写出来 —— 一旦默认值变了，
                         * 光带会变成"整块铺满 + 一条缝"，且只在装机时才看得出来。
                         */
                        tileMode = TileMode.Clamp,
                    ),
                    size = size,
                )
            }
        }
}

/**
 * 骨架块：一档中性容器色 + 一道扫过的微光。
 *
 * **尺寸完全由 [modifier] 决定** —— 内部不设任何宽高，所以同一个组件既可以是
 * 48dp 的剧集格，也可以是 200dp 的刊头卡。调用方给一个 `height(...)` 或
 * `aspectRatio(...)` 就成型。
 *
 * 底色取 `surfaceContainerHigh`：
 *   - 不用 `surfaceContainerLowest`/`surfaceContainerLow` —— 骨架是"页面之上的一层
 *     占位"，得从背景里分离得出来；
 *   - 也不用 `surfaceContainerHighest` —— 那是"可点元素"那一档（剧集格用的就是它），
 *     占位块不该看起来像能点的。
 */
@Composable
fun SkeletonBlock(
    modifier: Modifier = Modifier,
    shape: Shape = MaterialTheme.shapes.small,
    staggerIndex: Int = 0,
) {
    Box(
        modifier = modifier
            .background(color = MaterialTheme.colorScheme.surfaceContainerHigh, shape = shape)
            .shimmerGlint(shape = shape, staggerIndex = staggerIndex),
    )
}

/**
 * 让一屏骨架对读屏"说出"它在加载。
 *
 * 骨架块是纯绘制、一个文字节点都没有 —— 不挂这一句的话，TalkBack 在加载期间
 * 只会念出一片空白，用户分不清 App 是死了还是在忙。原来那句「正在读取…」
 * 就是干这个的，换成骨架后由 `contentDescription` 接着干。
 */
fun Modifier.skeletonSemantics(description: String): Modifier =
    semantics { contentDescription = description }

/**
 * 海报墙骨架。
 *
 * 列数、间距、卡片比例全部照抄 [PosterCard] 所在的那两处网格
 * （`horizontalArrangement` = `gapSmall`、`verticalArrangement` = `gapMedium`、
 * 3 列、2:3、`shapes.medium`），所以内容到达时占位是被"填上"而不是被"换掉"。
 *
 * 卡片是**一整块同色**，不画内部的灰条：`PosterCard` 的卡片内容本来就是
 * 「一张图 + 压在图上的字」，加载中的占位就是"图还没到"。画几条灰条等于
 * 承诺了一个并不存在的版式（这个卡片的文字不是独立行，是叠在图上的）。
 *
 * 用 `Column` + `Row` 手排，**不用 `LazyVerticalGrid`**：这个骨架整体是外层
 * `LazyVerticalGrid` 里的**一个整行项**，同方向嵌套懒列表会直接抛异常。
 * 行数固定两三行就够 —— 它只需要填满首屏，不必和真实条数一致。
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
 * 分类按钮组的骨架。
 *
 * 高度 **32dp 是算准的，不是估的**：`ToggleButtonDefaults.MinHeight`
 * = `ButtonSmallTokens.ContainerHeight`（M3 Expressive 的 small 档）。
 * 这个数必须准，因为它是这里**唯一会推动下方布局的维度** —— 高度错了，
 * 分类行下面的版块标题和海报墙会跟着跳。
 * 宽度无所谓（横向不推动任何东西），取三个 56–80dp 的差档，只为不排成
 * 三条等长的灰条。
 *
 * 间距直接取 `ButtonGroupDefaults.ConnectedSpaceBetween` —— 跟 `BeeChipRow`
 * 用同一个常量，改动时不会各走各的。圆角取 `shapes.small`(8dp)，
 * 也就是 M3 small 按钮的 square 形态。
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

/** 32dp = `ToggleButtonDefaults.MinHeight`，见 [SkeletonChipRow]。 */
private val CHIP_SKELETON_HEIGHT = 32.dp
private val CHIP_SKELETON_WIDTHS = listOf(72.dp, 56.dp, 80.dp)

// ------------------------------------------------------------------ 预览

/**
 * 从 48dp 到 200dp 的骨架块排在一起。这张稿是用来验证**微光真的按节点尺寸缩放**的：
 * 光带在每一块上的宽度占比应当接近（约 50%），而不是等宽 —— 等宽就说明写死了 dp。
 */
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
            // 刊头卡尺度（200dp 高）
            SkeletonBlock(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(BeeDimens.heroCardHeight),
                shape = MaterialTheme.shapes.extraLarge,
                staggerIndex = 0,
            )
            // 海报尺度（2:3）+ 剧集格尺度（48dp 高）并排
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
                    // 116dp 的详情封面尺度（3:4）
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
                    .height(BeeDimens.heroCardHeight),
                shape = MaterialTheme.shapes.extraLarge,
            )
            SkeletonChipRow(staggerIndex = 1)
            SkeletonPosterGrid(rows = 1, startStaggerIndex = 4)
        }
    }
}
