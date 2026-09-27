package com.cycling.beevideo.data.source.vod.js

import com.github.catvod.Proxy
import com.github.catvod.utils.Crypto
import com.github.catvod.utils.Trans
import com.github.catvod.utils.UriUtil
import com.whl.quickjs.wrapper.JSCallFunction
import com.whl.quickjs.wrapper.JSFunction
import com.whl.quickjs.wrapper.JSMethod
import com.whl.quickjs.wrapper.JSObject
import com.whl.quickjs.wrapper.QuickJSContext
import okhttp3.Call
import okhttp3.Callback
import okhttp3.Response
import java.io.IOException
import java.lang.reflect.Method
import java.util.Timer
import java.util.TimerTask
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ExecutorService
import java.util.concurrent.atomic.AtomicInteger

/**
 * 注入到 JS 全局对象上的一组宿主能力，逐行对齐参考宿主的 `Global.java`。
 *
 * 提供：`_http` / `req`、`setTimeout` / `clearTimeout`、`s2t` / `t2s`、
 * `md5X` / `aesX` / `desX` / `rsaX`、`joinUrl`、`getPort` / `getProxy` / `js2Proxy`。
 *
 * ⚠️ 注册靠反射（扫 `getMethods()` 上带 `@JSMethod` 的），所以：每个方法都必须标
 * `@JSMethod`、必须是**实例方法**（进 `companion object` 会变静态、`invoke` 抛异常）、
 * 且 R8 看不见这些引用，靠 `proguard-rules.pro` 的 keep 兜住。
 *
 * ⚠️ QuickJS 的 ctx 绑定创建线程。OkHttp 回调与 Timer 线程都**不能直接碰 ctx**，
 * 一律 `submit` 回 [JsSpider] 的单线程 executor。改这个结构 = 随机偶发 `QuickJSException`。
 *
 * ⚠️ `JSFunction` 是引用计数对象，`hold` / `release` 必须配对：漏 release 是 native
 * 泄漏，漏 hold 是回调触发时对象已被回收（随机崩溃）。
 */
