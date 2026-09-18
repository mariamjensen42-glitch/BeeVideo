package com.cycling.beevideo.data.proxy

import fi.iki.elonen.NanoHTTPD
import fi.iki.elonen.NanoHTTPD.Response.Status
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.InputStream

/**
 * jar 返回的 `Object[]` → HTTP 响应 的归一化测试。
 *
 * ─── 这里测的全是"真机上撞到过才会知道"的容错 ──────────────────────────
 * 这一层没有任何编译期约束：jar 返回的是一个裸 `Object[]`，元素个数、顺序、
 * 类型全靠约定。写错了的症状**指向不到这里**：
 *
 *   - 把响应头当响应体 → 播放器拿到一串 header 文本，报"流无法识别"
 *   - 硬转 `InputStream` → 老 jar 上 `ClassCastException`，且发生在
 *     "播放器开始取数据"那一刻的另一个线程里
 *   - 状态码缺省成 200 → 本该是 304/206 的响应变成全量 200，
 *     拖动进度条会重新下载整个文件（流量和体感都错，但不报错）
 *
 * 这几条都靠真机一集一集试太贵了，所以把它们钉成纯函数的断言。
 */
class ProxyPayloadTest {

    // ------------------------------------------------------------ 字段顺序

    /** 参考实现（FongMi `server/process/Proxy.java`）的顺序。 */
    @Test
    fun `响应体在第 3 位时原样采用`() {
        val body = ByteArrayInputStream("BODY".toByteArray())
        val headers = mapOf("X-A" to "1")

        val payload = proxyPayloadOf(arrayOf<Any?>(200, "video/mp4", body, headers))

        assertEquals(200, payload.code)
        assertEquals("video/mp4", payload.mime)
        assertSame(body, payload.body)
        assertSame(headers, payload.headers)
    }

    /**
     * 本项目 `Spider.proxy` 注释里记的顺序 —— 响应头在第 3 位。
     *
     * 这一条是整份文件的重点：**判据只有一个 —— 第三位是不是 Map**。
     * 只造一种顺序的话，那段交换代码永远不会被执行到。
     */
    @Test
    fun `响应头在第 3 位时交换两者`() {
        val body = ByteArrayInputStream("BODY".toByteArray())
        val headers = mapOf("X-A" to "1")

        val payload = proxyPayloadOf(arrayOf<Any?>(200, "video/mp4", headers, body))

        assertSame("交换后 body 应该是原来第 4 位那个", body, payload.body)
        assertSame("交换后 headers 应该是原来第 3 位那个", headers, payload.headers)
    }

    /**
     * 两位都不是 Map → **不交换**，按参考顺序把第 3 位当响应体。
     *
     * 这条曾经被我写反过：当时想的是"第 3 位是头，那体就在第 4 位"。但那样
     * 就是**在猜**了 —— 手头唯一能站住的证据是"第三位是 Map 才是响应头"，
     * 因为响应头必然是 Map，而响应体可能是流/字节数组/字符串，不可能被误认成头。
     *
     * 反过来的推论不成立：响应体**不可能**是 Map，所以"第四位是 Map"并不能
     * 推出"第三位是响应头"。没有 Map 时保持参考实现的顺序（`[状态码, MIME,
     * 响应体, 响应头]`）是唯一不需要额外假设的选择。
     */
    @Test
    fun `两位都不是 Map 时不交换，按参考顺序`() {
        val payload = proxyPayloadOf(arrayOf<Any?>(200, "video/mp4", "是体", "不是头"))
        assertEquals("是体", payload.body)
        assertNull(payload.headers)
    }

    // -------------------------------------------------------------- 状态码

    @Test
    fun `状态码不是数字时按 500`() {
        assertEquals(500, proxyPayloadOf(arrayOf<Any?>("200", "video/mp4", "x")).code)
        assertEquals(500, proxyPayloadOf(arrayOf<Any?>(null, "video/mp4", "x")).code)
    }

    /** jar 常返回 `Integer`，但 `Long` / 其它 Number 也得接住。 */
    @Test
    fun `状态码用 Number 读出，不假设是 Integer`() {
        assertEquals(206, proxyPayloadOf(arrayOf<Any?>(206L, "video/mp2t", "x")).code)
    }

