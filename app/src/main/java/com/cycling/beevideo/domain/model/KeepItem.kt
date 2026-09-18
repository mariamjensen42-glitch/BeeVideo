package com.cycling.beevideo.domain.model

/**
 * 一条收藏。
 *
 * 存的是**收藏那一刻的快照**，不是指针。收藏页要离线渲染，若为每部剧回源取详情：
 * 源挂了收藏夹就变空白、30 部就要发 30 个请求、同一部剧的片名还可能两次不一样。
 * 想看最新详情就点进去，那时才回源。
 *
 * 字段刚好是 `PosterCard` 渲染一张卡所需的全部，不多一个。
 */
data class KeepItem(
    /** 全局条目 id，形如 `站点key:源内id`（见 CatVodResponse.vodId） */
    val vodId: String,
    val name: String,
    /** 可能为空 —— 部分来源不给封面，卡片要能退回渐变占位 */
    val pic: String,
    /** 空或非数值时，海报角标不显示 */
    val score: String,
    /** 状态文案，如「更新至 12 集」 */
    val remarks: String,
    val createdAt: Long,
)
