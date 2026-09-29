package com.cycling.beevideo.data.settings

/**
 * 搜索关键词的持久化接缝。
 *
 * 抽成接口的理由与 [SourceStore] 一样：`SharedPreferences` 要 `Context`，纯 JVM 单测
 * 造不出来。上限、去重、编解码全是**逻辑**而不是存储细节，放在这一层就都能被直接测。
 */
interface SearchHistoryStore {

    /** 新的在前。实现负责与磁盘同步。 */
    var keywords: List<String>

    fun clear()

    companion object {

        /**
         * 上限。再多就不是"最近搜过"，而是一份需要管理的清单了。
         *
         * 截断发生在**写入时**：读取端是搜索页空态，每次重组都会跑，没有理由让它重复计算。
         */
        const val MAX = 20

        /** 分隔符。关键词来自单行输入框的 `trim()` 结果，不含换行，所以 `\n` 是安全的。 */
        const val SEPARATOR = "\n"

        fun decode(raw: String): List<String> =
            raw.split(SEPARATOR).filter { it.isNotBlank() }

        fun encode(list: List<String>): String = list.joinToString(SEPARATOR)

        /** 新词上移、重复去重、超上限丢最旧。 */
        fun record(current: List<String>, keyword: String): List<String> =
            (listOf(keyword) + current.filterNot { it == keyword }).take(MAX)

        fun remove(current: List<String>, keyword: String): List<String> =
            current.filterNot { it == keyword }
    }
}
