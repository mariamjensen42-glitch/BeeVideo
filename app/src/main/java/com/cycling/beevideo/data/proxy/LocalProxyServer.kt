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
 * ─── 它解决的是什么 ──────────────────────────────────────────────────
 * 真实 jar 不直接给出最终的媒体地址，而是调 `Proxy.getUrl(true)` 拼一个
 * `http://127.0.0.1:<port>/proxy?do=m3u8&url=<真实地址>` 交给播放器。
 * 播放器去取这个地址时，必须有**真的**服务在那儿听着，把请求交回 jar 自己的
 * 静态 `com.github.catvod.spider.Proxy.proxy(Map)` —— 由 jar 决定怎么取、
 * 怎么加头、怎么做 m3u8 改写。
 *
 * 没有这个服务时的表现（实测）：`MalformedURLException: invalid port: -1`
 * —— 站点能进详情页，一点播放就报错。**不是兼容层的签名问题。**
 *
 * ─── 端口协商 ────────────────────────────────────────────────────────
 * 从 9978 往 9998 逐个试（参考宿主 `Server.start()` 是 `i < 9999`）。
 * jar 侧有一部分实现会自己从 9978 往上扫端口去找宿主，所以**绑定成功就立刻
 * `Proxy.set(port)`**：两条发现路径（显式取端口 / 扫端口）就都指向同一个真相。
 *
 * ─── 只实现 `/proxy` ─────────────────────────────────────────────────
 * 参考宿主的 Nano 还挂了 `/action` `/cache` `/image` `/local` `/media` `/parse`
 * `/device` 以及一套网页 UI 的静态资源 —— 那些是**它的产品功能**（远程遥控、
 * 局域网投屏、本地文件浏览、字幕上传），不在本项目「手机竖屏 + 点播 + 本地播放」
 * 的范围里。收到一律 404 并说明原因，而不是静默返回空。
 */
class LocalProxyServer private constructor(
    port: Int,
    private val handler: ProxyHandler,
) : NanoHTTPD(port) {

    /**
     * 已经记过日志的动作名。NanoHTTPD 是**多线程**处理请求的，所以必须用并发集合
     * ——重复打日志本身无害，但 `HashSet` 并发写会真出问题。
     */
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

        /*
         * query 参数、HTTP 头、POST 表单**合并进同一张表**。
         * 这是 CatVod 的既定契约（参考实现三处 putAll），不是图省事：
         * jar 从同一张表里读 `do`（动作）和 `range`（拖动进度）——
         * 后者是播放器发的**头**，前者是**参数**，两者没法分表。
         */
        val params = LinkedHashMap<String, String>()
        // 取每个参数的**首个**值：CatVod 的入参是 Map<String,String>，
        // 而同名参数出现多次时（播放器不会这么发，但不代表别人不会）取第一个
        // 与已废弃的 `getParms()` 行为一致，是能对得上参考实现的那个语义。
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
            // 只有失败才记日志：一次播放会打进来几十个 /proxy 请求（含分片），
            // 逐条记会把 logcat 冲掉，反而看不见真正的错。
            Log.w(TAG, "没有 spider 接手：$uri?${params["do"] ?: "-"}")
            // 502：请求本身没错，是后端（jar）没接。NanoHTTPD 的枚举里没有 502。
            return text(RawProxyStatus(502), "没有 spider 接手这个请求：$uri")
        }

        /*
         * 成功路径也要说话，但**每个动作只记第一条**。
         *
         * 全记会重蹈上面那个覆辙（几十条分片请求）；完全不记则无法回答排查时
         * 第一个该问的问题："这个请求到底是 jar 接住了、还是我们回了 502？"
         * 按 `do` 去重后条目数是个位数（m3u8 / proxy / media …），代价可忽略。
         */
        val action = params["do"] ?: "-"
        if (loggedActions.add(action)) {
            val code = (result.getOrNull(0) as? Number)?.toInt() ?: -1
            Log.i(TAG, "jar 接手 do=$action → $code")
        }

        return toResponse(result)
    }

    /**
     * `Object[]` → HTTP 响应。
     *
     * 解析（字段顺序按类型归一、状态码兜底、响应体转流）全在
     * [proxyPayloadOf] / [proxyBodyStream] / [proxyStatusOf] 三个纯函数里，
     * 它们有自己的单测；**本方法只负责把它们拼成一个 `Response`**。
     *
     * 这样切一刀的动机很具体：那几条规则是"实测踩到了才发现"的容错，而它们
     * 出错的表现是运行期 `ClassCastException` 或内容错位的响应 —— 编译期
     * 毫无提示，靠真机一集一集试又太贵。
     */
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
         * 启动（幂等）。**必须在任何 jar 站点被创建之前调用** ——
         * jar 一旦有机会拿到 `-1` 端口并把地址交给播放器，这次播放就废了。
         *
         * @return 是否处于监听状态。端口全被占时返回 false（少见但可能：
         *   同一台机器上跑了别的 catvod 系应用）。
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

        /** 当前监听地址；未启动时是端口 -1 的无效地址（调用方应先用 [ensureStarted]）。 */
        fun address(): String = "http://127.0.0.1:${Proxy.getPort()}"

        @Synchronized
        fun stop() {
            instance?.let { runCatching { it.stop() } }
            instance = null
            Proxy.set(-1)
        }
    }
}
