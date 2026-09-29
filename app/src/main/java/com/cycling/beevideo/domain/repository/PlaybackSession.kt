package com.cycling.beevideo.domain.repository

import com.cycling.beevideo.domain.model.PlayRequest
import com.cycling.beevideo.domain.model.PlaybackState
import kotlinx.coroutines.flow.StateFlow

/**
 * 一次播放会话：给地址与请求头，起播、报状态、报位置、收场。
 * 「会话」是关键 —— 实现活得不比宿主短，界面重建不打断它。
 *
 * 这条 seam 让内核形状的知识（位置 / 时长 / Listener / release 顺序）不再漏在界面里，
 * `MediaControllerPlaybackSession` 是 adapter，将来换内核只需再写一个。
 *
 * 职责划分：**会话**管内核事实；**宿主**管策略（什么时候上报进度）；
 * **进度仓储**管节流（多久落一次库）。
 */
interface PlaybackSession {

    val state: StateFlow<PlaybackState>

    /**
     * 起播一个播放目标；已载入时等于换集。
     *
     * **不抛异常**：起不来一律转成 [PlaybackState.Failed]；
     * [com.cycling.beevideo.domain.model.PlayTarget.parse] 为真时不交给内核。
     */
    fun open(request: PlayRequest)

    /**
     * 当前播放位置（毫秒）。
     *
     * ⚠️ **`close()` 之后仍返回最后一次读到的位置**：卸载内核会把它清零，而
     * "退出播放页时落进度"恰恰发生在 close 之后。没有这条保证，症状是**进度永远记成 0**。
     */
    fun positionMs(): Long

    /** 时长（毫秒）。**0 = 来源没给时长**（部分直链与 m3u8 就是这样）。 */
    fun durationMs(): Long

    /**
     * 在"播"与"停"之间翻转。
     *
     * 判断留在内核侧：只有它分得清 Buffering 是"正在等首帧"（该停）还是
     * "暂停后又进了一次缓冲"（该播），界面按 state 反推必错一半。
     */
    fun togglePlayPause()

    /**
     * 明确暂停。**不是 toggle 的另一种写法** —— 调用方要的是"停下来"，
     * 而它往往发生在用户已经不看画面的时候（离开播放页又进不了小窗），
     * 那时状态是什么没人知道，翻转一下有可能把它翻成"继续放"。
     */
    fun pause()

    /** ⚠️ 拖拽中不要每帧调（HLS 每次都要重新定位分片），松手时调一次。 */
    fun seekTo(positionMs: Long)

    /** `1f` = 原速。 */
    fun setSpeed(speed: Float)

    /** 读回来而不是让界面自己记：倍速是内核级的，换集不重置它。 */
    fun speed(): Float

    /** 释放内核，幂等。**不负责落进度** —— 那是宿主的策略。 */
    fun close()
}
