package com.cycling.beevideo.data.source.vod.catvod

/**
 * 数据源层的异常。
 *
 * 单独一个类型是为了让上层能区分「**站点/配置的问题**」（可以提示用户去改配置、
 * 换站点）和「程序自身的 bug」。把这两类混成一个 `Exception` 的结果是：
 * 用户看到的永远是"未知错误，请重试"，而真正的原因被埋在第 8 层 cause 里。
 */
class CatVodException(
    override val message: String,
    override val cause: Throwable? = null,
) : Exception(message, cause)

/**
 * 解析配置里的 jar 声明。
 *
 * CatVod 的写法是 `"url;md5;hash"`，分隔符就是字面量 `;md5;`：
 * ```
 * http://host/spider.jar;md5;a1b2c3…
 * ```
 * 第二段是**内容 md5**，语义是"用来判断要不要重新下载"，不是必须校验的签名。
 * 所以这里把它当**缓存键**用（文件名 = md5），不存在的段一律空串。
 */
internal fun parseJarSpec(spec: String): Pair<String, String> {
    val s = spec.trim()
    if (s.isEmpty()) return "" to ""
    val parts = s.split(";md5;")
    val url = parts[0].trim()
    val md5 = parts.getOrNull(1)?.trim().orEmpty()
    return url to md5
}
