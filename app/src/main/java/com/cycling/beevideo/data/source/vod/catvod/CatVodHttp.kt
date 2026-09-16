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
     */
    private const val DEFAULT_UA =
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
        withContext(Dispatchers.IO) {
            val builder = Request.Builder().url(url).get()
            if (headers.keys.none { it.equals("User-Agent", ignoreCase = true) }) {
                builder.header("User-Agent", DEFAULT_UA)
            }
            headers.forEach { (k, v) -> builder.header(k, v) }
            client.newCall(builder.build()).execute().use { resp ->
                if (!resp.isSuccessful) error("HTTP ${resp.code} @ $url")
                resp.body?.string().orEmpty()
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
