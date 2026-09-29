package com.cycling.beevideo.ui.player

import android.Manifest
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.os.Build
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.cycling.beevideo.R
import com.cycling.beevideo.domain.model.PlaybackState
import com.cycling.beevideo.domain.repository.ContentRepository
import com.cycling.beevideo.ui.components.LoadState
import com.cycling.beevideo.ui.components.loadState
import kotlinx.coroutines.delay

/**
 * 播放页。`vod_play_url` 未必是地址（spider 源常给内部 id），兑换与起播都在
 * 持有者（`PlayerPlaybackState`）里，本页只管把详情推进去、把画面画出来。
 *
 * 持有者从 [coordinator] 取：它是 App 级的，退到后台再回来还是同一个。
 *
 * 退出这件事分两段：**返回键**退回 App 内的上一页、画面交给应用内小窗（`MiniPlayer`）；
 * **按 Home** 是否缩成系统小窗由设置里的画中画开关定（`PlayerPip`）。
 */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun PlayerScreen(
    content: ContentRepository,
    coordinator: PlaybackCoordinator,
    vodId: String,
    lineIndex: Int,
    episodeIndex: Int,
    /** 在小窗里。PiP 缩的是整个窗口，所以"只剩画面"这件事得在这一层做。 */
    inPipMode: Boolean = false,
    onBack: () -> Unit,
) {
    // 通知栏媒体控制要通知权限（API 33+ 默认不给）。不申请的话整套通知栏静默失效，
    // 用户只会觉得"功能没做"
    RequestNotificationPermission()

    // 同一部片复用同一个持有者：重进页面不该把正在放的东西重头来一遍
    val state = coordinator.attach(vodId, lineIndex, episodeIndex)

    val detailState = loadState(vodId) { content.detail(vodId) }
    val vod = (detailState as? LoadState.Ready)?.value

    // 详情就绪 → 推进持有者 → 应用路由起点 → 起播。键含 vod 本身：加载完成的那次重组才会触发
    //
    // ⚠️ 顺序不能反：同一部片复用持有者时（看完第 3 集返回、又点「第 05 集」进来），
    // 先起播会把上一次那一集又拉起来，用户先看见第 3 集闪一下再跳到第 5 集。
    // ⚠️ `vod` 为 null 时不 bind：会把复用中的持有者的快照抹掉，而它此刻可能正在播 ——
    // 那一下的落库写进去的就是一条空片名。
    LaunchedEffect(vod, lineIndex, episodeIndex) {
        vod?.let(state::bind)
        state.applyRoute(lineIndex, episodeIndex)
        state.ensurePlaying()
    }

    // 线路号取自持有者，不是路由参数：路由参数进来就定死，靠它换线路会重建整个会话
    val line = vod?.lines?.getOrNull(state.lineIndex)
    val episodes = line?.episodes.orEmpty()
    val safeIndex = state.episodeIndex
    val current = episodes.getOrNull(safeIndex)

    // 被系统回收的话，距上次周期上报的进度就没了
    LifecycleEventEffect(Lifecycle.Event.ON_STOP) { state.onStop() }

    val playbackState by state.state.collectAsStateWithLifecycle()
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

    // ⚠️ 布局判据用真实方向而非点击意图：requestedOrientation 是异步的，
    // 按意图切布局会让竖屏窗口把画面压成中间一条。
    // 小窗里也算全屏 —— 顶栏与选集在那块巴掌大的窗口里没有容身之处。
    val isLandscape =
        LocalConfiguration.current.orientation == Configuration.ORIENTATION_LANDSCAPE
    val fullscreenLayout = isLandscape || inPipMode

    val context = LocalContext.current

    // 开关是用户的（设置页，默认关），能力是设备的 —— 两个都要
    val pipSwitch by coordinator.pipEnabled.collectAsStateWithLifecycle()
    val pipEnabled = pipSwitch && remember { PlayerPip.isSupported(context) }

    // 只有播放页与小窗允许"按 Home 就缩成系统小窗"。放在这里而不是导航宿主：那个只组合一次，
    // `remember` 会一直拿着进设置页之前的旧值（见 `PipAutoEnterEffect`）
    PipAutoEnterEffect(shouldAutoEnterPip(currentState, pipEnabled))

    /*
     * 退出播放页 = **退回 App 内的上一页**，永远不退到手机桌面。
     *
     * ⚠️ 这里**不**调 `enterPictureInPictureMode`：那会把整个 App 缩成一块浮在手机桌面上的
     * 窗口 —— 用户按一下返回就被踢出 App 了。系统 PiP 只负责"离开 App"那一段（按 Home）。
     *
     * 开关开着才把小窗叫出来；关着（默认）就是"退出播放页 = 停止播放"。
     */
    val leavePlayer: () -> Unit = {
        if (pipEnabled) {
            coordinator.showMiniPlayer()
        } else {
            coordinator.hideMiniPlayer()
            state.pauseAndSave()
        }
        onBack()
    }

    // 全屏里返回 = 退出全屏；否则 = 退出播放页。PiP 里两个都不接管，交给系统关窗口
    BackHandler(enabled = !inPipMode && fullscreenIntent) { fullscreenIntent = false }
    BackHandler(enabled = !inPipMode && !fullscreenIntent) { leavePlayer() }

    val systemControls = rememberPlayerSystemControls()

    PlayerScaffold(
        state = PlayerUiState(
            title = vod?.name ?: stringResource(R.string.player_fallback_title),
            lineName = line?.name.orEmpty(),
            episodeName = current?.name ?: stringResource(R.string.player_no_episode),
            lines = vod?.lines.orEmpty(),
            currentLineIndex = state.lineIndex,
            episodes = episodes,
            currentIndex = safeIndex,
        ),
        onSelectLine = state::selectLine,
        onSelectEpisode = state::selectEpisode,
        onPrev = { if (safeIndex > 0) state.selectEpisode(safeIndex - 1) },
        onNext = { if (safeIndex < episodes.lastIndex) state.selectEpisode(safeIndex + 1) },
        onBack = leavePlayer,
        isFullscreen = fullscreenLayout,
        player = { modifier ->
            PlaybackSurface(
                session = state.session,
                isBuffering = !inPipMode &&
                    currentState is PlaybackState.Buffering &&
                    !controlsVisible,
                modifier = modifier,
            ) {
                // 小窗里系统自带展开/关闭按钮，再画一层控件只会挤成一团
                if (!inPipMode) {
                    // 手势层在最下面是刻意的：控件先消费事件，剩下的才落回手势层
                    PlayerGestureLayer(
                        positionMs = state.positionMs,
                        durationMs = state.durationMs,
                        systemControls = systemControls,
                        onTap = { controlsVisible = !controlsVisible },
                        onSeek = state::seekTo,
                        onFeedback = { gestureFeedback = it },
                    )
                    PlayerControls(
                        visible = controlsVisible,
                        isPlaying = isPlaying,
                        positionMs = state.positionMs,
                        durationMs = state.durationMs,
                        speed = state.speed,
                        // 图标跟意图走：转屏要几百毫秒，跟布局走按钮看着像没按下去
                        isFullscreen = fullscreenIntent,
                        onToggleFullscreen = { fullscreenIntent = !fullscreenIntent },
                        onPlayPause = state::togglePlayPause,
                        onSeek = state::seekTo,
                        onSpeedChange = state::selectSpeed,
                    )
                    PlayerGestureOverlay(
                        feedback = gestureFeedback,
                        modifier = Modifier.align(Alignment.Center),
                    )
                }
            }
        },
    )
}

/** 通知权限只在进播放页时问一次；拒绝就拒绝，不再纠缠（连播照常，只是没通知栏）。 */
@Composable
private fun RequestNotificationPermission() {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return
    val context = LocalContext.current
    val launcher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) {}
    LaunchedEffect(Unit) {
        val granted = ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.POST_NOTIFICATIONS,
        ) == PackageManager.PERMISSION_GRANTED
        if (!granted) launcher.launch(Manifest.permission.POST_NOTIFICATIONS)
    }
}
