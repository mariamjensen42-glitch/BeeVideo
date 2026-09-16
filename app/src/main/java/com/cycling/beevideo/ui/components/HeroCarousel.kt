package com.cycling.beevideo.ui.components

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.PageSize
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import coil.request.ImageRequest
import com.cycling.beevideo.R
import com.cycling.beevideo.data.demo.DemoContent
import com.cycling.beevideo.domain.model.Vod
import com.cycling.beevideo.ui.theme.BeeDimens
import com.cycling.beevideo.ui.theme.BeeMotion
import com.cycling.beevideo.ui.theme.BeeVideoTheme
import com.cycling.beevideo.ui.theme.MotionSpeed
import com.cycling.beevideo.ui.theme.PRESSED_SCALE
import com.cycling.beevideo.ui.theme.PosterScrim
import com.cycling.beevideo.ui.theme.PosterTextPrimary
import com.cycling.beevideo.ui.theme.PosterTextSecondary

/** 标签内边距。横向 12 / 纵向 4，正好让 14sp 的标签落在 28dp 高。 */
private val LABEL_PADDING_HORIZONTAL = 12.dp
private val LABEL_PADDING_VERTICAL = 4.dp

/**
 * 精选横滑 —— M3 carousel 的 **Hero 布局**。
 *
 * ─── 为什么从 uncontained 换成 Hero ─────────────────────────────────
 * M3 把 carousel 分成六个布局，用途各有分工。第一版用的是 `uncontained`：
 * 所有项**单一尺寸**，最接近传统横滑。问题是那样每一项都一样大、一样重，
 * 一屏看下去就是「一条等宽的卡片带」，没有主次，也就没有版面。
 *
 * `Hero` 布局的用途被写明是「spotlighting very large visual items
 * (**like a movie** or featured app)」，做法是「focus on one large image
 * **while providing a sneak peek of what's next**」，并要求 **snap 滚动、
 * 一次翻一项**。这正好是首页该有的东西：一个明确的主角 + 一条隔壁的暗示。
 *
 * 落到实现上：
 *   - 用 `HorizontalPager`（天然 snap，一次一项）代替 `LazyRow`
 *   - `PageSize.Fill` 让大项的宽度 = 屏宽 − 左留白 − 预告条 − 项间距，
 *     于是**右边恒定露出 `heroPeekWidth`（52dp）** —— 正是 M3 定义的
 *     small carousel item（40–56dp）。露出量不随屏宽变化，窄屏宽屏一个观感。
 *   - 项圆角 28dp（M3 对 carousel item 的规定），与下方海报网格的 12dp 对撞
 *
 * ─── 这个组件承担的是全产品的 hero moment ────────────────────────────
 * M3 Expressive 明确说 hero moment 要**同时动用多条战术**，
 * 而且「一个产品里只留一到两个」，多了就是噪音。所以这里一次叠四层，
 * 其余页面保持安静：
 *
 *   1. **排版** 片名走 `displayLargeEmphasized`（57sp）。这是全 App 唯一用到
 *      display 档的地方，此前最大是顶栏的 28sp。M3 的原话是「缩小字号刻度时，
 *      要保持成规模的对比，避免细微差别」—— 57sp 对 14sp 的正文是 4 倍落差。
 *      字体族是衬线（见 `BeeVideoTheme` 的 brand 字体槽），这是「影评刊物」感的来源。
 *   2. **颜色** 卡片底取方案里**最亮的那一档琥珀**，标签底取同一色族的深档。
 *
 *      ⚠️ 这里踩过两次坑，写清楚免得回退：
 *
 *      a) 一开始用 `primaryContainer`。浅色下很出彩（#F7C94F 亮琥珀），
 *         但深色下 primaryContainer 是 tone 30 的深橄榄 #5C4200，压在 tone 6 的
 *         surface #0B0A08 上分离度只有 **1.9:1** —— 卡片边界糊进背景，
 *         「hero」成了整屏最闷的一块。
 *      b) 改成 `primary` 之后深色对了，浅色又反了 —— 浅色的 primary 是 tone 40
 *         的 #7A5900，一块深橄榄压在暖白 #FFF9EF 上，同样发闷。
 *
 *      根因是 M3 把「最高强调的填充」在两套方案里放在了**不同的角色**上：
 *      浅色是 primary(tone 40)/primaryContainer(tone 84)，
 *      深色是 primary(tone 80)/primaryContainer(tone 30) ——
 *      **primary 与 primaryContainer 的明暗在两套方案里正好对调**。
 *      所以这里按**亮度**挑，而不是按角色名挑，两套主题都拿到那块亮琥珀。
 *
 *      实测：浅色 #F7C94F 对 #FFF9EF 分离 1.49:1（是 scheme 里最亮的填充），
 *      深色 #FFC107 对 #0B0A08 是 17:1。两套主题下它都是这屏最扎眼的一块。
 *   3. **形状** 28dp 圆角，跟下方海报网格的 12dp 直接对撞。
 *   4. **版式** 卡内多了一条「刊头行」：左边是 `精选` 印章，右边是 `01/05` 的
 *      篇次。这是刊物排版的常规做法（running head），在一个横滑里它同时承担
 *      「还有几篇」的信息 —— 竖向滚动页上的 carousel 得让人知道总量，
 *      否则无从判断要不要滑。
 *
 * 卡内所有元素的圆角都遵守同心规则：28（卡）− 20（内边距）= **8dp**，
 * 也就是 `shapes.small`，不能随手给一个 6dp。
 */
