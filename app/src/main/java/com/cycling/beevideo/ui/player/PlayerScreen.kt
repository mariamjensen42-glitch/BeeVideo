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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
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
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import androidx.media3.ui.PlayerView
import com.cycling.beevideo.R
import com.cycling.beevideo.domain.model.Episode
import com.cycling.beevideo.domain.model.PlaybackFailure
import com.cycling.beevideo.domain.model.PlaybackState
import com.cycling.beevideo.domain.model.resumePositionMs
import com.cycling.beevideo.domain.repository.ContentRepository
import com.cycling.beevideo.domain.repository.LibraryRepository
import com.cycling.beevideo.domain.repository.PlaybackSession
import com.cycling.beevideo.domain.repository.PlaybackSettings
import com.cycling.beevideo.player.Media3PlaybackSession
import com.cycling.beevideo.ui.components.BeeBackButton
import com.cycling.beevideo.ui.components.ContainmentBlock
import com.cycling.beevideo.ui.components.LoadState
import com.cycling.beevideo.ui.components.beeTopAppBarColors
import com.cycling.beevideo.ui.components.loadState
import com.cycling.beevideo.ui.preview.PreviewVods
import com.cycling.beevideo.ui.theme.BeeDimens
import com.cycling.beevideo.ui.theme.BeeMotion
import com.cycling.beevideo.ui.theme.BeeVideoTheme
import com.cycling.beevideo.ui.theme.MotionSpeed
import com.cycling.beevideo.ui.theme.PlayerSurface

/** 48dp 见方时 full 圆角 = 尺寸的一半 */
private val EPISODE_CELL_FULL = 24.dp
/** 选中时收成 medium —— 圆 → 方，Expressive 的形状变化 */
private val EPISODE_CELL_SELECTED = 12.dp

