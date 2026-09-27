package com.cycling.beevideo.player

import android.content.Context
import android.util.Log
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.datasource.cache.Cache
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.MediaSource
import com.cycling.beevideo.domain.model.PlayTarget
import com.cycling.beevideo.domain.model.PlaybackFailure
import com.cycling.beevideo.domain.model.PlaybackState
import com.cycling.beevideo.domain.repository.PlaybackSession
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * [PlaybackSession] 的 Media3 / ExoPlayer 实现：建播放器、按请求头建媒体源、
 * 显式指定 MIME、seek + prepare、注册 Listener、读位置与时长、以及"先取位置再释放"的顺序。
 *
 * 不用协程：ExoPlayer 必须在有 Looper 的线程上创建与调用（实践上就是主线程），
 * [open] / [close] 跟着调用方线程走。
 */
class Media3PlaybackSession(
    context: Context,
    /** `null` = 这次不走磁盘缓存。 */
    private val cache: Cache?,
) : PlaybackSession {

    /**
     * 便捷装配：按**配额**自己取那个进程内唯一的缓存实例。
     * 有它播放页才不用 `import MediaCacheProvider` 也能把会话建起来。
     */
    constructor(context: Context, quotaBytes: Long) : this(
        context = context,
        cache = MediaCacheProvider.get(context, quotaBytes),
    )

    private val exoPlayer: ExoPlayer = PlayerFactory.newPlayer(context).apply {
        playWhenReady = true
    }

    /** 内核实例，**只给画面绑定用** —— 起播 / 状态 / 进度一律走本类的接口。 */
    val player: Player get() = exoPlayer

    private val _state = MutableStateFlow<PlaybackState>(PlaybackState.Idle)
    override val state: StateFlow<PlaybackState> = _state.asStateFlow()

    // release() 会把内核内部位置清零，而落进度发生在 close 之后，所以最后读到的值要自己留着
    @Volatile
    private var lastPositionMs = 0L

    @Volatile
    private var lastDurationMs = 0L

    @Volatile
    private var closed = false

    /** 请求头是**构建期**参数，变了就得换一套媒体源工厂。 */
    private var sourceFactory: MediaSource.Factory? = null
    private var headers: Map<String, String> = emptyMap()

    private val listener = object : Player.Listener {

        override fun onPlaybackStateChanged(playbackState: Int) {
            _state.value = when (playbackState) {
                Player.STATE_BUFFERING -> PlaybackState.Buffering
                Player.STATE_READY ->
                    if (exoPlayer.isPlaying) PlaybackState.Playing else PlaybackState.Paused
                // ⚠️ 不能并进 Idle：并了之后"播完了"和"还没起播"同态，自动下一集无从下手
                Player.STATE_ENDED -> PlaybackState.Ended
                else -> PlaybackState.Idle
            }
        }

        override fun onIsPlayingChanged(isPlaying: Boolean) {
            // 只在 READY 时改状态：起播前后 ExoPlayer 会来回翻这个标志，跟着走状态会抖动
            if (exoPlayer.playbackState != Player.STATE_READY) return
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
        exoPlayer.addListener(listener)
    }

    override fun open(target: PlayTarget, resumeAtMs: Long) {
        // 两类地址不交给内核：硬塞进去的结果是黑屏和一条看不懂的解码错误，转成说得清的状态
        if (target.url.isEmpty()) {
            _state.value = PlaybackState.Failed(PlaybackFailure.NoAddress, detail = null)
            return
        }
        if (target.parse) {
            _state.value = PlaybackState.Failed(
                PlaybackFailure.RequiresExternalParser,
                detail = null,
            )
            return
        }

        val mime = mimeTypeOfPlayUrl(target.url)
        // 排查播放问题的第一个抓手（adb logcat -s BeePlayer）
        Log.i(
            TAG,
            "起播 缓存=${if (cache == null) "关" else "开"}" +
                " 已用=${if (cache == null) "-" else "${cache.cacheSpace / 1024}KB"}" +
                " mime=${mime ?: "(未指定，交给 Media3)"} url=${target.url}",
        )

        exoPlayer.setMediaSource(
            factoryFor(target.headers).createMediaSource(
                MediaItem.Builder()
                    .setUri(target.url)
                    // ⚠️ MIME 必须显式给：Media3 只看 URI 最后一段路径猜，jar 那种
                    // /proxy?do=m3u8&… 会被猜成 OTHER → 报 UnrecognizedInputFormatException
                    .setMimeType(mime)
                    .build(),
            ),
        )
        // 先跳再 prepare：顺序反了会先从头起播再跳一下，用户能看见那次跳变
        exoPlayer.seekTo(resumeAtMs)
        exoPlayer.prepare()

        lastPositionMs = resumeAtMs
        _state.value = PlaybackState.Buffering
    }

    override fun positionMs(): Long {
        if (!closed) {
            val live = exoPlayer.currentPosition
            if (live > 0L) lastPositionMs = live
        }
        return lastPositionMs
    }

    override fun durationMs(): Long {
        if (!closed) {
            val live = exoPlayer.duration
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
        exoPlayer.removeListener(listener)
        exoPlayer.release()
    }

    override fun togglePlayPause() {
        // 按 playWhenReady 而不是 isPlaying：缓冲时只有前者等于"用户想不想播"
        if (exoPlayer.playWhenReady) exoPlayer.pause() else exoPlayer.play()
    }

    override fun seekTo(positionMs: Long) {
        // ⚠️ 先写快照再 seek：currentPosition 在 seek 落地前仍读得到旧值，界面下一帧
        // 会拿它画进度条、被拖回去一下。拖到 0 时还要绕开 positionMs 里 live > 0 的守卫
        lastPositionMs = positionMs
        exoPlayer.seekTo(positionMs)
    }

    override fun setSpeed(speed: Float) {
        exoPlayer.setPlaybackSpeed(speed)
    }

    // 倍速落在 player 级的 playbackParameters 上，setMediaSource 换集不会清掉，
    // 所以"这一集调了 1.5x，下一集还是 1.5x"，界面必须从内核读回来
    override fun speed(): Float = exoPlayer.playbackParameters.speed

    /** 按请求头取媒体源工厂，同一条线路内换集时请求头不变，所以不会重建。 */
    private fun factoryFor(requestHeaders: Map<String, String>): MediaSource.Factory {
        sourceFactory?.let { existing ->
            if (headers == requestHeaders) return existing
        }
        headers = requestHeaders
        return PlayerFactory.mediaSourceFactory(requestHeaders, cache).also { sourceFactory = it }
    }

    private companion object {
        const val TAG = "BeePlayer"
    }
}
