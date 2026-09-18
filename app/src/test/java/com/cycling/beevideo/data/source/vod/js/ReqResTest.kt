package com.cycling.beevideo.data.source.vod.js

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [Req] / [Res] 的解析与默认值测试。
 *
 * ─── 这两个类是"JS 传进来的东西"的唯一入口 ────────────────────────────
 * [Req] 承载 JS 侧 `req(url, options)` 的 options，它的**默认值就是协议**：
 * 省略 `redirect` 等于"跟随重定向"、省略 `timeout` 等于 10 秒、省略 `postType`
 * 等于 JSON。改任何一个默认值，都会让一大批"只写了 `{headers: {...}}`"的
 * 真实源行为发生变化 —— 而且不报任何错。
 *
 * [Res] 是 `proxy` 那条路的返回值载体，`buffer == 2`（content 是 base64）
 * 是最容易忘的一条分支。
 *
 * 两者都**不能用 android.jar 的东西**（本项目开了 `isReturnDefaultValues`，
 * 桩会返回 null 而不是抛），所以实现里换成了 Kotlin 的 `isNullOrEmpty`
 * 与 `java.util.Base64` —— 这几条用例同时也在钉住那个替换。
 */
class ReqResTest {

    // ── Req：默认值就是协议 ───────────────────────────────────────────

    @Test
    fun `空 options 走全套默认值`() {
        val req = Req.objectFrom("{}")
        assertEquals(0, req.getBuffer())
        assertEquals(1, req.getRedirect())
        assertEquals(10000, req.getTimeout())
        assertEquals("json", req.getPostType())
        assertEquals("get", req.getMethod())
        assertEquals(true, req.isRedirect())
        assertEquals("UTF-8", req.getCharset())
        assertTrue(req.getHeader().isEmpty())
        assertEquals(null, req.getBody())
        assertEquals(null, req.getData())
    }

    @Test
    fun `显式字段覆盖默认值`() {
        val req = Req.objectFrom(
            """{"buffer":2,"redirect":0,"timeout":5000,"method":"post","postType":"form","body":"raw"}""",
        )
        assertEquals(2, req.getBuffer())
        assertEquals(0, req.getRedirect())
        assertEquals(false, req.isRedirect())
        assertEquals(5000, req.getTimeout())
        assertEquals("post", req.getMethod())
        assertEquals("form", req.getPostType())
        assertEquals("raw", req.getBody())
    }

    /** 空串等同于"没写"，走的还是默认值分支。 */
    @Test
    fun `空串字段按未提供处理`() {
        val req = Req.objectFrom("""{"method":"","postType":""}""")
        assertEquals("get", req.getMethod())
        assertEquals("json", req.getPostType())
    }

    @Test
    fun `从 Content-Type 抠字符集`() {
        assertEquals(
            "GBK",
            Req.objectFrom("""{"headers":{"Content-Type":"text/html; charset=GBK"}}""").getCharset(),
        )
        // 头名大小写不敏感，两种都认
        assertEquals(
            "utf-8",
            Req.objectFrom("""{"headers":{"content-type":"video/mp4; charset=utf-8"}}""").getCharset(),
        )
        // 没有 charset 参数 → UTF-8
        assertEquals(
            "UTF-8",
            Req.objectFrom("""{"headers":{"Content-Type":"application/json"}}""").getCharset(),
        )
    }

    /**
     * 解析失败**不能抛**。
     *
     * 这条不是防御性编程：`Req.objectFrom` 的调用点（`Global.req` /
     * `requestAsync`）都在 JS 调用栈上，抛出去会穿过 JNI 变成 JS 里的一个
     * `Error`，而真实源几乎不会 try/catch 包住 `req()` —— 整个站点直接失效。
     * 返回"全默认值"等于"JS 想发一个普通 GET"，那是有意义的降级。
     */
    @Test
    fun `非法 JSON 与 null 都退化成默认值`() {
        assertEquals(10000, Req.objectFrom("这不是 JSON").getTimeout())
        assertEquals(10000, Req.objectFrom(null).getTimeout())
        assertEquals(10000, Req.objectFrom("").getTimeout())
        assertEquals("get", Req.objectFrom("[]").getMethod())
    }

    // ── Res：代理返回值的载体 ────────────────────────────────────────

    @Test
    fun `缺字段时的默认值`() {
        val res = Res.objectFrom("{}")
        assertEquals(200, res.getCode())
        assertEquals(0, res.getBuffer())
        assertEquals("", res.getContent())
        // 猜不到类型时给 octet-stream（"你自己嗅探"），不是 text/html
        assertEquals("application/octet-stream", res.getContentType())
    }

    @Test
    fun `Content-Type 取响应头里的值`() {
        val res = Res.objectFrom("""{"headers":{"Content-Type":"video/mp4"}}""")
        assertEquals("video/mp4", res.getContentType())
    }

    @Test
    fun `纯文本正文按 UTF-8 出流`() {
        val res = Res.objectFrom("""{"code":404,"content":"没有找到"}""")
        assertEquals(404, res.getCode())
        assertEquals("没有找到", res.getStream().readBytes().toString(Charsets.UTF_8))
    }

    /**
     * ⚠️ `buffer == 2` 表示 content 是 **base64**（JS 侧用它传二进制）。
     *
     * 漏掉这条分支的话，图片/分片会被当成一串 base64 文本交出去 ——
     * 播放器拿到的是一堆 ASCII 字符，报的是解码失败，指不到这里。
     */
    @Test
    fun `buffer 为 2 时正文按 base64 解`() {
        val res = Res.objectFrom("""{"buffer":2,"content":"aGVsbG8="}""")
        assertEquals("hello", res.getStream().readBytes().toString(Charsets.UTF_8))
    }

    @Test
    fun `非法 JSON 与 null 都退化成空响应`() {
        assertEquals(200, Res.objectFrom("不是 JSON").getCode())
        assertEquals(200, Res.objectFrom(null).getCode())
        assertEquals("", Res.objectFrom("").getContent())
    }
}