    /**
     * 枚举里没有的码要**原样透传**，不能悄悄变成 500。
     *
     * 599 只是个占位：NanoHTTPD 的 `Status` 枚举里没有它（枚举最大到 505
     * `UNSUPPORTED_HTTP_VERSION`），所以必然走到 `RawProxyStatus` 那一支。
     */
    @Test
    fun `枚举里没有的状态码原样透传`() {
        val status = proxyStatusOf(599)
        assertTrue("599 不在 NanoHTTPD 的枚举里，应走 RawProxyStatus", status is RawProxyStatus)
        assertEquals(599, status.requestStatus)
    }

    @Test
    fun `枚举里有的状态码用枚举`() {
        assertSame(Status.OK, proxyStatusOf(200))
        assertSame(Status.PARTIAL_CONTENT, proxyStatusOf(206))
        assertSame(Status.FOUND, proxyStatusOf(302))
        assertSame(Status.NOT_FOUND, proxyStatusOf(404))
    }

    /** 连 HTTP 状态码都不算的值（0 / 负数 / 大于 599）只能是 bug，退到 500。 */
    @Test
    fun `非法状态码退到 500`() {
        assertSame(Status.INTERNAL_ERROR, proxyStatusOf(0))
        assertSame(Status.INTERNAL_ERROR, proxyStatusOf(-1))
        assertSame(Status.INTERNAL_ERROR, proxyStatusOf(600))
    }

    // ------------------------------------------------------------------ MIME

    @Test
    fun `MIME 缺失或不是字符串时按纯文本`() {
        assertEquals(
            NanoHTTPD.MIME_PLAINTEXT,
            proxyPayloadOf(arrayOf<Any?>(200, null, "x")).mime,
        )
        assertEquals(
            NanoHTTPD.MIME_PLAINTEXT,
            proxyPayloadOf(arrayOf<Any?>(200, 42, "x")).mime,
        )
    }

    // ---------------------------------------------------------------- 数组长度

    /**
     * 短数组不抛异常 —— 这个方法在 HTTP 服务的处理线程里被调用，抛出去只会变成
     * 一个"服务器内部错误"，而真正的原因（jar 返回了奇怪的数组）就再也看不到了。
     */
    @Test
    fun `空数组与短数组都不抛`() {
        val empty = proxyPayloadOf(arrayOf<Any?>())
        assertEquals(500, empty.code)
        assertEquals(NanoHTTPD.MIME_PLAINTEXT, empty.mime)
        assertNull(empty.body)
        assertNull(empty.headers)

        val short = proxyPayloadOf(arrayOf<Any?>(200, "video/mp4"))
        assertNull(short.body)
        assertNull(short.headers)
    }

    // ------------------------------------------------------------ 响应体的类型

    @Test
    fun `响应体是流时原样返回`() {
        val body = ByteArrayInputStream("STREAM".toByteArray())
        assertSame(body, proxyBodyStream(body))
    }

    @Test
    fun `响应体是字节数组时包成流`() {
        assertEquals("BYTES", proxyBodyStream("BYTES".toByteArray()).readText())
    }

    /** `String` 必须按 **UTF-8** 编码 —— 老 jar 返回中文时按平台默认码写会变乱码。 */
    @Test
    fun `响应体是字符串时按 UTF-8 编码`() {
        val stream = proxyBodyStream("中文内容")
        assertEquals("中文内容", stream.readText())
    }

    @Test
    fun `响应体为 null 时给空流`() {
        assertEquals(0, proxyBodyStream(null).readBytes().size)
    }

    /**
     * 没见过的类型：宁可返回 `toString()` 的字节，也不返回空流。
     *
     * 返回空流是把"这里收到了一个没见过的类型"伪装成"响应体是空的" ——
     * 播放器会安静地播不出东西，而没有一行日志能指向原因。
     */
    @Test
    fun `没见过的响应体类型退到 toString 而不是空流`() {
        assertEquals("12345", proxyBodyStream(12345).readText())
    }

    private fun InputStream.readText(): String = readBytes().toString(Charsets.UTF_8)
}
