package com.cycling.beevideo.ui.detail

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
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LoadingIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.MediumFlexibleTopAppBar
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.rememberTopAppBarState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import coil.compose.AsyncImage
import coil.request.ImageRequest
import com.cycling.beevideo.R
import com.cycling.beevideo.data.repository.DemoContentRepository
import com.cycling.beevideo.domain.model.Vod
import com.cycling.beevideo.domain.repository.ContentRepository
import com.cycling.beevideo.ui.components.BeeChipRow
import com.cycling.beevideo.ui.components.LoadState
import com.cycling.beevideo.ui.components.ScoreBadge
import com.cycling.beevideo.ui.components.beeTopAppBarColors
import com.cycling.beevideo.ui.components.loadState
import com.cycling.beevideo.ui.components.metaLine
import com.cycling.beevideo.ui.components.posterBrush
import com.cycling.beevideo.ui.components.scorePart
import com.cycling.beevideo.ui.theme.BeeBrandFont
import com.cycling.beevideo.ui.theme.BeeDimens
import com.cycling.beevideo.ui.theme.BeeMotion
import com.cycling.beevideo.ui.theme.BeeVideoTheme
import com.cycling.beevideo.ui.theme.MotionSpeed
import com.cycling.beevideo.ui.theme.PRESSED_SCALE
import com.cycling.beevideo.ui.theme.PosterScrim
import com.cycling.beevideo.ui.theme.PosterTextPrimary

/**
 * 详情页。
 *
 * 内容组织用 M3 的 **containment** 手法：把相关的信息各归进一个
 * `surface container low` 的 28dp 圆角块，而不是靠分割线切。
 *
 * 之前这一页 69.3% 的像素是纯 surface（#0B0B0D），整屏是一片黑 + 零星文字，
 * 所以看起来「没有官方的味道」—— 官方样张里几乎不存在大面积无层级的底色。
 * 现在海报、元数据、简介合成一块，线路与选集标题合成一块。
 *
 * 块用 `extraLarge`(28dp)，里面的海报和剧集格用 `medium`(12dp)：
 * 28 − 16(块内边距) = 12，正好同心。
 *
 * ─── 详情是**异步**取的 ───────────────────────────────────────────────
 * 列表项里的字段通常不全（MacCMS 的列表接口不返回 `vod_play_url`），
 * 所以从列表进详情必须再问一次来源。这也是为什么参数是 `vodId` 而不是整个
 * `Vod` 对象 —— 传对象进来会诱导界面直接拿列表里那份不完整的数据去渲染，
 * 结果就是"选集是空的"，且看不出是没请求还是真没有。
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun DetailScreen(
    content: ContentRepository,
    vodId: String,
    onBack: () -> Unit,
    onPlay: (lineIndex: Int, episodeIndex: Int) -> Unit,
) {
    val vodState = loadState(vodId) { content.detail(vodId) }
    val vod = (vodState as? LoadState.Ready)?.value
    var lineIndex by remember(vodId) { mutableIntStateOf(0) }
    val notFoundText = stringResource(R.string.detail_not_found)

    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior(
        state = rememberTopAppBarState(),
    )

    Scaffold(
        containerColor = MaterialTheme.colorScheme.surface,
        // nestedScroll 挂 Scaffold（官方 sample 写法）—— flexible 顶栏的测量高度
        // 会随滚动真的变小，innerPadding 因此每帧在变，内容跟着被顶上来
        modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        topBar = {
            MediumFlexibleTopAppBar(
                title = {
                    Text(
                        // 加载中/失败时不占位：正文里有更准确的说明，
                        // 顶栏先写一句"条目不存在"是错的（还没问到结果）
                        text = when (vodState) {
                            is LoadState.Ready -> vod?.name ?: notFoundText
                            else -> ""
                        },
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                },
                subtitle = {
                    if (vod != null) {
                        // 用 metaLine 而不是「%1$s 分 · %2$s」：XML 源没有评分字段时
                        // 格式串会留下一个没有数值的「分 · 2026」。
                        val sub = metaLine(scorePart(vod.score), vod.year)
                        if (sub.isNotEmpty()) Text(text = sub)
                    }
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(R.string.cd_back),
                        )
                    }
                },
                scrollBehavior = scrollBehavior,
                colors = beeTopAppBarColors(),
            )
        },
    ) { innerPadding ->
        val bodyModifier = Modifier
            .fillMaxSize()
            .padding(innerPadding)

        when {
            vodState is LoadState.Loading -> Box(bodyModifier, Alignment.Center) {
                LoadingIndicator(color = MaterialTheme.colorScheme.primary)
            }

            vodState is LoadState.Failed -> Box(bodyModifier, Alignment.Center) {
                Text(
                    text = stringResource(R.string.detail_load_failed, vodState.message),
                    modifier = Modifier.padding(horizontal = BeeDimens.gapHuge),
                    textAlign = TextAlign.Center,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.bodyMedium,
                )
            }

            vod == null -> Box(bodyModifier, Alignment.Center) {
                Text(
                    text = notFoundText,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.bodyMedium,
                )
            }

            else -> DetailBody(
                vod = vod,
                lineIndex = lineIndex,
                onSelectLine = { lineIndex = it },
                onPlay = onPlay,
                modifier = bodyModifier,
            )
        }
    }
}

@Composable
private fun DetailBody(
    vod: Vod,
    lineIndex: Int,
    onSelectLine: (Int) -> Unit,
    onPlay: (lineIndex: Int, episodeIndex: Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    val episodes = vod.lines.getOrNull(lineIndex)?.episodes.orEmpty()

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
        item(span = { GridItemSpan(maxLineSpan) }) {
            DetailInfoBlock(vod = vod)
        }

        item(span = { GridItemSpan(maxLineSpan) }) {
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
            }
        }

        itemsIndexed(episodes) { index, episode ->
            EpisodeCell(
                label = episode.name,
                onClick = { onPlay(lineIndex, index) },
            )
        }
    }
}

/** 容器块：28dp 圆角的 `surface container low`。全页的层级都由它撑起来。 */
@Composable
private fun ContainmentBlock(
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    Surface(
        shape = MaterialTheme.shapes.extraLarge,
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        modifier = modifier.fillMaxWidth(),
    ) {
        Column(modifier = Modifier.padding(BeeDimens.gapMedium)) {
            content()
        }
    }
}

