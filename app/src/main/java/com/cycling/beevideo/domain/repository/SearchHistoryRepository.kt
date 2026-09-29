package com.cycling.beevideo.domain.repository

import kotlinx.coroutines.flow.Flow

/**
 * 搜索历史的端口。
 *
 * 与 [LibraryRepository] 分开：那份管"用户在内容上留下的东西"（看到哪、收藏了谁），
 * 失效时机是"清内容源 / 无痕"；搜索关键词与它们无关，混在一起会让两个清除动作互相牵连。
 *
 * 用 [Flow] 而不是 `StateFlow`：初值就是空列表，界面不需要"构造期已就绪"的保证，
 * 而实现侧要用 `combine` 接无痕开关，`Flow` 直接就是那个形状。
 *
 * ⚠️ 无痕的拦截在**实现里**（唯一收口点，见 ADR-0010）：开启期间既不写入，对外也读不到。
 */
interface SearchHistoryRepository {

    /** 最近搜过的关键词，新的在前。 */
    val keywords: Flow<List<String>>

    /** 记一条。已存在时上移而不是重复；超过上限丢最旧的。空白词无操作。 */
    fun record(keyword: String)

    /** 删一条。不存在时无操作。 */
    fun remove(keyword: String)

    /** 全部清空。 */
    fun clear()
}
