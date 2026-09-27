package com.cycling.beevideo.data.source.vod.js

import com.github.catvod.utils.Util
import java.io.ByteArrayInputStream

/** 取第 [index] 个 JS 调用参数并转成字符串。缺席或 null 一律退化成空串。 */
internal fun Array<Any?>?.arg(index: Int): String = this?.getOrNull(index)?.toString().orEmpty()

/**
 * JS 给的响应体 → 字节流。
 * `base64` 时 content 可能是 `data:image/png;base64,xxxx` 这种 data URI，
 * 所以先按 `base64,` 切一刀再解 —— 直接解整串会抛 IllegalArgumentException。
 */
internal fun proxyStream(o: Any?, base64: Boolean): ByteArrayInputStream {
    if (o is ByteArray) return ByteArrayInputStream(o)
    var content = o?.toString().orEmpty()
    if (base64 && content.contains("base64,")) {
        content = content.split("base64,").getOrNull(1) ?: content
    }
    return ByteArrayInputStream(
        if (base64) Util.decode(content) else content.toByteArray(),
    )
}
