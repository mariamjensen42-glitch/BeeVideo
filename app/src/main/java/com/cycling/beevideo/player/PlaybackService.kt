package com.cycling.beevideo.player

import android.app.PendingIntent
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.util.Log
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import androidx.media3.session.SessionCommand
import androidx.media3.session.SessionResult
import com.cycling.beevideo.BeeApplication
import com.cycling.beevideo.MainActivity
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture

/**
 * 播放器的新家：通知栏 / 锁屏 / 耳机键的系统级控制全靠「内核住在会话服务里」这件事，
 * 界面只是它的一只控制器。**离开播放页不收场** —— 音频继续，换集与落进度也继续，
 * 那是界面外的策略（`PlayerPlaybackState`），它比页面活得久。
 *
 * 播放器在 `onCreate` 建：控制器一连（播放页第一次访问）服务才起，那时建不亏；
 * `onCreate` 里不建的话 `onGetSession` 无从交差（会话必须握着一个 Player）。
 */
class PlaybackService : MediaSessionService() {

    private lateinit var exoPlayer: ExoPlayer
    private var session: MediaSession? = null

    override fun onCreate() {
        super.onCreate()
        // 解码器偏好传提供者：MediaCodecSelector 每次选解码器时现读，改设置重新起播就生效
        exoPlayer = PlayerFactory.newPlayer(this) {
            (application as BeeApplication).playback.decoderPreference
        }.also(DecoderUsage::observeDecoders)
        session = MediaSession.Builder(this, exoPlayer)
            .setSessionActivity(
                PendingIntent.getActivity(
                    this,
                    0,
                    Intent(this, MainActivity::class.java),
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
                )
            )
            .setCallback(Callback())
            .build()
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession? = session

    override fun onDestroy() {
        // 先 release 播放器再 release 会话：反过来的话收尾期间播放器还可能回调进会话
        exoPlayer.release()
        session?.release()
        session = null
        super.onDestroy()
    }

    private inner class Callback : MediaSession.Callback {

        override fun onConnect(
            session: MediaSession,
            controller: MediaSession.ControllerInfo,
        ): MediaSession.ConnectionResult =
            // 无参构造；(session) 那个重载已废弃
            MediaSession.ConnectionResult.AcceptedResultBuilder()
                .setAvailableSessionCommands(
                    MediaSession.ConnectionResult.DEFAULT_SESSION_COMMANDS.buildUpon()
                        .add(SessionCommand(PlayCommand.ACTION, Bundle.EMPTY))
                        .build()
                )
                // ⚠️ 两条命令行**必须各设一次**：无参构造给的是 `EMPTY`/`EMPTY`（不是默认集），
                // 只设 session 命令的话控制器手上一条 player 命令都没有 —— 而且**全程静默**
                // （命令未授权时 BasePlayer 直接丢，不抛不报）。症状：有声音、画面全黑、
                // 暂停/拖动/倍速全无反应、进度条永远 00:00，`setVideoSurfaceView` 也是命令。
                .setAvailablePlayerCommands(MediaSession.ConnectionResult.DEFAULT_PLAYER_COMMANDS)
                .build()

        override fun onCustomCommand(
            session: MediaSession,
            controller: MediaSession.ControllerInfo,
            command: SessionCommand,
            args: Bundle,
        ): ListenableFuture<SessionResult> {
            if (command.customAction != PlayCommand.ACTION) {
                return Futures.immediateFuture(
                    SessionResult(SessionResult.RESULT_ERROR_NOT_SUPPORTED)
                )
            }
            open(args)
            return Futures.immediateFuture(SessionResult(SessionResult.RESULT_SUCCESS))
        }

        /** 与直连 ExoPlayer 时代同一套编排：显式 MIME、先 seek 再 prepare。 */
        private fun open(args: Bundle) {
            val target = PlayCommand.asTarget(args)
            if (target.url.isEmpty()) {
                Log.w(TAG, "起播被拒：地址为空")
                return
            }
            val playing = PlayCommand.nowPlaying(args)
            val mime = PlayCommand.mime(args)
            val cache = MediaCacheProvider.get(
                this@PlaybackService,
                PlayCommand.quotaBytes(args),
                PlayCommand.incognito(args),
            )
            Log.i(
                TAG,
                "起播 缓存=${if (cache == null) "关" else "开"}" +
                    " mime=${mime ?: "(未指定)"} url=${target.url}",
            )
            exoPlayer.setMediaSource(
                PlayerFactory.mediaSourceFactory(PlayCommand.headers(args), cache).createMediaSource(
                    MediaItem.Builder()
                        .setUri(target.url)
                        // ⚠️ MIME 必须显式给，理由见 MediaMime 的注释
                        .setMimeType(mime)
                        .setMediaMetadata(
                            MediaMetadata.Builder()
                                .setTitle(playing.title)
                                .setArtist(playing.subtitle)
                                .setArtworkUri(playing.artworkUri?.let(Uri::parse))
                                .build()
                        )
                        .build(),
                ),
            )
            exoPlayer.seekTo(PlayCommand.resumeAtMs(args))
            exoPlayer.prepare()
            exoPlayer.play()
        }
    }

    private companion object {
        const val TAG = "BeePlayer"
    }
}
