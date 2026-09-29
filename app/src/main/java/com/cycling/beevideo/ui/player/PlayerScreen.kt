package com.cycling.beevideo.ui.player

import android.content.res.Configuration
import androidx.activity.compose.BackHandler
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.cycling.beevideo.R
import com.cycling.beevideo.domain.model.PlaybackState
import com.cycling.beevideo.domain.model.resumePositionMs
import com.cycling.beevideo.domain.repository.ContentRepository
import com.cycling.beevideo.domain.repository.IncognitoMode
import com.cycling.beevideo.domain.repository.LibraryRepository
import com.cycling.beevideo.domain.repository.PlaybackSettings
import com.cycling.beevideo.player.Media3PlaybackSession
import com.cycling.beevideo.ui.components.LoadState
import com.cycling.beevideo.ui.components.loadState
import kotlinx.coroutines.delay

/** 播放页。`vod_play_url` 未必是地址（spider 源常给内部 id），进页面后还要再兑换一次。 */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun PlayerScreen(
    content: ContentRepository,
    library: LibraryRepository,
    settings: PlaybackSettings,
    incognito: IncognitoMode,
    vodId: String,
    lineIndex: Int,
    episodeIndex: Int,
    onBack: () -> Unit,
) {
    val context = LocalContext.current

    // 进页面时读一次：缓存目录建好之后改不了，与配额同样的取舍（见本页工厂里的说明）
    val incognitoNow by incognito.enabled.collectAsStateWithLifecycle()

    // 会话必须住 ViewModel：主题切换会重建 Activity，住 remember 里集号会退回路由参数
    val viewModel: PlayerViewModel = viewModel(
        factory = viewModelFactory {
            initializer {
                PlayerViewModel(
                    session = Media3PlaybackSession(
                        // ⚠️ application context：会话比 Activity 活得久
                        context = context.applicationContext,
                        // 配额建好之后改不了，只在进页面时读一次
                        quotaBytes = if (settings.cacheEnabled) settings.cacheQuotaBytes else 0L,
                        // 无痕期间写独立缓存目录，退出无痕时整个删掉
                        incognito = incognitoNow,
                    ),
                    library = library,
                    vodId = vodId,
                    initialEpisodeIndex = episodeIndex,
                    initialLineIndex = lineIndex,
                    autoPlayNext = settings.autoPlayNext,
                )
            }
        },
    )
    val playback = viewModel.playback

    val detailState = loadState(vodId) { content.detail(vodId) }
    val vod = (detailState as? LoadState.Ready)?.value
    // 线路号取自持有者，不是路由参数：路由参数进来就定死，靠它换线路会重建整个会话
    val line = vod?.lines?.getOrNull(playback.lineIndex)
    val episodes = line?.episodes.orEmpty()

    // 夹取要在持有者里做，界面夹的话落库的仍是越界那个
    LaunchedEffect(episodes.size) { playback.onEpisodesChanged(episodes.size) }
    val safeIndex = playback.episodeIndex
    val current = episodes.getOrNull(safeIndex)

    // vod?.name 必须进 key：详情是异步来的，漏掉它第一次绑定拿的是空快照
    LaunchedEffect(vod?.name, line?.name, current?.name) {
        playback.bindContext(vod, line?.name.orEmpty(), current?.name.orEmpty())
    }

    val targetState = loadState(vodId, playback.lineIndex, safeIndex, current?.url) {
        val episode = current ?: return@loadState null
        content.playTarget(vodId, line?.name.orEmpty(), episode.url)
    }
    val target = (targetState as? LoadState.Ready)?.value

    // 必须在起播之前拿到，否则用户能看见"先 0 起播、再跳一下"
    val progressState = loadState(vodId) { library.progressOf(vodId) }
    val progress = (progressState as? LoadState.Ready)?.value

    // 等 progressState 读完再开播：加载中先返回，读完转 Ready 会重启本 effect
    LaunchedEffect(target?.url, progressState) {
        val play = target ?: return@LaunchedEffect
        if (progressState is LoadState.Loading) return@LaunchedEffect

        playback.open(play, resumePositionMs(progress, line?.name.orEmpty(), safeIndex))
    }

    // 被系统回收的话，距上次周期上报的进度就没了
    LifecycleEventEffect(Lifecycle.Event.ON_STOP) { playback.onStop() }

    val playbackState by playback.state.collectAsStateWithLifecycle()
    // 局部变量：by 委托出来的值不能智能转换
    val currentState = playbackState

    // 含缓冲态：缓冲时内核的 playWhenReady 仍是 true，图标必须显示暂停
    val isPlaying =
        currentState is PlaybackState.Playing || currentState is PlaybackState.Buffering

    // 提到这一层是因为画面上的缓冲转圈要读它
    var controlsVisible by remember { mutableStateOf(true) }

    // 手势进行中不许收起，否则拖到一半控件消失，松手后只剩空画面
    var gestureFeedback by remember { mutableStateOf<PlayerGestureFeedback?>(null) }
    val gestureActive = gestureFeedback != null

    LaunchedEffect(controlsVisible, isPlaying, gestureActive) {
        if (controlsVisible && isPlaying && !gestureActive) {
            delay(PLAYER_CONTROLS_AUTO_HIDE_MS)
            controlsVisible = false
        }
    }

    var fullscreenIntent by rememberSaveable { mutableStateOf(false) }
    PlayerFullscreenEffect(fullscreenIntent)
    BackHandler(enabled = fullscreenIntent) { fullscreenIntent = false }

    // ⚠️ 布局判据用真实方向而非点击意图：requestedOrientation 是异步的，
    // 按意图切布局会让竖屏窗口把画面压成中间一条
    val isLandscape =
        LocalConfiguration.current.orientation == Configuration.ORIENTATION_LANDSCAPE

    val systemControls = rememberPlayerSystemControls()

    PlayerScaffold(
        state = PlayerUiState(
            title = vod?.name ?: stringResource(R.string.player_fallback_title),
            lineName = line?.name.orEmpty(),
            episodeName = current?.name ?: stringResource(R.string.player_no_episode),
            lines = vod?.lines.orEmpty(),
            currentLineIndex = playback.lineIndex,
            episodes = episodes,
            currentIndex = safeIndex,
        ),
        onSelectLine = playback::selectLine,
        onSelectEpisode = playback::selectEpisode,
        onPrev = { if (safeIndex > 0) playback.selectEpisode(safeIndex - 1) },
        onNext = { if (safeIndex < episodes.lastIndex) playback.selectEpisode(safeIndex + 1) },
        onBack = onBack,
        isFullscreen = isLandscape,
        player = { modifier ->
            PlaybackSurface(
                session = playback.session,
                isBuffering = currentState is PlaybackState.Buffering && !controlsVisible,
                modifier = modifier,
            ) {
                // 手势层在最下面是刻意的：控件先消费事件，剩下的才落回手势层
                PlayerGestureLayer(
                    positionMs = playback.positionMs,
                    durationMs = playback.durationMs,
                    systemControls = systemControls,
                    onTap = { controlsVisible = !controlsVisible },
                    onSeek = playback::seekTo,
                    onFeedback = { gestureFeedback = it },
                )
                PlayerControls(
                    visible = controlsVisible,
                    isPlaying = isPlaying,
                    positionMs = playback.positionMs,
                    durationMs = playback.durationMs,
                    speed = playback.speed,
                    // 图标跟意图走：转屏要几百毫秒，跟布局走按钮看着像没按下去
                    isFullscreen = fullscreenIntent,
                    onToggleFullscreen = { fullscreenIntent = !fullscreenIntent },
                    onPlayPause = playback::togglePlayPause,
                    onSeek = playback::seekTo,
                    onSpeedChange = playback::selectSpeed,
                )
                PlayerGestureOverlay(
                    feedback = gestureFeedback,
                    modifier = Modifier.align(Alignment.Center),
                )
            }
        },
    )

}
