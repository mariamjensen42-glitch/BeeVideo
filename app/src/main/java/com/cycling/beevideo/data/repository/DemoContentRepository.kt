package com.cycling.beevideo.data.repository

import com.cycling.beevideo.data.demo.DemoContent
import com.cycling.beevideo.domain.model.Category
import com.cycling.beevideo.domain.model.PlayTarget
import com.cycling.beevideo.domain.model.Vod
import com.cycling.beevideo.domain.repository.ContentRepository

/**
 * 演示用仓储实现，数据来自 [DemoContent]。
 *
 * ⚠️ 它现在**不再是 App 的运行数据源** —— 首页在没有配置来源时显示的是空态，
 * 不会退回演示数据（产品定位是播放器外壳，塞一份假内容进去等于假装有源）。
 *
 * 它留下的理由是**预览稿**：Compose 的 `@Preview` 跑不了网络也跑不了
 * `DexClassLoader`，各页面的预览稿需要一份确定的、同步可用的数据。
 * 所以这个类只被 preview 引用，不进任何业务路径。
 */
class DemoContentRepository : ContentRepository {

    override suspend fun categories(): List<Category> = DemoContent.categories

    override suspend fun listByCategory(categoryId: String, page: Int): List<Vod> =
        if (categoryId == ContentRepository.CATEGORY_RECOMMEND) {
            DemoContent.vods
        } else {
            DemoContent.vods.filter { it.categoryId == categoryId }
        }

    override suspend fun detail(vodId: String): Vod? = DemoContent.vodById(vodId)

    override suspend fun search(keyword: String): List<Vod> {
        val query = keyword.trim()
        if (query.isEmpty()) return emptyList()
        return DemoContent.vods.filter { it.name.contains(query, ignoreCase = true) }
    }

    override suspend fun playTarget(
        vodId: String,
        lineName: String,
        episodeId: String,
    ): PlayTarget? = episodeId.takeIf { it.isNotBlank() }?.let { PlayTarget(it, emptyMap()) }
}
