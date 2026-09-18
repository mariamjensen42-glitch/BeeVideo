package com.cycling.beevideo.data.source.vod.js

import com.github.catvod.net.OkHttp
import com.github.catvod.utils.Json
import com.github.catvod.utils.Util
import com.whl.quickjs.wrapper.JSObject
import com.whl.quickjs.wrapper.QuickJSContext
import okhttp3.Call
import okhttp3.FormBody
import okhttp3.Headers
import okhttp3.Headers.Companion.toHeaders
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import java.nio.charset.Charset
import java.security.SecureRandom

/**
 * JS 的 `req()` 落到 HTTP 的那一层
 * —— 逐行对齐参考宿主的 `quickjs/src/main/java/com/fongmi/quickjs/utils/Connect.java`。
 *
 * 职责只有两件：**把 [Req] 拼成 OkHttp 的 `Request`**，以及
 * **把 `Response` 摊成 JS 能读的对象**。它不持有客户端、不做重试、不管线程。
 *
 * ─── `buffer` 的四种取值（这是 JS 侧的契约，不能改） ──────────────────
 * ```
 * 0 → content 是**按 charset 解码的字符串**（默认，最常用）
 * 1 → content 是**整数数组**（JS 侧当 Uint8Array 用）
 * 2 → content 是 **base64 字符串**
 * 3 → content 是**字节数组**（原样塞进 JS 对象，[JSUtil] 之外的一手）
 * ```
 * 真实源里 0 和 2 最常见（2 用于二进制接口，比如加密后的图片）。
 * 搞错 = 拿到乱码或 `undefined`，而 JS 侧的表现通常是"解析不出数据"。
 *
 * ─── ⚠️ 不引 Guava ───────────────────────────────────────────────────
 * 参考实现取请求头的 `Content-Type` 用的是 Guava 的
 * `HttpHeaders.CONTENT_TYPE` —— 那只是为了一个字符串常量引入一整个 Guava。
 * 这里直接写字面量 `"Content-Type"`（HTTP 头名大小写不敏感，
 * 而 OkHttp 的 `Headers.get` 本来就是大小写不敏感的）。
 */
object Connect {

    private const val CONTENT_TYPE = "Content-Type"

    /**
     * 建一个**还没执行**的请求。
     *
     * 超时与重定向是**逐请求**从 [Req] 取的：真实 JS 源会在 options 里
     * 写 `{timeout: 30000, redirect: 0}`，而这两项直接影响能不能取到数据。
     */
    fun to(url: String, req: Req): Call {
        val client = OkHttp.client(req.isRedirect(), req.getTimeout().toLong())
        // 请求头可能是 null（options 里没写 headers）—— toHeaders() 需要非空 Map
        return client.newCall(getRequest(url, req, req.getHeader().toHeaders()))
    }

    /**
     * 把响应摊成 JS 对象：`{code, headers, content}`。
     *
     * ⚠️ **响应体必须在 `use` 里读完**（`Response` 是 `Closeable`）：
     * 漏掉关闭会让连接一直挂在池里，跑久了就是连接耗尽。
     * 参考实现用的是 Java 的 try-with-resources，这里是 Kotlin 的 `use`，语义相同。
     *
     * 任何异常都退化成 [error]（一个"空响应"对象），**不抛** ——
     * 抛出去的异常会穿过 JNI 到达 JS 侧，而 JS 爬虫通常没有 try/catch
     * 包住 `req()`，结果是整个站点查询失败且看不到原因。
     */
    fun success(ctx: QuickJSContext, req: Req, res: Response): JSObject = try {
        res.use {
            val jsObject = ctx.createNewJSObject()
            val jsHeader = ctx.createNewJSObject()
            setHeader(ctx, res, jsHeader)
            jsObject.setProperty("code", res.code)
            jsObject.setProperty("headers", jsHeader)

            val bytes = res.body?.bytes() ?: ByteArray(0)
            when (req.getBuffer()) {
                0 -> jsObject.setProperty("content", String(bytes, Charset.forName(req.getCharset())))
                1 -> jsObject.setProperty("content", JSUtil.toArray(ctx, bytes))
                2 -> jsObject.setProperty("content", Util.base64(bytes))
                3 -> jsObject.setProperty("content", bytes)
            }
            jsObject
        }
    } catch (_: Exception) {
        error(ctx)
    }

