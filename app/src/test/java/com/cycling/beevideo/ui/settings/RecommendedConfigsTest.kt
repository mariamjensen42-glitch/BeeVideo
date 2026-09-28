package com.cycling.beevideo.ui.settings

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 预置配置地址的回归护栏。
 *
 * 这批字符串是**编译进 APK 的常量**，写错了不会编译失败、只会在真机上表现为
 * "点了没反应"或"点开就崩"。三条断言各对应一个已经踩过或差点踩到的坑。
 */
class RecommendedConfigsTest {

    @Test
    fun `每条都必须带镜像前缀`() {
        /*
         * 配置里的 jar 是相对路径（`./jar/fan.txt`），会跟着配置地址解析。
         * 少了 `ghfast.top` 前缀就会落到 `cdn.jsdelivr.net` —— 那里对 `*.jar`
         * 全节点 403（响应体 9 字节），于是每个 `csp_` 源都报「下载 jar 失败」，
         * 看着像 App 坏了。这是本轮排查的主结论，别在改地址时漏掉。
         */
        RECOMMENDED_CONFIGS.forEach { config ->
            assertTrue(
                "缺镜像前缀（jar 会 403）：${config.url}",
                config.url.startsWith("https://ghfast.top/https://"),
            )
        }
    }

    @Test
    fun `地址互不重复`() {
        val urls = RECOMMENDED_CONFIGS.map { it.url }
        assertEquals("有多条预置指向同一个地址", urls.size, urls.toSet().size)
    }

    @Test
    fun `不含会让 App 直接崩的那两份配置`() {
        /*
         * `dianshi.json` / `jsm.json` 的体检数字最漂亮（151 / 150 站、103 个 csp_ 全命中），
         * 但它们的 `spider.jar`（4.8MB dex）会让进程 **native 崩** ——
         * 无 Java FATAL、无 ANR，只有一条 `am_proc_died`，真机必现。见 ADR-0008。
         * 后来者看到那份数字很容易手滑加回来，所以钉在这里。
         */
        RECOMMENDED_CONFIGS.forEach { config ->
            assertFalse("这份会让 App native 崩：${config.url}", config.url.contains("dianshi.json"))
            assertFalse("这份会让 App native 崩：${config.url}", config.url.contains("jsm.json"))
        }
    }
}
