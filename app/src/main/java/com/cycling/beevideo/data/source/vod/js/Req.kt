package com.cycling.beevideo.data.source.vod.js

import com.github.catvod.utils.Json
import com.google.gson.Gson
import com.google.gson.JsonElement
import com.google.gson.annotations.SerializedName

/**
 * JS 侧 `req(url, options)` / `http(url, options)` 的 **options 载体**
 * —— 逐行对齐参考宿主的 `quickjs/src/main/java/com/fongmi/quickjs/bean/Req.java`。
 *
 * JS 传进来的是一坨 `{method, headers, body, data, timeout, buffer, …}`，
 * Gson 直接按 `@SerializedName` 摊到这个类上。
 *
 * ─── ⚠️ 为什么字段是 private 的 `xxxValue`，而读法是 `getXxx()` ─────────
 * 这是 Kotlin 特有的坑，**不是风格选择**：参考实现是
 * `private Integer buffer` + `public int getBuffer()`，而 Kotlin 里
 * 写成 `var buffer: Int?` 会**自动生成** `getBuffer()` —— 再手写一个
 * `fun getBuffer()` 就是 **platform declaration clash**，编译期直接报
 * "accidentally overrides nothing"/"conflicting overloads"。
 *
 * 所以 backing field 换成不会撞名的 `bufferValue`，公开读法仍然是 `getBuffer()`：
 * 调用方（[Connect]）的代码与参考实现逐字一致。
 * `@SerializedName` 挂在 backing field 上，Gson 的绑定不受改名影响。
 *
 * ─── 默认值就是协议 ──────────────────────────────────────────────────
 * `redirect` 默认 **1（跟随）**、`timeout` 默认 **10000ms**、`postType` 默认
 * **json**、`method` 默认 **get**。这些不是"兜底"，是 JS 侧省略该字段时的既定语义。
 * 改任何一个都会让一大批只在 JS 里写 `{headers: {...}}` 的源行为变化。
 */
class Req {

    @SerializedName("buffer")
    private var bufferValue: Int? = null

    @SerializedName("redirect")
    private var redirectValue: Int? = null

    @SerializedName("timeout")
    private var timeoutValue: Int? = null

    @SerializedName("postType")
    private var postTypeValue: String? = null

    @SerializedName("method")
    private var methodValue: String? = null

    @SerializedName("body")
    private var bodyValue: String? = null

    @SerializedName("data")
    private var dataValue: JsonElement? = null

    @SerializedName("headers")
    private var headersValue: JsonElement? = null

    /** 0 = 字符串、1 = 字节数组、2 = base64、3 = 原始字节（见 [Connect]）。 */
    fun getBuffer(): Int = bufferValue ?: 0

    fun getRedirect(): Int = redirectValue ?: 1

    fun getTimeout(): Int = timeoutValue ?: 10000

    fun getPostType(): String = postTypeValue?.takeIf { it.isNotEmpty() } ?: "json"

    fun getMethod(): String = methodValue?.takeIf { it.isNotEmpty() } ?: "get"

    fun getBody(): String? = bodyValue

    fun getData(): JsonElement? = dataValue

    private fun getHeaders(): JsonElement? = headersValue

    fun isRedirect(): Boolean = getRedirect() == 1

    /**
     * 请求头。
     *
     * ⚠️ 这里**不做** `Content-Type` 之外的处理 —— 大小写两套都查一遍是
     * 参考实现的行为（HTTP 头名大小写不敏感，但 JS 侧写 `content-type`
     * 和 `Content-Type` 的都存在）。
     */
    fun getHeader(): Map<String, String> = Json.toMap(getHeaders())

    /**
     * 响应体该按什么字符集解码。
     *
     * 从 `Content-Type` 里抠 `charset=`；抠不到就是 **UTF-8**。
     *
     * 这是**必须的**：真实站点里 GBK 一大把，而 `String(bytes)` 默认按 UTF-8 解，
     * 结果是一屏乱码、且不报任何错。GBK 那部分由 JS 侧的 `gbk.js` 再兜一层。
     */
    fun getCharset(): String {
        val header = getHeader()
        for (key in CONTENT_TYPE_KEYS) {
            header[key]?.let { return charsetOf(it) }
        }
        return "UTF-8"
    }

    private fun charsetOf(value: String): String {
        for (text in value.split(";")) {
            if (text.contains("charset=")) {
                // trim 掉了尾随空格：`charset=utf-8 ` 会让 Charset.forName 抛异常，
                // 而那个异常会被 Connect 的 catch 吞成"请求失败"，指向不到这里
                return text.split("=").getOrNull(1)?.trim()?.takeIf { it.isNotEmpty() } ?: "UTF-8"
            }
        }
        return "UTF-8"
    }

    companion object {

        private val CONTENT_TYPE_KEYS = listOf("Content-Type", "content-type")

        /**
         * 从 JS 传来的 options 字符串反序列化。
         *
         * ⚠️ **恒返回非 null**（参考实现可能返回 null）：
         * `Gson.fromJson` 在输入是空串 / `"null"` / 语法错误时返回 null 或抛异常，
         * 而调用方（[Global.req]）紧接着就要用它的字段。返回一个全默认值的实例，
         * 语义正好等于"JS 只想发一个普通 GET"—— 那比 NPE 有用得多。
         */
        @JvmStatic
        fun objectFrom(json: String?): Req = try {
            Gson().fromJson(json, Req::class.java) ?: Req()
        } catch (_: Exception) {
            Req()
        }
    }
}
