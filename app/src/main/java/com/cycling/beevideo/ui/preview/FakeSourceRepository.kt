package com.cycling.beevideo.ui.preview

import com.cycling.beevideo.domain.model.ContentSource
import com.cycling.beevideo.domain.model.SourcePhase
import com.cycling.beevideo.domain.model.SourceStatus
import com.cycling.beevideo.domain.repository.ContentSourceRepository
import kotlinx.coroutines.flow.MutableStateFlow

/**
 * 预览与 JVM 测试用的假 [ContentSourceRepository]。
 *
 * 预览环境不可能装出一棵配置好的来源树（那要下载配置、建 `DexClassLoader`），
 * 所以这里直接给一个"已就绪"的状态。
 *
 * ─── 为什么状态从外面传进来 ─────────────────────────────────────────────
 * 它以前在 `HomeScreen` 和 `SettingsScreen` 里**各写了一份**，而且两份
 * **并不相同**：首页那份是一个来源（"示例来源"），设置页那份是三个来源 +
 * 一句可读的说明。合并成一个硬编码的状态会悄悄改掉其中一个预览要看的东西 ——
 * 首页看的是"顶栏副标题有没有来源名"，设置页看的是"来源列表和说明文字"。
 * 所以重复的是那五个空方法体，状态是各自的意图。
 *
 * 用 [singleSourceReady] / [threeSourcesReady] 取用，别在这里加别的默认值。
 */
class FakeSourceRepository(status: SourceStatus) : ContentSourceRepository {

    override val status: MutableStateFlow<SourceStatus> = MutableStateFlow(status)

    override suspend fun restore() = Unit

    override suspend fun applyConfig(url: String): String? = null

    override fun selectSource(sourceId: String) = Unit

    override suspend fun clear() = Unit

    companion object {

        /** 首页预览：只有一个来源 —— 看的是"顶栏副标题显示来源名"。 */
        fun singleSourceReady(): FakeSourceRepository = FakeSourceRepository(
            SourceStatus(
                phase = SourcePhase.READY,
                configUrl = "",
                sources = listOf(ContentSource(id = "demo", name = "示例来源")),
                activeSourceId = "demo",
                message = "",
            )
        )

        /**
         * 设置页预览：装好三个来源，带一句可读的说明。
         * 配置地址用本地 mock 服务的地址，与 `.workbuddy/scripts/` 里那份一致。
         */
        fun threeSourcesReady(): FakeSourceRepository = FakeSourceRepository(
            SourceStatus(
                phase = SourcePhase.READY,
                configUrl = "http://127.0.0.1:18080/config.json",
                sources = listOf(
                    ContentSource("mock_json", "Mock JSON 源"),
                    ContentSource("mock_xml", "Mock XML 源"),
                    ContentSource("mock_spider", "Mock Jar 源"),
                ),
                activeSourceId = "mock_json",
                message = "共 3 个来源",
            )
        )
    }
}