/**
 * 播放页。
 *
 * `vod_play_url` 里存的**不一定是地址**：MacCMS 那类源直接给可播地址，spider 源则常给一个
 * 内部 id，要由站点服务端二次兑换，所以进这一页后还要再问一次来源（`playTarget`）。
 * 这一步不能省，也不能在详情页一次性全兑换好 —— 几十集就是几十次请求，用户只看一集。
 *
 * 相当一部分源播放时要带 Referer 或自定义 UA，不带就是 403，所以 DataSource 按当前目标的
 * 请求头构造（见 [playTarget] 的 key）。
 */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun PlayerScreen(
    content: ContentRepository,
    library: LibraryRepository,
    settings: PlaybackSettings,
    vodId: String,
    lineIndex: Int,
    episodeIndex: Int,
    onBack: () -> Unit,
) {
    val context = LocalContext.current

    /*
     * 会话与集号挂在 ViewModel 上。
     *
     * ⚠️ 这不是"更规范"，是**必须**：`AndroidManifest` 的 `configChanges` 不含
     * `uiMode`，所以这个 App 自己的主题切换会重建 Activity。它们要是住在 `remember`
     * 里，重建后集号回到路由参数那一集，而库里的进度属于另一集 ——
     * `resumePositionMs` 要求集号严格相等，于是返回 0：看到第 8 集 12:40，
     * 去设置页选个深色，回来停在第 1 集 0:00。没有报错、没有日志。
     */
    val viewModel: PlayerViewModel = viewModel(
        factory = viewModelFactory {
            initializer {
                PlayerViewModel(
                    session = Media3PlaybackSession(
                        /*
                         * ⚠️ **application context，不是 Activity 的**。
                         *
                         * 会话现在住在 ViewModel 里 —— 它跨配置变化存活，也就是说它会
                         * 活得比创建它的那个 Activity 更久。拿 Activity 的 context 建
                         * ExoPlayer，等于把旧 Activity 一直钉在内存里：每切一次主题漏一个。
                         * 这是"把播放器从组合搬进持有者"顺带引入的风险，不是原来就有的。
                         */
                        context = context.applicationContext,
                        /*
                         * 磁盘缓存只在**进入这一页时**读一次设置：配额在缓存实例建好
                         * 之后就改不了（见 MediaCacheProvider 的注释），每次重组都去读
                         * 一遍 SharedPreferences 也只是白读。**取实例**那一步留在
                         * `player/` 里（`Media3PlaybackSession` 的次构造）—— 这一层
                         * 只该提供策略：开不开、配额多少。设置对象与设置页是同一个
                         * 实例，见 PlaybackSettings 的说明。
                         */
                        quotaBytes = if (settings.cacheEnabled) settings.cacheQuotaBytes else 0L,
                    ),
                    library = library,
                    vodId = vodId,
                    initialEpisodeIndex = episodeIndex,
                )
            }
        },
    )
    val playback = viewModel.playback

    val detailState = loadState(vodId) { content.detail(vodId) }
    val vod = (detailState as? LoadState.Ready)?.value
    val line = vod?.lines?.getOrNull(lineIndex)
    val episodes = line?.episodes.orEmpty()

    /*
     * 剧集列表变了就把集号夹进范围。夹取在**持有者**里做，不在这里 ——
     * 界面夹取的话持有者记账用的还是越界那个，落进库里的就是一条指向不存在剧集的进度。
     */
    LaunchedEffect(episodes.size) { playback.onEpisodesChanged(episodes.size) }
    val safeIndex = playback.episodeIndex
    val current = episodes.getOrNull(safeIndex)

    // 落进度要用线路名与剧集名，它们在详情加载完 / 切集时才确定 —— 推到持有者去记账
    LaunchedEffect(line?.name, current?.name) {
        playback.bindContext(line?.name.orEmpty(), current?.name.orEmpty())
    }

    val targetState = loadState(vodId, lineIndex, safeIndex, current?.url) {
        val episode = current ?: return@loadState null
        content.playTarget(vodId, line?.name.orEmpty(), episode.url)
    }
    val target = (targetState as? LoadState.Ready)?.value

    // 上次看到哪。必须在**起播之前**拿到 —— 先起播再跳过去，用户能看见那一次跳变
    val progressState = loadState(vodId) { library.progressOf(vodId) }
    val progress = (progressState as? LoadState.Ready)?.value

    /*
     * 起播（或换集）。等 progressState 读完再起播：加载中先返回，读完变成 Ready 会让这个
     * effect 重启一次，那一次才真正开播 —— 所以不会出现"先用 0 起播、再跳一下"。
     *
     * "空地址 / 需要站外解析"这两类判定搬进了会话：它会转成说得清的失败状态，
     * 界面不再自己挡一道。
     */
    LaunchedEffect(target?.url, progressState) {
        val play = target ?: return@LaunchedEffect
        if (progressState is LoadState.Loading) return@LaunchedEffect

        // 具体跳多少由 domain 的 resumePositionMs 判定（线路 + 集号都要对上）
        playback.open(play, resumePositionMs(progress, line?.name.orEmpty(), safeIndex))
    }

    // 退到后台时强制保存：系统随后回收进程的话，从上一次周期上报到现在的进度就没了，
    // 而"切出去干点别的再回来"恰恰是最常发生的操作
    LifecycleEventEffect(Lifecycle.Event.ON_STOP) { playback.onStop() }

    val playbackState by playback.state.collectAsStateWithLifecycle()
    // 局部变量：`by` 委托出来的值不能智能转换，而下面要按类型分支
    val currentState = playbackState

    var showInfo by rememberSaveable { mutableStateOf(false) }

    val statusText = when {
        detailState is LoadState.Failed ->
            stringResource(R.string.detail_load_failed, detailState.message)

        targetState is LoadState.Loading -> stringResource(R.string.player_resolving)
        targetState is LoadState.Failed ->
            stringResource(R.string.player_resolve_failed, targetState.message)

        target == null -> stringResource(R.string.player_resolve_empty)
        // 起不来的原因由会话给出（空地址 / 需要站外解析 / 内核报错），界面只负责翻成文案
        currentState is PlaybackState.Failed -> failedPlaybackText(currentState)
        currentState is PlaybackState.Buffering -> stringResource(R.string.player_state_buffering)
        else -> stringResource(R.string.player_state_ready)
    }

    PlayerScaffold(
        state = PlayerUiState(
            title = vod?.name ?: stringResource(R.string.player_fallback_title),
            lineName = line?.name.orEmpty(),
            episodeName = current?.name ?: stringResource(R.string.player_no_episode),
            episodes = episodes,
            currentIndex = safeIndex,
            statusText = statusText,
            urlText = target?.url ?: stringResource(R.string.player_url_placeholder),
            isBuffering = currentState is PlaybackState.Buffering,
        ),
        onSelectEpisode = playback::selectEpisode,
        onPrev = { if (safeIndex > 0) playback.selectEpisode(safeIndex - 1) },
        onNext = { if (safeIndex < episodes.lastIndex) playback.selectEpisode(safeIndex + 1) },
        onBack = onBack,
        onInfoClick = { showInfo = true },
        player = { modifier ->
            PlaybackSurface(
                session = playback.session,
                isBuffering = currentState is PlaybackState.Buffering,
                modifier = modifier,
            )
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
 * 把"起不来"的原因翻成文案。
 *
 * 原因分枚举、文案留在界面 —— 那是**文案**，不是内核知识，所以不进 domain。
 */
@Composable
private fun failedPlaybackText(failed: PlaybackState.Failed): String = when (failed.reason) {
    PlaybackFailure.NoAddress -> stringResource(R.string.player_resolve_empty)
    PlaybackFailure.RequiresExternalParser -> stringResource(R.string.player_parse_required)
    PlaybackFailure.Kernel -> stringResource(R.string.player_error, failed.detail.orEmpty())
}

/**
 * 画面槽：把会话接到 Media3 的 [PlayerView] 上。
 *
 * ⚠️ 这里是**唯一**还认识 Media3 的界面代码 —— 换内核时它要跟着换，
 * 因为"哪个内核配哪种视图"本身就是内核的事。会话本身不认识界面，
 * 而起播 / 状态 / 进度上报这些编排已经与内核无关了。
 */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun PlaybackSurface(
    session: PlaybackSession,
    isBuffering: Boolean,
    modifier: Modifier,
) {
    val player = (session as? Media3PlaybackSession)?.player ?: return
    Box(modifier) {
        AndroidView(
            factory = { ctx ->
                PlayerView(ctx).apply {
                    this.player = player
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
}

/**
 * 播放页的纯布局部分，不含播放器实现。播放器通过 [player] 槽位注入：Preview 里能渲染
 * （ExoPlayer 在预览环境跑不起来），换播放内核时这一层也不用动。
 *
 * 顶栏**故意不用** `MediumFlexibleTopAppBar`：播放页第一优先级是把竖向空间让给画面和选集，
 * 112dp 的展开态是负收益。
 */
/**
 * 播放页布局要看的东西，收成一个值。
 *
 * 以前它们是 8 个位置参数，加上 5 个回调和一个画面槽，`PlayerScaffold` 一共 14 个参数 ——
 * 调用点读到第 5 个就已经不知道谁是谁了。
 *
 * **回调与画面槽不在里面**：把 lambda / Composable 塞进数据类会破坏相等性判断
 * （每次重组都是新实例），也让 `@Preview` 没法把画面换成一块纯色。所以
 * state 只装"看到什么"，"能做什么"仍然单独传。
 */
private data class PlayerUiState(
    val title: String,
    val lineName: String,
    val episodeName: String,
    val episodes: List<Episode>,
    val currentIndex: Int,
    val statusText: String,
    val urlText: String,
    val isBuffering: Boolean,
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun PlayerScaffold(
    state: PlayerUiState,
    onSelectEpisode: (Int) -> Unit,
    onPrev: () -> Unit,
    onNext: () -> Unit,
    onBack: () -> Unit,
    onInfoClick: () -> Unit,
    /** 画面槽位。Preview 里换成纯色，换内核时也不用动这一层。 */
    player: @Composable (Modifier) -> Unit,
) {
    // 解构而不是到处写 state.xxx：函数体里那几十处引用一个都不用改
    val (title, lineName, episodeName, episodes, currentIndex, statusText, urlText, isBuffering) =
        state
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
                navigationIcon = { BeeBackButton(onBack) },
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
            // 画面不做裁切：媒体内容保持直角，与周围的圆角形成张力
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .aspectRatio(BeeDimens.videoAspect)
                    .background(PlayerSurface),
            ) {
                player(Modifier.fillMaxSize())
            }

            Spacer(Modifier.height(BeeDimens.gapSmall))

            // 这一屏的排版主体
            ContainmentBlock(Modifier.padding(horizontal = BeeDimens.screenMargin)) {
                Text(
                    text = episodeName,
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
                // 状态行比地址重要得多：地址是一串看不懂的 URL，状态才告诉用户现在怎么了
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

                // 上一集退到 secondary container，把 primary 让给"下一集"这个主要动作
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

            ContainmentBlock(Modifier.padding(horizontal = BeeDimens.screenMargin)) {
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

/**
 * 选集格 —— 形状随选中状态变化：未选中是 full 圆角（48dp 见方即为正圆），选中收成
 * medium，一圆一方让选中项在整排圆点里立刻跳出来。
 *
 * 圆角走 spatial 弹簧（会回弹），颜色走 effects 弹簧（绝不回弹），两者都用 fast ——
 * M3 把 fast 定义成「小组件」的档位，同一个格子里两种规格会看到两种节奏。
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
 * 播放信息对话框，**全屏**形态 —— M3 给 Compact 宽度指定的就是它：窄屏上放基础对话框，
 * 长地址会被挤成好几行。容器用 `surface container high`，比页面底色亮三档，
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
                // M3 规定头部高 56dp、左右留白 24dp。48dp 的 IconButton 里图标 24dp 居中，
                // 所以 start 给 12dp，图标才正好落在离左边 24dp 处
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
                // 底部操作栏高 56dp（8 + 40 + 8），左右留白与头部对齐
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
    val vod = PreviewVods.vods.first()
    val line = vod.lines.first()
    BeeVideoTheme(darkTheme = true) {
        PlayerScaffold(
            state = PlayerUiState(
                title = vod.name,
                lineName = line.name,
                episodeName = line.episodes[2].name,
                episodes = line.episodes,
                currentIndex = 2,
                statusText = "就绪",
                urlText = line.episodes[2].url,
                isBuffering = false,
            ),
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
            state = PlayerUiState(
                title = "空态",
                lineName = "",
                episodeName = "无可播放剧集",
                episodes = emptyList(),
                currentIndex = 0,
                statusText = "这个源没有给出可播放的地址",
                urlText = "—",
                isBuffering = false,
            ),
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
