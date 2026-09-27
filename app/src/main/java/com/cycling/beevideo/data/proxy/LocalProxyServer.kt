package com.cycling.beevideo.data.proxy

import android.util.Log
import com.github.catvod.Proxy
import fi.iki.elonen.NanoHTTPD
import fi.iki.elonen.NanoHTTPD.IHTTPSession
import fi.iki.elonen.NanoHTTPD.Method
import fi.iki.elonen.NanoHTTPD.Response
import fi.iki.elonen.NanoHTTPD.Response.IStatus
import fi.iki.elonen.NanoHTTPD.Response.Status
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap

/**
 * 本地代理服务 —— 把 jar 发出的自指播放地址接回 jar 自己。
 *
 * 真实 jar 不直接给最终媒体地址，而是给 `http://127.0.0.1:<port>/proxy?do=m3u8&url=…`，
 * 取这个地址时必须有**真的**服务听着，把请求交回 jar 的静态
 * `com.github.catvod.spider.Proxy.proxy(Map)`，由 jar 决定怎么取流、加头、改写 m3u8。
 *
 * ⚠️ 没有它的表现（实测）：`MalformedURLException: invalid port: -1` ——
 * 站点能进详情页，一点播放就报错。**不是兼容层的签名问题。**
 *
 * 端口从 9978 往 9998 逐个试，**绑定成功就立刻 `Proxy.set(port)`**：jar 侧有一部分
 * 实现会自己扫端口找宿主，两条发现路径必须指向同一个真相。
 *
 * 只实现 `/proxy`：参考宿主的 `/action` `/cache` `/image` `/media` `/parse` 等
 * 是它的产品功能（远程遥控、投屏、文件管理），不在本项目范围内，一律 404 并说明原因。
 */
class LocalProxyServer private constructor(
    port: Int,
    private val handler: ProxyHandler,
) : NanoHTTPD(port) {

    /** NanoHTTPD 多线程处理请求，必须用并发集合（`HashSet` 并发写会真出问题）。 */
    private val loggedActions = ConcurrentHashMap.newKeySet<String>()

    override fun serve(session: IHTTPSession): Response {
        val uri = session.uri?.trim().orEmpty()
        if (!uri.startsWith(PATH_PROXY)) {
            return text(
                Status.NOT_FOUND,
                "本项目只实现 $PATH_PROXY。收到：$uri\n" +
                    "参考宿主另有 /action /cache /image /local /media /parse /device，" +
                    "属于远程遥控/投屏/文件管理，不在本项目范围。",
            )
        }

        // ⚠️ query 参数、HTTP 头、POST 表单**合并进同一张表**，这是 CatVod 的既定契约：
        // jar 从同一张表里读 `do`（参数）和 `range`（播放器发的头），两者没法分表
        val params = LinkedHashMap<String, String>()
        // 取每个参数的首个值：与已废弃的 getParms() 行为一致
        session.parameters?.forEach { (name, values) ->
            values.firstOrNull()?.let { params[name] = it }
        }
        session.headers?.let(params::putAll)
        if (session.method == Method.POST) {
            val body = HashMap<String, String>()
            runCatching { session.parseBody(body) }
                .onFailure { Log.w(TAG, "POST 表单解析失败：$it") }
            params.putAll(body)
        }

        val result = runCatching { handler.proxy(params) }
            .onFailure { Log.w(TAG, "代理处理抛异常：$it") }
            .getOrNull()

        if (result == null) {
            // 只有失败才记日志：一次播放会打进几十个 /proxy（含分片），逐条记会把 logcat 冲掉
            Log.w(TAG, "没有 spider 接手：$uri?${params["do"] ?: "-"}")
            // 502：请求本身没错，是后端没接。NanoHTTPD 的枚举里没有 502
            return text(RawProxyStatus(502), "没有 spider 接手这个请求：$uri")
        }

        // 成功路径也要说话，但每个动作只记第一条，否则同样会被分片请求冲掉
        val action = params["do"] ?: "-"
        if (loggedActions.add(action)) {
            val code = (result.getOrNull(0) as? Number)?.toInt() ?: -1
            Log.i(TAG, "jar 接手 do=$action → $code")
        }

        return toResponse(result)
    }

    /** 解析（字段顺序按类型归一、状态码兜底、响应体转流）全在三个纯函数里，各有一组单测。 */
    private fun toResponse(rs: Array<Any?>): Response {
        val payload = proxyPayloadOf(rs)
        val response = NanoHTTPD.newChunkedResponse(
            proxyStatusOf(payload.code),
            payload.mime,
            proxyBodyStream(payload.body),
        )
        payload.headers?.forEach { (key, value) ->
            if (key is String && value != null) response.addHeader(key, value.toString())
        }
        return response
    }

    private fun text(status: IStatus, message: String): Response =
        NanoHTTPD.newFixedLengthResponse(status, NanoHTTPD.MIME_PLAINTEXT, message)

    companion object {

        private const val TAG = "LocalProxy"
        private const val PATH_PROXY = "/proxy"
        private const val PORT_FROM = 9978
        private const val PORT_TO = 9998
        private const val SOCKET_READ_TIMEOUT_MS = 500

        @Volatile
        private var instance: LocalProxyServer? = null

        /**
         * 启动（幂等）。
         *
         * ⚠️ **必须在任何 jar 站点被创建之前调用** —— jar 一旦拿到 `-1` 端口并把地址
         * 交给播放器，这次播放就废了。
         *
         * @return 是否处于监听状态。端口全被占时返回 false。
         */
        @Synchronized
        fun ensureStarted(handler: ProxyHandler): Boolean {
            if (instance != null) return true
            for (port in PORT_FROM..PORT_TO) {
                val server = LocalProxyServer(port, handler)
                try {
                    server.start(SOCKET_READ_TIMEOUT_MS)
                } catch (e: IOException) {
                    runCatching { server.stop() }
                    continue
                }
                instance = server
                Proxy.set(port)
                Log.i(TAG, "本地代理服务已启动：http://127.0.0.1:$port$PATH_PROXY")
                return true
            }
            Log.e(TAG, "端口 $PORT_FROM-$PORT_TO 全部被占用，本地代理服务未启动")
            return false
        }

        /** 未启动时是端口 -1 的无效地址（调用方应先用 [ensureStarted]）。 */
        fun address(): String = "http://127.0.0.1:${Proxy.getPort()}"

        @Synchronized
        fun stop() {
            instance?.let { runCatching { it.stop() } }
            instance = null
            Proxy.set(-1)
        }
    }
}
