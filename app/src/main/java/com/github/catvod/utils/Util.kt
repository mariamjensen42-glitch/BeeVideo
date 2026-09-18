package com.github.catvod.utils

import android.content.Context
import android.net.wifi.WifiManager
import com.github.catvod.Init
import java.net.Inet4Address
import java.net.NetworkInterface
import java.util.Base64

/**
 * 杂项工具 —— **本项目自带的兼容层**，对齐参考宿主的
 * `catvod/src/main/java/com/github/catvod/utils/Util.java`。
 *
 * ─── 谁在用 ──────────────────────────────────────────────────────────
 * JS 爬虫引擎里有两处：
 *   - `JsSpider.getStream`：把 JS 返回的 base64 内容解成字节流交回播放器；
 *   - `Connect.success`：`buffer == 2` 时把响应体编成 base64 再塞进 JS 对象。
 * 所以 [decode] / [base64] 的**宽容度**是有实际后果的：解不出来就是播放失败。
 *
 * ─── ⚠️ 与参考实现的关键差异：Base64 不用 `android.util.Base64` ────────
 * 参考实现用的是 `android.util.Base64` + `Base64.DEFAULT | Base64.NO_WRAP`。
 * 这里换成 `java.util.Base64`，理由**不是"更现代"，是单测会静默错**：
 *
 * `app/build.gradle.kts` 开了 `unitTests.isReturnDefaultValues = true`，
 * 于是纯 JVM 单测里 `android.util.Base64.decode(...)` **不抛异常，返回 null**
 * —— 断言会拿到一个 NullPointerException，而不是"解码结果不对"，
 * 排查方向直接偏掉。
 *
 * 行为差异（有意为之，且是**更宽容**的方向）：
 *   - Android 的解码器默认忽略空白；`java.util.Base64` 的基础解码器**不忽略**。
 *     所以这里先剥掉空白再解 —— 与 Android 的行为对齐。
 *   - Android 的解码器在 `DEFAULT` 下**同时接受**标准字母表与 URL-safe 字母表；
 *     `java.util.Base64` 要分开选。这里按"有没有 `-` / `_`"自动选，
 *     结果与 Android 一致。
 *   - 缺 `=` 补齐：JS 侧手写的 base64 常省略填充，Android 能解，严格的
 *     `java.util.Base64` 会抛。补齐后才等价。
 *
 * 编码侧只是**不带换行**：参考实现的 flags 里有 `NO_WRAP`，本来就不换行。
 *
 * ─── 没有搬过来的成员 ────────────────────────────────────────────────
 * `OKHTTP` 常量（`"okhttp/" + OkHttp.VERSION`）没搬：那个字段是 OkHttp **5.x** 的，
 * 本项目钉的是 4.12.0，取不到。它在本项目里也无人使用（JS 引擎不用它）。
 * `CHROME` 保留了 —— 纯字面量，且是被真实爬虫问得最多的一个 UA。
 */
object Util {

    const val CHROME =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/151.0.0.0 Safari/537.36"

    @JvmStatic
    fun base64(s: String): String = base64(s.toByteArray(Charsets.UTF_8))

    @JvmStatic
    fun base64(bytes: ByteArray): String =
        Base64.getEncoder().encodeToString(bytes)

