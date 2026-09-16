package com.cycling.beevideo.ui.player

import android.view.ViewGroup
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LoadingIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.ui.PlayerView
import com.cycling.beevideo.R
import com.cycling.beevideo.data.demo.DemoContent
import com.cycling.beevideo.domain.model.Episode
import com.cycling.beevideo.domain.repository.ContentRepository
import com.cycling.beevideo.ui.components.LoadState
import com.cycling.beevideo.ui.components.beeTopAppBarColors
import com.cycling.beevideo.ui.components.loadState
import com.cycling.beevideo.ui.theme.BeeDimens
import com.cycling.beevideo.ui.theme.BeeMotion
import com.cycling.beevideo.ui.theme.BeeVideoTheme
import com.cycling.beevideo.ui.theme.MotionSpeed
import com.cycling.beevideo.ui.theme.PlayerSurface

/** 选集格尺寸 48dp，未选中时 full 圆角 = 尺寸的一半 = 24dp */
private val EPISODE_CELL_FULL = 24.dp
/** 选中时收成 medium（12dp）—— 圆 → 方，这是 Expressive 的形状变化 */
private val EPISODE_CELL_SELECTED = 12.dp

/**
 * 播放页。
 *
 * ─── 播放地址是**单独一步**换来的 ─────────────────────────────────────
 * 详情里的 `vod_play_url` 里存的**不一定是地址**：MacCMS 那类源直接给可播地址，
 * 而 spider 源常常给一个内部 id，真实地址要由站点服务端二次兑换。
 * 所以进这一页之后还要再问一次来源（`playTarget`）。
 *
 * 这一步不能省、也不能"先在详情页一次性全兑换好"：一部剧动辄几十集，
 * 全部兑换就是几十次请求，用户只会看其中一集。
 *
 * ─── 请求头必须跟着地址走 ─────────────────────────────────────────────
 * 相当一部分源在播放时要带 Referer 或自定义 UA，不带就是 403。
 * 所以 DataSource 是按当前目标的请求头构造的，而**换集时如果头变了，
 * 播放器会拿到新的 MediaSource 工厂**（见 [playTarget] 的 key）。
 */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun PlayerScreen(
    content: ContentRepository,
    vodId: String,
    lineIndex: Int,
    episodeIndex: Int,
    onBack: () -> Unit,
) {
    val detailState = loadState(vodId) { content.detail(vodId) }
    val vod = (detailState as? LoadState.Ready)?.value
    val line = vod?.lines?.getOrNull(lineIndex)
    val episodes = line?.episodes.orEmpty()

    var currentIndex by remember(vodId, lineIndex) { mutableIntStateOf(episodeIndex) }
    /*
     * 详情是异步到的，首帧 episodes 还是空的 —— 那时不能夹，一夹就把
     * 用户点进来的那一集冲成第 0 集。等列表到了再夹才有意义。
     */
    val safeIndex = if (episodes.isEmpty()) {
        currentIndex
    } else {
        currentIndex.coerceIn(0, episodes.lastIndex)
    }
    val current = episodes.getOrNull(safeIndex)

    val targetState = loadState(vodId, lineIndex, safeIndex, current?.url) {
        val episode = current ?: return@loadState null
        content.playTarget(vodId, line?.name.orEmpty(), episode.url)
    }
    val target = (targetState as? LoadState.Ready)?.value

    val context = LocalContext.current
    val headers = target?.headers ?: emptyMap()

    /*
     * 请求头变了就换一套 DataSource —— `setDefaultRequestProperties` 是**构建期**
     * 定死的（内部会把 map 拷走），所以不能建好之后改。用 headers 当 remember 的
     * key：同一条线路内换集时 headers 不变，工厂不会重建。
     */
    val mediaSourceFactory = remember(headers) {
        DefaultMediaSourceFactory(
            DefaultHttpDataSource.Factory()
                .setDefaultRequestProperties(headers)
                // 很多源站会把 http 跳 https，不开这个开关会直接被拦下
                .setAllowCrossProtocolRedirects(true)
        )
    }

    val exoPlayer = remember {
        ExoPlayer.Builder(context).build().apply { playWhenReady = true }
    }

    LaunchedEffect(target?.url, mediaSourceFactory) {
        val play = target ?: return@LaunchedEffect
        // parse=true 表示这是网页、不是媒体流：本项目不内置解析服务，
        // 硬塞给播放器只会得到一条看不懂的解码错误
        if (play.url.isEmpty() || play.parse) return@LaunchedEffect
        exoPlayer.setMediaSource(mediaSourceFactory.createMediaSource(MediaItem.fromUri(play.url)))
        exoPlayer.prepare()
    }

    var isBuffering by remember { mutableStateOf(false) }
    var playerError by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(target?.url) { playerError = null }

    DisposableEffect(exoPlayer) {
        val listener = object : Player.Listener {
            override fun onPlaybackStateChanged(playbackState: Int) {
                isBuffering = playbackState == Player.STATE_BUFFERING
            }

            override fun onPlayerError(error: PlaybackException) {
                // 播放器的原始报错信息很长（含一堆解码器细节），但用户需要的是
                // "为什么放不出来"。这里只取它最后一段说明性的文本。
                playerError = error.cause?.message ?: error.errorCodeName
            }
        }
        exoPlayer.addListener(listener)
        onDispose {
            exoPlayer.removeListener(listener)
            exoPlayer.release()
        }
    }

    var showInfo by remember { mutableStateOf(false) }

    val statusText = when {
        detailState is LoadState.Failed ->
            stringResource(R.string.detail_load_failed, detailState.message)

        targetState is LoadState.Loading -> stringResource(R.string.player_resolving)
        targetState is LoadState.Failed ->
            stringResource(R.string.player_resolve_failed, targetState.message)

        target == null -> stringResource(R.string.player_resolve_empty)
        target.parse -> stringResource(R.string.player_parse_required)
        playerError != null -> stringResource(R.string.player_error, playerError.orEmpty())
        isBuffering -> stringResource(R.string.player_state_buffering)
        else -> stringResource(R.string.player_state_ready)
    }

    PlayerScaffold(
        title = vod?.name ?: stringResource(R.string.player_fallback_title),
        lineName = line?.name.orEmpty(),
        episodeName = current?.name ?: stringResource(R.string.player_no_episode),
        episodes = episodes,
        currentIndex = safeIndex,
        statusText = statusText,
        urlText = target?.url ?: stringResource(R.string.player_url_placeholder),
        isBuffering = isBuffering,
        onSelectEpisode = { currentIndex = it },
        onPrev = { if (safeIndex > 0) currentIndex = safeIndex - 1 },
        onNext = { if (safeIndex < episodes.lastIndex) currentIndex = safeIndex + 1 },
        onBack = onBack,
        onInfoClick = { showInfo = true },
        player = { modifier ->
            Box(modifier) {
                AndroidView(
                    factory = { ctx ->
                        PlayerView(ctx).apply {
                            this.player = exoPlayer
                            useController = true
                            layoutParams = ViewGroup.LayoutParams(
                                ViewGroup.LayoutParams.MATCH_PARENT,
                                ViewGroup.LayoutParams.MATCH_PARENT,
                            )
                        }
                    },
                    modifier = Modifier.fillMaxSize(),
                )
                if (isBuffering) {
                    LoadingIndicator(
                        modifier = Modifier.align(Alignment.Center),
                        color = MaterialTheme.colorScheme.primary,
                    )
                }
            }
        },
    )

    if (showInfo) {
        PlayInfoDialog(
            title = stringResource(R.string.player_info_title),
            lineName = line?.name.orEmpty(),
            episodeName = current?.name.orEmpty(),
            url = target?.url.orEmpty(),
            state = statusText,
            onDismiss = { showInfo = false },
        )
    }
}