@Composable
fun HeroCarousel(
    vods: List<Vod>,
    onVodClick: (Vod) -> Unit,
    modifier: Modifier = Modifier,
) {
    if (vods.isEmpty()) return

    val pagerState = rememberPagerState(pageCount = { vods.size })

    // 切分类后回到第一张。分页器不会自己回零，留着旧页码会让人以为没切换。
    LaunchedEffect(vods) { pagerState.scrollToPage(0) }

    HorizontalPager(
        state = pagerState,
        modifier = modifier,
        /*
         * 左边**不传** start padding。
         *
         * 这里的 `contentPadding` 是「第一页与最后一页各自的停靠位」，
         * 而且**同时决定 pageSize**（实测：pageSize = 视口 − start − end）。
         * 外层网格项已经缩进了 screenMargin，这里再传一次就会缩进两倍 ——
         * 实测过：卡片左边缘落在 32dp，而 chips 和海报网格都在 16dp，整页错位。
         * 传 0 之后卡片左边缘 = 16dp，与全站栅格对齐。
         *
         * 顺带修掉另一个现象：start 传 16 时，翻到第二页起，左侧会露出上一张
         * 8dp 的残影（因为上一页的右边缘 = 本页左边 − pageSpacing = 16 − 8 = 8，
         * 仍在裁剪区内）。传 0 之后它落到裁剪区外，M3 Hero 布局要的
         * 「左侧齐平、右侧一条预告」才成立。
         *
         * right padding 取 `heroPeekWidth`：视口宽度减去它才是 pageSize，
         * 于是右侧恒露出 ≈ 44dp 的下一张（M3 定义的 small carousel item 40–56dp）。
         * 翻到最后一页时右边自然留白，不会出现多余的空白页。
         */
        contentPadding = PaddingValues(end = BeeDimens.heroPeekWidth),
        pageSpacing = BeeDimens.heroCardGap,
        pageSize = PageSize.Fill,
        key = { vods[it].id },
    ) { page ->
        val vod = vods[page]
        HeroCard(
            vod = vod,
            position = page + 1,
            total = vods.size,
            onClick = { onVodClick(vod) },
        )
    }
}

/**
 * hero 卡的四个填充色（见文件头「颜色」那条）。
 *
 * 判定用 **surface 的亮度**，不用 `isSystemInDarkTheme()`：后者读的是系统设置，
 * 而 `BeeVideoTheme(darkTheme = false)` 是可以显式覆盖的（预览稿正是这么写的）。
 * 用系统设置会让浅色预览稿里取到深色那一套 —— 就是记录里禁止过的那类问题。
 */
