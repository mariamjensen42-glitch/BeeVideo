package com.cycling.beevideo.player

import android.os.Bundle
import com.cycling.beevideo.domain.model.NowPlaying
import com.cycling.beevideo.domain.model.PlayTarget

/**
 * 「起播」这条命令在 Controller 与 Service 之间的载体。
 *
 * ⚠️ 不走 `controller.setMediaItem`：媒体源必须按**请求头**现场建（头是构建期参数，
 * 见 `PlayerFactory`），而 setMediaItem 走的是服务的 `DefaultMediaSourceFactory`，
 * 头传不进去。所以起播走自定义命令，界面元数据跟着命令一起走。
 */
object PlayCommand {

    const val ACTION = "com.cycling.beevideo.PLAY_TARGET"

    private const val KEY_URL = "url"
    private const val KEY_PARSE = "parse"
    private const val KEY_HEADERS = "headers"
    private const val KEY_MIME = "mime"
    private const val KEY_RESUME = "resume"
    private const val KEY_TITLE = "title"
    private const val KEY_SUBTITLE = "subtitle"
    private const val KEY_ARTWORK = "artwork"
    private const val KEY_QUOTA = "quota"
    private const val KEY_INCOGNITO = "incognito"

    /** 界面侧打包。请求头已按用户自定义兜底合并过；MIME 由调用方判好（判据在 `MediaMime`）。 */
    fun bundle(
        url: String,
        parse: Boolean,
        headers: Map<String, String>,
        mime: String?,
        resumeAtMs: Long,
        nowPlaying: NowPlaying,
        quotaBytes: Long,
        incognito: Boolean,
    ): Bundle = Bundle().apply {
        putString(KEY_URL, url)
        putBoolean(KEY_PARSE, parse)
        putBundle(KEY_HEADERS, Bundle().apply { headers.forEach { (k, v) -> putString(k, v) } })
        putString(KEY_MIME, mime)
        putLong(KEY_RESUME, resumeAtMs)
        putString(KEY_TITLE, nowPlaying.title)
        putString(KEY_SUBTITLE, nowPlaying.subtitle)
        putString(KEY_ARTWORK, nowPlaying.artworkUri)
        putLong(KEY_QUOTA, quotaBytes)
        putBoolean(KEY_INCOGNITO, incognito)
    }

    /** 服务侧解包。`parse` 已在会话侧拦掉，到这里的都该播。 */
    fun asTarget(args: Bundle): PlayTarget {
        val headers = args.getBundle(KEY_HEADERS)
        return PlayTarget(
            url = args.getString(KEY_URL).orEmpty(),
            headers = headers?.keySet()?.associateWith { headers.getString(it).orEmpty() }.orEmpty(),
            parse = args.getBoolean(KEY_PARSE),
        )
    }

    fun resumeAtMs(args: Bundle): Long = args.getLong(KEY_RESUME)

    fun mime(args: Bundle): String? = args.getString(KEY_MIME)

    fun headers(args: Bundle): Map<String, String> = asTarget(args).headers

    fun nowPlaying(args: Bundle): NowPlaying = NowPlaying(
        title = args.getString(KEY_TITLE).orEmpty(),
        subtitle = args.getString(KEY_SUBTITLE).orEmpty(),
        artworkUri = args.getString(KEY_ARTWORK),
    )

    fun quotaBytes(args: Bundle): Long = args.getLong(KEY_QUOTA)

    fun incognito(args: Bundle): Boolean = args.getBoolean(KEY_INCOGNITO)
}
