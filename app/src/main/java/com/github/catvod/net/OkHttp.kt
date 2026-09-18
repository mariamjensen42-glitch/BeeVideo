package com.github.catvod.net

import com.cycling.beevideo.data.source.vod.catvod.CatVodHttp
import okhttp3.Call
import okhttp3.Dns
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.concurrent.TimeUnit

/**
 * CatVod 的网络工具类 —— 本项目自带的兼容层。
 *
 * 大量 spider 里的 HTTP 请求是这么写的：
 * ```java
 * String body = OkHttp.string("https://…");
 * Response resp = OkHttp.newCall(request).execute();
 * ```
 * 这些都是**静态调用**，所以每个方法都要 `@JvmStatic`；
 * 只写成 Kotlin 的 `object` 方法，Java 侧看到的是 `OkHttp.INSTANCE.string(…)`，
 * 一调就是 `NoSuchMethodError`（实测 jar 里确实是 `invoke-static`）。
 *
 * 同样必须放在 `com.github.catvod.net` 包下，理由见 `Spider.kt`。
 *
 * ─── ⚠️ `newCall` 返回的必须是**未执行**的 `Call` ────────────────────────
 * 原版是：
 * ```java
 * public static Call newCall(String url) { return client().newCall(new Request.Builder().url(url).build()); }
 * ```
 * 返回类型 `okhttp3.Call` 是**方法描述符的一部分**。曾经这里返回的是
 * **已经 execute 过的 `Response`** —— 描述符对不上，jar 里编译好的
 * `newCall(...).execute()` 在解析引用时就 `NoSuchMethodError`，
 * 而且报错完全指不到"返回类型写错了"。
 *
 * **这些方法都是阻塞的**，调用方（spider）在 IO 线程上跑，这里不做额外调度 ——
 * 包一层协程反而会改变调用语义（spider 可能自己在多线程里调）。
 */
object OkHttp {

    private val JSON = "application/json; charset=utf-8".toMediaType()
    private val FORM = "application/x-www-form-urlencoded; charset=utf-8".toMediaType()

    /** 全进程共用一个客户端，理由见 [CatVodHttp]。 */
    @JvmStatic
    fun client(): OkHttpClient = CatVodHttp.client

    /**
     * 播放用的客户端。原版会返回一个超时策略不同的实例；本项目复用同一个 ——
     * 那些为播放单独调优的参数（更大的 readTimeout 等）在点播场景下
     * 由 Media3 自己的缓冲策略覆盖，多开一个连接池得不偿失。
     */
    @JvmStatic
    fun player(): OkHttpClient = CatVodHttp.client

    /**
     * `Spider.safeDns()` 要靠它。原版返回内部类 `OkDns`，我们直接给系统解析器 ——
     * 对爬虫来说这两者都只是"一个 `okhttp3.Dns`"，而签名要求的就是这个接口类型。
     */
    @JvmStatic
    fun dns(): Dns = Dns.SYSTEM

    /**
     * 按「是否跟随重定向 + 超时」派生一个客户端。
     *
     * `Connect.to` 用它给 JS 爬虫的每次 `req()` 定制连接：
     * ```java
     * OkHttp.client(req.isRedirect(), req.getTimeout())
     * ```
     * `redirect` 默认 1，`timeout` 默认 10000ms（见 `Req`）。
     *
     * ⚠️ **每次都新建一个 `OkHttpClient`**，这是有意的，也是安全的：
     * `newBuilder().build()` 出来的实例**共享**父实例的连接池与线程池，
     * 只是超时/重定向策略不同。反过来，改成"缓存几个变体"会让爬虫拿到的超时
     * 不再等于它在 `options` 里要求的那一个 —— 而站点对超时的要求是**逐请求**的。
     *
     * 用 `newBuilder()` 而不是从零 `Builder()`：从零建等于每个请求一个连接池，
     * 这是参考宿主明确避开的事（见 `CatVodHttp` 的类注释）。
     */
    @JvmStatic
    fun client(redirect: Boolean, timeout: Long): OkHttpClient =
        CatVodHttp.client.newBuilder()
            .connectTimeout(timeout, TimeUnit.MILLISECONDS)
            .readTimeout(timeout, TimeUnit.MILLISECONDS)
            .writeTimeout(timeout, TimeUnit.MILLISECONDS)
            .followRedirects(redirect)
            .followSslRedirects(redirect)
            .build()

