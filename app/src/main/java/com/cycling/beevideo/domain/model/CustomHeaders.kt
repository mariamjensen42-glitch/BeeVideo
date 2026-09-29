package com.cycling.beevideo.domain.model

/**
 * 自定义请求头的两段纯换算：**用户填的文本 → 头表**，以及**头表 → 交给内核的头**。
 * 全部无 Android 依赖，可 JVM 直测。
 */

/**
 * 解析「每行一条 `名称: 值`」的文本。
 *
 * `#` 开头当注释、空行跳过；值里允许再有冒号（`Referer: https://a/b` 的 `https:`
 * 不能被切成名字）；名字按 HTTP 头字段名的规矩**不含空白**；同名两行以最后一行为准。
 *
 * ⚠️ 解析不了的行**跳过但不静默** —— 数量由 [invalidCustomHeaderLineCount] 给出，设置页
 * 把它显示出来。静默丢数据的结果是「我填了、没生效、不知道为什么」。
 */
fun parseCustomHeaders(text: String): Map<String, String> {
    val result = mutableMapOf<String, String>()
    text.lines().forEach { raw ->
        val line = raw.trim()
        if (line.isEmpty() || line.startsWith("#")) return@forEach
        val splitAt = line.indexOf(':')
        if (splitAt <= 0) return@forEach
        val name = line.take(splitAt).trim()
        val value = line.substring(splitAt + 1).trim()
        // 名字为空、值缺失、名字里带空白都算这条无效：发出去的空头/错头比不发更迷惑。
        // ⚠️ 名字里的空白必须挡 —— 「Referer https://a.com」这种漏写冒号的行，第一个
        // 冒号在 https: 里，不挡的话它会被静默当成名字叫 "Referer https" 的头
        if (name.isEmpty() || value.isEmpty() || name.any { it.isWhitespace() }) return@forEach
        result[name] = value
    }
    return result
}

/** 被跳过的非空行数。设置页用它给「有 N 行没被认出来」的提示。 */
fun invalidCustomHeaderLineCount(text: String): Int = text.lines().count { raw ->
    val line = raw.trim()
    line.isNotEmpty() && !line.startsWith("#") && parseCustomHeaders(line).isEmpty()
}

/**
 * 把用户在设置里填的自定义头合并进来源给的头。
 *
 * 规则是「**只补空缺，不覆盖**」（键按 HTTP 语义大小写不敏感地比对）：
 * 来源给的头是它在防什么就带什么（Referer / UA 缺一个就是 403），用户填的是
 * 没给头时的通用兜底 —— 让用户填的值把来源给的头盖掉，等于允许一个填错的
 * UA 把所有正常站点一起搞坏。
 */
fun withCustomHeaders(
    source: Map<String, String>,
    custom: Map<String, String>,
): Map<String, String> {
    if (custom.isEmpty()) return source
    val taken = source.keys.mapTo(mutableSetOf()) { it.lowercase() }
    val fillers = custom.filter { it.key.lowercase() !in taken }
    if (fillers.isEmpty()) return source
    return source + fillers
}
