package com.cycling.beevideo.data.source.vod.js

import android.content.Context
import android.util.Log
import com.github.catvod.utils.Asset
import com.github.catvod.utils.Json
import com.whl.quickjs.wrapper.JSArray
import com.whl.quickjs.wrapper.JSObject
import com.whl.quickjs.wrapper.QuickJSContext
import dalvik.system.DexClassLoader
import java.util.concurrent.Callable
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.Future
import org.json.JSONArray

/**
 * `.js` 爬虫（drpy 系）的宿主实现，逐行对齐参考宿主的 `quickjs/crawler/Spider.java`。
 * 它继承本项目的 `com.github.catvod.crawler.Spider`，所以对上层来说与 jar 爬虫完全一样。
 *
 * ⚠️ **线程模型**：`QuickJSContext` 绑定创建它的线程，跨线程调用会抛
 * `QuickJSException: Must be call same thread in QuickJSContext.create!`。
 * 所有 ctx 操作（建 ctx、所有 JS 调用、JSUtil 转换、proxy 系列）都排队进本类的
 * **私有单线程 executor**；不要改多线程池。
 *
 * ⚠️ 每个走到 JS 的调用都会 `Future.get()` 阻塞，所以本类方法必须在**后台线程**上调用
 * （上层已 `withContext(Dispatchers.IO)`）。在主线程序列化 = ANR。
 */
