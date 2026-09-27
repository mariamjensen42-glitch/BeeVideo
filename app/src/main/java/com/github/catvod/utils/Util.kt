package com.github.catvod.utils

import android.content.Context
import android.net.wifi.WifiManager
import com.github.catvod.Init
import java.net.Inet4Address
import java.net.NetworkInterface
import java.util.Base64

/**
 * 杂项工具 —— **本项目自带的兼容层**，对齐参考宿主的 `Util.java`。
 *
 * 谁在用：`JsSpider.getStream`（把 JS 返回的 base64 解成字节流）与 `Connect.success`
 * （`buffer == 2` 时把响应体编成 base64），所以 [decode] / [base64] 的**宽容度**
 * 是有实际后果的：解不出来就是播放失败。
 *
 * ⚠️ 与参考实现的关键差异：Base64 不用 `android.util.Base64`。理由不是"更现代"，
 * 是**单测会静默错** —— 项目开了 `isReturnDefaultValues = true`，纯 JVM 单测里
 * `android.util.Base64.decode(...)` 不抛异常、返回 null，断言会拿到 NPE 而不是
 * "解码结果不对"，排查方向直接偏掉。
 *
 * 行为差异是**更宽容**的方向：先剥空白（Android 默认忽略）、按有无 `-`/`_` 自动选
 * 字母表（Android 的 DEFAULT 两者都收）、缺 `=` 补齐。
 *
 * 没搬 `OKHTTP` 常量：那是 OkHttp 5.x 的字段，本项目钉 4.12.0，取不到，也无人使用。
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
     * Base64 解码，宽松策略见类注释。
     *
     * @throws IllegalArgumentException 输入不是合法 base64 时。**与参考实现不同**，
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
     * ⚠️ 与 `com.github.catvod.Proxy` 里的 `lanIp()` 功能重叠，这是有意的：那个是私有的、
     * 只服务于代理地址拼接；这个是兼容层的一部分，真实爬虫会调 `Util.getIp()`。
     * 取不到时返回空串（**不是** `127.0.0.1`），调用方靠空串判断"没有局域网地址"。
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
     * ⚠️ Android 12+ 上没定位权限时 `getConnectionInfo()` 的 IP 恒为 0（系统返回脱敏的
     * WifiInfo），所以这一条在真机上基本拿不到值 —— 它只是参考实现链条里的第三顺位，
     * 前两条（网卡扫描）才是有效的。照搬是为了行为对齐。
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
