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
 * 注入到 JS **全局对象**上的一组宿主能力
 * —— 逐行对齐参考宿主的 `quickjs/src/main/java/com/fongmi/quickjs/method/Global.java`。
 *
 * ─── 它给 JS 提供了什么 ──────────────────────────────────────────────
 * ```
 * _http(url, options)                 同步/异步 HTTP（http.js 的底座）
 * req(url, options)                   同步 HTTP
 * setTimeout(fn, ms) / clearTimeout   定时器
 * s2t / t2s                           简繁转换
 * md5X / aesX / desX / rsaX           加解密（接口签名）
 * joinUrl(parent, child)              URL 解析
 * getPort / getProxy / js2Proxy       本地代理地址拼接
 * ```
 *
 * ─── ⚠️ 注册方式是**反射**，这决定了本文件的所有约束 ──────────────────
 * ```java
 * for (Method m : getClass().getMethods())
 *     if (m.isAnnotationPresent(JSMethod.class))
 *         ctx.getGlobalObject().setProperty(m.getName(), args -> m.invoke(this, args));
 * ```
 * 于是：
 *   1. **每个要暴露的方法都必须标 `@JSMethod`**（漏标 = JS 侧 `undefined`）；
 *   2. 方法必须是**实例方法**（`m.invoke(this, ...)`）—— 放进 `companion object`
 *      就变成静态方法，`invoke` 会抛 `IllegalArgumentException`；
 *   3. **R8 看不见这些引用**，必须靠 `proguard-rules.pro` §8 的 keep 规则兜住。
 *
 * ─── ⚠️ 线程模型：三条线程在同一个 ctx 上碰面 ─────────────────────────
 * QuickJS 的 ctx **绑定创建它的线程**，跨线程调用直接抛
 * `Must be call same thread in QuickJSContext.create!`。所以：
 * ```
 * [JsSpider 的单线程 executor]  ← 所有 JS 调用都在这儿
 *         ↑ 提交回调
 * [OkHttp 回调线程]  ── postCallback ──→ submit(...)
 *         ↑
 * [java.util.Timer 线程] ── Timeout.run ──→ submit(...)
 * ```
 * 也就是说 **HTTP 回调和定时器都不直接碰 ctx**，一律 `submit` 回那个单线程
 * executor。改这个结构的后果是随机、偶发的 `QuickJSException`，很难复现。
 *
 * ─── 引用计数：`hold` / `release` 必须配对 ───────────────────────────
 * `JSFunction` 是引用计数对象。异步回调与定时器都会**把函数留到将来某一刻**再调，
 * 所以那一刻之前它不能被回收 —— 这就是 `hold()` 的全部意义。
 * 漏掉 `release()` 是 native 侧泄漏（跑几十个站点后内存不降）；
 * 漏掉 `hold()` 则是**回调触发时对象已被回收**，表现为随机崩溃。
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

    /**
     * 把标了 `@JSMethod` 的方法逐个挂到 JS 全局对象上。
     *
     * ⚠️ 反射调用的异常被**吞成 null**（与参考实现一致）：JS 侧传错参数类型
     * （比如 `md5X()` 不传参）时得到 `null`，而不是一个穿透 JNI 的异常。
     * 对爬虫来说"拿到 null"是能处理的，异常不是。
     */
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

    /**
     * 释放所有定时器与线程。
     *
     * 由 [JsSpider.releaseJs] 在**同一个线程**上调用 —— `Timeout.cancelAndRelease`
     * 里会 `func.release()`，那是 native 调用，跨线程同样会炸。
     */
    fun destroy() {
        destroyed = true
        for (timeout in timers.values) timeout.cancelAndRelease()
        timers.clear()
        timer.cancel()
    }

    // ── 文本 ──────────────────────────────────────────────────────────

    @JSMethod
    fun s2t(text: String?): String = Trans.s2t(false, text.orEmpty())

    @JSMethod
    fun t2s(text: String?): String = Trans.t2s(false, text.orEmpty())

    // ── 本地代理 ──────────────────────────────────────────────────────

    @JSMethod
    fun getPort(): Int = Proxy.getPort()

    /**
     * 本地代理的基地址，**带 `?do=js`**。
     *
     * ⚠️ `?do=js` 这个后缀是**这一层加的**，不是 `Proxy.getUrl` 带的 ——
     * 本地代理服务靠 `do=js` 才知道该把请求交给 JS 引擎而不是 jar
     * （见 `CatVodProxyDispatcher` 的分支顺序）。
     *
     * @param local true 用 `127.0.0.1`；JS 侧不传时按 **false**（局域网地址）处理，
     *   与参考实现一致（它把 `null` 拆箱会 NPE，这里退化成 false）。
     */
    @JSMethod
    fun getProxy(local: Boolean?): String = Proxy.getUrl(local == true) + "?do=js"

    /**
     * 拼一个"回到宿主、由 JS 自己来取流"的代理地址。
     *
     * ```
     * http://127.0.0.1:9978/proxy?do=js&from=catvod&siteType=…&siteKey=…&header=…&url=…
     * ```
     * `from=catvod` 是 [JsSpider.proxy] 分流两条代理路径的判据。
     *
     * ⚠️ `header` 与 `url` **必须 URL 编码**，且 `header` 是
     * `headers.stringify()`（一段 JSON 文本）编码后的结果 —— 它里面
     * 全是 `{` `}` `"`，不编码会把 query 切得粉碎。
     * 这里用的是**与 `android.net.Uri.encode` 等价**的编码器（见 [encode]），
     * 不是 `URLEncoder`（后者把空格编成 `+`，服务端解回来是加号）。
     *
     * @param dynamic true = 要一个**局域网**地址（给别的设备取），false = 本机。
     *   注意传进 [getProxy] 时**取了反**，这是参考实现的行为，不是笔误。
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

    // ── 定时器 ────────────────────────────────────────────────────────

    /**
     * @return 定时器 id；创建失败返回 `0`。
     *   ⚠️ `0` 不是合法 id（id 从 1 开始），所以 JS 侧可以用 `if (!id)` 判失败。
     */
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

    // ── HTTP ─────────────────────────────────────────────────────────

    /**
     * HTTP 入口。**同步 / 异步由 options 里有没有 `complete` 回调决定**：
     * ```
     * _http(url, {complete: res => …})  → 异步，立即返回 null
     * _http(url, {async: false})        → 同步，直接返回响应对象
     * ```
     * 这正是 `http.js` 里那两行的分工（`req` 走同步、默认走 Promise）。
     *
     * ⚠️ 同步分支会**阻塞调用它的那个线程**，也就是 [JsSpider] 的单线程 executor。
     * 一个慢请求会把该站点的所有后续调用排队堵住 —— 这是 JS 侧自己选的语义
     * （它写了 `async: false`），宿主不替它改成异步：改了会让"拿到响应再算签名"
     * 这类必须同步的用法彻底失效。
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

    // ── 杂项 ─────────────────────────────────────────────────────────

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

    // ── 异步 HTTP 的内部机制 ──────────────────────────────────────────

    /**
     * ⚠️ `complete.hold()` **必须在发起请求之前**，而且要在**任何可能失败的点之前**：
     * 下面 `catch` 里的 `completeError` 会把函数交回 executor 去调，
     * 那时如果没有 hold，对象可能已经被 QuickJS 回收。
     */
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

    /**
     * ⚠️ 回调**没能提交**时（executor 已关）必须当场 `res.close()`：
     * 这种情况下 `Connect.success` 永远跑不到，响应体没人读也没人关，
     * 连接会一直挂在池里直到超时。
     */
    private fun completeSuccess(complete: JSFunction, req: Req, res: Response) {
        val posted = postCallback(complete) { complete.call(Connect.success(ctx, req, res)) }
        if (!posted) res.close()
    }

    private fun completeError(complete: JSFunction) {
        postCallback(complete) { complete.call(Connect.error(ctx)) }
    }

    /**
     * 把回调排到 executor 上；排不进去就**当场释放**那个函数。
     *
     * 这一条"排不进去也要 release"是引用计数能对上的关键：
     * hold 已经加过一次，没有任何人再来减了。
     */
    private fun postCallback(callback: JSFunction, runnable: Runnable): Boolean {
        val posted = submit { callAndRelease(callback, runnable) }
        if (!posted) callback.release()
        return posted
    }

    private fun callAndRelease(callback: JSFunction, runnable: Runnable) {
        try {
            if (!destroyed) runnable.run()
        } finally {
            // 无论跑没跑成都得释放：hold 与 release 必须一一对应
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
        // 定时器已经 cancel（destroy 之后）→ 当场收回，别留一个永远不会触发的条目
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
     * 一个 `setTimeout`。
     *
     * 生命周期：`timers` 表持有它 → 触发时从表里摘掉 → cancel/release。
     * 三条出口（正常触发、`clearTimeout`、`destroy`）都必须走到
     * [cancelAndRelease]，否则就是一次 native 泄漏。
     *
     * ⚠️ 它**不能**声明成 `private class` 之外的东西：需要访问外层的
     * `submit` / `cancel` / `destroyed`，所以是 Kotlin 的 `inner class`
     * （等价于 Java 的非静态内部类）。
     */
    inner class Timeout(val id: Int, private val func: JSFunction) : TimerTask() {

        @Volatile
        private var canceled = false

        private var released = false

        override fun run() {
            // ⚠️ 定时器线程**不能直接调 JS**，必须回到 ctx 所属的线程
            if (this@Global.submit(Runnable { fire() })) return
            // 提交失败（executor 已关）→ 这是最后一次机会把它清掉
            this@Global.cancel(id)
        }

        private fun fire() {
            if (canceled) return
            try {
                func.call()
            } finally {
                // 触发即消耗：不进这一步，timers 里的条目会永远留着
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

        /** 十六进制大写字母表。`Uri.encode` 用的就是大写（`%2F` 而不是 `%2f`）。 */
        private val HEX = "0123456789ABCDEF".toCharArray()

        /**
         * `android.net.Uri.encode` 的等价实现：不编码
         * `A-Za-z0-9` 与 `- _ ! . ~ ' ( ) *`，其余按 UTF-8 逐字节 `%XX`。
         *
         * ⚠️ 用不了 `android.net.Uri`（它在纯 JVM 单测里是桩，返回 null），
         * 也用不了 `java.net.URLEncoder`（空格编成 `+`，且不区分
         * `~` 之类的保留差异）—— 后者拼出来的代理地址在服务端解回来是错的。
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
