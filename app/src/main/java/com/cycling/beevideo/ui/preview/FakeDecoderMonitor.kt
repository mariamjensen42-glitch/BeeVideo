package com.cycling.beevideo.ui.preview

import com.cycling.beevideo.domain.model.DecoderInUse
import com.cycling.beevideo.domain.repository.DecoderMonitor
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * 预览与 JVM 测试用的假解码器观察。
 *
 * 默认给一个真机样子的硬解名字 —— 预览要的是「那一行长什么样」，不是 null。
 */
class FakeDecoderMonitor(
    initial: DecoderInUse? = DecoderInUse("c2.qti.avc.decoder"),
) : DecoderMonitor {

    private val _inUse = MutableStateFlow(initial)
    override val inUse: StateFlow<DecoderInUse?> = _inUse.asStateFlow()

    fun report(name: String) {
        _inUse.value = DecoderInUse(name)
    }
}
