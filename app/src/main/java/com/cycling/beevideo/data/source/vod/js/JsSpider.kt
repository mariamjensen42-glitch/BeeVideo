package com.cycling.beevideo.data.source.vod.js

import android.content.Context
import android.util.Log
import com.github.catvod.utils.Asset
import com.github.catvod.utils.Json
import com.github.catvod.utils.UriUtil
import com.github.catvod.utils.Util
import com.whl.quickjs.wrapper.JSArray
import com.whl.quickjs.wrapper.JSObject
import com.whl.quickjs.wrapper.QuickJSContext
import dalvik.system.DexClassLoader
import org.json.JSONArray
import java.io.ByteArrayInputStream
import java.util.concurrent.Callable
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.Future

/**
 * `.js` 爬虫（drpy 系）的宿主实现
 * —— 逐行对齐参考宿主的 `quickjs/src/main/java/com/fongmi/quickjs/crawler/Spider.java`。
 *
 * 它继承本项目自带的 `com.github.catvod.crawler.Spider`，所以对上层
 * （[com.cycling.beevideo.data.source.vod.catvod.SpiderSiteClient] /
 * `SiteClientFactory` / 本地代理派发）来说，它与一个 jar 爬虫**完全一样**。
 * 这正是参考实现的分层方式：引擎不同，契约相同。
 *
 * ─── ⚠️ 线程模型：QuickJS 的 ctx 绑定单线程 ───────────────────────────
 * `QuickJSContext` 记录创建它的线程 id，任何其它线程调它的方法都会抛
 * `QuickJSException: Must be call same thread in QuickJSContext.create!`。
 * 所以：
 * ```
 * 私有单线程 executor（这个类的 executor 字段）
 *   ├─ initializeJS / createCtx / createObj   —— 建 ctx
 *   ├─ call(func, args)                        —— 所有 JS 函数调用
 *   └─ getExt / JSUtil.toObject / proxy1 / proxy2 里的 ctx 操作
 * 外部任意线程 → submit { … } —— 排队进那个线程
 * ```
 * **不要**把 `ctx` 的操作挪到别处，也不要"顺手"把 executor 换成多线程池 ——
 * 症状是随机的、只在并发时才出现的 `QuickJSException`。
 *
 * ─── ⚠️ 每个走到 JS 的调用都会 `Future.get()` ─────────────────────────
 * 也就是**阻塞**。所以本类的方法必须在后台线程上调用
 * （`SiteClientFactory` → `SpiderSiteClient` 的 `call` 已经 `withContext(Dispatchers.IO)`）。
 * 在主线程序列化一个 JS 爬虫 = ANR。
 *
 * ─── 与参考实现的差异：`dex` 可以为 null ──────────────────────────────
 * 参考实现的 `createFun` 里 `dex.loadClass(...)` 直接解引用，靠
 * "先给 global 赋值、再抛 NPE、被 catch 吞掉"来容忍没有 jar 的情况。
 * 这里显式判空（见 [createFun]）—— 行为一致，但不靠异常控制流程。
 * `dex` 是**可选**的：它只为 `com.github.catvod.js.Function`（主 spider.jar
 * 提供的一个额外宿主钩子）而存在，绝大多数配置里没有。
 */
