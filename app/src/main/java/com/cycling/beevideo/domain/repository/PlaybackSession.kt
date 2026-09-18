package com.cycling.beevideo.domain.repository

import com.cycling.beevideo.domain.model.PlayTarget
import com.cycling.beevideo.domain.model.PlaybackState
import kotlinx.coroutines.flow.StateFlow

/**
 * 一次播放会话 —— 给地址与请求头，起播、报状态、报位置、收场。
 *
 * 「会话」是关键：[实现]活得不比它的宿主短，界面重建不打断它。
 *
 * ─── 为什么要有这个端口 ────────────────────────────────────────────────
 * `player/` 过去只提供**构造**（`newPlayer` / `mediaSourceFactory` /
 * `mimeTypeOfPlayUrl`），三个都是 object 或顶层函数，**没有任何接口** ——
 * 而内核形状的知识（位置、时长、Listener、release 顺序、缓冲）全都漏在
 * 813 行的 `PlayerScreen` 里。于是换内核要重写界面，而播放页**一个测试都没有**
 * （要跑它需要设备 + 网络 + 真 ExoPlayer）。
 *
 * 这个接口就是那条 seam：`Media3PlaybackSession` 是一个 adapter，
 * 将来的 libVLC 是第二个 —— 那时它才从"假设的 seam"变成真的。
 *
 * ─── 刻意小 ───────────────────────────────────────────────────────────
 * 五个成员。**没有**剧集标识：会话不需要知道自己在播哪一集，那是宿主的账
 * （宿主把集号连同线路名一起记进观看进度）。多一个字段就多一处要对齐的状态。
 *
 * ─── 谁负责什么 ───────────────────────────────────────────────────────
 * - **会话**：内核事实。位置、时长、状态、起播与释放。
 * - **宿主**（状态持有者）：策略。什么时候上报进度（周期 / 退到后台 / 离开页面 /
 *   切集前），这些是"什么时候该做"，不是内核知识。
 * - **观看进度仓储**：节流。多久落一次库是存储侧的知识（见 `LibraryRepository`）。
 */
interface PlaybackSession {

    /** 内核当前状态。界面按它分支，不去问内核。 */
    val state: StateFlow<PlaybackState>

    /**
     * 起播一个播放目标；已载入时等于换集。
     *
     * **不会抛异常**：起不来（空地址 / 需要站外解析 / 内核报错）一律转成
     * [PlaybackState.Failed]。[PlayTarget.parse] 为真时**不交给内核**。
     *
     * @param resumeAtMs 从这个位置开始（0 = 从头）。判定归调用方 ——
     *   「线路 + 集号对得上才续播」是 `resumePositionMs` 的知识，会话不重复实现它。
     */
    fun open(target: PlayTarget, resumeAtMs: Long)

    /**
     * 当前播放位置（毫秒）。
     *
     * ⚠️ **`close()` 之后仍然返回关闭前最后一次读到的位置。** 卸载内核会把内核内部
     * 的位置清零，而"退出播放页时把进度记下来"恰恰发生在 `close()` 之后。
     * 没有这条保证，调用方就必须记住"先读位置再 close"，而漏掉它的表现是
     * **返回时进度永远记成 0** —— 看起来像"进度记不住"，极难定位。
     */
    fun positionMs(): Long

    /** 当前媒体的时长（毫秒）。**0 = 来源没给时长**（部分直链与 m3u8 就是这样）。 */
    fun durationMs(): Long

    /**
     * 释放内核。幂等。
     *
     * **不负责落进度** —— 那是宿主的策略：它知道这一刻是"退出"还是"切集"。
     * 会话只保证 [positionMs] 在这之后还读得到。
     */
    fun close()
}
