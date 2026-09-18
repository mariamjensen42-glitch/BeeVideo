package com.cycling.beevideo.data.source.vod.js

import com.github.catvod.utils.Json
import com.github.catvod.utils.Util
import com.google.gson.Gson
import com.google.gson.JsonElement
import com.google.gson.annotations.SerializedName
import java.io.ByteArrayInputStream

/**
 * JS 爬虫 `proxy(...)` 的返回值载体
 * —— 逐行对齐参考宿主的 `quickjs/src/main/java/com/fongmi/quickjs/bean/Res.java`。
 *
 * ─── 它在链路里的位置 ────────────────────────────────────────────────
 * 只有**一条**路径会用到它：`params["from"] == "catvod"` 的代理请求
 * （见 [JsSpider.proxy2]）。JS 侧返回一段 **JSON 文本**，宿主把它解析成
 * 这个类，再摊成三元素数组 `[code, contentType, InputStream]` 交给
 * `LocalProxyServer`。
 *
 * 另一条路径（[JsSpider.proxy1]）JS 返回的是**数组**，不走这里。
 *
 * ─── ⚠️ 字段用 private `xxxValue` 的原因同 [Req] ──────────────────────
 * Kotlin 的 `var code` 会自动生成 `getCode()`，与手写的 `fun getCode()`
 * 冲突（platform declaration clash）。参考实现的字段本来就是 private 的，
 * 这里只是换个不撞名的名字。
 */
class Res {

    @SerializedName("code")
    private var codeValue: Int? = null

    @SerializedName("buffer")
    private var bufferValue: Int? = null

    @SerializedName("content")
    private var contentValue: String? = null

    @SerializedName("headers")
    private var headersValue: JsonElement? = null

    fun getCode(): Int = codeValue ?: 200

    /** `2` = content 是 base64（见 [getStream]）。 */
    fun getBuffer(): Int = bufferValue ?: 0

    fun getContent(): String = contentValue?.takeIf { it.isNotEmpty() } ?: ""

    private fun getHeaders(): JsonElement? = headersValue

    fun getHeader(): Map<String, String> = Json.toMap(getHeaders())

    /**
     * 响应的 MIME。
     *
     * 抠不到时给 `application/octet-stream` —— **不是** 猜 `text/html`：
     * JS 侧返回二进制（图片、分片）比返回文本更常见，猜 html 会让播放器/图片库
     * 走进文本分支。`octet-stream` 是"我不知道，你自己嗅探"，是这里唯一安全的答案。
     */
    fun getContentType(): String {
        val header = getHeader()
        for (key in CONTENT_TYPE_KEYS) {
            header[key]?.let { return it }
        }
        return "application/octet-stream"
    }

    /**
     * 响应体。
     *
     * `buffer == 2` 时 content 是 base64（JS 侧用它传二进制），其余当纯文本。
     * 返回值类型是 `ByteArrayInputStream` 而不是 `InputStream`：下游
     * （`ProxyPayload` → NanoHTTPD）要按顺序读，给一个可重放的流类型更稳妥。
     */
    fun getStream(): ByteArrayInputStream =
        if (getBuffer() == 2) ByteArrayInputStream(Util.decode(getContent()))
        else ByteArrayInputStream(getContent().toByteArray())

    companion object {

        private val CONTENT_TYPE_KEYS = listOf("Content-Type", "content-type")

        /**
         * 从 JS 返回的 JSON 文本解析。
         *
         * ⚠️ **恒返回非 null**（参考实现可能返回 null）：解析失败时给一个
         * 默认实例（code=200、空内容），调用方拿到的就是"空响应"而不是 NPE。
         * 这一点在这里尤其重要 —— 它跑在 NanoHTTPD 的工作线程上，
         * 一个 NPE 会让**播放器拿到一个断掉的连接**，
         * 表现为"播到一半卡住"，从日志里完全看不出是解析失败。
         */
        @JvmStatic
        fun objectFrom(json: String?): Res = try {
            Gson().fromJson(json, Res::class.java) ?: Res()
        } catch (_: Exception) {
            Res()
        }
    }
}
