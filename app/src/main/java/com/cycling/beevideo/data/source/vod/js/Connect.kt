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
 * JS 的 `req()` 落到 HTTP 的那一层，逐行对齐参考宿主的 `Connect.java`。
 * 只做两件事：把 [Req] 拼成 OkHttp 的 `Request`，把 `Response` 摊成 JS 能读的对象。
 *
 * ⚠️ `buffer` 的四种取值是 JS 侧的契约，不能改：
 * `0` 按 charset 解码的字符串（默认）、`1` 整数数组、`2` base64、`3` 字节数组。
 * 搞错 = 拿到乱码或 `undefined`，JS 侧表现为"解析不出数据"。
 *
 * 不引 Guava：只为取一个 `Content-Type` 常量不值当，直接写字面量。
 */
object Connect {

    private const val CONTENT_TYPE = "Content-Type"

    /** 建一个**还没执行**的请求。超时与重定向是逐请求从 [Req] 取的。 */
    fun to(url: String, req: Req): Call {
        val client = OkHttp.client(req.isRedirect(), req.getTimeout().toLong())
        // 请求头可能是 null（options 里没写 headers）—— toHeaders() 需要非空 Map
        return client.newCall(getRequest(url, req, req.getHeader().toHeaders()))
    }

    /**
     * 把响应摊成 JS 对象：`{code, headers, content}`。
     *
     * ⚠️ 响应体必须在 `use` 里读完：漏掉关闭会让连接挂在池里，跑久了就是连接耗尽。
     *
     * ⚠️ 任何异常都退化成 [error]，**不抛** —— 抛出去的会穿过 JNI 到 JS 侧，
     * 而 JS 爬虫通常没用 try/catch 包住 `req()`。
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
     * ⚠️ `code` 是**空字符串**而不是数字 —— 与 [success] 不一致但必须照搬：
     * JS 侧有源用 `if (res.code == '')` 区分"请求失败"和"HTTP 4xx/5xx"。
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
     * POST 请求体。判据顺序有讲究：`data` 有值时才看 `postType`，三个分支互斥。
     *
     * ⚠️ `body` 那条还要求 `contentType != null`：`toRequestBody(null)` 会得到没有
     * Content-Type 的 body，这种请求在真实服务端上极容易被当成错误请求拒掉。
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
     * ⚠️ boundary 必须用 `SecureRandom`，不要换成固定串：边界值只要在正文里出现
     * 就会切错分片，随机化是这个协议唯一的保护。数字照搬参考实现以免产生行为差异。
     */
    private fun getFormDataBody(req: Req): RequestBody {
        val random = SecureRandom()
        val boundary = "--dio-boundary-${random.nextInt(42949)}${random.nextInt(67296)}"
        val builder = MultipartBody.Builder(boundary).setType(MultipartBody.FORM)
        Json.toMap(req.getData()).forEach { (key, value) -> builder.addFormDataPart(key, value) }
        return builder.build()
    }

    /**
     * 响应头 → JS 对象：单值头给字符串，多值头给数组。
     * ⚠️ 多值头不能只给第一个：`Set-Cookie` 丢一个就是登录态失效。
     */
    private fun setHeader(ctx: QuickJSContext, res: Response, target: JSObject) {
        for ((key, values) in res.headers.toMultimap()) {
            if (values.size == 1) target.setProperty(key, values[0])
            if (values.size >= 2) target.setProperty(key, JSUtil.toArray(ctx, values))
        }
    }
}
