package com.cycling.beevideo.data.source.vod.catvod

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit

/**
 * 站点请求用的 HTTP 客户端。
 *
 * 全 App **只有一个 OkHttpClient 实例**：OkHttp 的连接池与线程池是按实例持有的，
 * 每个站点各建一个的话，站点一多就是几十个线程池在抢。
 * jar 里的 spider 拿到的是同一个实例（见 `com.github.catvod.net.OkHttp`）。
 */
object CatVodHttp {

    /**
     * 默认 UA 取移动端 Chrome。
     *
     * 不用 OkHttp 自带的 `okhttp/4.x`：不少小站的 CDN 会按照 UA 做拦截，
     * 非浏览器 UA 直接 403。这是实测中最省事的一个取值。
     *
     * **公开**是为了给播放器的 DataSource 复用（见 `player.PlayerFactory`）：
     * 抓取站点接口和拉取媒体分片打的是同一批 CDN，用同一个 UA 才不会出现
     * 「接口通、播放 403」这种一半好一半坏的状态。播放器自带的默认 UA 是
     * `ExoPlayerLib/x.y.z`，比这个更容易被拦。
     */
    const val DEFAULT_UA =
        "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Mobile Safari/537.36"

    val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .callTimeout(40, TimeUnit.SECONDS)
        .followRedirects(true)
        .followSslRedirects(true)
        .retryOnConnectionFailure(true)
        .build()

    /**
     * @param headers 站点自定义请求头。**不要在这里塞 UA**，除非站点明确要求某个特定值 ——
     *                一旦传了就会覆盖默认 UA。
     */
    suspend fun getText(url: String, headers: Map<String, String> = emptyMap()): String =
        getTextWithUrl(url, headers).first

    /**
     * 同 [getText]，但**连重定向之后的最终地址一起返回**。
     *
     * ─── 为什么需要它 ──────────────────────────────────────────────────
     * 配置正文里的相对路径（`./lib/drpy2.min.js`、`./js/360影视.js`）必须按
     * **配置实际所在的地址**解析，而这个地址经常不等于用户填的那个：
     * 短链、CDN 跳转、`gh-proxy` 之类的加速前缀都会 302。
     *
     * 参考宿主的 `Decoder.getJson` 取的就是 `res.request().url()`
     * （OkHttp 在跟随重定向后会把 request 换成最终那一条），这里照做：
     * **`resp.request.url` 而不是我们传进去的 `url`**。
     *
     * @return `(正文, 最终地址)`
     */
    suspend fun getTextWithUrl(url: String, headers: Map<String, String> = emptyMap()): Pair<String, String> =
        withContext(Dispatchers.IO) {
            val builder = Request.Builder().url(url).get()
            if (headers.keys.none { it.equals("User-Agent", ignoreCase = true) }) {
                builder.header("User-Agent", DEFAULT_UA)
            }
            headers.forEach { (k, v) -> builder.header(k, v) }
            client.newCall(builder.build()).execute().use { resp ->
                if (!resp.isSuccessful) error("HTTP ${resp.code} @ $url")
                resp.body?.string().orEmpty() to resp.request.url.toString()
            }
        }

    /** 下载二进制（jar / 图片等），落盘由调用方负责。 */
    suspend fun getBytes(url: String, headers: Map<String, String> = emptyMap()): ByteArray =
        withContext(Dispatchers.IO) {
            val builder = Request.Builder().url(url).get()
            if (headers.keys.none { it.equals("User-Agent", ignoreCase = true) }) {
                builder.header("User-Agent", DEFAULT_UA)
            }
            headers.forEach { (k, v) -> builder.header(k, v) }
            client.newCall(builder.build()).execute().use { resp ->
                if (!resp.isSuccessful) error("HTTP ${resp.code} @ $url")
                resp.body?.bytes() ?: ByteArray(0)
            }
        }
}
