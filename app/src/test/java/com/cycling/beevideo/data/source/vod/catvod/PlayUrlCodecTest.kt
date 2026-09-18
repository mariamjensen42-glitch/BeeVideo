package com.cycling.beevideo.data.source.vod.catvod

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * CatVod 协议里「线路 / 剧集」两段字符串的解码。
 *
 * ─── 为什么这几条值得存在 ──────────────────────────────────────────────
 * 这是整个界面**所有剧集列表**的来源，而它错了不会报错、只会显示错的东西：
 *
 *  - 只认 `$$$` 不认逗号 → MacCMS 配了播放组白名单的站点上，
 *    "线路一,线路二" 被当成**一个**名字，第二条线路显示成「线路2」；
 *  - 逗号那一路不校验段数 → 线路名自身含逗号时被劈开，**名字与地址组错位** ——
 *    每条线路挂的都不是自己的剧集。这比名字显示不全严重得多；
 *  - 短标签推导错了 → 选集栏里全是同一个数字。
 *
 * 三条都属于"看着像正常、点下去才发现不对"，只能靠把断言钉在解析结果上。
 *
 * ⚠️ 这个 object 里曾经还有一个 `fillTemplate`（URL 模板占位符替换），**已删除**：
 * 它零调用方、零测试，而模板拼接实际走 `HttpSiteClient.buildUrl` 里那套写死的
 * MacCMS 参数名 —— 两套互不知情的模板机制并存，读的人会以为 `fillTemplate`
 * 是受支持的那条路。给死代码补测试等于把它钉住，删掉才是对的。
 */
class PlayUrlCodecTest {

    @Test
    fun `三种分隔符各司其职`() {
        val lines = PlayUrlCodec.decode(
            playFrom = "线路一${SEP}线路二",
            playUrl = "第01集\$http://a.m3u8#第02集\$http://b.m3u8" +
                "${SEP}第01集\$http://c.m3u8",
        )

        assertEquals(2, lines.size)
        assertEquals("线路一", lines[0].name)
        assertEquals(listOf("http://a.m3u8", "http://b.m3u8"), lines[0].episodes.map { it.url })
        assertEquals(listOf("第01集", "第02集"), lines[0].episodes.map { it.name })
        assertEquals("第二条线路只有一集，地址是 c", "http://c.m3u8", lines[1].episodes[0].url)
    }

    /**
     * MacCMS 配了播放组白名单时，`vod_play_from` 会是**逗号**分隔的
     * （见 `PlayUrlCodec` 文件头引的 `Provide.php` 两处 `str_replace`）。
     */
    @Test
    fun `线路名用逗号分隔时也要认`() {
        val lines = PlayUrlCodec.decode(
            playFrom = "线路一,线路二",
            playUrl = "第01集\$http://a${SEP}第01集\$http://b",
        )

        assertEquals(listOf("线路一", "线路二"), lines.map { it.name })
    }

    /**
     * 逗号段数**正好等于**地址组数才采信。对不上就退回整串当一条名字。
     *
     * 这是**错位**防线：线路名自己含逗号（"线路一,高清"）时无脑劈开，
     * 名字与地址组就错位了 —— 每条线路挂的都不是自己的剧集。
     */
    @Test
    fun `逗号段数对不上就不劈，整串当一条名字`() {
        val lines = PlayUrlCodec.decode(
            playFrom = "线路一,高清,线路二",
            playUrl = "第01集\$http://a${SEP}第01集\$http://b",
        )

        assertEquals(2, lines.size)
        assertEquals("线路一,高清,线路二", lines[0].name)
        assertEquals("名字只够配第一条，第二条按位置补位", "线路2", lines[1].name)
        // 关键：剧集**没有**跟着名字错位
        assertEquals("http://a", lines[0].episodes[0].url)
        assertEquals("http://b", lines[1].episodes[0].url)
    }

    /** 只有一条线路时逗号那一路根本不走 —— 名字整串留着。 */
    @Test
    fun `单线路时名字里的逗号不会被劈开`() {
        val lines = PlayUrlCodec.decode(
            playFrom = "线路一,高清",
            playUrl = "第01集\$http://a",
        )

        assertEquals("线路一,高清", lines.single().name)
    }

    @Test
    fun `线路名不够时按位置补位`() {
        val lines = PlayUrlCodec.decode(
            playFrom = "线路一",
            playUrl = "第01集\$http://a${SEP}第01集\$http://b",
        )

        assertEquals(listOf("线路一", "线路2"), lines.map { it.name })
    }

    @Test
    fun `没有播放地址时返回空表`() {
        assertTrue(PlayUrlCodec.decode("线路一", "").isEmpty())
        assertTrue(PlayUrlCodec.decode("线路一", null).isEmpty())
        assertTrue("只有空白也算没有", PlayUrlCodec.decode(null, "   ").isEmpty())
    }

    /** 某条线路解析出 0 集就整条丢掉：留着它，选集栏会多一个点了没反应的 chip。 */
    @Test
    fun `空的线路组被整条丢掉`() {
        val lines = PlayUrlCodec.decode("A${SEP}B", "第01集\$http://a${SEP}")

        assertEquals(1, lines.size)
        assertEquals("A", lines.single().name)
    }

    /** 只有地址没有名字：真实源里出现过（尤其是单集资源）。 */
    @Test
    fun `只看地址没有集名时按位置起名`() {
        val episode = PlayUrlCodec.decode(null, "http://a.m3u8").single().episodes.single()

        assertEquals("第1集", episode.name)
        assertEquals("http://a.m3u8", episode.url)
    }

    /** 有名字没地址的那一集没有可播的东西，丢掉。 */
    @Test
    fun `地址为空的那一集被丢掉`() {
        val episodes = PlayUrlCodec.decode(null, "第01集\$#第02集\$http://b")
            .single()
            .episodes

        assertEquals(1, episodes.size)
        assertEquals("第02集", episodes.single().name)
    }

    /**
     * 短标签的推导规则：**先取名字里第一段数字**；没有数字时，短名字原样用，
     * 长名字退成集号。
     */
    @Test
    fun `短标签先取数字，再退成原样或集号`() {
        val episodes = PlayUrlCodec.decode(
            null,
            "第03集\$http://a#上\$http://b#很长很长的名字\$http://c",
        ).single().episodes

        assertEquals("有数字就取数字", "03", episodes[0].short)
        assertEquals("没有数字但很短就原样用", "上", episodes[1].short)
        // 退成的是**集号**（下标 + 1），不是下标 —— 第三条是"第 3 集"
        assertEquals("没有数字又太长就退成集号", "3", episodes[2].short)
    }

    private companion object {
        /**
         * 线路分隔符。
         *
         * 写成常量再插值，而不是在字面量里直接敲 `$$$` —— Kotlin 会把 `$` 当模板前缀，
         * `"线路一$$$线路二"` 连编译都过不去（这本身就是那个坑的第一手证据）。
         */
        const val SEP = "\$\$\$"
    }
}