private data class HeroFills(
    val cardContainer: Color,
    val cardContent: Color,
    val labelContainer: Color,
    val labelContent: Color,
)

@Composable
private fun heroFills(): HeroFills {
    val scheme = MaterialTheme.colorScheme
    return if (scheme.surface.luminance() < 0.5f) {
        // 深色方案：primary 就是那块亮琥珀，primaryContainer 是它的深档
        HeroFills(
            cardContainer = scheme.primary,
            cardContent = scheme.onPrimary,
            labelContainer = scheme.primaryContainer,
            labelContent = scheme.onPrimaryContainer,
        )
    } else {
        // 浅色方案：两者对调
        HeroFills(
            cardContainer = scheme.primaryContainer,
            cardContent = scheme.onPrimaryContainer,
            labelContainer = scheme.primary,
            labelContent = scheme.onPrimary,
        )
    }
}

/**
 * hero 卡有真实封面时压的暗层。
 *
 * 三段而不是两段：顶部要压一点（刊头行里的篇次是浅色字），中间几乎全透
 * （让封面露出来），底部最重（大片名压在图上）。只做"上透下暗"的话，
 * 右上角的篇次压在明亮画面上会读不出来。
 */
private val HeroScrim = Brush.verticalGradient(
    colorStops = arrayOf(
        0.00f to PosterScrim.copy(alpha = 0.42f),
        0.45f to PosterScrim.copy(alpha = 0.12f),
        1.00f to PosterScrim.copy(alpha = 0.78f),
    ),
)

