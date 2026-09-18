package com.cycling.beevideo.domain.repository

import com.cycling.beevideo.domain.model.ThemeMode
import kotlinx.coroutines.flow.StateFlow

/**
 * 外观（深浅色）设置的端口。
 *
 * ─── 为什么要暴露 [StateFlow]，而不是像 [PlaybackSettings] 那样裸 get/set ───
 * 设置页改一次值，**订阅方在另一棵树里** —— 界面配色由 `MainActivity`
 * 最外层的 `BeeVideoTheme` 决定，它在设置页之上好几层。
 *
 * 裸 get/set 也能"生效"，但只能靠 Activity 重建来生效；而重建会打断正在播的
 * 视频、丢掉导航栈，代价远大于收益。所以这里把值做成流，让主题层订阅它。
 *
 * ⚠️ 也正因为如此，**实现必须保证进程内只有一个实例**：两个实例会各持一份
 * `MutableStateFlow`，设置页写的是 B 的 prefs（能落盘），但 `MainActivity`
 * 订阅的是 A 的流（永远不发射）—— 表现是「改了没反应，重启才生效」。
 * 所以它由 `BeeApplication` 持有并向下传，不在页面里 new。
 */
interface ThemeSettings {

    /** 当前模式。实现保证：初值在构造时就已从磁盘读出，订阅者拿到的第一帧就是对的。 */
    val mode: StateFlow<ThemeMode>

    /** 改模式并落盘。相同的值不应写盘、也不应发射。 */
    fun set(mode: ThemeMode)
}
