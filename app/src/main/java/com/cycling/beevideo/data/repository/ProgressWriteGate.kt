package com.cycling.beevideo.data.repository

/**
 * 进度写入的节流闸门。
 *
 * 播放页每秒上报一次，但每秒写一次库没有必要 —— 进度精确到秒没有意义，而每笔写都是一次
 * 磁盘事务（画面正在播，卡一下就是掉帧）。频率属于存储侧的知识，界面只管如实上报。
 *
 * 单独成类是因为这里有个很容易写错、错了又看不出来的地方：若用 `lastAt = Long.MIN_VALUE`
 * 表示"还没写过"，第一次判断 `now - lastAt < window` 会**溢出成负数** → 判定为窗口内 →
 * **第一次写入被吞掉**，表现为"刚开播那几秒进度记不住"。用可空 `lastAt` 避开这个算术。
 *
 * 时钟是注入的，因此本类不依赖任何 Android API，可用纯 JVM 测试覆盖四条路径
 * （见 ProgressWriteGateTest）。
 *
 * ⚠️ 时间源必须**单调**（`SystemClock.elapsedRealtime` 一类）。用 `System.currentTimeMillis`
 * 的话，用户改系统时间或 NTP 往回校时会让窗口判断长时间不成立，表现为"进度突然不再保存"。
 */
class ProgressWriteGate(
    /** 写入的最小间隔（毫秒） */
    private val windowMs: Long,
    /** 时间源，必须单调 */
    private val now: () -> Long,
) {

    private var lastAt: Long? = null

    /**
     * 这一次写入是否放行。
     *
     * @param force 切集 / 退出 / 退到后台传 true，不受窗口约束。放行后**同样刷新时间戳**，
     *              否则紧接着的周期上报会立刻又写一次，这一秒就白省了。
     */
    @Synchronized
    fun allow(force: Boolean): Boolean {
        val at = now()
        val last = lastAt
        if (!force && last != null && at - last < windowMs) return false
        lastAt = at
        return true
    }
}
