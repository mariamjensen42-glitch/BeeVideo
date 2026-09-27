package com.cycling.beevideo.data.repository

import android.util.Log
import com.cycling.beevideo.data.local.ConfigCache
import kotlin.coroutines.cancellation.CancellationException

/** 正文、**重定向后的最终地址**、是否来自离线副本。 */
internal data class FetchedConfig(
    val text: String,
    val url: String,
    val fromCache: Boolean,
)

/** 取配置正文，失败时回落到同一地址的上次成功副本（见 [ConfigCache]）。 */
internal class ConfigFetcher(
    private val fetchTextWithUrl: suspend (url: String) -> Pair<String, String>,
    private val configCache: ConfigCache,
) {

    /** 最终地址给 `CatVodConfigDecoder` 用来解析相对路径；fromCache 要如实标出来。 */
    suspend fun fetch(url: String): FetchedConfig = try {
        val (text, finalUrl) = fetchTextWithUrl(url)
        configCache.write(url, text)
        FetchedConfig(text, finalUrl, fromCache = false)
    } catch (e: CancellationException) {
        throw e
    } catch (e: Throwable) {
        val cached = configCache.read(url)
        // 没有副本就按原样抛 —— 首次配置时本来就不该有东西可回落
        if (cached == null) throw e
        Log.w(TAG, "配置下载失败（${e.message}），改用本地副本")
        // 副本是按**原地址**存的，拿不到当初的最终地址；不跳转的配置两者一样
        FetchedConfig(cached, url, fromCache = true)
    }

    private companion object {
        const val TAG = "BeeSource"
    }
}