    // ── 一次性请求 ────────────────────────────────────────────────────

    @JvmStatic
    fun string(url: String): String = string(url, emptyMap())

    @JvmStatic
    fun string(url: String, headers: Map<String, String>): String =
        runCatching {
            newCall(url, headers).execute().use { it.body?.string().orEmpty() }
        }.getOrDefault("")

    @JvmStatic
    fun get(url: String): String = string(url)

    @JvmStatic
    fun post(url: String, body: String): String = post(url, body, emptyMap())

    @JvmStatic
    fun post(url: String, body: String, headers: Map<String, String>): String =
        runCatching {
            newCall(url, headers, body.toRequestBody(JSON)).execute()
                .use { it.body?.string().orEmpty() }
        }.getOrDefault("")

    /** 表单 POST。部分站点的搜索接口只吃这种编码。 */
    @JvmStatic
    fun postForm(url: String, form: String): String =
        runCatching {
            val req = Request.Builder().url(url).post(form.toRequestBody(FORM)).build()
            CatVodHttp.client.newCall(req).execute().use { it.body?.string().orEmpty() }
        }.getOrDefault("")

    // ── Call 工厂（原版的 `newCall` 家族）───────────────────────────────
    //
    // 只实现现实中会被用到的那几个重载。原版还有带 `ArrayMap` 参数的变体
    // （`newCall(url, headers, ArrayMap params)`），那需要引入 androidx.collection
    // 的 ArrayMap，而实测的 7 个 jar 里没有一处用到它 —— 不做投机性实现。

    @JvmStatic
    fun newCall(url: String): Call = build(url, null, emptyMap(), null)

    @JvmStatic
    fun newCall(url: String, tag: String?): Call = build(url, null, emptyMap(), tag)

    @JvmStatic
    fun newCall(client: OkHttpClient, url: String): Call = build(url, client, emptyMap(), null)

    @JvmStatic
    fun newCall(client: OkHttpClient, url: String, tag: String?): Call =
        build(url, client, emptyMap(), tag)

    @JvmStatic
    fun newCall(url: String, headers: Map<String, String>): Call =
        build(url, null, headers, null)

    @JvmStatic
    fun newCall(url: String, headers: Map<String, String>, body: RequestBody): Call =
        build(url, null, headers, null, body)

    @JvmStatic
    fun newCall(client: OkHttpClient, url: String, body: RequestBody): Call =
        build(url, client, emptyMap(), null, body)

    private fun build(
        url: String,
        client: OkHttpClient? = null,
        headers: Map<String, String> = emptyMap(),
        tag: String? = null,
        body: RequestBody? = null,
    ): Call {
        val builder = Request.Builder().url(url)
        headers.forEach { (k, v) -> builder.header(k, v) }
        if (tag != null) builder.tag(tag)
        if (body != null) builder.method("POST", body) else builder.get()
        return (client ?: CatVodHttp.client).newCall(builder.build())
    }

    // ── 取消 ─────────────────────────────────────────────────────────

    /**
     * 按 tag 取消。原版靠 `Dispatcher` 上挂着调用来实现，这里同样 ——
     * 所以**只有通过上面那些带 tag 的 `newCall` 建出来的请求才认得出来**。
     */
    @JvmStatic
    fun cancel(tag: String) = cancel(client(), tag)

    @JvmStatic
    fun cancel(client: OkHttpClient, tag: String) {
        val d = client.dispatcher
        (d.queuedCalls() + d.runningCalls())
            .filter { it.request().tag() == tag }
            .forEach { it.cancel() }
    }

    @JvmStatic
    fun cancelAll() = cancelAll(client())

    @JvmStatic
    fun cancelAll(client: OkHttpClient) {
        client.dispatcher.cancelAll()
    }
}
