package com.cycling.beevideo.data.local

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * 观看进度表。字段与 [com.cycling.beevideo.domain.model.PlayProgress] 一一对应。
 *
 * 不在 domain 的模型上直接挂 `@Entity`：那样表结构会等于领域模型，以后想把 `lineName`
 * 改名就会连带变成一次数据库迁移 —— 领域模型该跟着语义走，不该跟着存储走。
 *
 * 一部剧一行，只记「上次看到哪」。逐集进度不在 MVP 范围。
 */
@Entity(tableName = "history")
data class HistoryEntity(
    /** 形如 `站点key:源内id`，同时是主键 */
    @PrimaryKey val vodId: String,
    val lineName: String,
    val episodeIndex: Int,
    val episodeName: String,
    val positionMs: Long,
    /** 0 表示来源没给时长 */
    val durationMs: Long,
    val updatedAt: Long,
)

/** 收藏表。快照字段在这里是数据本身，不是冗余（理由见 KeepItem）。 */
@Entity(tableName = "keep")
data class KeepEntity(
    @PrimaryKey val vodId: String,
    val name: String,
    val pic: String,
    val score: String,
    val remarks: String,
    /** 列表排序的唯一依据 */
    val createdAt: Long,
)
