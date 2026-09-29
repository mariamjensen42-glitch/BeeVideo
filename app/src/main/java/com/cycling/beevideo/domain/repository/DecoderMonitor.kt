package com.cycling.beevideo.domain.repository

import com.cycling.beevideo.domain.model.DecoderInUse
import kotlinx.coroutines.flow.StateFlow

/**
 * 「这次播放实际用的是哪个解码器」的观察口。
 *
 * 偏好只是偏好 —— 设备没有对应软件解码器时内核会静默回落硬解。没有这个口子，
 * 用户「切了软解还是卡」就会归咎于源站，而这个项目对静默失败的态度是不允许它存在。
 *
 * 上报方在播放内核（`player`），读方只有设置页，所以做成进程级的只读流。
 */
interface DecoderMonitor {

    /** 最近一次初始化的解码器；null = 本次运行还没播过东西。 */
    val inUse: StateFlow<DecoderInUse?>
}