/**
 * 播放页的纯布局部分，不含播放器实现。
 *
 * 播放器通过 [player] 槽位注入 —— 这样做有两个好处：
 * 一是 Preview 里能渲染（ExoPlayer 在预览环境跑不起来），
 * 二是将来把 Media3 换成别的内核时，这一层不用动。
 *
 * 顶栏这里**故意不换成 MediumFlexibleTopAppBar**：播放页的第一优先级是把
 * 竖向空间让给画面和选集，112dp 的展开态是负收益。改用 small 顶栏 +
 * `enterAlwaysScrollBehavior`，向下滚时整条顶栏退场。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun PlayerScaffold(
    title: String,
    lineName: String,
    episodeName: String,
    episodes: List<Episode>,
    currentIndex: Int,
    statusText: String,
    urlText: String,
    isBuffering: Boolean,
    onSelectEpisode: (Int) -> Unit,
    onPrev: () -> Unit,
    onNext: () -> Unit,
    onBack: () -> Unit,
    onInfoClick: () -> Unit,
    player: @Composable (Modifier) -> Unit,
) {
    val scrollBehavior = TopAppBarDefaults.enterAlwaysScrollBehavior()

    Scaffold(
        containerColor = MaterialTheme.colorScheme.surface,
        modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        text = title,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                },
                subtitle = {
                    if (lineName.isNotEmpty()) {
                        Text(text = stringResource(R.string.player_line, lineName))
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
                actions = {
                    IconButton(onClick = onInfoClick) {
                        Icon(
                            imageVector = Icons.Outlined.Info,
                            contentDescription = stringResource(R.string.player_action_info),
                        )
                    }
                },
                // 容器色跟页面底色一致，滚动前后不变（原因见 beeTopAppBarColors）
                colors = beeTopAppBarColors(),
                scrollBehavior = scrollBehavior,
            )
        },
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .verticalScroll(rememberScrollState()),
        ) {
            // 画面不加圆角：媒体内容不做裁切，直角反而和周围的圆角形成张力
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .aspectRatio(BeeDimens.videoAspect)
                    .background(PlayerSurface),
            ) {
                player(Modifier.fillMaxSize())
            }

            Spacer(Modifier.height(BeeDimens.gapSmall))

            // 正在播放的内容 —— 这一屏的排版主体
            ContainmentBlock {
                Text(
                    text = episodeName,
                    // 播放页的大标题：headline small 的强调档（24sp Bold）
                    style = MaterialTheme.typography.headlineSmallEmphasized,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Spacer(Modifier.height(BeeDimens.gapTight))
                Text(
                    text = stringResource(R.string.player_line, lineName),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.labelMedium,
                )
                Spacer(Modifier.height(BeeDimens.gapTight))
                /*
                 * 状态行放在地址上方，而且用 labelLargeEmphasized：
                 * 「解析中 / 失败原因 / 需要网页解析」这几种情况比地址本身重要得多 ——
                 * 地址是一串看不懂的 URL，而状态告诉用户现在到底怎么了。
                 */
                Text(
                    text = statusText,
                    color = MaterialTheme.colorScheme.onSurface,
                    style = MaterialTheme.typography.labelLargeEmphasized,
                )
                Spacer(Modifier.height(BeeDimens.gapTight))
                Text(
                    text = urlText,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.bodySmall,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                Spacer(Modifier.height(BeeDimens.gapSmall))

                // 两个动作分主次：下一集是主要动作走 primary，上一集退到 secondary container
                Row(horizontalArrangement = Arrangement.spacedBy(BeeDimens.gapTiny)) {
                    FilledTonalButton(
                        onClick = onPrev,
                        enabled = currentIndex > 0,
                    ) {
                        Text(
                            text = stringResource(R.string.player_prev),
                            style = MaterialTheme.typography.labelLarge,
                        )
                    }
                    Button(
                        onClick = onNext,
                        enabled = currentIndex < episodes.lastIndex,
                    ) {
                        Text(
                            text = stringResource(R.string.player_next),
                            style = MaterialTheme.typography.labelLargeEmphasized,
                        )
                    }
                }
            }

            Spacer(Modifier.height(BeeDimens.gapSmall))

            ContainmentBlock {
                Text(
                    text = stringResource(R.string.player_section_episodes),
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(BeeDimens.gapTiny))
                LazyRow(
                    horizontalArrangement = Arrangement.spacedBy(BeeDimens.gapTiny),
                ) {
                    itemsIndexed(episodes) { index, episode ->
                        EpisodeChip(
                            label = episode.short,
                            selected = index == currentIndex,
                            onClick = { onSelectEpisode(index) },
                        )
                    }
                }
            }

            Spacer(Modifier.height(BeeDimens.gapHuge))
        }
    }
}

