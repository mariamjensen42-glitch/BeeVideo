package com.cycling.beevideo.data.repository

import android.os.SystemClock
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * 「同一批请求合并成一个」的窗口。
 *
 * ─── 它是什么、不是什么 ────────────────────────────────────────────────
 * **不是**数据缓存，是**请求合并**：只把短时间内用同一个 key 发出来的重复调用
 * 折叠成一次网络请求。窗口一过再调照样重新请求，所以不存在「数据陈旧到用户
 * 察觉不到变化」的问题，将来加下拉刷新也不需要先想着来清这里。
 *
 * ─── 为什么需要 ────────────────────────────────────────────────────────
 * 界面有两类成对调用，它们的第二发在数据层看来是**同一个响应**：
 *
 *   - 首页：`categories()` + `listByCategory(RECOMMEND)` —— MacCMS 的
 *     `ac=list` 一次就返回 `class` + `list`（这正是 `HomeContent` 把两者放在
 *     一起的原因）。不合并的话首页每加载一次就把同一个 URL 打两遍，
 *     装机日志里确认过就是两条一模一样的 `ac=list`。
 *   - 详情：详情页 → 播放页，两处都要 `detail(vodId)`。jar 源的
 *     `detailContent` 是本地解析、不便宜，MacCMS 源则是一次跨网请求。
 *
 * 窗口取几秒的用意：**第二发往往比第一发晚几百毫秒到几秒**（先渲染出列表、
 * 用户点进去才触发第二次）。窗口只要大于「第一发返回」到「第二发进来」的
 * 间隔就够，取值大小本身不影响正确性，只影响能合并掉多少。
 *
 * ─── 并发语义 ──────────────────────────────────────────────────────────
 * 用互斥锁而不是只读 volatile：第一发还在飞的时候第二发进来，会在锁上等，
 * 等第一发写完之后命中快照 —— 也就是**在途请求也会被合并**，而不是又发一次。
 * 代价是第二发要等第一发的网络时间，但那条请求本来也要等网络。
 *
 * @param windowMs 快照的有效期（毫秒）。
 * @param clock 取当前时间。默认单调时钟；**注入**是为了单测能控制时间流逝，
 *   否则纯 JVM 测试里 `SystemClock` 是 android.jar 的桩、恒返回 0，
 *   「窗口过期」这条分支永远测不到。
 */
internal class MergeWindow<T>(
    private val windowMs: Long,
    private val clock: () -> Long = { SystemClock.elapsedRealtime() },
) {

    private val lock = Mutex()

    /*
     * 读走 volatile、写走锁：`invalidate()` 需要能在**非挂起**上下文里调用
     * （换配置、清来源都是从普通函数里来的），而写入必须和 `get` 互斥。
     * 两边都只要可见性，不要求原子复合，所以 volatile 够。
     */
    @Volatile
    private var snapshot: Entry<T>? = null

    private class Entry<T>(val key: String, val at: Long, val value: T)

    /**
     * 取 key 对应的值：窗口内命中就直接返回，否则调 [fetch] 并记下结果。
     *
     * [fetch] 抛出的异常**不会**被记进快照 —— 失败不该在窗口内被当成结果复用，
     * 否则用户重试一次还是拿到同一个错误。异常按原样抛给调用方。
     */
    suspend fun get(key: String, fetch: suspend () -> T): T = lock.withLock {
        val cached = snapshot
        if (cached != null && cached.key == key && clock() - cached.at < windowMs) {
            return@withLock cached.value
        }
        fetch().also { snapshot = Entry(key, clock(), it) }
    }

    /**
     * 作废快照。
     *
     * 换配置（[VodContentRepository.load]）和清来源时**必须**调：这两处
     * `activeSourceId` 有可能不变（配置里站点 key 撞名，比如都叫 `mock_json`），
     * 光靠 key 认不出来换了朝代。
     */
    fun invalidate() {
        snapshot = null
    }
}
