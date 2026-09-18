package com.cycling.beevideo.data.repository

import com.cycling.beevideo.data.source.vod.catvod.CatVodException
import com.cycling.beevideo.domain.repository.ContentException
import java.io.IOException
import java.net.ConnectException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import org.json.JSONException
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 底层异常 → 给用户看的一句话。
 *
 * ─── 为什么这几条值得存在 ──────────────────────────────────────────────
 * 这是底层错误与界面之间**唯一的翻译点**。漏掉一个分支，用户看到的就是
 * `java.net.ConnectException` 这种串 —— 而自配源最常见的失败原因恰好就是网络，
 * 所以这几句话直接决定"用户能不能自己排查"。
 *
 * ⚠️ 最要紧的是**分支顺序**：`UnknownHostException` / `ConnectException` /
 * `SocketTimeoutException` 三个都是 `IOException` 的子类。`when` 从上往下匹配，
 * 它们必须排在 `IOException` **前面**。顺序写反了不报错、不崩，
 * 只是所有网络失败都变成笼统的"网络错误：xxx" —— 也就是把排查线索抹掉了。
 */
class ReadableTest {

    @Test
    fun `域名解析不了要说检查网址和网络`() {
        assertEquals(
            "无法解析地址，请检查网址和网络",
            readable(UnknownHostException("example.com")),
        )
    }

    @Test
    fun `连接被拒绝要说确认地址可访问`() {
        assertEquals(
            "连接被拒绝，请确认地址可访问",
            readable(ConnectException("Connection refused")),
        )
    }

    /** 超时**也是** `IOException` 的子类 —— 这条同时钉住了分支顺序。 */
    @Test
    fun `超时要说请求超时，不能退化成笼统的网络错误`() {
        assertEquals("请求超时", readable(SocketTimeoutException("timeout")))
    }

    @Test
    fun `其它 IO 错误带上原因`() {
        assertEquals("网络错误：broken pipe", readable(IOException("broken pipe")))
    }

    @Test
    fun `JSON 解析失败要说配置不是合法 JSON`() {
        assertEquals("配置内容不是合法的 JSON", readable(JSONException("bad json")))
    }

    /** 已经翻译过的两类异常原样透传 —— 不要在边界上再包一层。 */
    @Test
    fun `自己的异常原样透传`() {
        assertEquals("配置里没有可用站点", readable(CatVodException("配置里没有可用站点")))
        assertEquals("还没有配置内容源", readable(ContentException("还没有配置内容源")))
    }

    /** 兜底：没有 message 就用类名，绝不留空白。 */
    @Test
    fun `没有 message 时用类名兜底`() {
        assertEquals("IllegalStateException", readable(IllegalStateException()))
    }
}