    /** URL-safe 变体（`-` / `_`，无填充）。JS 侧拼 URL 时用得上。 */
    @JvmStatic
    fun base64Url(bytes: ByteArray): String =
        Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)

    /**
     * Base64 解码。[decode] 的宽松策略见类注释。
     *
     * @throws IllegalArgumentException 输入不是合法 base64 时。**与参考实现不同**
     *   （它返回 null 或抛 `IllegalArgumentException`，取决于具体 flags），
     *   调用方（`JsSpider.getStream`）自己决定要不要接住。
     */
    @JvmStatic
    fun decode(s: String): ByteArray {
        val cleaned = s.filterNot { it.isWhitespace() }
        val decoder = if (cleaned.contains('-') || cleaned.contains('_')) {
            Base64.getUrlDecoder()
        } else {
            Base64.getDecoder()
        }
        return decoder.decode(pad(cleaned))
    }

    /**
     * 低位字节转十六进制。`Integer.valueOf(s, 16)` 的等价物，
     * 但**不抛** `NumberFormatException` —— 配置里给个奇数长度或非法字符是常见的。
     */
    @JvmStatic
    fun hex2byte(s: String): ByteArray {
        val clean = s.trim()
        val length = clean.length / 2
        val bytes = ByteArray(length)
        for (i in 0 until length) {
            bytes[i] = clean.substring(i * 2, i * 2 + 2).toIntOrNull(16)?.toByte() ?: 0
        }
        return bytes
    }

    /** `text` 包含 `regex`，或者**整体**匹配 `regex`。正则写错时返回 false，不抛。 */
    @JvmStatic
    fun containOrMatch(text: String?, regex: String): Boolean = try {
        text != null && (text.contains(regex) || text.matches(Regex(regex)))
    } catch (_: Exception) {
        false
    }

    /** 去掉末尾 1 个字符。 */
    @JvmStatic
    fun substring(text: String?): String = substring(text, 1)

    /** 去掉末尾 `num` 个字符。长度不够时**原样返回**（参考实现如此）。 */
    @JvmStatic
    fun substring(text: String?, num: Int): String {
        if (text != null && text.length > num) return text.substring(0, text.length - num)
        return text.orEmpty()
    }

    /**
     * 本机局域网 IPv4。
     *
     * ⚠️ 与 `com.github.catvod.Proxy` 里的 `lanIp()` **功能重叠**，这是有意的：
     * 那个是私有的、只服务于代理地址拼接；这个是兼容层的一部分，
     * 真实爬虫会调 `Util.getIp()`（`invokestatic`）。两者都保留。
     *
     * 取不到时返回空串（参考实现如此），**不是** `"127.0.0.1"` ——
     * 调用方要靠空串判断"没有局域网地址"。
     */
    @JvmStatic
    fun getIp(): String = try {
        var ip = getHostAddress("wlan")
        if (ip.isEmpty()) ip = getHostAddress("eth")
        if (ip.isEmpty()) ip = getWifiAddress()
        if (ip.isEmpty()) ip = getHostAddress("")
        ip
    } catch (_: Exception) {
        ""
    }

    /**
     * 从 WifiManager 拿地址。
     *
     * ⚠️ Android 12+ 上没有定位权限时 `getConnectionInfo()` 的 IP 恒为 0
     * （系统返回脱敏的 WifiInfo），所以这一条**在真机上基本拿不到值** ——
     * 它只是参考实现链条里的第三顺位，前两条（网卡扫描）才是有效的。
     * 照搬是为了行为对齐，不是因为它在现代 Android 上还能工作。
     */
    private fun getWifiAddress(): String {
        val context = Init.context() ?: return ""
        @Suppress("DEPRECATION")
        val manager = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager
            ?: return ""
        @Suppress("DEPRECATION")
        val ip = manager.connectionInfo?.ipAddress ?: 0
        if (ip == 0) return ""
        return String.format("%d.%d.%d.%d", ip and 0xFF, (ip shr 8) and 0xFF, (ip shr 16) and 0xFF, (ip shr 24) and 0xFF)
    }

    private fun getHostAddress(keyword: String): String {
        val interfaces = NetworkInterface.getNetworkInterfaces() ?: return ""
        for (nif in interfaces) {
            if (keyword.isNotEmpty() && !nif.name.startsWith(keyword)) continue
            for (address in nif.inetAddresses) {
                if (!address.isLoopbackAddress && address is Inet4Address) return address.hostAddress.orEmpty()
            }
        }
        return ""
    }

    /** 补 `=` 到 4 的倍数。 */
    private fun pad(s: String): String {
        val remainder = s.length % 4
        if (remainder == 0) return s
        return s + "=".repeat(4 - remainder)
    }
}
