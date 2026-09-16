package com.cycling.beevideo.domain.model

/**
 * 内容源在界面上的样子。
 *
 * ─── 为什么只有 id 和 name ────────────────────────────────────────────
 * 界面需要知道的东西只有两件：这颗 chip 显示什么、选中它之后传给谁。
 * 来源在配置里的那些字段（接口地址、扩展参数、jar 路径……）**一律不进 domain**
 * —— 一旦它们进来，UI 就会开始按"这个源是 JSON 还是 jar"分叉，分层就白做了。
 */
data class ContentSource(
    val id: String,
    val name: String,
)

/** 内容源的装载阶段。界面按这个分支，不再自己猜。 */
enum class SourcePhase {
    /** 还没配置过任何来源 */
    EMPTY,
    LOADING,
    READY,
    FAILED,
}

/**
 * 内容源的当前状态。
 *
 * [message] 在 [SourcePhase.FAILED] 时是给用户看的失败原因；其余阶段一般用不上
 * —— 但 [SourcePhase.READY] 时会带上来源条数之类的说明，供设置页显示。
 */
data class SourceStatus(
    val phase: SourcePhase,
    /** 用户填的配置地址。失败时要回填到输入框，不能让用户重打一遍。 */
    val configUrl: String,
    val sources: List<ContentSource>,
    /** 当前选中的来源；为空表示还没选（界面取第一个） */
    val activeSourceId: String,
    val message: String,
) {
    companion object {
        val Initial = SourceStatus(
            phase = SourcePhase.EMPTY,
            configUrl = "",
            sources = emptyList(),
            activeSourceId = "",
            message = "",
        )
    }
}
