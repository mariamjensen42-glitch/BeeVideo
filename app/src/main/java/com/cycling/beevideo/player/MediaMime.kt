package com.cycling.beevideo.player

import androidx.media3.common.MimeTypes

/**
 * 播放地址 → 显式 MIME（`null` = 交给 Media3 自己判）。
 *
 * ─── 为什么必须自己判 ────────────────────────────────────────────────
 * `DefaultMediaSourceFactory` 不带 mime 时走 `Util.inferContentType(Uri)`，
 * 而它只看 **URI 最后一段路径**：
 *
 * ```java
 * String lastPathSegment = uri.getLastPathSegment();
 * return lastPathSegment == null ? C.CONTENT_TYPE_OTHER : inferContentType(lastPathSegment);
 * ```
 *
 * query 完全不参与。于是 jar 那种自指地址
 * `http://127.0.0.1:9978/proxy?do=m3u8&url=…EP01.m3u8`
 * 的 `getLastPathSegment()` 是 `proxy` → 判成 OTHER → 走 `ProgressiveMediaSource`
 * → `UnrecognizedInputFormatException: None of the available extractors … could
 * read the stream {sniff failures: [NoDeclaredBrand, NoDeclaredBrand]}`。
 *
 * 这个报错**指向不到真正的原因**：看起来像"流本身坏了 / 源站防盗链挡了"，实际是
 * 宿主猜错了容器类型。实测 2026-09-16，同类地址换成显式 HLS 后正常起播。
 *
 * ─── 两条判据，顺序不能换 ────────────────────────────────────────────
 * ① **本地代理的自指地址**（路径 `/proxy` 且 query 里有 `do`）：只认
 *    [ACTION_M3U8] 这一个动作，**其余一律返回 null**（= 交给 Media3 自己猜）。
 *
 *    ⚠️ 这类地址**不能**往下落进 ②。原因很具体：取分片的地址里照样带着
 *    `.m3u8`（在 `url=` 参数里，而百分号编码不会动 `.`），靠 ② 的扩展名判据
 *    会被误判成 HLS，让 HLS 解析器去读一个二进制分片 —— 报错更远、更难查。
 *    这条是 `MediaMimeTest` 抓出来的，不是推理出来的。
 *
 *    ⚠️ **动作名的出处要分清**，否则会重犯一次已经犯过的错（把推断当约定）：
 *      - `do=m3u8` —— 真机上实测到过（糯米线路1 第01集，起播 mime=application/x-mpegURL）
 *      - `do=ck`   —— 真实 jar 的 dex 里有这个字面量（`custom_spider.jar` / `fty.jar`）
 *      - `do=proxy` / `do=media` —— **本项目自己推断的名字**，4 个真实 jar 的
 *        `do=` 取值清单里没有它们（实际是 `ali bili webdav local 6qc xbpq
 *        parseMix XYQBiu MixWeb ck`）
 *
 *    所以「其余一律 null」不只是保守，它是**唯一站得住的做法**：真实的动作名
 *    是多方言的，我们只对见过的那一个负责，剩下的交给 Media3 的探测。
 *    等哪天真在某个源上撞见 `do=proxy` 在取分片，再回来加分支不迟 ——
 *    在那之前为它写死一条判据，就是在给一个假名字造证据。
 *
 * ② **直链**：整串找扩展名/类型关键字，**含 query** —— `?url=x.mp4` 这种把真实
 *    地址塞在参数里的形态同样拿不到扩展名。
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

/**
 * 是不是本地代理的自指地址。判据取"路径 `/proxy` + query 里有 `do`"，
 * 而**不**去校验 host/端口：jar 拼地址用的是宿主给它的端口，我们只关心形态。
 */
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
 * 必须带前导边界：`ado=`、以及已编码的嵌套地址（``%3Fdo%3D``）都不该命中 ——
 * 前者是别的参数，后者是 `url=` 里的内容，不是这条地址自己的动作。
 *
 * 只用来**取**动作名；要不要据此改判由 [mimeTypeOfPlayUrl] 决定。
 */
private val DO_PARAM = Regex("[?&]do=([^&]*)")
