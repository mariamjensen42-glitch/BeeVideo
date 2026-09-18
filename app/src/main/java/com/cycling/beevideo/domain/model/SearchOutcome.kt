package com.cycling.beevideo.domain.model

/**
 * 一次搜索的结果**加上覆盖情况**。
 *
 * ─── 为什么不只返回 `List<Vod>` ────────────────────────────────────────
 * 搜索是**跨源**的：一次请求会打向多个站点，而站点数受两个上限约束 ——
 * 当前来源里标了 `searchable` 的数量，以及 [com.cycling.beevideo.domain.repository.ContentRepository]
 * 侧设的一次搜索站点上限。超过上限的站点**根本不会被请求**。
 *
 * 只回一个列表的话，这件事对用户完全不可见：搜一部剧没结果，用户无法区分
 * "这 80 个源都说没有"和"只搜了前 10 个源、答案可能就在剩下那些里"。
 * 后者会让人得出"这个 App 的搜索很烂"的结论，而实际上是我们自己
 * **悄悄少搜了 90%**。
 *
 * 所以把两个数字一起交出去，让界面能说清"已搜 N / M 个源"。
 */
data class SearchOutcome(
    /** 去重后的结果。同名条目跨站点视为同一部剧。 */
    val vods: List<Vod>,
    /** 实际发出请求的站点数。 */
    val searchedSources: Int,
    /** 当前来源里标了可搜索的站点总数。`searchedSources < searchableSources` 即为截断。 */
    val searchableSources: Int,
) {

    /** 是否因为站点上限而少搜了一部分源。 */
    val truncated: Boolean get() = searchedSources < searchableSources

    companion object {
        val EMPTY = SearchOutcome(emptyList(), 0, 0)
    }
}
