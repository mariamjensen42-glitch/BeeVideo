package com.cycling.beevideo.data.proxy

import fi.iki.elonen.NanoHTTPD
import fi.iki.elonen.NanoHTTPD.Response.IStatus
import fi.iki.elonen.NanoHTTPD.Response.Status
import java.io.ByteArrayInputStream
import java.io.InputStream

/**
 * jar 的 `Object[]` 归一化之后的样子。
 *
 * 抽成纯数据 + 纯函数（不带任何 NanoHTTPD 的 `Response`）只有一个目的：
 * **让它可测**。下面这几条按字段类型容错的规则全部来自"实测踩到了才发现"，
 * 而它们错一次的表现都是运行期的 `ClassCastException` 或者一个内容错位的响应 ——
 * 两种都不会在编译期有任何提示。详见 [proxyPayloadOf]。
 */
internal data class ProxyPayload(
    /** HTTP 状态码。取不到时按 500 —— 见 [proxyPayloadOf]。 */
    val code: Int,
    /** Content-Type。取不到时按 `text/plain`。 */
    val mime: String,
    /** 响应体原文，交给 [proxyBodyStream] 转流。 */
    val body: Any?,
    /** 响应头集合；不是 Map 时按"没有头"处理。 */
    val headers: Map<*, *>?,
)

/**
 * `Object[]` → [ProxyPayload]。
 *
 * ⚠️ 这个数组**没有单一权威的字段顺序**，所以按**运行时类型**归一，
 * 而不是硬编一个下标约定：
 *   参考实现（FongMi `server/process/Proxy.java`）读的是
 *     `[status:Int, mime:String, body:InputStream, headers:Map?]`
 *   而本项目 `Spider.proxy` 的注释记的是 `[状态码, MIME, 响应头, 响应体]`。
 * 两者对前两位一致，分歧只在 2/3 谁是谁。**谁在第三位是 Map，谁就是响应头。**
 *
 * 下标越界 / 类型不对一律不抛：这个方法在 HTTP 服务的处理线程里被调用，
 * 抛出去只会变成一个"服务器内部错误"，而真正的原因（jar 返回了奇怪的数组）
 * 就再也看不到了。缺什么给什么默认值，是这里唯一合理的选择。
 *
 * [ProxyPayload.body] 不在这里转流，是因为"不是流"的那几种情况本身就要被断言
 * —— 见 `ProxyPayloadTest`。
 */
internal fun proxyPayloadOf(raw: Array<Any?>): ProxyPayload {
    val code = (raw.getOrNull(0) as? Number)?.toInt() ?: 500
    val mime = raw.getOrNull(1) as? String ?: NanoHTTPD.MIME_PLAINTEXT

    var second = raw.getOrNull(2)
    var third = raw.getOrNull(3)
    if (second is Map<*, *>) {
        val swap = second
        second = third
        third = swap
    }

    return ProxyPayload(
        code = code,
        mime = mime,
        body = second,
        headers = third as? Map<*, *>,
    )
}

/**
 * 响应体 → 输入流，三种表示都见过：`InputStream`（新版 jar）、
 * `ByteArray`（老版）、`String`。
 *
 * 硬转 `InputStream` 会在老 jar 上 `ClassCastException`，而那个报错发生在
 * 播放器取数据的那一刻，指向不到这里的类型假设。
 *
 * 兜底用 `toString().toByteArray()` 而不是直接给空流：宁可返回一段看着像乱码的
 * 文本，也好过把"这里收到了一个没见过的类型"伪装成"响应体是空的"。
 */
internal fun proxyBodyStream(body: Any?): InputStream = when (body) {
    null -> ByteArrayInputStream(EMPTY)
    is InputStream -> body
    is ByteArray -> ByteArrayInputStream(body)
    is String -> ByteArrayInputStream(body.toByteArray(Charsets.UTF_8))
    else -> ByteArrayInputStream(body.toString().toByteArray(Charsets.UTF_8))
}

/**
 * jar 可能返回 2xx/3xx 之外的码（206、304 等），NanoHTTPD 的枚举认不全，
 * 认不出就用 [RawProxyStatus] 原样透传；连域名都不合法的码（0、负数、大于 599）
 * 才退到 500 —— 那种值只能是 bug，透传出去播放器会拿到一个没法解析的状态行。
 */
internal fun proxyStatusOf(code: Int): IStatus =
    Status.lookup(code) ?: if (code in 100..599) RawProxyStatus(code) else Status.INTERNAL_ERROR

/** NanoHTTPD 的 `Status` 枚举里没有的状态码。 */
internal class RawProxyStatus(private val code: Int) : IStatus {
    override fun getDescription(): String = "$code Proxy Status"
    override fun getRequestStatus(): Int = code
}

private val EMPTY = ByteArray(0)
