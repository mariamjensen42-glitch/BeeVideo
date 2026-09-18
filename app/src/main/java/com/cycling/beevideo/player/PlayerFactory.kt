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
 * 播放器的组装点。
 *
 * 从 `PlayerScreen` 里抽出来的原因不是「代码短一点」，是那三块配置
 * （缓冲策略、缓存数据源、请求头/UA）都是**与界面无关的播放知识**，
 * 混在 composable 里既没法单测，也没法在换播放页时复用。
 *
 * ─── 播放器与 MediaSource 是两种生命周期，别绑在一起 ──────────────────
 * [newPlayer] 建的 `ExoPlayer` **不带** MediaSource 工厂，媒体源由
 * [mediaSourceFactory] 单独产出、由 `PlayerScreen` 在换集时 `setMediaSource()` 塞进去。
 * 这样做是因为请求头（很多源要 Referer，不带就是 403）只在拿到 `playTarget`
 * 之后才知道，而请求头是 `DataSource.Factory` 的**构建期**参数、建好改不了。
 * 早先的写法是按 headers 去 `remember` 整个东西，结果是「切集瞬间 target
 * 短暂为 null → headers 变成空 map → 整个播放器被重建 → 再变回来又重建一次」，
 * 一次切集建两个播放器。播放器只建一次、媒体源每次换，才是对的分法。
 */
object PlayerFactory {

    // ------------------------------------------------------------ 播放器

    /**
     * 建一个**空**播放器（无媒体源）。调用方随后 `setMediaSource` + `prepare`。
     */
    fun newPlayer(context: Context): ExoPlayer =
        ExoPlayer.Builder(context)
            .setLoadControl(loadControl())
            .build()

    /**
     * 缓冲策略。
     *
     * ─── 改了什么、为什么 ────────────────────────────────────────────────
     * 默认值是「至少缓冲 50 秒 / 最多缓冲 50 秒 / 2.5 秒才起播 / 回退缓冲 0 秒」。
     * 这四个数字在这个场景下各有一个问题：
     *
     *   - **起播门槛 2.5 秒**：源站首包慢的时候，用户对着转圈干等。降到 1.5 秒，
     *     代价只是极小概率的早停（真停了也只是重缓冲一次）。
     *   - **最多缓冲 50 秒**：50 秒 × 771 KB/s ≈ **38 MB**。用户看 30 秒就退出，
     *     那 38 MB 里有一大半是白下载的。降到 30 秒（≈23 MB），既保住抗抖动
     *     能力，又砍掉三分之一的废弃流量。**注意有磁盘缓存之后，多缓冲的那部分
     *     不再是纯浪费**（它留在磁盘上，下次看还省流量），所以这里不取更激进的值。
     *   - **回退缓冲 0 秒**：默认完全不保留已经播过的数据，往回拖 10 秒就要重新
     *     走一遍网络（HLS 是从最近的 TS 分片重下）。给 15 秒 + 从关键帧开始，
     *     让「拖回去看一眼」变成纯内存操作。
     *   - **targetBufferBytes**：默认按设备可用内存算，8G 机器上能到 80 MB+。
     *     钉在 64 MB，既够 1080p 用，又不会因为开着直播/长片被系统 LMK 盯上。
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

    // -------------------------------------------------------- 媒体源工厂

    /**
     * 按当前目标的请求头建 MediaSource 工厂。换线路（请求头变了）时才需要重建。
     *
     * 返回类型写 `MediaSource.Factory` 而不是 `MediaSourceFactory`：后者在 media3
     * 1.11.1 已标记废弃，只保留给老代码，`:app:compileDebugKotlin` 会直接告警。
     *
     * @param cache [MediaCacheProvider.get] 的返回值，`null` 表示这次不走磁盘缓存。
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
            // 与站点请求（CatVodHttp）取同一组超时：媒体分片往往比接口更慢，
            // 但也不该无限等 —— 卡住时宁可报错重试，也不要用户对着黑屏
            .setConnectTimeoutMs(CONNECT_TIMEOUT_MS)
            .setReadTimeoutMs(READ_TIMEOUT_MS)

        if (cache == null) return upstream

        return CacheDataSource.Factory()
            .setCache(cache)
            .setUpstreamDataSourceFactory(upstream)
            /*
             * ⚠️ 这两个是**必须显式设**的，别删，也别以为它们是默认值 —— 反编译
             * `CacheDataSource$Factory` 的构造函数确认过，它只给
             * `cacheReadDataSourceFactory`（FileDataSource）和 `cacheKeyFactory`
             * 赋了值，`cacheWriteDataSinkFactory` 是 **null**、`flags` 是 **0**。
             *
             *   - 不设 sink：CacheDataSource 只读不写 —— 「缓存开着」但磁盘上
             *     永远是空的，且没有任何报错。
             *   - 不设 FLAG_IGNORE_CACHE_ON_ERROR：缓存出问题（索引库损坏、
             *     磁盘写满、分片读到一半断了）时异常会**直接抛给播放器**，
             *     用户看到的是「这个源播不了」，而真正的原因是本地磁盘。
             *     带上这个 flag，出错时它会把缓存整体弃用、退回纯网络，
             *     这一次运行内不再碰缓存。
             */
            .setCacheWriteDataSinkFactory(CacheDataSink.Factory().setCache(cache))
            .setFlags(CacheDataSource.FLAG_IGNORE_CACHE_ON_ERROR)
    }

    /**
     * 补齐 UA。
     *
     * 站点接口和媒体分片打的是**同一批 CDN**，UA 不一致就会出现「详情页能打开、
     * 一播就 403」这种一半好一半坏的状态。所以：来源明确给了 UA 就用它的，
     * 没给就补上 [CatVodHttp.DEFAULT_UA]（移动端 Chrome），而不是让 ExoPlayer
     * 用它自带的 `ExoPlayerLib/x.y.z` —— 那个 UA 被 CDN 拦的概率高得多。
     */
    private fun withDefaultUserAgent(headers: Map<String, String>): Map<String, String> =
        if (headers.keys.any { it.equals("User-Agent", ignoreCase = true) }) {
            headers
        } else {
            headers + ("User-Agent" to CatVodHttp.DEFAULT_UA)
        }

    // 缓冲窗口。每个数字的理由都写在 [loadControl] 的注释里。
    private const val MIN_BUFFER_MS = 15_000
    private const val MAX_BUFFER_MS = 30_000
    private const val BUFFER_FOR_PLAYBACK_MS = 1_500
    private const val BUFFER_FOR_PLAYBACK_AFTER_REBUFFER_MS = 4_000
    private const val BACK_BUFFER_MS = 15_000
    private const val TARGET_BUFFER_BYTES = 64 * 1024 * 1024

    private const val CONNECT_TIMEOUT_MS = 15_000
    private const val READ_TIMEOUT_MS = 20_000
}
