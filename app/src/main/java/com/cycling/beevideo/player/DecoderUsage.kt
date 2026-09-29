package com.cycling.beevideo.player

import android.util.Log
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.analytics.AnalyticsListener
import com.cycling.beevideo.domain.model.DecoderInUse
import com.cycling.beevideo.domain.repository.DecoderMonitor
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * [DecoderMonitor] 的实现：**进程级单例**。
 *
 * 播放器现在住在服务里，而读它的设置页在另一条栈上 —— 两边必须拿到同一份，
 * 所以只能是单例，不能跟着某个会话/服务走。
 */
object DecoderUsage : DecoderMonitor {

    private const val TAG = "BeePlayer"

    private val _inUse = MutableStateFlow<DecoderInUse?>(null)
    override val inUse: StateFlow<DecoderInUse?> = _inUse.asStateFlow()

    /** 装上解码器初始化的监听。播放器建好之后调一次。 */
    fun observeDecoders(player: ExoPlayer) {
        player.addAnalyticsListener(listener)
    }

    private val listener = object : AnalyticsListener {
        override fun onVideoDecoderInitialized(
            eventTime: AnalyticsListener.EventTime,
            decoderName: String,
            initializationDurationMs: Long,
        ) {
            // 视频 / 音频各报各的，界面要的是「哪个解码器在干活」，取视频那个
            Log.i(TAG, "视频解码器：$decoderName")
            _inUse.value = DecoderInUse(decoderName)
        }
    }
}
