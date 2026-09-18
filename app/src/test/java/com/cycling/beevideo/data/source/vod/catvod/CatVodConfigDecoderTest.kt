package com.cycling.beevideo.data.source.vod.catvod

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [CatVodConfigDecoder] 的差分校验。
 *
 * ─── 期望值从哪来 ────────────────────────────────────────────────────
 * 全部由**参考实现本身**产出：把 `TV-fongmi` 的
 * `app/.../api/Decoder.java` 里的 `JS_URI` / `fix` / `replace` 三段**原样**抽出来，
 * 配上参考的 `UriUtil.java`，在 JVM 上跑同一批输入。
 * 生成物与原始输出见 `.workbuddy/scripts/_decoderref_java.txt`。
 *
 * ⚠️ 为什么不能"照着注释手写期望值"：这段逻辑的两个拐点都很隐蔽 ——
 *   - `"./x.js?q"` 形态走的是**正则**分支，其余走整体替换；
 *   - 正则分支里 `./` 会先被换成 `__JS1__` 占位符，最后再还原。
 * 手写期望值等于在测"我对它的理解"，而不是"这份移植对不对"。
 *
 * ⚠️ 输入刻意用 **ASCII**：中文经 java stdout 会被控制台编码糟蹋，
 * 比对就不再是逐字节的了。中文路径的行为由中文长度引起不了差异。
 */
class CatVodConfigDecoderTest {

    @Test
    fun `与参考实现逐例一致`() {
        for ((url, input, expected) in CASES) {
            assertEquals("fix($url)", expected, CatVodConfigDecoder.fix(url, input))
        }
    }

    /**
     * ⚠️ `fix` **不是幂等的**，也不是路径归一化器 —— 这是**参考实现的行为**，别"修"。
     *
     * `"./a/./b.js?q"` 这种「路径里还嵌着一个 `./`」的形态：正则分支只把**最外层**的
     * `./` 换成绝对地址，里面那个被 `__JS1__` 占位符保护下来、最后原样还原。
     * 于是结果里**留着一个 `./`**；再跑一次 `fix` 才会被整体替换掉（那时的输出成了
     * `…/a/https://…/b.js?q`，明显不是我们想要的）。
     *
     * 对本项目没有影响：`fix` 每次加载配置**只跑一次**，而且
     * `ConfigDiskCache` 里存的是**原文**（修正是解析前临时做的）。
     *
     * 写这条用例是为了把这个"看着像 bug"的行为钉住 ——
     * 顺手把它改成"顺带归一化点段"就等于偏离参考实现。
     */
    @Test
    fun `内层点段不会被归一化（与参考实现一致，不是 bug）`() {
        val url = "https://cdn.jsdelivr.net/gh/qist/tvbox@master/0821.json"
        assertEquals(
            """{"a":"https://cdn.jsdelivr.net/gh/qist/tvbox@master/a/./b.js?q"}""",
            CatVodConfigDecoder.fix(url, """{"a":"./a/./b.js?q"}"""),
        )
    }

    /**
     * 正文里真的出现 `__JS1__` / `__JS2__` 时会被**还原**成 `./` / `../`。
     *
     * 占位符是正则分支的临时产物，正常输入碰不到；这条钉的是
     * "还原那一步不能省"—— 省了就会让配置里冒出 `__JS1__lib/x.js` 这种字符串。
     */
    @Test
    fun `占位符会被还原成相对前缀`() {
        assertEquals("""{"a":"./x"}""", CatVodConfigDecoder.fix("https://h/p.json", """{"a":"__JS1__x"}"""))
        assertEquals("""{"a":"../y"}""", CatVodConfigDecoder.fix("https://h/p.json", """{"a":"__JS2__y"}"""))
    }

    /**
     * 端到端：修过相对路径之后，解析出来的 `.js` 站点 `api` / `ext` 必须是**绝对地址**。
     *
     * 这是"22 个 drpy 站点打不开"那条问题的回归锁 —— 那批站点的
     * `api` 是 `./lib/drpy2.min.js`，不修就一路原样传到 JS 引擎，
     * 报「JS 源取不到」。
     */
    @Test
    fun `修过之后 type=3 的 js 站点 api 与 ext 都是绝对地址`() {
        val url = "https://cdn.jsdelivr.net/gh/qist/tvbox@master/0821.json"
        val raw = """{"sites":[{"key":"a","type":3,"api":"./lib/drpy2.min.js","ext":"./js/x.js"}]}"""

        val site = CatVodConfigParser.parse(CatVodConfigDecoder.fix(url, raw), url).sites.single()

        assertEquals(
            "https://cdn.jsdelivr.net/gh/qist/tvbox@master/lib/drpy2.min.js",
            site.api,
        )
        assertEquals("https://cdn.jsdelivr.net/gh/qist/tvbox@master/js/x.js", site.ext)
        assertTrue(site.api.endsWith(".js"))
    }

