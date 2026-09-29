package com.cycling.beevideo.ui.preview

import com.cycling.beevideo.domain.repository.IncognitoMode
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * 预览与单测用的无痕开关。
 *
 * 只有内存状态：真实实现那个"关闭时删临时数据"的收尾在这里是**故意没有**的 ——
 * 它要碰文件系统，而假实现的存在意义正是让状态持有者能在纯 JVM 里跑起来。
 */
class FakeIncognitoMode(initial: Boolean = false) : IncognitoMode {

    private val _enabled = MutableStateFlow(initial)

    override val enabled: StateFlow<Boolean> = _enabled.asStateFlow()

    override fun set(enabled: Boolean) {
        _enabled.value = enabled
    }
}
