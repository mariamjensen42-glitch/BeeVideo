package com.cycling.beevideo.data.settings

import android.content.Context

/**
 * 内容源配置的本地持久化。
 *
 * 「不内置任何来源」这条产品约束要求来源由用户自己填，那就必须**记得住**
 * —— 每次开 App 都重输一遍地址，等于逼用户放弃。
 *
 * 用 `SharedPreferences` 而不是 Room / DataStore：这里存的是两个短字符串，
 * 没有查询需求。为一个字符串引入一个数据库，是拿复杂度换不到任何东西。
 */
class ContentSourceStore(context: Context) {

    private val prefs = context.applicationContext
        .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    /** 用户填的配置地址（可以是站点配置，也可以是单站点 api 地址） */
    var configUrl: String
        get() = prefs.getString(KEY_CONFIG_URL, "").orEmpty()
        set(value) = prefs.edit().putString(KEY_CONFIG_URL, value).apply()

    /** 上次选中的来源 id */
    var activeSourceId: String
        get() = prefs.getString(KEY_ACTIVE_SOURCE, "").orEmpty()
        set(value) = prefs.edit().putString(KEY_ACTIVE_SOURCE, value).apply()

    fun clear() {
        prefs.edit().clear().apply()
    }

    private companion object {
        const val PREFS_NAME = "beevideo.content_source"
        const val KEY_CONFIG_URL = "config_url"
        const val KEY_ACTIVE_SOURCE = "active_source_id"
    }
}
