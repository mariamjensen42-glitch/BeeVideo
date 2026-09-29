package com.cycling.beevideo.domain.repository

import kotlinx.coroutines.flow.StateFlow

/**
 * 无痕模式的端口。
 *
 * 它管的是**记录行为**，不是"删除某份数据"：开启期间观看进度与收藏一律不落库，
 * 对外读到的是一个空视图 —— 库里的旧记录还在，只是这段时间谁都看不到。
 * 于是"关掉就都回来了"是自然结果，不需要任何回滚逻辑。
 *
 * ─── 为什么要 [StateFlow] 而不是裸 get/set（对比 [PlaybackSettings]）────────
 * 开关一拨，**四处界面同时要变**：设置页的开关、历史页的空态、详情页的收藏按钮、
 * 播放页取哪个缓存目录 —— 而拨开关的那一处与它们都不在同一棵子树里。
 * 裸 get/set 也能"生效"，但只能靠 Activity 重建生效，代价是打断正在播的视频。
 *
 * ⚠️ 与 [ThemeSettings] 同一条硬约束：**实现必须保证进程内只有一个实例**，
 * 由 `BeeApplication` 持有并向下传。两个实例会各持一条流，拨了开关别人收不到。
 */
interface IncognitoMode {

    /** 实现保证：初值在构造时就已从磁盘读出，订阅者拿到的第一帧就是对的。 */
    val enabled: StateFlow<Boolean>

    /**
     * 开关。相同的值不写盘、不发射。
     *
     * ⚠️ **关闭**这一个方向还带一次收尾（把会话期间落到磁盘的临时数据清掉）——
     * 那属于实现的职责，调用方只管拨。
     */
    fun set(enabled: Boolean)
}
