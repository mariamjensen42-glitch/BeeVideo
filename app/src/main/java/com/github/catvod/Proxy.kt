package com.github.catvod

import java.net.Inet4Address
import java.net.NetworkInterface

/**
 * 本地代理服务的地址持有者 —— **本项目自带的兼容层**，对齐参考宿主的
 * `catvod/src/main/java/com/github/catvod/Proxy.java`（20 行，行为完全照搬）。
 *
 * ─── 这个类为什么存在 ────────────────────────────────────────────────
 * 真实 jar 在给出播放地址时会调 [getUrl] 拼出自指地址：
 * ```
 * http://127.0.0.1:<port>/proxy?do=m3u8&url=…
 * ```
 * 播放器随后去取这个地址，宿主的本地代理服务再把请求回灌给 jar 自己的静态
 * `com.github.catvod.spider.Proxy.proxy(Map)`（见 `DexJarLoader`）。
 *
 * 宿主**不调用** [set] 的话 `port` 停在 -1，拼出来的就是
 * `http://127.0.0.1:-1/proxy`，播放器直接报
 * `java.net.MalformedURLException: invalid port: -1` —— 站点能进详情、就是播不出来。
 * 实测就是这么错的。
 *
 * ─── ⚠️ 必须是真静态方法 ──────────────────────────────────────────────
 * jar 里编译好的是 `invokestatic Lcom/github/catvod/Proxy;.getPort:()I`。
 * 普通 Kotlin `object` 会生成 `Proxy.INSTANCE.getPort()`（实例方法），
 * jar 调不到 → `NoSuchMethodError`。所以用 `companion object` + `@JvmStatic`，
 * 这样静态方法落在 `Proxy` 类本身上。
 *
 * `port` 用 `@Volatile`：写它的在启动代理服务的那个线程，读它的可能是
 * jar 在任意线程发起的播放地址构造。
 */
class Proxy private constructor() {

    companion object {

        private const val DEFAULT_PORT = -1

        @Volatile
        private var port: Int = DEFAULT_PORT

        /** 由本地代理服务在监听成功后调用。 */
        @JvmStatic
        fun set(port: Int) {
            this.port = port
        }

        @JvmStatic
        fun getPort(): Int = port

        /**
         * @param local true 返回 `127.0.0.1`（同机播放器用，本项目的主路径）；
         *              false 返回本机局域网 IP（给投屏 / 别的设备来取）。
         */
        @JvmStatic
        fun getUrl(local: Boolean): String = "http://${if (local) "127.0.0.1" else lanIp()}:$port/proxy"

        /**
         * 局域网 IPv4。取不到就退回 `127.0.0.1`。
         *
         * 参考实现用的是它自己 `utils/Util.getIp()`；我们只在这一处需要，
         * 不为一个方法引入一整个 `Util` 类 —— 兼容层里每多一个类，
         * 就多一份"签名猜错了"的风险，而静态探针**看不见** native 加密过的 jar。
         */
        private fun lanIp(): String = runCatching {
            NetworkInterface.getNetworkInterfaces()
                ?.toList()
                ?.filter { it.isUp && !it.isLoopback }
                ?.flatMap { it.inetAddresses.toList() }
                ?.firstOrNull { it is Inet4Address && !it.isLoopbackAddress }
                ?.hostAddress
        }.getOrNull() ?: "127.0.0.1"
    }
}
