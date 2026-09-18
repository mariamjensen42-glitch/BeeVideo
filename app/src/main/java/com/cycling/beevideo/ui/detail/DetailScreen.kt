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
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bookmark
import androidx.compose.material.icons.outlined.BookmarkBorder
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.MediumFlexibleTopAppBar
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.rememberTopAppBarState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
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
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import coil.compose.AsyncImage
import coil.request.ImageRequest
import com.cycling.beevideo.R
import com.cycling.beevideo.domain.model.PlayProgress
import com.cycling.beevideo.domain.model.Vod
import com.cycling.beevideo.domain.repository.ContentRepository
import com.cycling.beevideo.domain.repository.LibraryRepository
import com.cycling.beevideo.ui.components.BeeBackButton
import com.cycling.beevideo.ui.components.BeeChipRow
import com.cycling.beevideo.ui.components.BeePosterImage
import com.cycling.beevideo.ui.components.ContainmentBlock
import com.cycling.beevideo.ui.preview.FakeContentRepository
import com.cycling.beevideo.ui.preview.FakeLibraryRepository
import com.cycling.beevideo.ui.preview.PreviewViewModelStoreOwner
import com.cycling.beevideo.ui.components.LoadState
import com.cycling.beevideo.ui.components.ScoreBadge
import com.cycling.beevideo.ui.components.SkeletonBlock
import com.cycling.beevideo.ui.components.SkeletonChipRow
import com.cycling.beevideo.ui.components.beeTopAppBarColors
import com.cycling.beevideo.ui.components.metaLine
import com.cycling.beevideo.ui.components.posterBrush
import com.cycling.beevideo.ui.components.scorePart
import com.cycling.beevideo.ui.components.skeletonSemantics
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
 * 内容组织用 M3 的 **containment** 手法：相关信息各归进一个 `surface container low` 的
 * 28dp 圆角块，而不是靠分割线切。块用 `extraLarge`(28dp)、里面的海报和剧集格用
 * `medium`(12dp)：28 − 16(块内边距) = 12，正好同心。
 *
 * 详情是**异步**取的：列表项里的字段通常不全（MacCMS 的列表接口不返回 `vod_play_url`），
 * 所以从列表进详情必须再问一次来源。参数是 `vodId` 而不是整个 `Vod` —— 传对象进来会
 * 诱导界面拿列表里那份不完整的数据渲染，结果就是"选集是空的"，且看不出是没请求还是真没有。
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun DetailScreen(
    content: ContentRepository,
    library: LibraryRepository,
    vodId: String,
    onBack: () -> Unit,
    onPlay: (lineIndex: Int, episodeIndex: Int) -> Unit,
) {
    /*
     * 加载与编排交给状态持有者（见 `DetailState` 的说明）。挂在 ViewModel 上是因为
     * `configChanges` 不含 `uiMode` —— 主题切换会重建 Activity，而**选中的线路必须活下来**：
     * 进度是按「线路名 + 集号」定位的，线路丢了进度条也就对不上了。
     */
    val viewModel: DetailViewModel = viewModel(
        factory = viewModelFactory {
            initializer { DetailViewModel(content, library, vodId) }
        },
    )
    val state = viewModel.state

    val vodState by state.vod.collectAsStateWithLifecycle()
    // 局部变量：`by` 委托出来的值不能智能转换，而下面要按类型分支
    val currentVodState = vodState
    val vod = (vodState as? LoadState.Ready)?.value

    /*
     * 每次**进入这一页**都重读一次进度：从播放页返回时会重新进组合，而那一页刚往库里
     * 写过 —— 不重读的话进度条停在上次进来时的位置。
     *
     * 不订阅数据库是有意的：播放时每秒一次的写入会让返回栈底部的这一页跟着重组。
     */
    LaunchedEffect(Unit) { state.refreshProgress() }
    val progressState by state.progress.collectAsStateWithLifecycle()
    val progress = (progressState as? LoadState.Ready)?.value

    // 收藏状态相反：它会被本页的按钮改动，必须订阅才能立刻反映到图标上
    val isKept by state.isKept.collectAsStateWithLifecycle()

    val lineIndex = state.lineIndex
    val notFoundText = stringResource(R.string.detail_not_found)

    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior(
        state = rememberTopAppBarState(),
    )

    Scaffold(
        containerColor = MaterialTheme.colorScheme.surface,
        // nestedScroll 挂 Scaffold（官方 sample 写法）：flexible 顶栏的测量高度随滚动真的变小，
        // innerPadding 因此每帧在变，内容跟着被顶上来
        modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        topBar = {
            MediumFlexibleTopAppBar(
                title = {
                    Text(
                        // 加载中/失败时不占位：正文里有更准确的说明，顶栏先写"条目不存在"是错的
                        text = when (currentVodState) {
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
                        // 格式串会留下一个没有数值的「分 · 2026」
                        val sub = metaLine(scorePart(vod.score), vod.year)
                        if (sub.isNotEmpty()) Text(text = sub)
                    }
                },
                navigationIcon = { BeeBackButton(onBack) },
                actions = {
                    // 收藏按钮只在详情真的到手后才出现：收藏要存片名、封面这些快照字段，
                    // 加载完成前它们是空的。先显示按钮、点下去存一条没有标题的收藏，更糟。
                    if (vod != null) {
                        KeepAction(
                            kept = isKept,
                            onClick = { state.toggleKeep(vod) },
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
            currentVodState is LoadState.Loading -> DetailSkeleton(
                modifier = bodyModifier.skeletonSemantics(
                    stringResource(R.string.detail_loading)
                ),
            )

            currentVodState is LoadState.Failed -> Box(bodyModifier, Alignment.Center) {
                Text(
                    text = stringResource(R.string.detail_load_failed, currentVodState.message),
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
                progress = progress,
                lineIndex = lineIndex,
                onSelectLine = state::selectLine,
                onPlay = onPlay,
                modifier = bodyModifier,
            )
        }
    }
}

/**
 * 顶栏上的收藏开关。实心 = 已收藏、描边 = 未收藏 —— M3 对"可切换图标"的一贯约定：
 * **状态变化不能只靠颜色**，色觉障碍用户看不到颜色差，而填充与描边是形状差别。
 *
 * 不弹 Toast / Snackbar：图标从描边变实心就是完整反馈，而且收藏随时可以反悔，
 * 弹提示反而打断视野。
 */
@Composable
private fun KeepAction(kept: Boolean, onClick: () -> Unit) {
    IconButton(onClick = onClick) {
        Icon(
            imageVector = if (kept) Icons.Filled.Bookmark else Icons.Outlined.BookmarkBorder,
            contentDescription = stringResource(
                if (kept) R.string.cd_keep_remove else R.string.cd_keep_add
            ),
        )
    }
}

@Composable
private fun DetailBody(
    vod: Vod,
    progress: PlayProgress?,
    lineIndex: Int,
    onSelectLine: (Int) -> Unit,
    onPlay: (lineIndex: Int, episodeIndex: Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    val line = vod.lines.getOrNull(lineIndex)
    val episodes = line?.episodes.orEmpty()

    /*
     * 「上次看到」标记的定位规则：线路名与集号必须同时对上 —— 与 domain 的 resumePositionMs
     * 是同一条判据，这里标记哪一格，点进去就该从哪一格恢复。两处各写一套的话，会出现
     * "标记在第 3 集、点进去却从头播"，而这种不一致极难被当成 bug 报上来。
     *
     * 集号只在同一条线路内可比，所以切线路时标记会消失 —— 这是**正确的**，不是丢失。
     */
    val markedIndex = progress
        ?.takeIf { it.lineName == line?.name }
        ?.episodeIndex
        ?.takeIf { it in episodes.indices }

    // 来源没给时长（durationMs = 0）时不给比例 —— 画一条永远 0% 的条比不画更像加载失败
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
                // 只有"上次看到的那一集"带进度条，其余是 null
                progressFraction = if (index == markedIndex) markedFraction else null,
                onClick = { onPlay(lineIndex, index) },
            )
        }
    }
}

/**
 * 详情页骨架 —— 出现位置是**整块替换** `DetailBody`，所以结构要逐块对齐它：
 *
 *   ① 信息块：`detailPosterWidth`(116dp) 的 3:4 封面 + 右侧四行元信息 + 三行简介
 *   ② 线路块：两个标签行 + 一个按钮组
 *   ③ 剧集网格：4 列 × 2 行，48dp 高，`shapes.medium`
 *
 * 圆角沿用同一套同心规则（28 的容器块 − 16 内边距 = 12），封面与剧集格都用
 * `shapes.medium`；容器块直接复用 [ContainmentBlock]，而不是手画一个灰块 ——
 * 骨架的"块感"本来就该来自真实的那个组件，不然内容到达时颜色深浅会变一次。
 *
 * 用 `Column` 而不是 `LazyVerticalGrid`：内容只有一屏多，没有懒加载的必要；
 * 真在同方向嵌一个懒列表反而会抛异常（详情页真身用的是 LazyVerticalGrid，
 * 但它是这一屏的**根**，不是嵌在别的懒列表里的项）。
 *
 * 灰条的宽度有意长短不一：等长的四条读起来像表格，参差才像一段文字。
 */
@Composable
private fun DetailSkeleton(modifier: Modifier = Modifier) {
    Column(
        modifier = modifier
            .verticalScroll(rememberScrollState())
            .padding(
                start = BeeDimens.screenMargin,
                end = BeeDimens.screenMargin,
                top = BeeDimens.gapSmall,
                bottom = BeeDimens.gapHuge,
            ),
        // 与 DetailBody 的网格间距一致
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
            // 简介：三段，逐段收短
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

        // 剧集网格：列数、格高、间距全部照抄 DetailBody 的 LazyVerticalGrid
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

/**
 * 骨架里一条"文字"的高度。
 *
 * 12dp 是按最小的一档字（`labelMedium` 12sp）定的 —— 骨架条比真字略高一点点
 * 才像"这里有字"，比真字矮会读成"这里有线"。它**不影响**容器块的总高
 * （块高由 116dp 的封面那一列决定），所以不需要精确到某一行。
 */
private val SKELETON_TEXT_BAR = 12.dp

/** 简介骨架的三段宽度，逐段收短。 */
private val SKELETON_INTRO_FRACTIONS = listOf(1f, 1f, 0.68f)

/** 剧集骨架行数。两行 8 格，加上信息块正好一屏。 */
private const val SKELETON_EPISODE_ROWS = 2

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
 * 片名**不在这里重复** —— 它已经在顶栏的展开态里，而且字号是顶栏的角色，正文再写一遍
 * 就会和顶栏打架。海报上的片名是封面内容本身，不是标题，保留。
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
                 * 渐变当底、图盖在上面，不用 Coil 的 placeholder 机制：图没加载出来
                 * （或这个源不给封面）时底下的渐变就是占位，省掉一套 placeholder 状态。
                 */
                BeePosterImage(pic = vod.pic, contentDescription = vod.name)

                /*
                 * 片名贴底左对齐，跟首页海报卡同一套语言。**不叠 scrim**：posterBrush
                 * 的渐变上亮下暗，底部 L≈0.16，白字压上去对比 4.7:1 够用，116dp 宽的小图上
                 * 再蒙一层反而显脏。有真实封面时才压一层薄 scrim 保住文字（只压暗底部 40%）。
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
 * 剧集格。用 Card 而不是自己画 Box：卡片自带点击语义、状态层和无障碍角色。
 * 容器色取 `surface container highest` —— M3 里它是「同级元素里最靠前的一档」，
 * 正好把可点的剧集从块里再托起来。高度固定 48dp，是可点目标的下限。
 *
 * [progressFraction] 非空时才画进度条，表示"这一集上次看到这里"。条贴格子底边，
 * 左右各让出 12dp（= 卡片圆角半径）——不让的话两端会顶出圆角外。
 */
@Composable
private fun EpisodeCell(
    label: String,
    progressFraction: Float?,
    onClick: () -> Unit,
) {
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

            if (progressFraction != null) {
                EpisodeProgressBar(
                    fraction = progressFraction,
                    modifier = Modifier.align(Alignment.BottomCenter),
                )
            }
        }
    }
}

/** 剧集格底部的进度条。轨道 + 填充两层 Box，不引组件 —— 它只有一条直线的形态。 */
@Composable
private fun EpisodeProgressBar(fraction: Float, modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = BeeDimens.episodeProgressInset)
            .height(BeeDimens.episodeProgressHeight)
            .background(
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = EPISODE_TRACK_ALPHA),
                shape = MaterialTheme.shapes.extraSmall,
            ),
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth(fraction)
                .fillMaxHeight()
                .background(
                    color = MaterialTheme.colorScheme.primary,
                    shape = MaterialTheme.shapes.extraSmall,
                ),
        )
    }
}

