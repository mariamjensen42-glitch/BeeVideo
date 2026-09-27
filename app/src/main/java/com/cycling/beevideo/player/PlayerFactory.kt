package com.cycling.beevideo.player

import android.content.Context
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.datasource.cache.Cache
import androidx.media3.datasource.cache.CacheDataSink
import androidx.media3.datasource.cache.CacheDataSource
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.LoadControl
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.exoplayer.source.MediaSource
import com.cycling.beevideo.data.source.vod.catvod.CatVodHttp

/**
 * 播放器的组装点：缓冲策略、缓存数据源、请求头 / UA。
 *
 * ⚠️ **播放器与 MediaSource 是两种生命周期，别绑在一起**：[newPlayer] 建的 ExoPlayer
 * 不带媒体源工厂，媒体源由 [mediaSourceFactory] 单独产出。请求头（很多源要 Referer）
 * 是 `DataSource.Factory` 的**构建期**参数、建好改不了；早先按 headers 去 `remember`
 * 整个东西，结果是切集瞬间 target 短暂为 null → headers 变空 → 播放器被重建、再变回来
 * 又重建一次，一次切集建两个播放器。
 */
object PlayerFactory {

    /** 建一个**空**播放器（无媒体源）。调用方随后 `setMediaSource` + `prepare`。 */
    fun newPlayer(context: Context): ExoPlayer =
        ExoPlayer.Builder(context)
            .setLoadControl(loadControl())
            .build()

    /**
     * 缓冲策略。四个数字都偏离默认值，理由是实测的：
     *  - 起播门槛 2.5s → 1.5s：源站首包慢时用户对着转圈干等
     *  - 最多缓冲 50s(≈38MB) → 30s(≈23MB)：看 30 秒就退出时大半是白下载的。
     *    ⚠️ 有磁盘缓存之后多缓冲的部分不再是纯浪费（留在盘上下次省流量），所以不更激进
     *  - 回退缓冲 0s → 15s + 从关键帧起：让"拖回去看一眼"变成纯内存操作
     *  - targetBufferBytes 钉在 64MB：默认按可用内存算，8G 机器上能到 80MB+
     */
    fun loadControl(): LoadControl = DefaultLoadControl.Builder()
        .setBufferDurationsMs(
            MIN_BUFFER_MS,
            MAX_BUFFER_MS,
            BUFFER_FOR_PLAYBACK_MS,
            BUFFER_FOR_PLAYBACK_AFTER_REBUFFER_MS,
        )
        .setBackBuffer(BACK_BUFFER_MS, /* retainBackBufferFromKeyframe = */ true)
        .setTargetBufferBytes(TARGET_BUFFER_BYTES)
        .build()

    /**
     * 按当前目标的请求头建 MediaSource 工厂。换线路（请求头变了）时才需要重建。
     *
     * 返回类型写 `MediaSource.Factory` 而不是 `MediaSourceFactory`：后者在 media3 1.11.1
     * 已废弃，会直接告警。
     */
    fun mediaSourceFactory(headers: Map<String, String>, cache: Cache?): MediaSource.Factory =
        DefaultMediaSourceFactory(dataSourceFactory(headers, cache))

    private fun dataSourceFactory(
        headers: Map<String, String>,
        cache: Cache?,
    ): DataSource.Factory {
        val upstream = DefaultHttpDataSource.Factory()
            .setDefaultRequestProperties(withDefaultUserAgent(headers))
            // 很多源站会把 http 跳 https，不开这个开关会直接被拦下
            .setAllowCrossProtocolRedirects(true)
            // 与站点请求（CatVodHttp）取同一组超时
            .setConnectTimeoutMs(CONNECT_TIMEOUT_MS)
            .setReadTimeoutMs(READ_TIMEOUT_MS)

        if (cache == null) return upstream

        return CacheDataSource.Factory()
            .setCache(cache)
            .setUpstreamDataSourceFactory(upstream)
            // ⚠️ 这两个是**必须显式设**的（反编译 CacheDataSource$Factory 构造函数确认过：
            // 它只给 cacheReadDataSourceFactory 和 cacheKeyFactory 赋了值）。
            //  - 不设 sink：只读不写 —— "缓存开着"但磁盘永远是空的，且没有任何报错
            //  - 不设 FLAG_IGNORE_CACHE_ON_ERROR：缓存一坏（索引损坏 / 磁盘满 / 分片断）
            //    异常直接抛给播放器，用户看到的是"这个源播不了"，真正原因是本地磁盘
            .setCacheWriteDataSinkFactory(CacheDataSink.Factory().setCache(cache))
            .setFlags(CacheDataSource.FLAG_IGNORE_CACHE_ON_ERROR)
    }

    /**
     * 补齐 UA：站点接口和媒体分片打的是同一批 CDN，UA 不一致会出现「详情页能打开、
     * 一播就 403」。来源给了就用它的，没给补 [CatVodHttp.DEFAULT_UA]，
     * 而不是让 ExoPlayer 用它自带的 UA（被 CDN 拦的概率高得多）。
     */
    private fun withDefaultUserAgent(headers: Map<String, String>): Map<String, String> =
        if (headers.keys.any { it.equals("User-Agent", ignoreCase = true) }) {
            headers
        } else {
            headers + ("User-Agent" to CatVodHttp.DEFAULT_UA)
        }

    private const val MIN_BUFFER_MS = 15_000
    private const val MAX_BUFFER_MS = 30_000
    private const val BUFFER_FOR_PLAYBACK_MS = 1_500
    private const val BUFFER_FOR_PLAYBACK_AFTER_REBUFFER_MS = 4_000
    private const val BACK_BUFFER_MS = 15_000
    private const val TARGET_BUFFER_BYTES = 64 * 1024 * 1024

    private const val CONNECT_TIMEOUT_MS = 15_000
    private const val READ_TIMEOUT_MS = 20_000
}
