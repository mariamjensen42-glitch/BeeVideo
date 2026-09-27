package com.cycling.beevideo.player

import androidx.media3.common.MimeTypes

/**
 * 播放地址 → 显式 MIME（`null` = 交给 Media3 自己判）。
 *
 * ⚠️ 必须自己判：`DefaultMediaSourceFactory` 不带 mime 时走 `Util.inferContentType(Uri)`，
 * 而它**只看 URI 最后一段路径**，query 完全不参与。jar 那种自指地址
 * `http://127.0.0.1:9978/proxy?do=m3u8&url=…EP01.m3u8` 的 lastPathSegment 是 `proxy`
 * → 判成 OTHER → 走 ProgressiveMediaSource → `UnrecognizedInputFormatException`。
 * 这个报错指向不到真正原因（看着像源站防盗链），实测换成显式 HLS 后正常起播。
 *
 * 判据顺序不能换：
 * ① 本地代理的自指地址（路径 `/proxy` + query 里有 `do`）：只认 [ACTION_M3U8]，
 *    其余一律返回 null。
 *
 *    ⚠️ 这类地址**不能**落到 ②：取分片的地址里也带着 `.m3u8`（在 `url=` 参数里，
 *    百分号编码不会动 `.`），靠扩展名判会被误判成 HLS，让 HLS 解析器去读二进制分片。
 *    这条是单测抓出来的，不是推理出来的。
 *
 *    ⚠️ 动作名的出处要分清，别再犯"把推断当约定"的错：`do=m3u8` 是真机实测到的；
 *    `do=ck` 是真实 jar dex 里的字面量；**`do=proxy` / `do=media` 是本项目自己编的名字**，
 *    4 个真实 jar 的 `do=` 清单里都没有它们。真实的动作名是多方言的，只对见过的那一个负责。
 *
 * ② 直链：整串找扩展名/类型关键字，**含 query** —— `?url=x.mp4` 这种形态同样拿不到扩展名。
 */
fun mimeTypeOfPlayUrl(url: String): String? {
    if (url.isEmpty()) return null
    val probe = url.lowercase()

    if (isLocalProxyUrl(probe)) {
        return if (proxyActionOf(probe) == ACTION_M3U8) MimeTypes.APPLICATION_M3U8 else null
    }

    if (probe.contains(".m3u8") || probe.contains("mpegurl")) return MimeTypes.APPLICATION_M3U8
    if (probe.contains(".mpd") || probe.contains("dash+xml")) return MimeTypes.APPLICATION_MPD

    return null
}

/** 判据取"路径 `/proxy` + query 里有 `do`"，不校验 host/端口：只关心形态。 */
private fun isLocalProxyUrl(probe: String): Boolean =
    probe.contains("$PROXY_PATH?") && DO_PARAM.containsMatchIn(probe)

/** `do` 的值；调用方须先用 [isLocalProxyUrl] 确认过存在。 */
private fun proxyActionOf(probe: String): String? =
    DO_PARAM.find(probe)?.groupValues?.getOrNull(1)

private const val PROXY_PATH = "/proxy"
private const val ACTION_M3U8 = "m3u8"

/**
 * `?do=xxx` / `&do=xxx`，取到下一个 `&` 或结束。
 *
 * ⚠️ 必须带前导边界：`ado=` 和已编码的嵌套地址（`%3Fdo%3D`）都不该命中 ——
 * 前者是别的参数，后者是 `url=` 里的内容。
 */
private val DO_PARAM = Regex("[?&]do=([^&]*)")