    /**
     * 失败时的占位对象。
     *
     * ⚠️ `code` 在这里是**空字符串**而不是数字 —— 这与 [success] 不一致，
     * 但**必须照搬**：JS 侧有源用 `if (res.code == '')` 这样的判据区分
     * "请求失败"和"HTTP 4xx/5xx"。改成 `0` 会让那些判断失效，
     * 而表现是"接口失败时源不报错、只是返回空列表"。
     */
    fun error(ctx: QuickJSContext): JSObject {
        val jsObject = ctx.createNewJSObject()
        val jsHeader = ctx.createNewJSObject()
        jsObject.setProperty("headers", jsHeader)
        jsObject.setProperty("content", "")
        jsObject.setProperty("code", "")
        return jsObject
    }

    private fun getRequest(url: String, req: Req, headers: Headers): Request {
        val builder = Request.Builder().url(url).headers(headers)
        return when {
            req.getMethod().equals("post", ignoreCase = true) ->
                builder.post(getPostBody(req, headers[CONTENT_TYPE])).build()
            // "header" 就是 HTTP 的 HEAD：只要响应头，不要响应体
            req.getMethod().equals("header", ignoreCase = true) -> builder.head().build()
            else -> builder.get().build()
        }
    }

    /**
     * POST 请求体。
     *
     * 判据的顺序**有讲究**：`data` 有值时才看 `postType`，三个分支互斥；
     * 都不匹配才退回"把 `body` 当裸文本发"。
     *
     * `body` 那条还要求 `contentType != null`：OkHttp 的
     * `toRequestBody(null)` 会得到一个**没有 Content-Type 的 body**，
     * 而这种请求在真实服务端上极容易被当成错误请求拒掉。
     * 参考实现同样要求 contentType 非空，否则发空 body。
     */
    private fun getPostBody(req: Req, contentType: String?): RequestBody {
        val data = req.getData()
        if (data != null && "json" == req.getPostType()) return getJsonBody(req)
        if (data != null && "form" == req.getPostType()) return getFormBody(req)
        if (data != null && "form-data" == req.getPostType()) return getFormDataBody(req)
        val body = req.getBody()
        if (body != null && contentType != null) return body.toRequestBody(contentType.toMediaType())
        // 空 body 的 POST：合法但少见（JS 侧想触发一个纯副作用接口）
        return ByteArray(0).toRequestBody(null)
    }

    private fun getJsonBody(req: Req): RequestBody =
        req.getData()!!.toString().toRequestBody("application/json; charset=utf-8".toMediaType())

    private fun getFormBody(req: Req): RequestBody {
        val builder = FormBody.Builder()
        Json.toMap(req.getData()).forEach { (key, value) -> builder.add(key, value) }
        return builder.build()
    }

    /**
     * `multipart/form-data`。
     *
     * boundary 用 `SecureRandom` 生成 —— 参考实现如此。**不要换成固定串**：
     * 边界值只要在正文里出现就会切错分片，随机化是这个协议唯一的保护。
     *
     * 数字 42949 / 67296 是参考实现里挑的取值（拼出一个足够长的十进制数），
     * 照搬以免和它产生行为差异。
     */
    private fun getFormDataBody(req: Req): RequestBody {
        val random = SecureRandom()
        val boundary = "--dio-boundary-${random.nextInt(42949)}${random.nextInt(67296)}"
        val builder = MultipartBody.Builder(boundary).setType(MultipartBody.FORM)
        Json.toMap(req.getData()).forEach { (key, value) -> builder.addFormDataPart(key, value) }
        return builder.build()
    }

    /**
     * 响应头 → JS 对象。
     *
     * 单值头直接给字符串，多值头（`Set-Cookie`、`Vary`…）给数组 ——
     * 这是参考实现的判据，也是 JS 侧能处理的两种形态。
     * ⚠️ 多值头**不能**只给第一个：`Set-Cookie` 丢一个就是登录态失效。
     */
    private fun setHeader(ctx: QuickJSContext, res: Response, target: JSObject) {
        for ((key, values) in res.headers.toMultimap()) {
            if (values.size == 1) target.setProperty(key, values[0])
            if (values.size >= 2) target.setProperty(key, JSUtil.toArray(ctx, values))
        }
    }
}
