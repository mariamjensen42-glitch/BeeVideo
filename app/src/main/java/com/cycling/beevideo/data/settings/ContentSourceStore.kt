package com.cycling.beevideo.data.settings

import android.content.Context

/**
 * [ContentSourceStore] 的接口面。
 *
 * 存在的理由是**可测**：[VodContentRepository] 的装载逻辑（状态机、取消、
 * 缓存失效）全靠这几个字段，而 `SharedPreferences` 要 `Context` ——
 * 纯 JVM 单测里造不出来。把它抽成接口之后，装载逻辑第一次可以被测试覆盖，
 * 「装载被取消时状态会不会卡在 LOADING」这类判据才钉得住。
 */
interface SourceStore {

    /** 用户填的配置地址（可以是站点配置，也可以是单站点 api 地址） */
    var configUrl: String

    /** 上次选中的来源 id */
    var activeSourceId: String

    /** 清空全部记录。 */
    fun clear()
}

/**
 * 内容源配置的本地持久化。
 *
 * 「不内置任何来源」这条产品约束要求来源由用户自己填，那就必须**记得住**
 * —— 每次开 App 都重输一遍地址，等于逼用户放弃。
 *
 * 用 `SharedPreferences` 而不是 Room / DataStore：这里存的是两个短字符串，
 * 没有查询需求。为一个字符串引入一个数据库，是拿复杂度换不到任何东西。
 */
class ContentSourceStore(context: Context) : SourceStore {

    private val prefs = context.applicationContext
        .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    override var configUrl: String
        get() = prefs.getString(KEY_CONFIG_URL, "").orEmpty()
        set(value) = prefs.edit().putString(KEY_CONFIG_URL, value).apply()

    override var activeSourceId: String
        get() = prefs.getString(KEY_ACTIVE_SOURCE, "").orEmpty()
        set(value) = prefs.edit().putString(KEY_ACTIVE_SOURCE, value).apply()

    override fun clear() {
        prefs.edit().clear().apply()
    }

    private companion object {
        const val PREFS_NAME = "beevideo.content_source"
        const val KEY_CONFIG_URL = "config_url"
        const val KEY_ACTIVE_SOURCE = "active_source_id"
    }
}