/**
 * 进度轨道用 `onSurface` 的低透明度而不是 `outlineVariant`：格子本身已经是
 * `surfaceContainerHighest`，轨道要能在同一族的底色上被看见，而 outline 系是为
 * "分割线"设计的，压在这里会糊掉。这一档是"够看清边界、又不至于像条分割线"。
 */
private const val EPISODE_TRACK_ALPHA = 0.20f

// ------------------------------------------------------------------ 预览

/*
 * 预览用 [FakeContentRepository] / [FakeLibraryRepository]（纯预览实现，不发网络请求、
 * 不碰数据库）。真实仓储需要 context + 已配置的来源，预览环境两样都没有。
 *
 * 这两个夹具住在 `ui/preview/` 而不是 `data/` —— 那正是为了让 ui **不必在编译期
 * 依赖 data**（依赖方向是 `ui → domain ← data`，见 scope 文档附录 A）。
 */
private val previewContent = FakeContentRepository()

/**
 * 带进度的那份，专门用来看**进度条标记**长什么样。线路名必须与 [PreviewVods] 里的一致 ——
 * 判据是「线路名 + 集号同时对上」，名字写错的话预览里什么都看不到，而且不会报错。
 */
private val previewLibraryWithProgress = FakeLibraryRepository(
    progress = PlayProgress(
        vodId = "v01",
        lineName = "线路一 · 演示",
        episodeIndex = 2,
        episodeName = "第 03 集",
        positionMs = 600_000L,
        durationMs = 2_700_000L,
        updatedAt = 1_700_000_000_000L,
    ),
)

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
        PreviewViewModelStoreOwner {
            DetailScreen(
                content = previewContent,
                library = previewLibraryWithProgress,
                vodId = "v01",
                onBack = {},
                onPlay = { _, _ -> },
            )
        }
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
        PreviewViewModelStoreOwner {
            DetailScreen(
                content = previewContent,
                library = FakeLibraryRepository(),
                vodId = "not-exist",
                onBack = {},
                onPlay = { _, _ -> },
            )
        }
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
        DetailScreen(
            content = previewContent,
            library = previewLibraryWithProgress,
            vodId = "v01",
            onBack = {},
            onPlay = { _, _ -> },
        )
    }
}
