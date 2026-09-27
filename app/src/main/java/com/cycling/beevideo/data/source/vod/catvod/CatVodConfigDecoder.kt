package com.cycling.beevideo.data.source.vod.catvod

import com.github.catvod.utils.UriUtil

/**
 * 配置正文的**相对路径修正**，逐行对齐参考宿主的 `Decoder.java` 的 `fix` / `replace`。
 *
 * 真实配置里大量出现 `{"type":3,"api":"./lib/drpy2.min.js","ext":"./js/360影视.js"}`，
 * 这些路径是相对配置文件 URL 的。参考宿主在**配置下载之后、JSON 解析之前**对整段正文
 * 做一次文本替换，之后所有环节（`api` / `ext` / `jar`）拿到的都是绝对地址。
 *
 * ⚠️ 不做这一步的后果实测过：用户那份配置 86 个站点里 79 个 type=3，其中 22 个 api、
 * 33 个 ext 是相对路径 —— 不修就是一大片站点打不开。
 *
 * 为什么是文本替换而不是解析完再改字段：相对路径可能出现的位置不止 `api` / `ext`
 * （还有 `jar`、嵌套字符串、将来新增的字段），逐字段处理漏掉的那次**不会报错**。
 * 代价是正文里任何位置的 `./` 都会被替换，这是参考实现接受了十几年的取舍。
 *
 * ⚠️ 只搬了一半：`**` 前缀的 base64 配置、`2423` 前缀的 CBC-AES 配置**没有搬**。
 * 遇到编码过的配置会走到 `CatVodConfigParser` 的"不是合法 JSON"报错。
 */
object CatVodConfigDecoder {

    /**
     * `"./xxx.js?query"` 这种**带 query 的 .js 相对引用**。
     *
     * ⚠️ 必须先于整体替换执行，且替换时先把两段 `./` / `../` 换成占位符 ——
     * 否则整体替换会把 query 里可能出现的 `./` 也改掉，把正确地址改坏。
     */
    private val JS_URI = Regex("\"(\\.|\\.\\.)/(.?|.+?)\\.js\\?(.?|.+?)\"")

    /**
     * 把 `data` 里的相对路径按 `url`（配置的**最终**地址）解析成绝对地址。
     * 四步对应：处理带 query 的 `.js` → `../` → `./` → 还原占位符。
     *
     * ⚠️ 第 4 步不能省：不还原的话配置里会真的出现 `__JS1__lib/x.js` 这种字符串。
     */
    fun fix(url: String, data: String): String {
        var out = data
        // 先按原文找出所有匹配再依次替换（与参考实现的 while(matcher.find()) 一致）
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
     * 处理一个 `"./x.js?q"` 匹配。中间两行是**保护性**的：把这个片段自己的相对前缀
     * 换成占位符带进正文，等整体替换跑完再还原；少了它这段里的 `./` 会被二次处理。
     */
    private fun replace(url: String, data: String, ext: String): String {
        var t = ext.replace("\"./", "\"" + UriUtil.resolve(url, "./"))
        t = t.replace("\"../", "\"" + UriUtil.resolve(url, "../"))
        t = t.replace("./", "__JS1__").replace("../", "__JS2__")
        return data.replace(ext, t)
    }
}