class Global private constructor(
    private val ctx: QuickJSContext,
    private val executor: ExecutorService,
) {

    private val timers = ConcurrentHashMap<Int, Timeout>()
    private val timerId = AtomicInteger()
    private val timer = Timer("quickjs-timer", true)

    @Volatile
    private var destroyed = false

    init {
        setProperty()
    }

    // 反射调用的异常吞成 null（与参考实现一致）：JS 拿到 null 能处理，异常不能
    private fun setProperty() {
        val global = ctx.globalObject
        for (method in javaClass.methods) {
            if (!method.isAnnotationPresent(JSMethod::class.java)) continue
            global.setProperty(method.name, toCallFunction(method))
        }
    }

    private fun toCallFunction(method: Method): JSCallFunction = JSCallFunction { args ->
        try {
            method.invoke(this, *(args ?: emptyArray()))
        } catch (_: Exception) {
            null
        }
    }

    /** 由 [JsSpider.releaseJs] 在同一线程调用（`Timeout.cancelAndRelease` 里是 native 调用）。 */
    fun destroy() {
        destroyed = true
        for (timeout in timers.values) timeout.cancelAndRelease()
        timers.clear()
        timer.cancel()
    }

    @JSMethod
    fun s2t(text: String?): String = Trans.s2t(false, text.orEmpty())

    @JSMethod
    fun t2s(text: String?): String = Trans.t2s(false, text.orEmpty())

    @JSMethod
    fun getPort(): Int = Proxy.getPort()

    /**
     * ⚠️ `?do=js` 是这一层加的，不是 `Proxy.getUrl` 带的 —— 本地代理靠它才知道
     * 该把请求交给 JS 引擎而不是 jar（见 `CatVodProxyDispatcher` 的分支顺序）。
     * `local` 不传时按 false（局域网地址）处理。
     */
    @JSMethod
    fun getProxy(local: Boolean?): String = Proxy.getUrl(local == true) + "?do=js"

    /**
     * 拼一个"回到宿主、由 JS 自己来取流"的代理地址，`from=catvod` 是 [JsSpider.proxy]
     * 分流的判据。
     *
     * ⚠️ `header`（`headers.stringify()` 的 JSON 文本）与 `url` 必须 URL 编码，
     * 否则里面的 `{}"` 会把 query 切碎。注意传进 [getProxy] 时 `dynamic` **取了反**，
     * 这是参考实现的行为，不是笔误。
     */
    @JSMethod
    fun js2Proxy(
        dynamic: Boolean?,
        siteType: Int?,
        siteKey: String?,
        url: String?,
        headers: JSObject?,
    ): String {
        val header = encode(headers?.stringify().orEmpty())
        val target = encode(url.orEmpty())
        return getProxy(dynamic != true) +
            "&from=catvod&siteType=$siteType&siteKey=$siteKey&header=$header&url=$target"
    }

    /** @return 定时器 id；失败返回 `0`（id 从 1 开始，JS 侧可用 `if (!id)` 判失败）。 */
    @JSMethod
    fun setTimeout(func: JSFunction?, delay: Int?): Int {
        val timeout = createTimeout(func) ?: return 0
        return if (schedule(timeout, delay)) timeout.id else 0
    }

    @JSMethod
    fun clearTimeout(id: Int?): Any? {
        cancel(id)
        return null
    }

    /**
     * 同步 / 异步由 options 里有没有 `complete` 回调决定（`http.js` 的分工）。
     *
     * ⚠️ 同步分支会阻塞 [JsSpider] 的单线程 executor，慢请求会把该站点后续调用全堵住。
     * 不替 JS 改成异步：那会让"拿到响应再算签名"这类必须同步的用法失效。
     */
    @JSMethod
    fun _http(url: String?, options: JSObject?): JSObject? {
        val complete = options?.getJSFunction("complete")
        if (complete == null) return req(url, options)
        requestAsync(url.orEmpty(), options, complete)
        return null
    }

    /** 同步 HTTP。任何失败都退化成空响应对象，**不抛**。 */
    @JSMethod
    fun req(url: String?, options: JSObject?): JSObject = try {
        val req = Req.objectFrom(options?.stringify())
        val res = Connect.to(url.orEmpty(), req).execute()
        Connect.success(ctx, req, res)
    } catch (_: Exception) {
        Connect.error(ctx)
    }

    @JSMethod
    fun joinUrl(parent: String?, child: String?): String = UriUtil.resolve(parent, child)

    @JSMethod
    fun md5X(text: String?): String = Crypto.md5(text)

    @JSMethod
    fun aesX(
        mode: String,
        encrypt: Boolean,
        input: String,
        inBase64: Boolean,
        key: String,
        iv: String?,
        outBase64: Boolean,
    ): String = Crypto.aes(mode, encrypt, input, inBase64, key, iv, outBase64)

    @JSMethod
    fun desX(
        mode: String,
        encrypt: Boolean,
        input: String,
        inBase64: Boolean,
        key: String,
        iv: String?,
        outBase64: Boolean,
    ): String = Crypto.des(mode, encrypt, input, inBase64, key, iv, outBase64)

    @JSMethod
    fun rsaX(
        mode: String,
        pub: Boolean,
        encrypt: Boolean,
        input: String,
        inBase64: Boolean,
        key: String,
        outBase64: Boolean,
    ): String = Crypto.rsa(mode, pub, encrypt, input, inBase64, key, outBase64)

    // ⚠️ hold 必须在发起请求之前、在任何可能失败的点之前：catch 里会把函数交回
    // executor 去调，那时没有 hold 对象可能已被回收
    private fun requestAsync(url: String, options: JSObject, complete: JSFunction) {
        complete.hold()
        try {
            val req = Req.objectFrom(options.stringify())
            Connect.to(url, req).enqueue(getCallback(complete, req))
        } catch (_: Throwable) {
            completeError(complete)
        }
    }

    private fun getCallback(complete: JSFunction, req: Req): Callback = object : Callback {
        override fun onResponse(call: Call, response: Response) {
            completeSuccess(complete, req, response)
        }

        override fun onFailure(call: Call, e: IOException) {
            completeError(complete)
        }
    }

    // ⚠️ 回调没能提交（executor 已关）必须当场 res.close()：否则没人读也没人关，连接挂在池里
    private fun completeSuccess(complete: JSFunction, req: Req, res: Response) {
        val posted = postCallback(complete) { complete.call(Connect.success(ctx, req, res)) }
        if (!posted) res.close()
    }

    private fun completeError(complete: JSFunction) {
        postCallback(complete) { complete.call(Connect.error(ctx)) }
    }

    // 排不进去也要 release：hold 已经加过一次，没人再来减
    private fun postCallback(callback: JSFunction, runnable: Runnable): Boolean {
        val posted = submit { callAndRelease(callback, runnable) }
        if (!posted) callback.release()
        return posted
    }

    private fun callAndRelease(callback: JSFunction, runnable: Runnable) {
        try {
            if (!destroyed) runnable.run()
        } finally {
            callback.release()
        }
    }

    private fun createTimeout(func: JSFunction?): Timeout? {
        if (func == null || destroyed) return null
        val timeout = Timeout(timerId.incrementAndGet(), func)
        timers[timeout.id] = timeout
        func.hold()
        return timeout
    }

    private fun schedule(timeout: Timeout, delay: Int?): Boolean = try {
        timer.schedule(timeout, getDelay(delay).toLong())
        true
    } catch (_: Throwable) {
        // 定时器已 cancel（destroy 之后）→ 当场收回，别留一个永不触发的条目
        cancel(timeout.id)
        false
    }

    /** 负延迟按 0 处理（`Timer.schedule` 对负数会抛 `IllegalArgumentException`）。 */
    private fun getDelay(delay: Int?): Int = maxOf(0, delay ?: 0)

    private fun cancel(id: Int?) {
        if (id == null) return
        val timeout = timers.remove(id) ?: return
        timeout.cancelAndRelease()
    }

    private fun submit(runnable: Runnable): Boolean = try {
        if (destroyed || executor.isShutdown) {
            false
        } else {
            executor.submit(runnable)
            true
        }
    } catch (_: Throwable) {
        false
    }

    /**
     * 一个 `setTimeout`。三条出口（触发、`clearTimeout`、`destroy`）都必须走到
     * [cancelAndRelease]，否则就是一次 native 泄漏。
     *
     * ⚠️ 必须是 `inner class`：要访问外层的 `submit` / `cancel` / `destroyed`。
     */
    inner class Timeout(val id: Int, private val func: JSFunction) : TimerTask() {

        @Volatile
        private var canceled = false

        private var released = false

        override fun run() {
            // ⚠️ 定时器线程不能直接调 JS，必须回到 ctx 所属线程
            if (this@Global.submit(Runnable { fire() })) return
            this@Global.cancel(id)
        }

        private fun fire() {
            if (canceled) return
            try {
                func.call()
            } finally {
                // 触发即消耗，不然 timers 里的条目永远留着
                this@Global.cancel(id)
            }
        }

        @Synchronized
        fun cancelAndRelease() {
            canceled = true
            cancel()
            release()
        }

        @Synchronized
        fun release() {
            if (released) return
            released = true
            func.release()
        }
    }

    companion object {

        private val HEX = "0123456789ABCDEF".toCharArray()

        /**
         * `android.net.Uri.encode` 的等价实现：不编码 `A-Za-z0-9` 与 `- _ ! . ~ ' ( ) *`。
         *
         * ⚠️ 用不了 `android.net.Uri`（纯 JVM 单测里是桩、返回 null），也用不了
         * `URLEncoder`（空格编成 `+`，服务端解回来是错的）。
         */
        private fun encode(value: String): String {
            val out = StringBuilder(value.length)
            for (byte in value.toByteArray(Charsets.UTF_8)) {
                val code = byte.toInt() and 0xFF
                val ch = code.toChar()
                if (code < 0x80 && (ch.isLetterOrDigit() || UNRESERVED.indexOf(ch) >= 0)) {
                    out.append(ch)
                } else {
                    out.append('%').append(HEX[code shr 4]).append(HEX[code and 0x0F])
                }
            }
            return out.toString()
        }

        private const val UNRESERVED = "-_!.~'()*"

        fun create(ctx: QuickJSContext, executor: ExecutorService): Global = Global(ctx, executor)
    }
}
