package com.cycling.beevideo.data.settings

import android.content.Context
import com.cycling.beevideo.domain.repository.IncognitoMode
import com.cycling.beevideo.domain.repository.SearchHistoryRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine

/**
 * [SearchHistoryRepository] 的 SharedPreferences 实现。
 *
 * ⚠️ **单独一个 prefs 文件**：`ContentSourceStore.clear()` 是整份 `edit().clear()`，
 * 混在一起会让「清除内容源」顺手清掉搜索历史 —— 用户不会认为这是同一个操作
 * （同 `PrefsThemeSettings` 的理由）。
 *
 * ⚠️ 无痕拦截在这里，不在界面：拨开关的在设置页，读它的地方（搜索页空态）在另一棵树里，
 * 界面各判一次必漏。与 `RoomLibraryRepository` 是同一个收口点思路（ADR-0010）。
 *
 * 写入是同步 `apply()`：一次搜索才写一条短字符串，`commit()` 会在这条热路径上等 I/O，
 * 而丢一次的代价只是"少一条历史"。
 */
class PrefsSearchHistoryRepository(
    context: Context,
    private val incognito: IncognitoMode,
) : SearchHistoryRepository {

    private val store = PrefsSearchHistoryStore(context)

    private val local = MutableStateFlow(store.keywords)

    /** 无痕下读到的永远是空 —— 磁盘上的旧记录还在，只是这段时间谁都看不到。 */
    override val keywords: Flow<List<String>> = combine(local, incognito.enabled) { list, on ->
        if (on) emptyList() else list
    }

    override fun record(keyword: String) {
        if (incognito.enabled.value) return
        val trimmed = keyword.trim()
        if (trimmed.isEmpty()) return
        write(SearchHistoryStore.record(local.value, trimmed))
    }

    // 与 deleteProgress 同一条约定：无痕期间入口本来够不着，挡一下是为了不留
    // "某个入口仍能改动用户数据"的例外
    override fun remove(keyword: String) {
        if (incognito.enabled.value) return
        write(SearchHistoryStore.remove(local.value, keyword))
    }

    override fun clear() {
        if (incognito.enabled.value) return
        store.clear()
        local.value = emptyList()
    }

    private fun write(list: List<String>) {
        // 先去重再写：省掉一次无意义的 prefs 编辑
        if (list == local.value) return
        store.keywords = list
        local.value = list
    }
}

/** `SharedPreferences` 版。编解码与上限全在 [SearchHistoryStore] 的伴生对象里。 */
private class PrefsSearchHistoryStore(context: Context) : SearchHistoryStore {

    private val prefs = context.applicationContext
        .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    override var keywords: List<String>
        get() = SearchHistoryStore.decode(prefs.getString(KEY_KEYWORDS, "").orEmpty())
        set(value) = prefs.edit()
            .putString(KEY_KEYWORDS, SearchHistoryStore.encode(value))
            .apply()

    override fun clear() {
        prefs.edit().clear().apply()
    }

    private companion object {
        const val PREFS_NAME = "beevideo.search_history"
        const val KEY_KEYWORDS = "keywords"
    }
}
