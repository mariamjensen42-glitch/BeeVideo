package com.cycling.beevideo.data.repository

import android.os.SystemClock
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * 「同一批请求合并成一个」的窗口。
 *
 * **不是数据缓存，是请求合并**：只把短时间内用同一个 key 发来的重复调用折叠成一次
 * 网络请求，窗口一过再调照样重新请求。所以不存在"数据陈旧"的问题，将来加下拉刷新
 * 也不需要先想着清这里。
 *
 * 需要它是因为界面有两类成对调用，第二发在数据层看来是同一个响应：
 *  - 首页 `categories()` + `listByCategory(RECOMMEND)`：MacCMS 的 `ac=list` 一次就
 *    返回 class + list（这正是 `HomeContent` 把两者放一起的原因），不合并就每加载
 *    一次打两遍同一个 URL（装机日志确认过是两条一模一样的 `ac=list`）
 *  - 详情页 → 播放页，两处都要 `detail(vodId)`
 *
 * 窗口取几秒：第二发往往比第一发晚几百毫秒到几秒，只要大于这个间隔就够，
 * 取值大小不影响正确性，只影响能合并掉多少。
 *
 * 用互斥锁而不是只读 volatile：第二发在锁上等第一发写完再命中快照 ——
 * **在途请求也会被合并**。
 *
 * @param clock 取当前时间。⚠️ **必须注入**：纯 JVM 测试里 `SystemClock` 是桩、恒 0，
 *   「窗口过期」那条分支永远测不到。
 */
internal class MergeWindow<T>(
    private val windowMs: Long,
    private val clock: () -> Long = { SystemClock.elapsedRealtime() },
) {

    private val lock = Mutex()

    // 读走 volatile、写走锁：invalidate() 要在非挂起上下文里调用，两边都只要可见性
    @Volatile
    private var snapshot: Entry<T>? = null

    private class Entry<T>(val key: String, val at: Long, val value: T)

    /**
     * 取 key 对应的值：窗口内命中就直接返回，否则调 [fetch] 并记下结果。
     *
     * ⚠️ [fetch] 抛出的异常**不会**被记进快照 —— 失败不该在窗口内被当成结果复用，
     * 否则用户重试一次还是拿到同一个错误。
     */
    suspend fun get(key: String, fetch: suspend () -> T): T = lock.withLock {
        val cached = snapshot
        if (cached != null && cached.key == key && clock() - cached.at < windowMs) {
            return@withLock cached.value
        }
        fetch().also { snapshot = Entry(key, clock(), it) }
    }

    /**
     * 换配置与清来源时**必须**调：这两处 `activeSourceId` 有可能不变
     * （配置里站点 key 撞名），光靠 key 认不出来换了朝代。
     */
    fun invalidate() {
        snapshot = null
    }
}
