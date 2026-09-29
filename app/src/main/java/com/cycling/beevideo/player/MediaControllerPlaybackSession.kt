package com.cycling.beevideo.player

import android.content.ComponentName
import android.content.Context
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.session.MediaController
import androidx.media3.session.SessionCommand
import androidx.media3.session.SessionToken
import com.cycling.beevideo.domain.model.PlayRequest
import com.cycling.beevideo.domain.model.PlaybackFailure
import com.cycling.beevideo.domain.model.PlaybackState
import com.cycling.beevideo.domain.model.withCustomHeaders
import com.cycling.beevideo.domain.repository.PlaybackSession
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * [PlaybackSession] 的 Media3 会话实现：**界面侧只握一只 [MediaController]**，
 * 内核在 [PlaybackService] 里。
 *
 * 与旧直连 ExoPlayer 的实现比，差别只有一条：连接是**异步**的。`open` 在连上之前
 * 会被记下来，连上那一刻补发 —— 对调用方来说顺序不变，只是第一次起播多等一拍
 * （服务进程同App，连接是毫秒级）。
 *
 * 「`close()` 之后仍读得到最后位置」的约定不变：释放控制器会把位置清零，而落进度
 * 恰恰发生在 close 之后。
 */
class MediaControllerPlaybackSession(
    context: Context,
    /** 用户自定义请求头的兜底。每次起播现读：改设置不用重开会话。 */
    private val customHeaders: () -> Map<String, String>,
    /** 缓存配额与无痕。同样每次起播现读。 */
    private val quotaBytes: () -> Long,
    private val incognito: () -> Boolean,
) : PlaybackSession {

    private val appContext = context.applicationContext

    private val controllerFuture = MediaController.Builder(
        appContext,
        SessionToken(appContext, ComponentName(appContext, PlaybackService::class.java)),
    ).buildAsync()

    @Volatile
    private var controller: MediaController? = null

    private val _state = MutableStateFlow<PlaybackState>(PlaybackState.Idle)
    override val state: StateFlow<PlaybackState> = _state.asStateFlow()

    /**
     * 已连接的控制器。连接是异步的，而 `PlayerView` 必须等它到位才绑得上 ——
     * 画面槽读这条流，读不到就先空着（那个空档只有几十毫秒，且落在进页面最前面）。
     */
    private val _player = MutableStateFlow<Player?>(null)
    val playerFlow: StateFlow<Player?> = _player.asStateFlow()

    // 控制器在连接建立前收到的请求先记着，连上那一刻补发
    private var pendingPlay: PlayRequest? = null

    // 释放控制器会把位置清零，而落进度发生在 close 之后，快照自己留着
    @Volatile
    private var lastPositionMs = 0L

    @Volatile
    private var lastDurationMs = 0L

    @Volatile
    private var closed = false

    private val listener = object : Player.Listener {

        override fun onPlaybackStateChanged(playbackState: Int) {
            _state.value = when (playbackState) {
                Player.STATE_BUFFERING -> PlaybackState.Buffering
                Player.STATE_READY ->
                    if (controller?.isPlaying == true) PlaybackState.Playing else PlaybackState.Paused

                // ⚠️ 不能并进 Idle：并了之后"播完了"和"还没起播"同态，自动下一集无从下手
                Player.STATE_ENDED -> PlaybackState.Ended
                else -> PlaybackState.Idle
            }
        }

        override fun onIsPlayingChanged(isPlaying: Boolean) {
            // 只在 READY 时改状态：起播前后 ExoPlayer 会来回翻这个标志，跟着走状态会抖动
            val c = controller ?: return
            if (c.playbackState != Player.STATE_READY) return
            _state.value = if (isPlaying) PlaybackState.Playing else PlaybackState.Paused
        }

        override fun onPlayerError(error: PlaybackException) {
            _state.value = PlaybackState.Failed(
                reason = PlaybackFailure.Kernel,
                // 原始报错含一堆解码器细节，只取最后一段说明性文本
                detail = error.cause?.message ?: error.errorCodeName,
            )
        }
    }

    init {
        controllerFuture.addListener(
            {
                val c = runCatching { controllerFuture.get() }.getOrNull()
                if (c == null) {
                    // 连不上（服务被系统杀得比建快）比播不了更糟，但至少要让状态说得通
                    _state.value = PlaybackState.Failed(PlaybackFailure.Kernel, detail = null)
                    return@addListener
                }
                controller = c
                _player.value = c
                c.addListener(listener)
                pendingPlay?.let { send(it) }
                pendingPlay = null
            },
            // 回调必须落在有 Looper 的线程上（控制器要求主线程）
            LooperExecutor(Looper.getMainLooper()),
        )
    }

    override fun open(request: PlayRequest) {
        // 两类地址不交给内核：硬塞进去的结果是黑屏和一条看不懂的解码错误
        if (request.target.url.isEmpty()) {
            _state.value = PlaybackState.Failed(PlaybackFailure.NoAddress, detail = null)
            return
        }
        if (request.target.parse) {
            _state.value = PlaybackState.Failed(PlaybackFailure.RequiresExternalParser, detail = null)
            return
        }
        val c = controller
        if (c == null) {
            pendingPlay = request
            _state.value = PlaybackState.Buffering
            return
        }
        send(request)
    }

    private fun send(request: PlayRequest) {
        val headers = withCustomHeaders(
            source = request.target.headers,
            custom = customHeaders(),
        )
        controller?.sendCustomCommand(
            SessionCommand(PlayCommand.ACTION, Bundle.EMPTY),
            PlayCommand.bundle(
                url = request.target.url,
                parse = request.target.parse,
                headers = headers,
                mime = mimeTypeOfPlayUrl(request.target.url),
                resumeAtMs = request.resumeAtMs,
                nowPlaying = request.nowPlaying,
                quotaBytes = quotaBytes(),
                incognito = incognito(),
            ),
        )
    }

    override fun positionMs(): Long {
        if (!closed) {
            val live = controller?.currentPosition ?: 0L
            if (live > 0L) lastPositionMs = live
        }
        return lastPositionMs
    }

    override fun durationMs(): Long {
        if (!closed) {
            val live = controller?.duration ?: 0L
            // 来源没给时长时是 C.TIME_UNSET（负数），归一成 0 = "不知道"
            if (live > 0L) lastDurationMs = live
        }
        return lastDurationMs
    }

    override fun close() {
        if (closed) return
        // 先各读一次：这就是"关掉之后还读得到"的那份快照
        positionMs()
        durationMs()
        closed = true
        controller?.removeListener(listener)
        MediaController.releaseFuture(controllerFuture)
        controller = null
        _player.value = null
    }

    override fun togglePlayPause() {
        val c = controller ?: return
        // 按 playWhenReady 而不是 isPlaying：缓冲时只有前者等于"用户想不想播"
        if (c.playWhenReady) c.pause() else c.play()
    }

    override fun pause() {
        controller?.pause()
    }

    override fun seekTo(positionMs: Long) {
        // ⚠️ 先写快照再 seek：currentPosition 在 seek 落地前仍读得到旧值，界面下一帧
        // 会拿它画进度条、被拖回去一下。拖到 0 时还要绕开 positionMs 里 live > 0 的守卫
        lastPositionMs = positionMs
        controller?.seekTo(positionMs)
    }

    override fun setSpeed(speed: Float) {
        controller?.setPlaybackSpeed(speed)
    }

    // 倍速落在 player 级的 playbackParameters 上，换集不会清掉，
    // 所以"这一集调了 1.5x，下一集还是 1.5x"，界面必须从内核读回来
    override fun speed(): Float = controller?.playbackParameters?.speed ?: 1f

    private class LooperExecutor(looper: Looper) : java.util.concurrent.Executor {
        private val handler = Handler(looper)
        override fun execute(command: Runnable) {
            handler.post(command)
        }
    }
}