class JsSpider(
    private val api: String,
    private val dex: DexClassLoader?,
) : com.github.catvod.crawler.Spider() {

    private val executor: ExecutorService = Executors.newSingleThreadExecutor()

    private var ctx: QuickJSContext? = null
    private var jsObject: JSObject? = null
    private var global: Global? = null

    /**
     * 这个源是不是 drpy 的"`__jsEvalReturn` 形态"。
     *
     * 两种形态的 `ext` 传法**不一样**（见 [getExt]），所以在 [createObj]
     * 解析源码时就定下来，不能在 [getExt] 里现判。
     */
    private var cat = false

    private fun <T> submit(callable: Callable<T>): Future<T> = executor.submit(callable)

    /**
     * 调 JS 函数并**等它 settle**（Promise 也等）。
     *
     * `.get().get()` 两层：
     *   - 外层 `Future.get()` —— 等 executor 把这次调用跑完；
     *   - 内层 `CompletableFuture.get()` —— 等 JS 侧那个 Promise settle。
     *
     * 内层抛出的 `ExecutionException` 里包着 JS 的 `Error` 消息，
     * 是排查"源为什么取不到数据"的**唯一**线索，别把它吞掉。
     */
    private fun call(func: String, vararg args: Any?): Any? =
        submit(Callable { Async.run(requireJsObject(), func, *args) }).get().get()

    private fun requireJsObject(): JSObject =
        jsObject ?: throw IllegalStateException("JS 爬虫尚未初始化（init 之前调用了查询）")

    // ── 生命周期 ──────────────────────────────────────────────────────

    /**
     * 初始化。
     *
     * ⚠️ `context` **参数没用到**，这是参考实现的行为（照搬）：
     * JS 侧要的 Context 走全局的 `com.github.catvod.Init`（由
     * `BeeApplication.onCreate` 塞进去），而不是从这里传。
     * 保留这个参数是为了**符合基类的 ABI** —— `SiteClientFactory` 调的就是两参版本。
     *
     * ⚠️ 调用顺序：`initializeJS()` 必须先跑完再调 JS 的 `init`。
     * 少了它 `jsObject` 是 null，`call` 直接抛。
     */
    @Suppress("UNUSED_PARAMETER")
    override fun init(context: Context, extend: String?) {
        initializeJS()
        call("init", submit(Callable { getExt(extend) }).get())
    }

    private fun initializeJS() {
        submit(Callable {
            createCtx()
            createFun()
            createObj()
            null
        }).get()
    }

    /**
     * 建 QuickJS 上下文，装好三样东西：
     * ```
     * console       → [JsConsole]（JS 的 console.log 落到 logcat）
     * js/lib/http.js→ 定义全局 http()/req()，并补上 global/window/self 别名
     * local         → [Local.class]（本地键值缓存）
     * 模块加载器     → 把 import 的模块名解析成绝对地址再去取源码
     * ```
     *
     * ⚠️ `setProperty("local", Local::class.java)` 传的是 **Class**。
     * 捆绑库会 `newInstance()` 之后按**实例方法**反射登记（见 [Local] 的注释）——
     * 所以 `Local` 必须是普通 class。写成 `object` 会在这一行抛
     * `NullPointerException: The JavaObj cannot be null`。
     *
     * ⚠️ `http.js` 的 `evaluate` 失败时**不抛异常**（返回 null），
     * 而后果是 JS 侧没有 `http` 函数 —— 每个源都会以
     * `http is not defined` 失败。所以读不到就明确记一条日志，
     * 否则这个错误看起来像"源写错了"。
     */
    private fun createCtx() {
        val context = QuickJSContext.create()
        ctx = context
        context.setConsole(JsConsole())
        val httpJs = Asset.read(HTTP_JS)
        if (httpJs.isEmpty()) {
            Log.e(TAG, "读不到 $HTTP_JS —— assets 没打进去？JS 侧会没有 http() 函数")
        }
        context.evaluate(httpJs)
        context.globalObject.setProperty("local", Local::class.java)
        context.setModuleLoader(object : QuickJSContext.BytecodeModuleLoader() {

            /**
             * 把 `import … from 'xxx'` 里的 `xxx` 按**当前模块的地址**解析成绝对地址。
             * 少了它，drpy 源里大量的相对导入（`./lib/xxx.js`）全部加载失败。
             */
            override fun moduleNormalizeName(baseModuleName: String, moduleName: String): String =
                UriUtil.resolve(baseModuleName, moduleName)

            /**
             * 取模块源码并**预编译成字节码**（`BytecodeModuleLoader` 的约定：
             * 返回 null 表示"这个模块取不到"，QuickJS 会报 could not load module）。
             *
             * ⚠️ 语法错误**必须让它抛出去**（这里先记日志再 rethrow）：
             * 吞成 null 的话，JS 侧只看到"模块加载失败"，真正的
             * `SyntaxError: Unexpected token` 就永远看不到了 —— 而后者才是
             * 写源的人需要的信息。
             */
            override fun getModuleBytecode(moduleName: String): ByteArray? {
                val code = JsModule.get().fetch(moduleName)
                if (code == null) {
                    Log.w(TAG, "模块取不到：$moduleName")
                    return null
                }
                return try {
                    context.compileModule(code, moduleName)
                } catch (e: Throwable) {
                    Log.e(TAG, "模块编译失败：$moduleName", e)
                    throw e
                }
            }
        })
    }

    /**
     * 建 [Global]（宿主能力的注入点），并尝试加载**可选**的 jar 钩子
     * `com.github.catvod.js.Function`。
     *
     * ⚠️ [Global] 的创建**不能**放进 try 里和 dex 加载混在一起：
     * 参考实现把它写在 try 的第一行（碰巧也能工作），但如果哪天有人调换了顺序，
     * dex 为 null 就会让 `global` 永远不被赋值 —— 而 [destroy] 会因此漏掉
     * 所有定时器和线程，症状是"站点关闭后线程还在跑"。
     */
    private fun createFun() {
        global = Global.create(requireCtx(), executor)
        val loader = dex ?: return
        try {
            val clazz = loader.loadClass("com.github.catvod.js.Function")
            clazz.getDeclaredConstructor(QuickJSContext::class.java).newInstance(requireCtx())
        } catch (_: Throwable) {
            // 可选钩子：只有主 spider.jar 里才有这个类，没有是正常路径
        }
    }

    /**
     * 把源文件求值成 `globalThis.__JS_SPIDER__`。
     *
     * drpy 有两种源形态，`spider.js` 那段模板负责把它们统一：
     * ```
     * export function __jsEvalReturn() { … }   → 调它，拿返回的对象
     * export default { … }                     → 直接用（是函数就调一下）
     * ```
     *
     * ⚠️ `content.replace(spider, globalName)` 把源码里所有裸写的
     * `__JS_SPIDER__` 改成 `globalThis.__JS_SPIDER__`：**模块作用域里的
     * 顶层赋值不会落到全局对象上**，不改写的话 `globalThis.__JS_SPIDER__`
     * 永远是 undefined，然后每个方法调用都变成"方法不存在"（静默返回 null）。
     */
    private fun createObj() {
        val spider = SPIDER_KEY
        val globalName = "globalThis.$spider"
        val content = JsModule.get().fetch(api)
            ?: throw IllegalStateException("JS 源取不到：$api（http 地址不通？assets 没打进去？）")

        cat = content.contains("__jsEvalReturn")

        // 先把源码里的裸 __JS_SPIDER__ 改写成全局赋值，再求值
        requireCtx().evaluateModule(content.replace(spider, globalName), api)

        // 这段模板把上面两种导出形态统一挂到 globalThis.__JS_SPIDER__
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
     * 销毁。三步都要做，且**顺序不能换**：
     * ① 让 JS 侧自己收尾（`destroy` 是源的约定钩子）；
     * ② 释放 Global / JS 对象 / ctx（native 资源）；
     * ③ 关线程池。
     *
     * ⚠️ ①② 都必须吞异常：源里那个 `destroy` 抛错是常态（很多源根本不实现它，
     * 那会走"方法不存在 → null"的正常路径，但有的实现了却写错）。
     * 让它把 ③ 跳过去 = **线程池泄漏**，跑久了就是线程耗尽。
     */
    override fun destroy() {
        try {
            call("destroy")
        } catch (e: Throwable) {
            Log.w(TAG, "JS 源的 destroy 钩子失败：$e")
        }
        try {
            releaseJS()
        } catch (e: Throwable) {
            Log.w(TAG, "释放 JS 上下文失败：$e")
        } finally {
            executor.shutdownNow()
        }
    }

    // ── 素材源契约 ────────────────────────────────────────────────────
    //
    // 下面每个方法都是"转发给 JS 函数 + 把结果转回 String"。
    // ⚠️ 一律 `as? String ?: ""`：JS 侧没实现某个入口时 `Async` 会 complete(null)，
    // 而基类的默认返回就是空串 —— 空串会让上层解析出"没有数据"，
    // 而 null 会让调用方在别处 NPE（失败点远离真正的原因）。

    override fun homeContent(filter: Boolean): String = call("home", filter) as? String ?: ""

    override fun homeVideoContent(): String = call("homeVod") as? String ?: ""

    override fun categoryContent(
        tid: String,
        pg: String,
        filter: Boolean,
        extend: HashMap<String, String>,
    ): String {
        // extend 要先转成 JS 对象，**必须**在 ctx 线程上做
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

    /**
     * ⚠️ 源没实现 `sniffer` 时按 **false**（= 不做人工嗅探），不是抛异常。
     * 参考实现这里会 NPE（`(Boolean) null` 拆箱），本项目收到 false ——
     * 两者对真实源的效果相同（都没有 sniffer），但前者会把一次正常查询变成失败。
     */
    override fun manualVideoCheck(): Boolean = call("sniffer") as? Boolean ?: false

    override fun isVideoFormat(url: String?): Boolean = call("isVideo", url) as? Boolean ?: false

    override fun action(action: String?): String? = call("action", action) as? String

    // ── 本地代理回路 ──────────────────────────────────────────────────

    /**
     * 代理请求的入口。两条分支由 **`from` 参数**决定：
     * ```
     * from=catvod → proxy2  地址由 [Global.js2Proxy] 拼出来（JS 自己取流）
     * 其它        → proxy1  JS 返回数组 [code, mime, content, headers, base64]
     * ```
     *
     * ⚠️ 判据必须是 `from` 而不是 `do`：`do=js` 只说明"找 JS 引擎"，
     * 而 `from=catvod` 才说明"这条是 JS 自己用 `js2Proxy` 拼的"。
     * 分派顺序见 `CatVodProxyDispatcher`。
     */
    override fun proxy(params: Map<String, String>?): Array<Any>? {
        val map = params ?: return null
        return if ("catvod" == map["from"]) proxy2(map) else proxy1(map)
    }

    /**
     * JS 返回**数组**形态的代理结果：
     * ```
     * [0] code      [1] content-type
     * [2] content（字符串或 base64）  [3] 响应头对象（可选）
     * [4] 1 = content 是 base64（可选）
     * ```
     * 返回 `[code, mime, InputStream, headers]` 给 `LocalProxyServer`。
     */
    private fun proxy1(params: Map<String, String>): Array<Any>? {
        val obj = submit(Callable { JSUtil.toObject(requireCtx(), params) }).get()
        val proxy = call("proxy", obj) as? JSArray ?: return null

        // stringify 也是 ctx 操作，必须在同一个线程上
        val json = submit(Callable { proxy.stringify() }).get()
        val array = try {
            JSONArray(json)
        } catch (e: Exception) {
            Log.w(TAG, "JS proxy 返回的不是合法 JSON 数组：$json", e)
            return null
        }

        val headers = if (array.length() > 3) Json.toMap(array.optString(3)) else null
        val base64 = array.length() > 4 && array.optInt(4) == 1

        // ⚠️ 基类签名要求元素类型是 `Any`（非空），而 headers 可能是 null。
        // 所以先按 `Array<Any?>` 装填，最后做一次**零成本**的类型放宽
        // （擦除后都是 `Object[]`，不是数据转换）。
        val result = arrayOfNulls<Any>(4)
        result[0] = array.optInt(0)
        result[1] = array.optString(1)
        result[2] = getStream(array.opt(2), base64)
        result[3] = headers
        @Suppress("UNCHECKED_CAST")
        return result as Array<Any>
    }

    /**
     * JS 返回 **JSON 文本**形态的代理结果（被 `js2Proxy` 拼出来的那些地址）。
     *
     * ⚠️ `url` 在 [Global.js2Proxy] 里被 URL 编码过，到这儿已经被
     * NanoHTTPD 解回原文，所以直接 `split("/")` 就能还原成路径段数组
     * —— 这是 JS 侧 `proxy(array, header)` 的入参约定。
     */
    private fun proxy2(params: Map<String, String>): Array<Any>? {
        val url = params["url"].orEmpty()
        val header = params["header"]

        val array = submit(Callable {
            JSUtil.toArray(requireCtx(), url.split("/"))
        }).get()

        // header 是可选的（JS 侧可以不带头）；空串或 null 时传 undefined 给 JS
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

    /**
     * JS 给的响应体 → 字节流。
     *
     * `base64 = true` 时 content 可能是 `data:image/png;base64,xxxx` 这种
     * **data URI**，所以先按 `base64,` 切一刀再解 —— 直接解整串会抛
     * `IllegalArgumentException`（`data:image…` 不是合法 base64）。
     */
    private fun getStream(o: Any?, base64: Boolean): ByteArrayInputStream {
        if (o is ByteArray) return ByteArrayInputStream(o)
        var content = o?.toString().orEmpty()
        if (base64 && content.contains("base64,")) {
            content = content.split("base64,").getOrNull(1) ?: content
        }
        return ByteArrayInputStream(
            if (base64) Util.decode(content) else content.toByteArray(),
        )
    }

    // ── ext 的两种形态 ────────────────────────────────────────────────

    /**
     * `ext` 交给 JS `init(ext)` 的形态，**取决于源的写法**：
     * ```
     * drpy（有 __jsEvalReturn） → {stype: 3, skey: <站点key>, ext: <ext>}
     * 其它                     → ext 本身（是 JSON 对象就解析成对象，否则原样字符串）
     * ```
     * ⚠️ `skey` 必须是**站点 key**：一个 `.js` 文件被配成多个站点是常态，
     * JS 侧靠它区分自己这次该用哪套参数。这跟 jar 那边 `siteKey` 的用途一样。
     *
     * ⚠️ 这个方法只能在 **ctx 线程**上跑（它调 `ctx.parse` / `createNewJSObject`），
     * 所以调用方都包在 `submit { }` 里。
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
        const val TAG = "QuickJS"

        /** 源里的全局对象名，见 [createObj]。 */
        const val SPIDER_KEY = "__JS_SPIDER__"

        const val HTTP_JS = "js/lib/http.js"
        const val SPIDER_JS = "js/lib/spider.js"
    }
}