@Composable
private fun HeroCard(
    vod: Vod,
    position: Int,
    total: Int,
    onClick: () -> Unit,
) {
    val interactionSource = remember { MutableInteractionSource() }
    val pressed by interactionSource.collectIsPressedAsState()
    val scale by animateFloatAsState(
        targetValue = if (pressed) PRESSED_SCALE else 1f,
        // 缩放属于空间变化 → spatial 弹簧（会回弹）；颜色才归 effects
        animationSpec = BeeMotion.floatSpatial(MotionSpeed.FAST),
        label = "heroCardScale",
    )
    val fills = heroFills()

    /*
     * 有封面时文字改成图上那套白色 + 压暗层，没封面时沿用琥珀底上的深色字。
     * 这不是"两套设计"——是**同一套排版**在两种底色上的必要切换：深色字
     * 压在照片上必然读不出来，而琥珀底是品牌主色，不该为了照片把它整个丢掉。
     */
    val hasPic = vod.pic.isNotEmpty()
    val textColor = if (hasPic) PosterTextPrimary else fills.cardContent

    Card(
        onClick = onClick,
        interactionSource = interactionSource,
        modifier = Modifier
            .fillMaxWidth()
            .height(BeeDimens.heroCardHeight)
            .graphicsLayer {
                scaleX = scale
                scaleY = scale
            },
        shape = MaterialTheme.shapes.extraLarge,
        colors = CardDefaults.cardColors(
            containerColor = fills.cardContainer,
            contentColor = fills.cardContent,
        ),
    ) {
        Box(modifier = Modifier.fillMaxSize()) {
            if (hasPic) {
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
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(HeroScrim),
                )
            }

            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(BeeDimens.heroCardPadding),
                /*
                 * SpaceBetween：刊头行贴顶、片名与元信息贴底，中间的空档由卡片高度撑开。
                 * 这一片留白是有用的 —— M3 的 containment 战术要求「最重要的内容要有
                 * 充裕的空间」，一张塞满的卡和一张留有呼吸的卡，气质完全不同。
                 * 片名只有一行时留白会更宽，这正是版面要的呼吸。
                 */
                verticalArrangement = Arrangement.SpaceBetween,
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = stringResource(R.string.home_featured_label),
                        /*
                         * 标签底取同一色族的深档（见 heroFills），而不是另起一个色相：
                         *   浅色 → #7A5900 深琥珀底 + 白字，压在 #F7C94F 上（4.1:1 分离）
                         *   深色 → #5C4200 深橄榄底 + #FFDF9E 字，压在 #FFC107 上（5.8:1 分离）
                         * 两套主题下它都是一枚清楚的小印章。有封面时它压在照片上，
                         * 深色底反而更清楚 —— 所以这一枚在两处都不用改色。
                         *
                         * 没往第三色组（tertiary 暖橙红）上取：那是评分角标的专用色，
                         * 一屏里放两个 tertiary 就等于没有 tertiary。
                         */
                        color = fills.labelContent,
                        style = MaterialTheme.typography.labelLargeEmphasized,
                        modifier = Modifier
                            .background(
                                color = fills.labelContainer,
                                shape = MaterialTheme.shapes.small,
                            )
                            .padding(
                                horizontal = LABEL_PADDING_HORIZONTAL,
                                vertical = LABEL_PADDING_VERTICAL,
                            ),
                    )

                    /*
                     * 篇次。刊物的常规做法（running head）—— 翻页时 `01` 变 `02`，
                     * 右边那半截告诉你「还有几篇」，不然横滑是看不见尽头的。
                     */
                    Text(
                        text = stringResource(R.string.home_featured_index, position, total),
                        color = textColor,
                        style = MaterialTheme.typography.labelLargeEmphasized,
                    )
                }

                Column {
                    Text(
                        text = vod.name,
                        /*
                         * 全 App 唯一的大字，也是唯一用到 display 档的地方。
                         *
                         * 为什么是 displayMedium(45sp) 而不是 displayLarge(57sp)：
                         * 实测机是 1080×2400 @440dpi，也就是 **393dp 宽**（不是 411dp）。
                         * 卡片可用文字宽度 = 393 − 16(左留白) − 52(预告条) − 8(项间距)
                         * − 40(内边距) ≈ 277dp。57sp 的 CJK 字宽约等于字号本身，
                         * 5 个字就是 285dp —— 超过 277，于是「山与海之间」被折成
                         * 「山与海之 / 间」，一个孤字掉到第二行，这是排版事故。
                         * 45sp 的 5 字是 225dp、6 字 270dp，都留有余量；
                         * 7 字以上才折行，那时折得也还算匀。
                         *
                         * 教训：CJK 的 display 字号要按**最窄的机型**反推字数，
                         * 不能按拉丁文的经验估。
                         */
                        color = textColor,
                        style = MaterialTheme.typography.displayMediumEmphasized,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Spacer(Modifier.height(BeeDimens.gapTight))
                    /*
                     * 元信息整行交给 [metaLine] 拼，不用带位置占位符的格式串：
                     * XML 源（type=0）没有评分字段，格式串会渲染出
                     * 「分 · 2026 · 更新至 12」这种没有数值的孤立单位。
                     *
                     * 全空时整行不画 —— 卡片是 SpaceBetween 布局，少一行只是
                     * 多留一点呼吸，不会塌。
                     */
                    val meta = metaLine(scorePart(vod.score), vod.year, vod.remarks)
                    if (meta.isNotEmpty()) {
                        Text(
                            text = meta,
                            color = if (hasPic) PosterTextSecondary else fills.cardContent,
                            style = MaterialTheme.typography.bodyLarge,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
            }
        }
    }
}

// ------------------------------------------------------------------ 预览

@Preview(
    name = "精选横滑 · 深色",
    group = "组件",
    showBackground = true,
    backgroundColor = 0xFF0B0A08,
    widthDp = 411,
)
@Composable
private fun HeroCarouselPreview() {
    BeeVideoTheme(darkTheme = true) {
        HeroCarousel(
            vods = DemoContent.vods.take(3),
            onVodClick = {},
        )
    }
}

@Preview(
    name = "精选横滑 · 浅色",
    group = "组件",
    showBackground = true,
    backgroundColor = 0xFFFFF9EF,
    widthDp = 411,
)
@Composable
private fun HeroCarouselLightPreview() {
    BeeVideoTheme(darkTheme = false) {
        HeroCarousel(
            vods = DemoContent.vods.take(3),
            onVodClick = {},
        )
    }
}