/** 容器块，与详情页同一套：28dp 圆角 + `surface container low` + 16dp 内边距。 */
@Composable
private fun ContainmentBlock(
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    Surface(
        shape = MaterialTheme.shapes.extraLarge,
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = BeeDimens.screenMargin),
    ) {
        Column(modifier = Modifier.padding(BeeDimens.gapMedium)) {
            content()
        }
    }
}

/**
 * 选集格 —— 形状随选中状态变化。
 *
 * 未选中：full 圆角（24dp，48dp 见方即为正圆）；选中：收成 medium（12dp）。
 * 一圆一方形成张力，选中项在整排圆点里立刻跳出来。
 *
 * 圆角走 spatial 弹簧（会回弹），颜色走 effects 弹簧（绝不回弹）。
 * 两者都是 **fast**：M3 把 fast 定义为「小组件」的档位（按钮、开关正是这个量级），
 * 一个 48dp 的格子里形状走 fast、颜色走 default，同一次点击会看到两种节奏。
 */
@Composable
private fun EpisodeChip(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
) {
    val corner by animateDpAsState(
        targetValue = if (selected) EPISODE_CELL_SELECTED else EPISODE_CELL_FULL,
        animationSpec = BeeMotion.dpSpatial(MotionSpeed.FAST),
        label = "episodeCorner",
    )
    val scheme = MaterialTheme.colorScheme
    val container by animateColorAsState(
        targetValue = if (selected) scheme.primaryContainer else scheme.surfaceContainerHighest,
        animationSpec = BeeMotion.colorEffects(MotionSpeed.FAST),
        label = "episodeContainer",
    )
    val content by animateColorAsState(
        targetValue = if (selected) scheme.onPrimaryContainer else scheme.onSurfaceVariant,
        animationSpec = BeeMotion.colorEffects(MotionSpeed.FAST),
        label = "episodeContent",
    )

    Surface(
        onClick = onClick,
        modifier = Modifier.size(BeeDimens.playerCellSize),
        shape = RoundedCornerShape(corner),
        color = container,
        contentColor = content,
    ) {
        Box(contentAlignment = Alignment.Center) {
            Text(
                text = label,
                style = if (selected) {
                    MaterialTheme.typography.labelMediumEmphasized
                } else {
                    MaterialTheme.typography.labelMedium
                },
                maxLines = 1,
            )
        }
    }
}

