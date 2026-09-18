package com.cycling.beevideo.ui.preview

import com.cycling.beevideo.domain.model.Category
import com.cycling.beevideo.domain.model.Episode
import com.cycling.beevideo.domain.model.PlayLine
import com.cycling.beevideo.domain.model.Vod
import com.cycling.beevideo.domain.repository.ContentRepository

/**
 * 预览稿用的示例内容。
 *
 * ⚠️ **不是 App 的运行数据源**：首页在没有配置来源时显示空态，不会退回这里
 * （产品定位是播放器外壳，塞一份假内容进去等于假装有源）。
 *
 * 它只为 `@Preview` 存在 —— 预览环境跑不了网络、也跑不了 `DexClassLoader`，
 * 各页面的预览稿需要一份确定的、同步可用的数据。
 *
 * 真实来源会给出封面地址，这里一律留空，封面走 `posterBrush()` 生成的渐变占位 ——
 * 预览稿正好用它来检查占位样式本身。
 *
 * ─── 为什么在 ui/preview/ 而不是 data/ ────────────────────────────────
 * 它以前住在 `data/demo/`，于是**每个预览都让 ui 在编译期依赖 data** ——
 * 架构图里不存在的一条边，代码里处处都在。而 `docs/scope-and-m1-delivery.md`
 * 的依赖方向写得很硬：`ui → domain ← data`。
 *
 * 搬到 ui 这边之后它同时变成 JVM 测试可用的夹具（`app/src/test` 看得见 main），
 * 一份东西两用，而不是各留一份。
 */
object PreviewVods {

    /**
     * 公开测试流，仅用于验证播放内核可用。
     * 不指向任何具体影视内容。
     */
    private val SAMPLE_STREAMS = listOf(
        "https://vjs.zencdn.net/v/oceans.mp4",
        "https://media.w3.org/2010/05/sintel/trailer.mp4",
        "https://test-streams.mux.dev/x36xhzz/x36xhzz.m3u8",
    )

    val categories: List<Category> = listOf(
        // 与真实仓储一致：首位是「推荐」伪分类（真实来源返回的 name 是空的，
        // 由界面按 id 取字符串资源；预览稿直接写死名称，免得预览里出现空 chip）
        Category(ContentRepository.CATEGORY_RECOMMEND, "推荐"),
        Category("movie", "电影"),
        Category("tv", "剧集"),
        Category("doc", "纪录片"),
        Category("anime", "动漫"),
    )

    private data class Seed(
        val id: String,
        val name: String,
        val categoryId: String,
        val year: String,
        val area: String,
        val genre: String,
        val score: String,
        val remarks: String,
        val epCount: Int,
    )

    private val seeds = listOf(
        Seed("v01", "长风渡海", "tv", "2024", "大陆", "剧情", "8.6", "全 12 集", 12),
        Seed("v02", "无名之辈", "movie", "2023", "大陆", "悬疑", "7.9", "完结", 10),
        Seed("v03", "山与海之间", "doc", "2025", "大陆", "纪录片", "9.1", "更新至 06", 6),
        Seed("v04", "雾港迷踪", "tv", "2024", "大陆", "悬疑", "8.2", "全 12 集", 12),
        Seed("v05", "少年游侠传", "tv", "2023", "大陆", "古装", "7.5", "全 10 集", 10),
        Seed("v06", "都市夜归人", "movie", "2025", "大陆", "都市", "6.8", "更新至 08", 8),
        Seed("v07", "星尘归途", "tv", "2024", "美国", "科幻", "8.9", "全 10 集", 10),
        Seed("v08", "深夜食肆", "tv", "2022", "日本", "治愈", "9.0", "全 12 集", 12),
        Seed("v09", "铁马冰河", "movie", "2023", "大陆", "战争", "7.2", "全 12 集", 12),
        Seed("v10", "春日便利店", "tv", "2025", "韩国", "爱情", "7.8", "更新至 08", 8),
        Seed("v11", "深海回声", "doc", "2024", "英国", "悬疑", "8.4", "全 06 集", 6),
        Seed("v12", "纸上江湖", "anime", "2022", "大陆", "动画", "8.8", "全 12 集", 12),
        Seed("v13", "云上牧场", "anime", "2024", "大陆", "动画", "8.1", "全 08 集", 8),
    )

    val vods: List<Vod> = seeds.map { seed ->
        Vod(
            id = seed.id,
            name = seed.name,
            categoryId = seed.categoryId,
            year = seed.year,
            area = seed.area,
            genre = seed.genre,
            score = seed.score,
            remarks = seed.remarks,
            director = "——",
            actors = "——",
            intro = "${seed.year} 年${seed.area}${seed.genre}作品。" +
                "本条目为原型演示数据，接入真实内容源后由来源提供简介。",
            pic = "",
            lines = listOf(
                PlayLine("线路一 · 演示", buildEpisodes(seed, 0)),
                PlayLine("线路二 · 备用", buildEpisodes(seed, 1)),
            ),
        )
    }

    /**
     * 生成剧集。名称与短标签都由「来源」给出 ——
     * 真实内容源会直接返回这两项，界面不得自行解析。
     */
    private fun buildEpisodes(seed: Seed, offset: Int): List<Episode> =
        (1..seed.epCount).map { index ->
            Episode(
                name = "第 %02d 集".format(index),
                short = "%02d".format(index),
                url = SAMPLE_STREAMS[(index + offset) % SAMPLE_STREAMS.size],
            )
        }

    fun vodById(id: String): Vod? = vods.firstOrNull { it.id == id }
}
