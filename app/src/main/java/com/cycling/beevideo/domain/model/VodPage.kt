package com.cycling.beevideo.domain.model

/**
 * 一页内容 + 「后面还有没有」。
 *
 * ⚠️ 分页信息必须跟列表一起回来：CatVod 的分类响应里带 `pagecount`，而**只有爬虫知道**它。
 * 上层改用"这一页看起来不满"来猜，既会多打一次请求，又会在源按固定条数返回时**静默截断**。
 */
data class VodPage(
    val vods: List<Vod>,
    /** 源声明的总页数。`null` = 源没给（爬虫实现各异）→ 见 [hasMoreAfter]。 */
    val totalPages: Int? = null,
) {

    /**
     * 第 [page] 页之后还有没有内容。
     *
     * 源没给页数时按"这一页有东西就继续"：多打一次空请求，好过把内容截在用户看不见的地方。
     * 真正的兜底在调用方 —— 追加后**一条新条目都没有**也算到底（源拿重复项充数时靠它收尾）。
     */
    fun hasMoreAfter(page: Int): Boolean =
        totalPages?.let { page < it } ?: vods.isNotEmpty()
}
