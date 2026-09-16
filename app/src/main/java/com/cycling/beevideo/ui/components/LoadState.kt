package com.cycling.beevideo.ui.components

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import kotlin.coroutines.cancellation.CancellationException

/**
 * 一次异步加载的三种结局。
 *
 * ─── 为什么不用 `null` 当"还没加载完" ─────────────────────────────────
 * 「加载中」和「加载失败」在界面上完全是两件事：前者要转圈、让用户等；
 * 后者要一句原因 + 一个重试入口。都用 null 表示的话，界面就只能显示
 * 一个永远转不完的圈 —— 那是把失败伪装成了进行中。
 */
sealed interface LoadState<out T> {

    data object Loading : LoadState<Nothing>

    data class Ready<T>(val value: T) : LoadState<T>

    /** [message] 已经是可以直接显示给用户的一句话（由数据层翻译好）。 */
    data class Failed(val message: String) : LoadState<Nothing>
}

/**
 * 按 [keys] 加载一次数据，并在键变化时自动重来。
 *
 * 键变化会取消上一次的加载 —— `produceState` 本身就是这样（协程重启），
 * 所以快速切换分类时不会出现"上一个分类的结果后到、覆盖掉当前分类"。
 *
 * 失败**只转成状态**，不往外抛：界面每一处都要处理失败，
 * 抛出去等于逼着每个调用点写一遍 try。
 */
@Composable
fun <T> loadState(vararg keys: Any?, block: suspend () -> T): LoadState<T> {
    val state by produceState<LoadState<T>>(initialValue = LoadState.Loading, *keys) {
        value = LoadState.Loading
        value = try {
            LoadState.Ready(block())
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            LoadState.Failed(e.message ?: "加载失败")
        }
    }
    return state
}
