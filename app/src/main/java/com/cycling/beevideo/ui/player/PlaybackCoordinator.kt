package com.cycling.beevideo.ui.player

import android.content.Context
import com.cycling.beevideo.domain.model.PlaybackState
import com.cycling.beevideo.domain.model.parseCustomHeaders
import com.cycling.beevideo.domain.repository.ContentRepository
import com.cycling.beevideo.domain.repository.IncognitoMode
import com.cycling.beevideo.domain.repository.LibraryRepository
import com.cycling.beevideo.domain.repository.PlaybackSettings
import com.cycling.beevideo.player.MediaControllerPlaybackSession
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * 播放会话的 **App 级宿主**。
 *
 * 播放器住在服务里（`PlaybackService`），离开页面音频继续 —— 所以"当前在看什么"
 * 不能跟着页面走：换集、落进度、自动连播都得在页面没了之后照常发生。页面只是
 * 往这里挂一个持有者（`PlayerPlaybackState`），挂上了就显示、退了也不拆。
 */
class PlaybackCoordinator(
    context: Context,
    private val content: ContentRepository,
    private val library: LibraryRepository,
    private val settings: PlaybackSettings,
    private val incognito: IncognitoMode,
    private val scope: CoroutineScope,
) {

    /**
     * 会话懒建：控制器一连，服务就起来了。放进播放页的第一次访问再建，
     * 别让一次随手打开 App 也把服务与连接拉起来。
     */
    val session: MediaControllerPlaybackSession by lazy {
        MediaControllerPlaybackSession(
            context = context,
            // 每次起播现读：改设置重新起播就生效，不用重开会话
            customHeaders = { parseCustomHeaders(settings.customHeaderText) },
            quotaBytes = { if (settings.cacheEnabled) settings.cacheQuotaBytes else 0L },
            incognito = { incognito.enabled.value },
        )
    }

    /**
     * 退出播放页要不要**继续放**（应用内小窗；按 Home 还缩成系统小窗）。
     *
     * ⚠️ 它**不管返回键要不要退栈** —— 返回键一律退回 App 内的上一页。开关关着时
     * 退栈的同时落进度并暂停（"退出播放页 = 停止播放"）。
     *
     * ⚠️ 读的是流不是一次性取值：设置页拨完开关，播放页与小窗都得**当场**跟着变，
     * 否则症状就是"开关关了，小窗还在"。
     */
    val pipEnabled: StateFlow<Boolean> get() = settings.pictureInPicture

    private var holder: PlayerPlaybackState? = null

    private val _current = MutableStateFlow<PlayerPlaybackState?>(null)

    /** 当前在放的是哪一部。迷你窗靠它知道自己该不该出现、显示什么、点了往哪跳。 */
    val current: StateFlow<PlayerPlaybackState?> = _current.asStateFlow()

    private val _miniVisible = MutableStateFlow(false)

    /**
     * 用户没有把小窗关掉。
     *
     * ⚠️ 它只是"要不要显示"的一半：另一半是"当前不在播放页"（在播放页时画面归页面）。
     * 所以进播放页不用清它 —— 回来再退出去，小窗还在。
     */
    val miniVisible: StateFlow<Boolean> = _miniVisible.asStateFlow()

    /** 退出播放页：把画面交给应用内小窗（在 `PlayerScreen` 的返回那条路上调）。 */
    fun showMiniPlayer() {
        _miniVisible.value = true
    }

    /** 用户关掉了小窗。**只负责隐藏**，暂停与否由调用方定。 */
    fun hideMiniPlayer() {
        _miniVisible.value = false
    }

    /**
     * 播放页进来时挂上持有者。同一部片复用同一个 —— 重进页面不该把正在放的东西
     * 重头来一遍；换了片（或上一部已经放挂了），旧的那份先落库再退役。
     *
     * ⚠️ 复用那一支**不看** [lineIndex]/[episodeIndex]：它们是"用户从哪儿进来"，
     * 只在新建持有者时作初值。进来的人要跳集得自己调 [PlayerPlaybackState.applyRoute]
     * （在 `LaunchedEffect` 里，见 `PlayerScreen`）—— 这里做会落在组合期。
     */
    fun attach(vodId: String, lineIndex: Int, episodeIndex: Int): PlayerPlaybackState {
        holder?.let { existing ->
            if (existing.vodId == vodId && existing.state.value !is PlaybackState.Failed) {
                return existing
            }
            existing.detach()
        }
        return PlayerPlaybackState(
            vodId = vodId,
            session = session,
            content = content,
            library = library,
            initialEpisodeIndex = episodeIndex,
            initialLineIndex = lineIndex,
            autoPlayNext = settings.autoPlayNext,
            parentScope = scope,
        ).also {
            holder = it
            _current.value = it
        }
    }

    /** 进程收场（服务销毁 / 单测）。正常使用中不会走到。 */
    fun release() {
        holder?.detach()
        holder = null
        _current.value = null
        session.close()
    }

    /**
     * 用户已经不看着画面了（系统小窗被关掉）：落一次进度再暂停，**不退役**。
     *
     * 调用方是 Activity 而不是播放页 —— 那一刻播放页很可能已经不在组合里了。
     */
    fun pauseForExit() {
        holder?.pauseAndSave()
    }
}
