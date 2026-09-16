package com.cycling.beevideo.domain.repository

import com.cycling.beevideo.domain.model.Category
import com.cycling.beevideo.domain.model.PlayTarget
import com.cycling.beevideo.domain.model.Vod

/**
 * 内容仓储接口。
 *
 * 定义在 domain 层，由 data 层的具体来源实现（本地媒体 / 网络直链 / 用户配置的点播源）。
 * UI 只依赖这个接口，因此新增一种内容来源时，UI 与 domain 都不需要改动。
 *
 * ─── 失败的表达方式：抛 [ContentException] ───────────────────────────
 * 这几个方法**不返回 null 表示失败**。
 *
 * 理由是「取不到」和「没有」在界面上是两件完全不同的事：源站挂了要让用户看到
 * 错误和重试，而一个分类下确实没有内容只是空列表。如果两者都返回空表，
 * 界面上就只能显示"暂无内容"——用户会以为是自己点错了。
 *
 * `detail` 是例外：那里的 null 有明确含义「源站确认不存在这条」，属于正常结果。
 */
interface ContentRepository {

    /** 列出当前选中来源的分类（含 [CATEGORY_RECOMMEND]） */
    suspend fun categories(): List<Category>

    /** 按分类取内容列表；page 从 1 开始 */
    suspend fun listByCategory(categoryId: String, page: Int = 1): List<Vod>

    /** 取单条详情；源站确认不存在时返回 null */
    suspend fun detail(vodId: String): Vod?

    /** 搜索；空关键词返回空列表 */
    suspend fun search(keyword: String): List<Vod>

    /**
     * 把剧集的播放标识兑换成真正能交给播放器的目标。
     *
     * @param lineName  线路名。部分源要靠它区分同一部剧的不同线路。
     * @param episodeId [com.cycling.beevideo.domain.model.Episode.url]，
     *                  可能已经是地址，也可能是待兑换的内部 id。
     */
    suspend fun playTarget(vodId: String, lineName: String, episodeId: String): PlayTarget?

    companion object {
        /**
         * 「推荐」伪分类。
         *
         * 它**不是**来源自己的分类，而是由来源的首页内容（CatVod 的
         * `homeContent`）提供的那一批精选。放在分类行首位，是因为首页第一眼
         * 该看到内容而不是一串筛选条件。
         */
        const val CATEGORY_RECOMMEND = "recommend"
    }
}

/**
 * 内容层的统一异常。
 *
 * domain 里不能出现 `CatVodException` 这类实现细节，所以 data 层在边界上
 * 把各种底层错误（网络、JSON、反射调用 foreign code）统一翻译成这个类型。
 * [message] 是**给用户看的**，得能读懂 —— 不要往里塞堆栈或内部类名。
 */
class ContentException(
    message: String,
    cause: Throwable? = null,
) : Exception(message, cause)