    /**
     * ⚠️ 反向的一条：`csp_XXX` 是**类名**，不是路径，修相对路径时**不能碰它**。
     *
     * 这正是 [CatVodConfigParser] 当初把 `SPIDER/API` 排除在 `absolutize` 之外的
     * 原因（踩过一次：`csp_MockSite` 被解析成 `http://host/csp_MockSite`）。
     * 现在改由本类统一在文本层面处理相对路径，这条性质必须继续成立。
     */
    @Test
    fun `csp 类名不被相对路径修正碰到`() {
        val url = "https://cdn.jsdelivr.net/gh/qist/tvbox@master/0821.json"
        val raw = """{"sites":[{"key":"a","type":3,"api":"csp_MockSite","ext":"{\"a\":1}"}]}"""

        val site = CatVodConfigParser.parse(CatVodConfigDecoder.fix(url, raw), url).sites.single()

        assertEquals("csp_MockSite", site.api)
        assertEquals("""{"a":1}""", site.ext)
    }

    private companion object {
        /** (配置最终地址, 配置正文, 参考实现的输出)。由脚本产出，不要手改。 */
        val CASES: List<Triple<String, String, String>> = listOf(
            Triple("https://cdn.jsdelivr.net/gh/qist/tvbox@master/0821.json", "{\"sites\":[{\"key\":\"a\",\"type\":3,\"api\":\"./lib/drpy2.min.js\",\"ext\":\"./js/site.js\"}]}", "{\"sites\":[{\"key\":\"a\",\"type\":3,\"api\":\"https://cdn.jsdelivr.net/gh/qist/tvbox@master/lib/drpy2.min.js\",\"ext\":\"https://cdn.jsdelivr.net/gh/qist/tvbox@master/js/site.js\"}]}"),
            Triple("https://cdn.jsdelivr.net/gh/qist/tvbox@master/0821.json", "{\"a\":\"./x.js?v=1\"}", "{\"a\":\"https://cdn.jsdelivr.net/gh/qist/tvbox@master/x.js?v=1\"}"),
            Triple("https://cdn.jsdelivr.net/gh/qist/tvbox@master/0821.json", "{\"a\":\"../x.js\"}", "{\"a\":\"https://cdn.jsdelivr.net/gh/qist/x.js\"}"),
            Triple("https://cdn.jsdelivr.net/gh/qist/tvbox@master/0821.json", "{\"a\":\"./a/./b.js?q\"}", "{\"a\":\"https://cdn.jsdelivr.net/gh/qist/tvbox@master/a/./b.js?q\"}"),
            Triple("https://cdn.jsdelivr.net/gh/qist/tvbox@master/0821.json", "{\"a\":\"./x.js\",\"b\":\"./y.js\"}", "{\"a\":\"https://cdn.jsdelivr.net/gh/qist/tvbox@master/x.js\",\"b\":\"https://cdn.jsdelivr.net/gh/qist/tvbox@master/y.js\"}"),
            Triple("https://cdn.jsdelivr.net/gh/qist/tvbox@master/0821.json", "{\"a\":\"https://abs/x.js\"}", "{\"a\":\"https://abs/x.js\"}"),
            Triple("https://cdn.jsdelivr.net/gh/qist/tvbox@master/0821.json", "{\"key\":\"csp_SixV\",\"api\":\"csp_SixV\",\"ext\":\"http://www.xb6v.com/\"}", "{\"key\":\"csp_SixV\",\"api\":\"csp_SixV\",\"ext\":\"http://www.xb6v.com/\"}"),
            Triple("https://cdn.jsdelivr.net/gh/qist/tvbox@master/0821.json", "{\"a\":\"__JS1__x\",\"b\":\"__JS2__y\"}", "{\"a\":\"./x\",\"b\":\"../y\"}"),
            Triple("https://cdn.jsdelivr.net/gh/qist/tvbox@master/0821.json", "{\"name\":\"a./b\",\"api\":\"./z.js\"}", "{\"name\":\"ahttps://cdn.jsdelivr.net/gh/qist/tvbox@master/b\",\"api\":\"https://cdn.jsdelivr.net/gh/qist/tvbox@master/z.js\"}"),
            Triple("https://cdn.jsdelivr.net/gh/qist/tvbox@master/0821.json", "{\"jar\":\"./jar/fan.txt;md5;abc\",\"spider\":\"./jar/x.jar\"}", "{\"jar\":\"https://cdn.jsdelivr.net/gh/qist/tvbox@master/jar/fan.txt;md5;abc\",\"spider\":\"https://cdn.jsdelivr.net/gh/qist/tvbox@master/jar/x.jar\"}"),
            Triple("https://cdn.jsdelivr.net/gh/qist/tvbox@master/0821.json", "{\"a\":\"./\"}", "{\"a\":\"https://cdn.jsdelivr.net/gh/qist/tvbox@master/\"}"),
            Triple("https://cdn.jsdelivr.net/gh/qist/tvbox@master/0821.json", "{\"a\":\"../\"}", "{\"a\":\"https://cdn.jsdelivr.net/gh/qist/\"}"),
            Triple("https://cdn.jsdelivr.net/gh/qist/tvbox@master/0821.json", "{\"a\":\"https://x/./y.js?./z\"}", "{\"a\":\"https://x/https://cdn.jsdelivr.net/gh/qist/tvbox@master/y.js?https://cdn.jsdelivr.net/gh/qist/tvbox@master/z\"}"),
            Triple("https://cdn.jsdelivr.net/gh/qist/tvbox@master/0821.json", "{\"api\":\"./lib/a.min.js?t=1\",\"ext\":\"../e/f.js?g=2\"}", "{\"api\":\"https://cdn.jsdelivr.net/gh/qist/tvbox@master/lib/a.min.js?t=1\",\"ext\":\"https://cdn.jsdelivr.net/gh/qist/e/f.js?g=2\"}"),
            Triple("https://cdn.jsdelivr.net/gh/qist/tvbox@master/0821.json", "{\"a\":\".gitignore\"}", "{\"a\":\".gitignore\"}"),
            Triple("https://cdn.jsdelivr.net/gh/qist/tvbox@master/0821.json", "{\"a\":\"./a.js\"}", "{\"a\":\"https://cdn.jsdelivr.net/gh/qist/tvbox@master/a.js\"}"),
            Triple("https://cdn.jsdelivr.net/gh/qist/tvbox@master/0821.json", "{\"a\":\"./x.js?u=http://q/./r\"}", "{\"a\":\"https://cdn.jsdelivr.net/gh/qist/tvbox@master/x.js?u=http://q/./r\"}"),
            Triple("https://cdn.jsdelivr.net/gh/qist/tvbox@master/0821.json", "{\"a\":\"no-relative-here\"}", "{\"a\":\"no-relative-here\"}"),
            Triple("https://cdn.jsdelivr.net/gh/qist/tvbox@master/0821.json", "{\"a\":\".\"}", "{\"a\":\".\"}"),
            Triple("https://cdn.jsdelivr.net/gh/qist/tvbox@master/0821.json", "{\"a\":\"..\"}", "{\"a\":\"..\"}"),
            Triple("https://h/p.json?v=2", "{\"sites\":[{\"key\":\"a\",\"type\":3,\"api\":\"./lib/drpy2.min.js\",\"ext\":\"./js/site.js\"}]}", "{\"sites\":[{\"key\":\"a\",\"type\":3,\"api\":\"https://h/lib/drpy2.min.js\",\"ext\":\"https://h/js/site.js\"}]}"),
            Triple("https://h/p.json?v=2", "{\"a\":\"./x.js?v=1\"}", "{\"a\":\"https://h/x.js?v=1\"}"),
            Triple("https://h/p.json?v=2", "{\"a\":\"../x.js\"}", "{\"a\":\"https://h/x.js\"}"),
            Triple("https://h/p.json?v=2", "{\"a\":\"./a/./b.js?q\"}", "{\"a\":\"https://h/a/./b.js?q\"}"),
            Triple("https://h/p.json?v=2", "{\"a\":\"./x.js\",\"b\":\"./y.js\"}", "{\"a\":\"https://h/x.js\",\"b\":\"https://h/y.js\"}"),
            Triple("https://h/p.json?v=2", "{\"a\":\"https://abs/x.js\"}", "{\"a\":\"https://abs/x.js\"}"),
            Triple("https://h/p.json?v=2", "{\"key\":\"csp_SixV\",\"api\":\"csp_SixV\",\"ext\":\"http://www.xb6v.com/\"}", "{\"key\":\"csp_SixV\",\"api\":\"csp_SixV\",\"ext\":\"http://www.xb6v.com/\"}"),
            Triple("https://h/p.json?v=2", "{\"a\":\"__JS1__x\",\"b\":\"__JS2__y\"}", "{\"a\":\"./x\",\"b\":\"../y\"}"),
            Triple("https://h/p.json?v=2", "{\"name\":\"a./b\",\"api\":\"./z.js\"}", "{\"name\":\"ahttps://h/b\",\"api\":\"https://h/z.js\"}"),
            Triple("https://h/p.json?v=2", "{\"jar\":\"./jar/fan.txt;md5;abc\",\"spider\":\"./jar/x.jar\"}", "{\"jar\":\"https://h/jar/fan.txt;md5;abc\",\"spider\":\"https://h/jar/x.jar\"}"),
            Triple("https://h/p.json?v=2", "{\"a\":\"./\"}", "{\"a\":\"https://h/\"}"),
            Triple("https://h/p.json?v=2", "{\"a\":\"../\"}", "{\"a\":\"https://h/\"}"),
            Triple("https://h/p.json?v=2", "{\"a\":\"https://x/./y.js?./z\"}", "{\"a\":\"https://x/https://h/y.js?https://h/z\"}"),
            Triple("https://h/p.json?v=2", "{\"api\":\"./lib/a.min.js?t=1\",\"ext\":\"../e/f.js?g=2\"}", "{\"api\":\"https://h/lib/a.min.js?t=1\",\"ext\":\"https://h/e/f.js?g=2\"}"),
            Triple("https://h/p.json?v=2", "{\"a\":\".gitignore\"}", "{\"a\":\".gitignore\"}"),
            Triple("https://h/p.json?v=2", "{\"a\":\"./a.js\"}", "{\"a\":\"https://h/a.js\"}"),
            Triple("https://h/p.json?v=2", "{\"a\":\"./x.js?u=http://q/./r\"}", "{\"a\":\"https://h/x.js?u=http://q/./r\"}"),
            Triple("https://h/p.json?v=2", "{\"a\":\"no-relative-here\"}", "{\"a\":\"no-relative-here\"}"),
            Triple("https://h/p.json?v=2", "{\"a\":\".\"}", "{\"a\":\".\"}"),
            Triple("https://h/p.json?v=2", "{\"a\":\"..\"}", "{\"a\":\"..\"}"),
            Triple("https://h", "{\"sites\":[{\"key\":\"a\",\"type\":3,\"api\":\"./lib/drpy2.min.js\",\"ext\":\"./js/site.js\"}]}", "{\"sites\":[{\"key\":\"a\",\"type\":3,\"api\":\"https://h/lib/drpy2.min.js\",\"ext\":\"https://h/js/site.js\"}]}"),
            Triple("https://h", "{\"a\":\"./x.js?v=1\"}", "{\"a\":\"https://h/x.js?v=1\"}"),
            Triple("https://h", "{\"a\":\"../x.js\"}", "{\"a\":\"https://h/x.js\"}"),
            Triple("https://h", "{\"a\":\"./a/./b.js?q\"}", "{\"a\":\"https://h/a/./b.js?q\"}"),
            Triple("https://h", "{\"a\":\"./x.js\",\"b\":\"./y.js\"}", "{\"a\":\"https://h/x.js\",\"b\":\"https://h/y.js\"}"),
            Triple("https://h", "{\"a\":\"https://abs/x.js\"}", "{\"a\":\"https://abs/x.js\"}"),
            Triple("https://h", "{\"key\":\"csp_SixV\",\"api\":\"csp_SixV\",\"ext\":\"http://www.xb6v.com/\"}", "{\"key\":\"csp_SixV\",\"api\":\"csp_SixV\",\"ext\":\"http://www.xb6v.com/\"}"),
            Triple("https://h", "{\"a\":\"__JS1__x\",\"b\":\"__JS2__y\"}", "{\"a\":\"./x\",\"b\":\"../y\"}"),
            Triple("https://h", "{\"name\":\"a./b\",\"api\":\"./z.js\"}", "{\"name\":\"ahttps://h/b\",\"api\":\"https://h/z.js\"}"),
            Triple("https://h", "{\"jar\":\"./jar/fan.txt;md5;abc\",\"spider\":\"./jar/x.jar\"}", "{\"jar\":\"https://h/jar/fan.txt;md5;abc\",\"spider\":\"https://h/jar/x.jar\"}"),
            Triple("https://h", "{\"a\":\"./\"}", "{\"a\":\"https://h/\"}"),
            Triple("https://h", "{\"a\":\"../\"}", "{\"a\":\"https://h/\"}"),
            Triple("https://h", "{\"a\":\"https://x/./y.js?./z\"}", "{\"a\":\"https://x/https://h/y.js?https://h/z\"}"),
            Triple("https://h", "{\"api\":\"./lib/a.min.js?t=1\",\"ext\":\"../e/f.js?g=2\"}", "{\"api\":\"https://h/lib/a.min.js?t=1\",\"ext\":\"https://h/e/f.js?g=2\"}"),
            Triple("https://h", "{\"a\":\".gitignore\"}", "{\"a\":\".gitignore\"}"),
            Triple("https://h", "{\"a\":\"./a.js\"}", "{\"a\":\"https://h/a.js\"}"),
            Triple("https://h", "{\"a\":\"./x.js?u=http://q/./r\"}", "{\"a\":\"https://h/x.js?u=http://q/./r\"}"),
            Triple("https://h", "{\"a\":\"no-relative-here\"}", "{\"a\":\"no-relative-here\"}"),
            Triple("https://h", "{\"a\":\".\"}", "{\"a\":\".\"}"),
            Triple("https://h", "{\"a\":\"..\"}", "{\"a\":\"..\"}"),
            Triple("https://h/", "{\"sites\":[{\"key\":\"a\",\"type\":3,\"api\":\"./lib/drpy2.min.js\",\"ext\":\"./js/site.js\"}]}", "{\"sites\":[{\"key\":\"a\",\"type\":3,\"api\":\"https://h/lib/drpy2.min.js\",\"ext\":\"https://h/js/site.js\"}]}"),
            Triple("https://h/", "{\"a\":\"./x.js?v=1\"}", "{\"a\":\"https://h/x.js?v=1\"}"),
            Triple("https://h/", "{\"a\":\"../x.js\"}", "{\"a\":\"https://h/x.js\"}"),
            Triple("https://h/", "{\"a\":\"./a/./b.js?q\"}", "{\"a\":\"https://h/a/./b.js?q\"}"),
            Triple("https://h/", "{\"a\":\"./x.js\",\"b\":\"./y.js\"}", "{\"a\":\"https://h/x.js\",\"b\":\"https://h/y.js\"}"),
            Triple("https://h/", "{\"a\":\"https://abs/x.js\"}", "{\"a\":\"https://abs/x.js\"}"),
            Triple("https://h/", "{\"key\":\"csp_SixV\",\"api\":\"csp_SixV\",\"ext\":\"http://www.xb6v.com/\"}", "{\"key\":\"csp_SixV\",\"api\":\"csp_SixV\",\"ext\":\"http://www.xb6v.com/\"}"),
            Triple("https://h/", "{\"a\":\"__JS1__x\",\"b\":\"__JS2__y\"}", "{\"a\":\"./x\",\"b\":\"../y\"}"),
            Triple("https://h/", "{\"name\":\"a./b\",\"api\":\"./z.js\"}", "{\"name\":\"ahttps://h/b\",\"api\":\"https://h/z.js\"}"),
            Triple("https://h/", "{\"jar\":\"./jar/fan.txt;md5;abc\",\"spider\":\"./jar/x.jar\"}", "{\"jar\":\"https://h/jar/fan.txt;md5;abc\",\"spider\":\"https://h/jar/x.jar\"}"),
            Triple("https://h/", "{\"a\":\"./\"}", "{\"a\":\"https://h/\"}"),
            Triple("https://h/", "{\"a\":\"../\"}", "{\"a\":\"https://h/\"}"),
            Triple("https://h/", "{\"a\":\"https://x/./y.js?./z\"}", "{\"a\":\"https://x/https://h/y.js?https://h/z\"}"),
            Triple("https://h/", "{\"api\":\"./lib/a.min.js?t=1\",\"ext\":\"../e/f.js?g=2\"}", "{\"api\":\"https://h/lib/a.min.js?t=1\",\"ext\":\"https://h/e/f.js?g=2\"}"),
            Triple("https://h/", "{\"a\":\".gitignore\"}", "{\"a\":\".gitignore\"}"),
            Triple("https://h/", "{\"a\":\"./a.js\"}", "{\"a\":\"https://h/a.js\"}"),
            Triple("https://h/", "{\"a\":\"./x.js?u=http://q/./r\"}", "{\"a\":\"https://h/x.js?u=http://q/./r\"}"),
            Triple("https://h/", "{\"a\":\"no-relative-here\"}", "{\"a\":\"no-relative-here\"}"),
            Triple("https://h/", "{\"a\":\".\"}", "{\"a\":\".\"}"),
            Triple("https://h/", "{\"a\":\"..\"}", "{\"a\":\"..\"}"),
            Triple("http://a/b/c/d;p?q", "{\"sites\":[{\"key\":\"a\",\"type\":3,\"api\":\"./lib/drpy2.min.js\",\"ext\":\"./js/site.js\"}]}", "{\"sites\":[{\"key\":\"a\",\"type\":3,\"api\":\"http://a/b/c/lib/drpy2.min.js\",\"ext\":\"http://a/b/c/js/site.js\"}]}"),
            Triple("http://a/b/c/d;p?q", "{\"a\":\"./x.js?v=1\"}", "{\"a\":\"http://a/b/c/x.js?v=1\"}"),
            Triple("http://a/b/c/d;p?q", "{\"a\":\"../x.js\"}", "{\"a\":\"http://a/b/x.js\"}"),
            Triple("http://a/b/c/d;p?q", "{\"a\":\"./a/./b.js?q\"}", "{\"a\":\"http://a/b/c/a/./b.js?q\"}"),
            Triple("http://a/b/c/d;p?q", "{\"a\":\"./x.js\",\"b\":\"./y.js\"}", "{\"a\":\"http://a/b/c/x.js\",\"b\":\"http://a/b/c/y.js\"}"),
            Triple("http://a/b/c/d;p?q", "{\"a\":\"https://abs/x.js\"}", "{\"a\":\"https://abs/x.js\"}"),
            Triple("http://a/b/c/d;p?q", "{\"key\":\"csp_SixV\",\"api\":\"csp_SixV\",\"ext\":\"http://www.xb6v.com/\"}", "{\"key\":\"csp_SixV\",\"api\":\"csp_SixV\",\"ext\":\"http://www.xb6v.com/\"}"),
            Triple("http://a/b/c/d;p?q", "{\"a\":\"__JS1__x\",\"b\":\"__JS2__y\"}", "{\"a\":\"./x\",\"b\":\"../y\"}"),
            Triple("http://a/b/c/d;p?q", "{\"name\":\"a./b\",\"api\":\"./z.js\"}", "{\"name\":\"ahttp://a/b/c/b\",\"api\":\"http://a/b/c/z.js\"}"),
            Triple("http://a/b/c/d;p?q", "{\"jar\":\"./jar/fan.txt;md5;abc\",\"spider\":\"./jar/x.jar\"}", "{\"jar\":\"http://a/b/c/jar/fan.txt;md5;abc\",\"spider\":\"http://a/b/c/jar/x.jar\"}"),
            Triple("http://a/b/c/d;p?q", "{\"a\":\"./\"}", "{\"a\":\"http://a/b/c/\"}"),
            Triple("http://a/b/c/d;p?q", "{\"a\":\"../\"}", "{\"a\":\"http://a/b/\"}"),
            Triple("http://a/b/c/d;p?q", "{\"a\":\"https://x/./y.js?./z\"}", "{\"a\":\"https://x/http://a/b/c/y.js?http://a/b/c/z\"}"),
            Triple("http://a/b/c/d;p?q", "{\"api\":\"./lib/a.min.js?t=1\",\"ext\":\"../e/f.js?g=2\"}", "{\"api\":\"http://a/b/c/lib/a.min.js?t=1\",\"ext\":\"http://a/b/e/f.js?g=2\"}"),
            Triple("http://a/b/c/d;p?q", "{\"a\":\".gitignore\"}", "{\"a\":\".gitignore\"}"),
            Triple("http://a/b/c/d;p?q", "{\"a\":\"./a.js\"}", "{\"a\":\"http://a/b/c/a.js\"}"),
            Triple("http://a/b/c/d;p?q", "{\"a\":\"./x.js?u=http://q/./r\"}", "{\"a\":\"http://a/b/c/x.js?u=http://q/./r\"}"),
            Triple("http://a/b/c/d;p?q", "{\"a\":\"no-relative-here\"}", "{\"a\":\"no-relative-here\"}"),
            Triple("http://a/b/c/d;p?q", "{\"a\":\".\"}", "{\"a\":\".\"}"),
            Triple("http://a/b/c/d;p?q", "{\"a\":\"..\"}", "{\"a\":\"..\"}"),
            Triple("http://a/b/", "{\"sites\":[{\"key\":\"a\",\"type\":3,\"api\":\"./lib/drpy2.min.js\",\"ext\":\"./js/site.js\"}]}", "{\"sites\":[{\"key\":\"a\",\"type\":3,\"api\":\"http://a/b/lib/drpy2.min.js\",\"ext\":\"http://a/b/js/site.js\"}]}"),
            Triple("http://a/b/", "{\"a\":\"./x.js?v=1\"}", "{\"a\":\"http://a/b/x.js?v=1\"}"),
            Triple("http://a/b/", "{\"a\":\"../x.js\"}", "{\"a\":\"http://a/x.js\"}"),
            Triple("http://a/b/", "{\"a\":\"./a/./b.js?q\"}", "{\"a\":\"http://a/b/a/./b.js?q\"}"),
            Triple("http://a/b/", "{\"a\":\"./x.js\",\"b\":\"./y.js\"}", "{\"a\":\"http://a/b/x.js\",\"b\":\"http://a/b/y.js\"}"),
            Triple("http://a/b/", "{\"a\":\"https://abs/x.js\"}", "{\"a\":\"https://abs/x.js\"}"),
            Triple("http://a/b/", "{\"key\":\"csp_SixV\",\"api\":\"csp_SixV\",\"ext\":\"http://www.xb6v.com/\"}", "{\"key\":\"csp_SixV\",\"api\":\"csp_SixV\",\"ext\":\"http://www.xb6v.com/\"}"),
            Triple("http://a/b/", "{\"a\":\"__JS1__x\",\"b\":\"__JS2__y\"}", "{\"a\":\"./x\",\"b\":\"../y\"}"),
            Triple("http://a/b/", "{\"name\":\"a./b\",\"api\":\"./z.js\"}", "{\"name\":\"ahttp://a/b/b\",\"api\":\"http://a/b/z.js\"}"),
            Triple("http://a/b/", "{\"jar\":\"./jar/fan.txt;md5;abc\",\"spider\":\"./jar/x.jar\"}", "{\"jar\":\"http://a/b/jar/fan.txt;md5;abc\",\"spider\":\"http://a/b/jar/x.jar\"}"),
            Triple("http://a/b/", "{\"a\":\"./\"}", "{\"a\":\"http://a/b/\"}"),
            Triple("http://a/b/", "{\"a\":\"../\"}", "{\"a\":\"http://a/\"}"),
            Triple("http://a/b/", "{\"a\":\"https://x/./y.js?./z\"}", "{\"a\":\"https://x/http://a/b/y.js?http://a/b/z\"}"),
            Triple("http://a/b/", "{\"api\":\"./lib/a.min.js?t=1\",\"ext\":\"../e/f.js?g=2\"}", "{\"api\":\"http://a/b/lib/a.min.js?t=1\",\"ext\":\"http://a/e/f.js?g=2\"}"),
            Triple("http://a/b/", "{\"a\":\".gitignore\"}", "{\"a\":\".gitignore\"}"),
            Triple("http://a/b/", "{\"a\":\"./a.js\"}", "{\"a\":\"http://a/b/a.js\"}"),
            Triple("http://a/b/", "{\"a\":\"./x.js?u=http://q/./r\"}", "{\"a\":\"http://a/b/x.js?u=http://q/./r\"}"),
            Triple("http://a/b/", "{\"a\":\"no-relative-here\"}", "{\"a\":\"no-relative-here\"}"),
            Triple("http://a/b/", "{\"a\":\".\"}", "{\"a\":\".\"}"),
            Triple("http://a/b/", "{\"a\":\"..\"}", "{\"a\":\"..\"}"),
        )
    }
}
