package com.cycling.beevideo.domain.repository

import com.cycling.beevideo.domain.model.KeepItem
import com.cycling.beevideo.domain.model.PlayProgress
import kotlinx.coroutines.flow.Flow

/**
 * 用户自己产生的数据：**观看进度**与**收藏**。
 *
 * 与 [ContentRepository] 的区别不是"哪个页面用"，而是数据从哪来：来源给的内容换源就整套
 * 不一样，用户产生的数据换源之后仍属于用户。这两者共用同一个生命周期和同一份存储，
 * 而且总是连在一起用（详情页既要显示收藏状态、又要标记看到哪一集），所以放在一个接口里。
 *
 * 只暴露界面上真实存在的操作 —— 没有"清空全部收藏"这种当前没人调用的入口。
 */
interface LibraryRepository {

    // ------------------------------------------------------ 观看进度（续播）

    /** 没看过返回 null，这是正常结果而非失败。 */
    suspend fun progressOf(vodId: String): PlayProgress?

    /**
     * 记录播放进度。
     *
     * **不是 suspend**：最关键的一次调用发生在界面正在销毁时（返回、离开播放页），
     * 那一刻调用方的协程随时会被取消，写成 suspend 的话写入可能一次都没开始就没了 ——
     * 症状是"退出时最后几秒的进度记不住"，极难定位。所以交给仓储自己去保证写完。
     *
     * 调用方也不需要自己控制频率：实现会按时间窗折叠高频重复写入。多久写一次库是
     * 存储侧的知识，界面只管如实上报。
     *
     * @param force 切集 / 退出 / 退到后台传 true，那次位置必须落库、不被时间窗吞掉
     */
    fun saveProgress(progress: PlayProgress, force: Boolean = false)

    // ------------------------------------------------------------------ 收藏

    /** 按收藏时间倒序。用 [Flow] 是因为详情页取消收藏后，收藏页那张卡必须立刻消失。 */
    val keeps: Flow<List<KeepItem>>

    /** 某条目当前是否已收藏。详情页按钮靠它决定画实心还是描边。 */
    fun isKept(vodId: String): Flow<Boolean>

    /**
     * 收藏 / 取消收藏，返回**切换之后**的状态（true = 现在已收藏）。
     *
     * 不拆成 add/remove：拆开的话每个调用点都得先读状态再决定调哪个，那次读和写之间
     * 就是竞态（连点两下会两条都写进去）。
     */
    suspend fun toggleKeep(item: KeepItem): Boolean
}
