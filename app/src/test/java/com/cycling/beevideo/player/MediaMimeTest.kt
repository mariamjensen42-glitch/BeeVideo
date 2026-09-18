package com.cycling.beevideo.player

import androidx.media3.common.MimeTypes
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * `mimeTypeOfPlayUrl` 的判据测试。
 *
 * ─── 为什么值得存在 ──────────────────────────────────────────────────
 * 猜错容器**不会报"类型不对"**，只会报
 * `UnrecognizedInputFormatException: None of the available extractors … could read
 * the stream` —— 看起来像源站的流坏了、或防盗链挡了，实际是宿主选错了
 * MediaSource。实测就是这个报错把我引偏了一轮。
 *
 * 人工在真机上试一集要装包、点三四下、翻日志，而这只是一串纯函数判断，
 * 没有理由不钉在这里。
 *
 * ─── ⚠️ 这些用例断言的是**宿主自己的契约**，不是"真站的约定" ────────────
 * 下面出现的 `do=m3u8` / `do=proxy` / `do=media` 三个动作名，出处并不一样：
 *
 *   - `do=m3u8` —— 真机上实测到过（糯米线路1 第01集起播成功）
 *   - `do=proxy` / `do=media` —— **本项目推断出来的名字**，4 个真实 jar 的
 *     dex 里全部 `do=` 取值是 `ali bili webdav local 6qc xbpq parseMix
 *     XYQBiu MixWeb ck`，**没有**这两个
 *
 * 所以这些用例守的是"**我们自己造出的地址**（mock jar 与本地代理服务产出的
 * 那几种形态）不会被判错"，而不是"任意真实 jar 的地址都能判对"。
 * 加了新动作名之后，[mimeTypeOfPlayUrl] 对它的行为仍然需要一次真站验证才作数。
 */
class MediaMimeTest {

    @Test
    fun `本地代理的 m3u8 动作判为 HLS`() {
        assertEquals(
            MimeTypes.APPLICATION_M3U8,
            mimeTypeOfPlayUrl(
                "http://127.0.0.1:9978/proxy?do=m3u8&url=https%3A%2F%2Fcdn.example.com%2FEP01.m3u8",
            ),
        )
    }

    /** 真实用例：是这条地址（query 里带扩展名）暴露出的问题。 */
    @Test
    fun `直链 m3u8 判为 HLS`() {
        assertEquals(
            MimeTypes.APPLICATION_M3U8,
            mimeTypeOfPlayUrl("https://cdn.example.com/202510/剧名/剧名EP01.m3u8"),
        )
    }

    /**
     * ⚠️ 这条是**反向**用例：地址里带着 `.m3u8` 字样（在 `url=` 参数里），
     * 但因为路径是 `/proxy` 且带了 `do`，**不能**落到扩展名判据上。
     *
     * 用裸的 `contains("m3u8")` 实现就会在这里错 —— 那种错法在真机上的表现
     * 是"HLS 解析器去读一个二进制分片"，报错更远、更难查。
     *
     * ─── 关于 `do=proxy` 这个名字 ─────────────────────────────────────
     * 它**没有真站出处**（见类注释）。这条用例之所以还留着，是因为
     * `.workbuddy/scripts/mock_spider.jar` 会真的产出这个形态的地址
     * （`Proxy.java` 的 `segment` 分支），L2 层确实有执行者。
     *
     * 断言的内容也因此是弱的、但正确的：**路径是 `/proxy` + 有 `do` + 动作不是
     * `m3u8` → 不指定 MIME**。它守的是"不会误判成 HLS"，而不是"`do=proxy`
     * 就是取分片"—— 后者我们不掌握。
     */
    @Test
    fun `代理地址里的非 m3u8 动作不判为 HLS`() {
        assertNull(
            mimeTypeOfPlayUrl(
                "http://127.0.0.1:9978/proxy?do=proxy&url=https%3A%2F%2Fcdn.example.com%2FEP01.m3u8",
            ),
        )
    }

    /**
     * 真实 jar 会用一堆别的动作名（`ali` / `bili` / `webdav` …），
     * 对我们来说与 `do=proxy` 同类：路径是代理、动作不认识 → 交给 Media3 自己判。
     */
    @Test
    fun `代理地址里的陌生动作同样不判为 HLS`() {
        assertNull(
            mimeTypeOfPlayUrl(
                "http://127.0.0.1:9978/proxy?do=ali&url=https%3A%2F%2Fcdn.example.com%2FEP01.m3u8",
            ),
        )
    }

    @Test
    fun `MPD 判为 DASH`() {
        assertEquals(
            MimeTypes.APPLICATION_MPD,
            mimeTypeOfPlayUrl("https://cdn.example.com/manifest.mpd?token=abc"),
        )
    }

    /** `.mp4` 与"参数里塞地址"的 mp4 都交给 Media3 猜（progressive 本就是对的选择）。 */
    @Test
    fun `mp4 不指定 MIME`() {
        assertNull(mimeTypeOfPlayUrl("https://cdn.example.com/movie.mp4"))
        assertNull(mimeTypeOfPlayUrl("http://127.0.0.1:9978/proxy?do=media&url=https%3A%2F%2Fa%2Fb.mp4"))
    }

    @Test
    fun `空地址不判`() {
        assertNull(mimeTypeOfPlayUrl(""))
    }

    /** `do` 必须带边界，`do=m3u8xx` 是另一个动作。 */
    @Test
    fun `do 参数要匹配完整动作名`() {
        assertNull(mimeTypeOfPlayUrl("http://127.0.0.1:9978/proxy?do=m3u8x&url=a.mp4"))
    }

    /**
     * 已编码的嵌套地址不该被当成这条地址自己的动作。
     *
     * `url=` 里塞着 `%3Fdo%3Dm3u8` 时，正则若不带前导边界就会把它读出来，
     * 于是**一个分片请求会被判成 HLS** —— 又是一次"解析器读二进制"。
     */
    @Test
    fun `已编码的嵌套 do 不参与判定`() {
        assertNull(
            mimeTypeOfPlayUrl(
                "http://127.0.0.1:9978/proxy?do=proxy&url=a%3Fdo%3Dm3u8%26x%3Db",
            ),
        )
    }

    /**
     * 没有 `do` 的 `/proxy` 地址**不算**自指代理地址，会落到扩展名判据。
     *
     * 这是有意的：真实 jar 里裸的 `/proxy` 不带 `do` 时，我们无从知道它取的是什么，
     * 但地址里既然带着媒体扩展名，按扩展名判比按"路径是 /proxy"一票否决更可能对。
     */
    @Test
    fun `没有 do 的 proxy 地址按扩展名判`() {
        assertEquals(
            MimeTypes.APPLICATION_M3U8,
            mimeTypeOfPlayUrl("http://127.0.0.1:9978/proxy?url=https%3A%2F%2Fcdn.example.com%2FEP01.m3u8"),
        )
    }
}
