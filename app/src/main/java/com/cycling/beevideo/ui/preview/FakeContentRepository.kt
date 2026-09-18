package com.cycling.beevideo.ui.preview

import com.cycling.beevideo.domain.model.Category
import com.cycling.beevideo.domain.model.PlayTarget
import com.cycling.beevideo.domain.model.SearchOutcome
import com.cycling.beevideo.domain.model.Vod
import com.cycling.beevideo.domain.repository.ContentRepository

/**
 * 预览与 JVM 测试用的假 [ContentRepository]，数据来自 [PreviewVods]。
 *
 * ⚠️ 它**不在 App 的运行路径上** —— 首页在没有配置来源时显示的是空态，
 * 不会退回这里（产品定位是播放器外壳，塞一份假内容进去等于假装有源）。
 *
 * 留下的理由是**预览稿**：Compose 的 `@Preview` 跑不了网络也跑不了
 * `DexClassLoader`，各页面需要一份确定的、同步可用的数据。
 *
 * 它曾住在 `data/repository/`，于是每个预览都让 ui 依赖 data。
 * 现在它是 domain 接口在 JVM 上的一个真 adapter（`app/src/test` 看得见 main），
 * 于是**一份夹具同时供预览和单测使用**。
 */
class FakeContentRepository : ContentRepository {

    override suspend fun categories(): List<Category> = PreviewVods.categories

    override suspend fun listByCategory(categoryId: String, page: Int): List<Vod> =
        if (categoryId == ContentRepository.CATEGORY_RECOMMEND) {
            PreviewVods.vods
        } else {
            PreviewVods.vods.filter { it.categoryId == categoryId }
        }

    override suspend fun detail(vodId: String): Vod? = PreviewVods.vodById(vodId)

    /**
     * 夹具只有一个"来源"，所以 `searchedSources == searchableSources`，
     * 永远不会显示"只搜了一部分"的提示 —— 那本来就是夹具。
     */
    override suspend fun search(keyword: String): SearchOutcome {
        val query = keyword.trim()
        if (query.isEmpty()) return SearchOutcome.EMPTY
        val hits = PreviewVods.vods.filter { it.name.contains(query, ignoreCase = true) }
        return SearchOutcome(vods = hits, searchedSources = 1, searchableSources = 1)
    }

    /**
     * 直接把剧集标识当地址返回。
     *
     * `PreviewVods` 的剧集 `url` 就是公开测试流地址，所以这里不需要"兑换"这一步 ——
     * 那正是真实来源里 `playTarget` 存在的理由，夹具不必模拟它。
     */
    override suspend fun playTarget(
        vodId: String,
        lineName: String,
        episodeId: String,
    ): PlayTarget? = episodeId.takeIf { it.isNotBlank() }?.let { PlayTarget(it, emptyMap()) }
}
