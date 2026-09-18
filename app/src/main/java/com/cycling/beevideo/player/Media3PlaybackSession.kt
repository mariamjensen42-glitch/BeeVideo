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
 * [PlaybackSession] 的 Media3 / ExoPlayer 实现。
 *
 * ─── 它搬走了什么 ─────────────────────────────────────────────────────
 * 以前这些东西全在 `PlayerScreen` 的 composable 里：建播放器、按请求头建媒体源、
 * 显式指定 MIME、`seekTo` + `prepare`、注册 `Player.Listener`、读位置与时长、
 * 以及"先取位置再释放"的顺序。它们都是**内核知识**，与界面无关 ——
 * 混在 composable 里的代价是这一层不可测，也没有第二个内核的落脚点。
 *
 * 缓冲策略、磁盘缓存、UA 补齐仍在 [PlayerFactory] 里（那是"播放知识"而不是
 * "内核驱动"），本类只负责把它装配到一个会话上。
 *
 * ─── 为什么不用协程 ───────────────────────────────────────────────────
 * ExoPlayer 必须在**有 Looper 的线程**上创建与调用（实践上就是主线程）。
 * [open] / [close] 是普通函数，跟着调用方的线程走 —— 也就是主线程，
 * 与之前的行为一致。这里不引入自己的作用域，就没有"关闭时还要取消谁"的问题。
 */
class Media3PlaybackSession(
    context: Context,
    /** `null` = 这次不走磁盘缓存（见 `MediaCacheProvider.get`）。 */
    private val cache: Cache?,
) : PlaybackSession {

    /**
     * 便捷装配：按**配额**自己取那个进程内唯一的缓存实例。
     *
     * 有它是因为"取缓存实例"是 `player/` 的知识（要 Media3 的 `SimpleCache`），
     * 而调用方（播放页）只该提供**策略**：开不开、配额多少。
     * 没有它的话播放页就得 `import player.MediaCacheProvider` 才能把会话建起来。
     *
     * `quotaBytes <= 0` 视为不用缓存（见 [MediaCacheProvider.get]）。
     */
    constructor(context: Context, quotaBytes: Long) : this(
        context = context,
        cache = MediaCacheProvider.get(context, quotaBytes),
    )

    private val exoPlayer: ExoPlayer = PlayerFactory.newPlayer(context).apply {
        playWhenReady = true
    }

    /**
     * 内核实例。**只给画面绑定用** —— 起播 / 状态 / 进度一律走本类的接口。
     *
     * 之所以要暴露它：Media3 的 `PlayerView` 必须拿到一个 `Player` 才能渲染，
     * 而"哪个内核配哪种视图"本来就是内核的事，藏不掉（见 `PlaybackSurface` 的说明）。
     */
    val player: Player get() = exoPlayer

    private val _state = MutableStateFlow<PlaybackState>(PlaybackState.Idle)
    override val state: StateFlow<PlaybackState> = _state.asStateFlow()

    /*
     * 关闭之后还要读得到的那份快照。
     *
     * `release()` 会把内核内部位置清零，而"退出播放页时落进度"发生在 close 之后 ——
     * 所以最后读到的那两个值必须自己留着（接口上写明了这条保证）。
     * `@Volatile` 是因为宿主可能在别的线程上读（它读的是接口，不保证同线程）。
     */
    @Volatile
    private var lastPositionMs = 0L

    @Volatile
    private var lastDurationMs = 0L

    @Volatile
    private var closed = false

    /** 当前请求头对应的媒体源工厂。请求头是**构建期**参数，变了就得换一套。 */
    private var sourceFactory: MediaSource.Factory? = null
    private var headers: Map<String, String> = emptyMap()

    private val listener = object : Player.Listener {

        override fun onPlaybackStateChanged(playbackState: Int) {
            _state.value = when (playbackState) {
                Player.STATE_BUFFERING -> PlaybackState.Buffering
                Player.STATE_READY ->
                    if (exoPlayer.isPlaying) PlaybackState.Playing else PlaybackState.Paused
                // STATE_IDLE / STATE_ENDED：没有可播的东西了
                else -> PlaybackState.Idle
            }
        }

        override fun onIsPlayingChanged(isPlaying: Boolean) {
            // 只在 READY 时改状态：起播前后 ExoPlayer 会来回翻这个标志，
            // 跟着它走会让状态在 Buffering 与 Playing 之间抖动
            if (exoPlayer.playbackState != Player.STATE_READY) return
            _state.value = if (isPlaying) PlaybackState.Playing else PlaybackState.Paused
        }

        override fun onPlayerError(error: PlaybackException) {
            _state.value = PlaybackState.Failed(
                reason = PlaybackFailure.Kernel,
                // 播放器原始报错含一堆解码器细节，只取最后一段说明性文本
                detail = error.cause?.message ?: error.errorCodeName,
            )
        }
    }

    init {
        exoPlayer.addListener(listener)
    }

    override fun open(target: PlayTarget, resumeAtMs: Long) {
        /*
         * 两类地址**不交给内核**。硬塞进去的结果分别是黑屏和一条看不懂的解码错误，
         * 而用户会以为是播放器坏了 —— 所以在这里就转成说得清的状态。
         */
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
        /*
         * 这一行是排查播放问题的**第一个抓手**（`adb logcat -s BeePlayer`）：
         * 缓存开没开、已用空间、判出来的 MIME、最终地址。它以前在 `PlayerScreen` 里，
         * 跟着内核知识一起搬了过来 —— 换内核时它该在新内核的实现里，而不是界面上。
         */
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
                    /*
                     * MIME **必须显式给**：Media3 自己猜只看 URI 最后一段路径，
                     * jar 那种 `http://127.0.0.1:9978/proxy?do=m3u8&url=…` 会被猜成
                     * OTHER → 走渐进式 → 报 `UnrecognizedInputFormatException`，
                     * 看起来像源站的流坏了。判据与理由见 [mimeTypeOfPlayUrl]。
                     */
                    .setMimeType(mime)
                    .build(),
            ),
        )
        // 先跳再 prepare：顺序反了会先从头起播、再跳一下，用户能看见那次跳变
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

    /**
     * 按请求头取媒体源工厂，能复用就复用。
     *
     * 同一条线路内换集时请求头不变，所以工厂不会被重建；换线路才换一套。
     */
    private fun factoryFor(requestHeaders: Map<String, String>): MediaSource.Factory {
        sourceFactory?.let { existing ->
            if (headers == requestHeaders) return existing
        }
        headers = requestHeaders
        return PlayerFactory.mediaSourceFactory(requestHeaders, cache).also { sourceFactory = it }
    }

    private companion object {
        /** 与 `MediaCache` 同一个 tag：播放相关的问题都看 `adb logcat -s BeePlayer`。 */
        const val TAG = "BeePlayer"
    }
}