/** 区块标签。基准 title small —— 强调留给标题和行动，不层层加粗。 */
@Composable
private fun SectionLabel(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(vertical = BeeDimens.gapTiny),
    )
}

/**
 * 信息块：海报 + 元数据 + 简介。
 *
 * 片名**不在这里重复** —— 它已经在顶栏的展开态里，而且字号是顶栏的角色。
 * 正文再写一遍就会和顶栏打架，也把「一块地方说一件事」的规矩破坏掉。
 * 海报上的片名是封面内容本身，不是标题，保留。
 */
@Composable
private fun DetailInfoBlock(vod: Vod) {
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
                /*
                 * 真实封面。这里**没有**跟着换成 Coil 的 placeholder 机制，
                 * 而是把渐变当成底、图盖在上面：图没加载出来（或这个源不给封面）
                 * 时底下的渐变就是占位，不需要额外写一套 placeholder 状态。
                 */
                PosterImage(pic = vod.pic, contentDescription = vod.name)

                /*
                 * 片名贴底、左对齐，跟首页海报卡同一套语言（那边是
                 * PosterCard）。这里**不叠 scrim**：posterBrush 的渐变是
                 * 上亮下暗，底部 L≈0.16，白字压上去对比度 4.7:1，够用；
                 * 116dp 宽的小图上再蒙一层反而显脏。
                 * 有真实封面时靠这一层薄 scrim 保住文字——它只压暗底部 40%。
                 */
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
                    style = MaterialTheme.typography.titleMediumEmphasized.copy(
                        fontFamily = BeeBrandFont,
                    ),
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
                    // 「更新至 N 集」是这一块里第二重要的信息，给一档强调
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

/** 详情页封面的压暗层：只在底部 45% 起作用，别把整张小图蒙灰。 */
private val DetailPosterScrim = Brush.verticalGradient(
    colorStops = arrayOf(
        0.55f to Color.Transparent,
        1.00f to PosterScrim.copy(alpha = 0.72f),
    ),
)

/**
 * 封面图。
 *
 * [pic] 为空时**什么都不画**，把底下的渐变露出来 —— 这是相当一部分源的常态
 * （尤其是聚合类源），所以占位路径必须是被认真设计过的一条，而不是"出图失败的兜底"。
 */
@Composable
private fun PosterImage(pic: String, contentDescription: String?) {
    if (pic.isEmpty()) return
    val context = LocalContext.current
    AsyncImage(
        model = remember(pic) {
            ImageRequest.Builder(context)
                .data(pic)
                .crossfade(true)
                .build()
        },
        contentDescription = contentDescription,
        contentScale = ContentScale.Crop,
        modifier = Modifier.fillMaxSize(),
    )
}

/**
 * 剧集格。
 *
 * 用 Card 而不是自己画 Box：卡片自带点击语义、状态层和无障碍角色。
 * 容器色取 `surface container highest` —— M3 里它是「同级元素里最靠前的一档」，
 * 正好用来把可点的剧集从块里再托起来。高度固定 48dp，是可点目标的下限。
 */
@Composable
private fun EpisodeCell(label: String, onClick: () -> Unit) {
    val interactionSource = remember { MutableInteractionSource() }
    val pressed by interactionSource.collectIsPressedAsState()
    val scale by animateFloatAsState(
        targetValue = if (pressed) PRESSED_SCALE else 1f,
        animationSpec = BeeMotion.floatSpatial(MotionSpeed.FAST),
        label = "episodeScale",
    )

    Card(
        onClick = onClick,
        modifier = Modifier
            .height(BeeDimens.episodeCellHeight)
            .graphicsLayer {
                scaleX = scale
                scaleY = scale
            },
        interactionSource = interactionSource,
        shape = MaterialTheme.shapes.medium,
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerHighest,
        ),
    ) {
        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text(
                text = label,
                color = MaterialTheme.colorScheme.onSurface,
                style = MaterialTheme.typography.labelLarge,
                maxLines = 1,
            )
        }
    }
}

