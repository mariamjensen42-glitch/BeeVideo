package com.cycling.beevideo.data.source.vod.catvod

import com.github.catvod.utils.UriUtil

/**
 * 配置正文的**相对路径修正** —— 逐行对齐参考宿主的
 * `app/src/main/java/com/fongmi/android/tv/api/Decoder.java` 里的 `fix` / `replace`。
 *
 * ─── 它解决的问题（不修的话，一堆站点直接不可用）─────────────────────
 * 真实配置里大量出现相对路径：
 * ```json
 * {"type":3,"api":"./lib/drpy2.min.js","ext":"./js/360影视.js"}
 * ```
 * 这些路径是**相对配置文件的 URL** 的。参考宿主在**配置下载之后、JSON 解析之前**
 * 对整段正文做一次文本替换，把 `./` / `../` 换成按配置地址解析后的绝对地址；
 * 于是后面所有环节（`api`、`ext`、`jar`）拿到的都是绝对地址，谁都不用再管相对路径。
 *
 * ⚠️ BeeVideo 原来**没有这一步**，而 [CatVodConfigParser] 又只对 JSON/XML 的 `api`
 * 做绝对化（那条例外是为保护 `csp_XXX` 这种**类名**不被当成路径，本身是对的）——
 * 结果 `type=3` 的 `.js` 站点 api 停在 `./lib/drpy2.min.js`，
 * 传到 JS 引擎就是「JS 源取不到」。
 * 实测用户正在用的那份配置：**86 个站点里 79 个 type=3，其中 22 个 api 是相对路径、
 * 33 个 ext 是相对路径** —— 不修就是一大片站点打不开。
 *
 * ─── 为什么是"文本替换"而不是"解析完再改字段"───────────────────────────
 * 这是参考实现的做法，也是它唯一站得住的地方：**相对路径可能出现的位置不止
 * `api` / `ext` 两个字段** —— `jar`、`sites` 里嵌套的任意字符串、将来新增的字段
 * 都可能有。逐字段处理等于每加一个字段就补一次，而漏掉的那次**不会报错**，
 * 只会"某个源莫名其妙打不开"。文本层面一次替换覆盖全部。
 *
 * 代价也说清楚：正文里**任何**位置的 `./` 都会被替换（哪怕它出现在片名里）。
 * 这是参考实现接受了十几年的取舍，照搬。
 *
 * ─── ⚠️ 只有一半：配置解密没搬 ────────────────────────────────────────
 * 参考宿主的 `Decoder` 还有另一半：`**` 前缀的 base64 配置、`2423` 前缀的
 * CBC-AES 配置（`verify` / `base64` / `cbc`）。**这一半没有搬** ——
 * 本项目目前只认明文 JSON，遇到编码过的配置会走到
 * [CatVodConfigParser] 的"不是合法 JSON"报错。要支持时按参考实现补即可，
 * 依赖（`Util.hex2byte` / `Crypto.decryptAesCbc`）都已经在本项目里了。
 * 这里保留 `Decoder` 这个名字就是为了能和参考实现**逐行对照**。
 */
object CatVodConfigDecoder {

    /**
     * `"./xxx.js?query"` 这种**带 query 的 .js 相对引用**。
     *
     * ⚠️ 它必须**先于**下面的整体替换执行，而且替换时要先把两段 `./` / `../`
     * 换成 `__JS1__` / `__JS2__` 占位符（见 [replace]）——
     * 否则整体替换会把 query 里可能出现的 `./` 也一起改掉，
     * 把一个本来正确的地址改坏。
     *
     * 与参考实现同一个 pattern，一个字没改。
     */
    private val JS_URI = Regex("\"(\\.|\\.\\.)/(.?|.+?)\\.js\\?(.?|.+?)\"")

    /**
     * 把 `data` 里的相对路径按 `url`（配置的**最终**地址）解析成绝对地址。
     *
     * 四步与参考实现一一对应：
     * ```
     * 1. 先处理 "./x.js?query" 形态（带 query 的特殊形态）
     * 2. "../" → 解析结果
     * 3. "./"  → 解析结果
     * 4. 还原占位符 __JS1__ / __JS2__
     * ```
     * ⚠️ 第 4 步**不能省**：第 1 步留下的占位符如果不还原，
     * 配置里就会真的出现 `__JS1__lib/x.js` 这种字符串。
     */
    fun fix(url: String, data: String): String {
        var out = data
        // 先按原文找出所有匹配，再依次替换（与参考实现的 while(matcher.find()) 一致）
        for (match in JS_URI.findAll(data)) {
            out = replace(url, out, match.value)
        }
        if (out.contains("../")) out = out.replace("../", UriUtil.resolve(url, "../"))
        if (out.contains("./")) out = out.replace("./", UriUtil.resolve(url, "./"))
        if (out.contains("__JS1__")) out = out.replace("__JS1__", "./")
        if (out.contains("__JS2__")) out = out.replace("__JS2__", "../")
        return out
    }

    /**
     * 处理一个 `"./x.js?q"` 匹配。
     *
     * `t.replace("./", "__JS1__")` 这两行是**保护性**的：把这个片段自己的
     * 相对前缀换成占位符带进正文，等 [fix] 的整体替换跑完、最后再还原。
     * 少了它，这个片段里的 `./` 会被整体替换**二次**处理。
     */
    private fun replace(url: String, data: String, ext: String): String {
        var t = ext.replace("\"./", "\"" + UriUtil.resolve(url, "./"))
        t = t.replace("\"../", "\"" + UriUtil.resolve(url, "../"))
        t = t.replace("./", "__JS1__").replace("../", "__JS2__")
        return data.replace(ext, t)
    }
}
