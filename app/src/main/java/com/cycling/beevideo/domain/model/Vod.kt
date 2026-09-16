package com.cycling.beevideo.domain.model

/**
 * 统一媒体模型。
 *
 * 无论内容来自本地文件、网络直链还是用户配置的点播源，
 * 最终都收敛成这一套结构，UI 层只认它。
 */

/** 一条播放线路（同一部剧的不同来源） */
data class PlayLine(
    val name: String,
    val episodes: List<Episode>,
)

/** 单个剧集 */
data class Episode(
    /** 完整名称，如「第 01 集」 */
    val name: String,
    /**
     * 短标签，供选集栏这类空间紧张处使用。
     * 由来源直接提供，**不由界面解析 [name] 推导** —— 靠字符串替换取集数，
     * 一旦来源改了命名格式就会静默出错。
     */
    val short: String,
    /**
     * 剧集的播放标识 —— **注意它不一定是 URL**。
     *
     * MacCMS 那类 JSON 源在 `vod_play_url` 里直接给可播地址；而 spider 源
     * 常常只给一个内部 id，真实地址要由站点服务端二次兑换。
     * 这两种情况统一在 [com.cycling.beevideo.domain.repository.ContentRepository.playTarget]
     * 里收敛，界面层不需要判断。
     */
    val url: String,
)

/** 影片条目 */
data class Vod(
    val id: String,
    val name: String,
    val categoryId: String,
    val year: String,
    val area: String,
    val genre: String,
    val score: String,
    val remarks: String,
    val director: String,
    val actors: String,
    val intro: String,
    /**
     * 封面地址。**可能为空** —— 有的源不提供封面，界面必须能退回占位样式，
     * 不能拿空地址去请求（那会变成一次必然失败的网络往返）。
     */
    val pic: String,
    val lines: List<PlayLine>,
)

/** 首页分类 */
data class Category(
    val id: String,
    val name: String,
)

/**
 * 一个「可以交给播放器的目标」。
 *
 * 不只是地址，还带上**必需的请求头**：相当一部分源在播放时要带 Referer 或
 * 自定义 UA，不带就是 403。把它和地址放在一起返回，是因为这两样东西必须
 * 同时到手才有意义 —— 拆成两次调用就会出现「拿到了地址、换集时头没跟上」。
 */
data class PlayTarget(
    val url: String,
    val headers: Map<String, String>,
    /**
     * 这个地址是**网页**而不是媒体流，需要站外的解析服务才能取到真实地址。
     *
     * 本项目**不内置**这类解析服务（那等于替用户决定用谁）。标记出来是为了让
     * 播放页能说清楚"为什么放不出来"——直接播放一个 HTML 页面的结果是黑屏 +
     * 一条无解的报错，用户会以为是播放器坏了。
     */
    val parse: Boolean = false,
)