// ------------------------------------------------------------------ 预览

/*
 * 预览用 [DemoContentRepository]（纯预览实现，不会发网络请求）。
 * 真实仓储需要 context + 已配置的来源，预览环境两样都没有。
 */
private val previewContent = DemoContentRepository()

@Preview(
    name = "详情 · 手机",
    group = "页面",
    showBackground = true,
    backgroundColor = 0xFF0B0A08,
    widthDp = 411,
    heightDp = 891,
)
@Composable
private fun DetailScreenPreview() {
    BeeVideoTheme(darkTheme = true) {
        DetailScreen(content = previewContent, vodId = "v01", onBack = {}, onPlay = { _, _ -> })
    }
}

@Preview(
    name = "详情 · 条目不存在",
    group = "页面",
    showBackground = true,
    backgroundColor = 0xFF0B0A08,
    widthDp = 411,
    heightDp = 300,
)
@Composable
private fun DetailScreenNotFoundPreview() {
    BeeVideoTheme(darkTheme = true) {
        DetailScreen(content = previewContent, vodId = "not-exist", onBack = {}, onPlay = { _, _ -> })
    }
}

@Preview(
    name = "详情 · 浅色",
    group = "页面",
    showBackground = true,
    backgroundColor = 0xFFFFF9EF,
    widthDp = 411,
    heightDp = 891,
)
@Composable
private fun DetailScreenLightPreview() {
    BeeVideoTheme(darkTheme = false) {
        DetailScreen(content = previewContent, vodId = "v01", onBack = {}, onPlay = { _, _ -> })
    }
}