/**
 * 播放信息对话框。
 *
 * 手机端固定用**全屏对话框**（顶栏带关闭图标 + 分隔线）。这是 M3 给 Compact
 * 宽度指定的形态：窄屏上放基础对话框，长地址会被挤成好几行、上下留白也不够。
 *
 * 容器角色是 `surface container high` —— 比页面底色亮三档，边界清楚，
 * 一眼能看出这是个挡住整个 App 的模态任务。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun PlayInfoDialog(
    title: String,
    lineName: String,
    episodeName: String,
    url: String,
    state: String,
    onDismiss: () -> Unit,
) {
    val body: @Composable () -> Unit = {
        Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
            InfoRow(stringResource(R.string.player_info_line), lineName)
            Spacer(Modifier.height(BeeDimens.gapTiny))
            InfoRow(stringResource(R.string.player_info_episode), episodeName)
            Spacer(Modifier.height(BeeDimens.gapTiny))
            InfoRow(stringResource(R.string.player_info_state), state)
            Spacer(Modifier.height(BeeDimens.gapTiny))
            InfoRow(stringResource(R.string.player_info_url), url)
        }
    }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Surface(
            modifier = Modifier.fillMaxSize(),
            color = MaterialTheme.colorScheme.surfaceContainerHigh,
        ) {
            Column {
                /*
                 * 全屏对话框头部：M3 规定高度 56dp、左右留白 24dp。
                 * 48dp 的 IconButton 里图标是 24dp 居中，所以 start 给 12dp，
                 * 图标正好落在离左边 24dp 的位置。
                 */
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(56.dp)
                        .padding(start = BeeDimens.gapSmall, end = BeeDimens.gapLarge),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    IconButton(onClick = onDismiss) {
                        Icon(
                            imageVector = Icons.Filled.Close,
                            contentDescription = stringResource(R.string.cd_close),
                        )
                    }
                    Spacer(Modifier.width(BeeDimens.gapTiny))
                    Text(
                        text = title,
                        style = MaterialTheme.typography.titleLarge,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                }
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                Column(
                    modifier = Modifier
                        .weight(1f)
                        .padding(BeeDimens.gapLarge),
                ) {
                    body()
                }
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                // 底部操作栏：高 56dp（8 + 40 + 8），左右留白与头部对齐
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(
                            horizontal = BeeDimens.gapLarge,
                            vertical = BeeDimens.gapTiny,
                        ),
                    horizontalArrangement = Arrangement.End,
                ) {
                    TextButton(onClick = onDismiss) {
                        Text(
                            text = stringResource(R.string.dialog_close),
                            style = MaterialTheme.typography.labelLarge,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun InfoRow(label: String, value: String) {
    Row {
        Text(
            text = label,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            style = MaterialTheme.typography.labelMedium,
            modifier = Modifier.width(56.dp),
        )
        Text(
            text = value,
            color = MaterialTheme.colorScheme.onSurface,
            style = MaterialTheme.typography.bodyMedium,
        )
    }
}

// ------------------------------------------------------------------ 预览

@Preview(
    name = "播放页 · 布局（无播放器）",
    group = "页面",
    showBackground = true,
    backgroundColor = 0xFF0B0A08,
    widthDp = 411,
    heightDp = 891,
)
@Composable
private fun PlayerScaffoldPreview() {
    val vod = DemoContent.vods.first()
    val line = vod.lines.first()
    BeeVideoTheme(darkTheme = true) {
        PlayerScaffold(
            title = vod.name,
            lineName = line.name,
            episodeName = line.episodes[2].name,
            episodes = line.episodes,
            currentIndex = 2,
            statusText = "就绪",
            urlText = line.episodes[2].url,
            isBuffering = false,
            onSelectEpisode = {},
            onPrev = {},
            onNext = {},
            onBack = {},
            onInfoClick = {},
            player = { modifier -> Box(modifier.background(PlayerSurface)) },
        )
    }
}

@Preview(
    name = "播放页 · 无可播放剧集",
    group = "页面",
    showBackground = true,
    backgroundColor = 0xFF0B0A08,
    widthDp = 411,
    heightDp = 700,
)
@Composable
private fun PlayerScaffoldEmptyPreview() {
    BeeVideoTheme(darkTheme = true) {
        PlayerScaffold(
            title = "空态",
            lineName = "",
            episodeName = "无可播放剧集",
            episodes = emptyList(),
            currentIndex = 0,
            statusText = "这个源没有给出可播放的地址",
            urlText = "—",
            isBuffering = false,
            onSelectEpisode = {},
            onPrev = {},
            onNext = {},
            onBack = {},
            onInfoClick = {},
            player = { modifier -> Box(modifier.background(PlayerSurface)) },
        )
    }
}

@Preview(
    name = "播放信息 · 全屏对话框",
    group = "页面",
    showBackground = true,
    backgroundColor = 0xFF0B0A08,
    widthDp = 411,
    heightDp = 891,
)
@Composable
private fun PlayInfoDialogPreview() {
    BeeVideoTheme(darkTheme = true) {
        PlayInfoDialog(
            title = "播放信息",
            lineName = "线路 1",
            episodeName = "第 3 集",
            url = "https://example.com/demo.m3u8",
            state = "就绪",
            onDismiss = {},
        )
    }
}