class JsSpider(
    private val api: String,
    private val dex: DexClassLoader?,
) : com.github.catvod.crawler.Spider() {

    private val executor: ExecutorService = Executors.newSingleThreadExecutor()

    private var ctx: QuickJSContext? = null
    private var jsObject: JSObject? = null
    private var global: Global? = null

    /** 是不是 drpy 的"`__jsEvalReturn` 形态"。两种形态的 `ext` 传法不同（见 [getExt]）。 */
    private var cat = false

    /** `pdfh` / `pdfa` / `pd` / `pdfl` 的宿主实现，见 [JsDomFunctions]。 */
    private var domFunctions: JsDomFunctions? = null

    private fun <T> submit(callable: Callable<T>): Future<T> = executor.submit(callable)

    /**
     * 调 JS 函数并等它 settle（Promise 也等）。
     * `.get().get()`：外层等 executor 跑完，内层等 JS 侧那个 Promise。
     * 内层抛出的 `ExecutionException` 里包着 JS 的 Error 消息，是排查"源为什么取不到数据"的唯一线索。
     */
    private fun call(func: String, vararg args: Any?): Any? =
        submit(Callable { Async.run(requireJsObject(), func, *args) }).get().get()

    private fun requireJsObject(): JSObject =
        jsObject ?: throw IllegalStateException("JS 爬虫尚未初始化（init 之前调用了查询）")

    // ── 生命周期 ──────────────────────────────────────────────────────

    /**
     * ⚠️ `context` **参数没用到**，这是参考实现的行为：JS 侧要的 Context 走全局的
     * `com.github.catvod.Init`。保留参数是为了符合基类 ABI（`SiteClientFactory` 调两参版本）。
     */
    @Suppress("UNUSED_PARAMETER")
    override fun init(context: Context, extend: String?) {
        initializeJS()
        call("init", submit(Callable { getExt(extend) }).get())
    }

    private fun initializeJS() {
        submit(Callable {
            ctx = createJsContext()
            createFun()
            createObj()
            null
        }).get()
    }

    /**
     * 建 [Global]、注册 DOM 函数、尝试加载可选的 jar 钩子 `com.github.catvod.js.Function`。
     *
     * ⚠️ 注册必须在 [createObj] **之前**（drpy2 的模块顶层就取 `pdfh`）。
     * ⚠️ [Global] 的创建不能放进 try 里和 dex 加载混在一起：dex 为 null 时若 `global` 没被赋值，
     * [destroy] 会漏掉所有定时器和线程。
     */
    private fun createFun() {
        val context = requireCtx()
        global = Global.create(context, executor)
        domFunctions = JsDomFunctions(context).also { it.register() }

        val loader = dex ?: run {
            Log.i(JS_LOG_TAG, "无可选 jar：pdfh/pdfa/pd/pdfl 用宿主内置实现")
            return
        }
        try {
            val clazz = loader.loadClass("com.github.catvod.js.Function")
            clazz.getDeclaredConstructor(QuickJSContext::class.java).newInstance(context)
            Log.i(JS_LOG_TAG, "jar 提供了 com.github.catvod.js.Function，已覆盖宿主内置实现")
        } catch (e: Throwable) {
            Log.i(JS_LOG_TAG, "jar 无 com.github.catvod.js.Function，继续用宿主内置实现（${e.javaClass.simpleName}）")
        }
    }

    /**
     * 把源文件求值成 `globalThis.__JS_SPIDER__`。
     *
     * `spider.js` 那段模板负责统一 drpy 的两种源形态（`__jsEvalReturn()` / `export default`）。
     * ⚠️ `replace(spider, globalName)` 是必要的：模块作用域里的顶层赋值不会落到全局对象上，
     * 不改写的话每个方法调用都变成"方法不存在"（静默返回 null）。
     */
    private fun createObj() {
        val spider = SPIDER_KEY
        val globalName = "globalThis.$spider"
        val content = JsModule.get().fetch(api)
            ?: throw IllegalStateException("JS 源取不到：$api（http 地址不通？assets 没打进去？）")

        cat = content.contains("__jsEvalReturn")

        requireCtx().evaluateModule(content.replace(spider, globalName), api)

        val template = Asset.read(SPIDER_JS)
        if (template.isEmpty()) {
            throw IllegalStateException("读不到 $SPIDER_JS —— assets 没打进去？")
        }
        requireCtx().evaluateModule(template.format(api), SPIDER_JS)

        jsObject = requireCtx().getProperty(requireCtx().globalObject, spider) as? JSObject
            ?: throw IllegalStateException(
                "JS 源没有导出爬虫对象（$api）。" +
                    "源应当 export 一个 default 对象或 __jsEvalReturn() 函数。",
            )
    }

    private fun releaseJS() {
        submit(Callable {
            // 先丢 DOM 缓存：它是一种"最后一段 html"的单槽引用，留着不释放
            domFunctions?.clear()
            domFunctions = null
            global?.destroy()
            global = null
            jsObject?.release()
            jsObject = null
            ctx?.destroy()
            ctx = null
            null
        }).get()
    }

    /**
     * 三步都要做且顺序不能换：JS 侧自己收尾 → 释放 native 资源 → 关线程池。
     * ⚠️ 前两步必须吞异常：源里的 `destroy` 抛错是常态，让它把第三步跳过去 = 线程池泄漏。
     */
    override fun destroy() {
        try {
            call("destroy")
        } catch (e: Throwable) {
            Log.w(JS_LOG_TAG, "JS 源的 destroy 钩子失败：$e")
        }
        try {
            releaseJS()
        } catch (e: Throwable) {
            Log.w(JS_LOG_TAG, "释放 JS 上下文失败：$e")
        } finally {
            executor.shutdownNow()
        }
    }

    // ── 素材源契约 ────────────────────────────────────────────────────
    //
    // 一律 `as? String ?: ""`：JS 侧没实现某个入口时 Async 会 complete(null)，而基类默认返回空串。
    // 用 null 会让调用方在别处 NPE，失败点远离真正的原因。

    override fun homeContent(filter: Boolean): String = call("home", filter) as? String ?: ""

    override fun homeVideoContent(): String = call("homeVod") as? String ?: ""

    override fun categoryContent(
        tid: String,
        pg: String,
        filter: Boolean,
        extend: HashMap<String, String>,
    ): String {
        // extend 要先转成 JS 对象，必须在 ctx 线程上做
        val obj = submit(Callable { JSUtil.toObject(requireCtx(), extend) }).get()
        return call("category", tid, pg, filter, obj) as? String ?: ""
    }

    override fun detailContent(ids: List<String>): String =
        call("detail", ids.firstOrNull()) as? String ?: ""

    override fun searchContent(key: String, quick: Boolean): String =
        call("search", key, quick) as? String ?: ""

    override fun searchContent(key: String, quick: Boolean, pg: String): String =
        call("search", key, quick, pg) as? String ?: ""

    override fun playerContent(flag: String?, id: String?, vipFlags: List<String>?): String {
        val array = submit(Callable { JSUtil.toArray(requireCtx(), vipFlags) }).get()
        return call("play", flag, id, array) as? String ?: ""
    }

    override fun liveContent(url: String?): String = call("live", url) as? String ?: ""

    /** ⚠️ 源没实现 `sniffer` 时返回 false 而不是抛异常（参考实现这里会 NPE）。 */
    override fun manualVideoCheck(): Boolean = call("sniffer") as? Boolean ?: false

    override fun isVideoFormat(url: String?): Boolean = call("isVideo", url) as? Boolean ?: false

    override fun action(action: String?): String? = call("action", action) as? String

    // ── 本地代理回路 ──────────────────────────────────────────────────

    /**
     * 代理入口，两条分支由 **`from` 参数**决定：`from=catvod` → [proxy2]（JS 自己取流），
     * 其它 → [proxy1]（JS 返回数组）。
     * ⚠️ 判据必须是 `from` 而不是 `do`：`do=js` 只说明"找 JS 引擎"。分派顺序见 CatVodProxyDispatcher。
     */
    override fun proxy(params: Map<String, String>?): Array<Any>? {
        val map = params ?: return null
        return if ("catvod" == map["from"]) proxy2(map) else proxy1(map)
    }

    /** JS 返回数组形态：`[code, mime, content, headers?, base64?]` → `[code, mime, InputStream, headers]`。 */
    private fun proxy1(params: Map<String, String>): Array<Any>? {
        val obj = submit(Callable { JSUtil.toObject(requireCtx(), params) }).get()
        val proxy = call("proxy", obj) as? JSArray ?: return null

        // stringify 也是 ctx 操作，必须在同一个线程上
        val json = submit(Callable { proxy.stringify() }).get()
        val array = try {
            JSONArray(json)
        } catch (e: Exception) {
            Log.w(JS_LOG_TAG, "JS proxy 返回的不是合法 JSON 数组：$json", e)
            return null
        }

        val headers = if (array.length() > 3) Json.toMap(array.optString(3)) else null
        val base64 = array.length() > 4 && array.optInt(4) == 1

        // 基类签名要求元素非空，而 headers 可能是 null：先按 Array<Any?> 装填再放宽（擦除后都是 Object[]）
        val result = arrayOfNulls<Any>(4)
        result[0] = array.optInt(0)
        result[1] = array.optString(1)
        result[2] = proxyStream(array.opt(2), base64)
        result[3] = headers
        @Suppress("UNCHECKED_CAST")
        return result as Array<Any>
    }

    /**
     * JS 返回 JSON 文本形态（被 `js2Proxy` 拼出来的地址）。
     * ⚠️ `url` 在 Global.js2Proxy 里被 URL 编码过，到这儿已被 NanoHTTPD 解回原文，
     * 所以直接 split("/") 就是 JS 侧 `proxy(array, header)` 要的路径段数组。
     */
    private fun proxy2(params: Map<String, String>): Array<Any>? {
        val url = params["url"].orEmpty()
        val header = params["header"]

        val array = submit(Callable {
            JSUtil.toArray(requireCtx(), url.split("/"))
        }).get()

        // header 是可选的：空串或 null 时传 undefined 给 JS
        val headerObj: Any? = if (header.isNullOrEmpty()) {
            null
        } else {
            submit(Callable { requireCtx().parse(header) }).get()
        }

        val proxy = call("proxy", array, headerObj) as? String ?: return null
        val res = Res.objectFrom(proxy)

        val result = arrayOfNulls<Any>(3)
        result[0] = res.getCode()
        result[1] = res.getContentType()
        result[2] = res.getStream()
        @Suppress("UNCHECKED_CAST")
        return result as Array<Any>
    }

    // ── ext 的两种形态 ────────────────────────────────────────────────

    /**
     * `ext` 交给 JS `init(ext)` 的形态：drpy 系是 `{stype: 3, skey, ext}`，其它源是 ext 本身。
     * ⚠️ `skey` 必须是**站点 key** —— 一个 .js 文件被配成多个站点是常态，JS 侧靠它区分参数。
     * ⚠️ 只能在 ctx 线程上跑，所以调用方都包在 `submit { }` 里。
     */
    private fun getExt(ext: String?): Any? {
        if (!cat) return if (Json.isObj(ext)) requireCtx().parse(ext) else ext
        val obj = requireCtx().createNewJSObject()
        obj.setProperty("stype", 3)
        obj.setProperty("skey", siteKey)
        if (!Json.isObj(ext)) obj.setProperty("ext", ext.orEmpty())
        else obj.setProperty("ext", requireCtx().parse(ext) as JSObject)
        return obj
    }

    private fun requireCtx(): QuickJSContext =
        ctx ?: throw IllegalStateException("JS 上下文尚未创建（init 之前调用了查询）")

    private companion object {

        const val SPIDER_KEY = "__JS_SPIDER__"

        const val SPIDER_JS = "js/lib/spider.js"
    }
}
