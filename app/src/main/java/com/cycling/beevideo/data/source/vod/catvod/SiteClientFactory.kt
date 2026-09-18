package com.cycling.beevideo.data.source.vod.catvod

import android.content.Context
import android.util.Log
import com.cycling.beevideo.data.proxy.LocalProxyServer
import com.cycling.beevideo.data.source.vod.js.JsLoader
import com.github.catvod.crawler.SpiderApi
import dalvik.system.DexClassLoader
import java.util.concurrent.ConcurrentHashMap

/**
 * [SiteClientFactory] 的接口面。
 *
 * 与 `SourceStore` / `ConfigCache` 同一个理由：装载与清空逻辑要能在纯 JVM
 * 单测里跑，而这个工厂的构造要 `Context`（它还要起本地代理、建 QuickJS 上下文）。
 * 仓储真正用到的只有两件事 —— 取一个站点客户端、把缓存的客户端全部作废。
 */
interface SiteClients {

    suspend fun client(siteKey: String, config: CatVodConfig): SiteClient

    /** 换配置 / 改了设置时调用。 */
    fun clear()
}

/**
 * 按站点类型造出对应的 [SiteClient]，并按站点 key 缓存。
 *
 * 缓存是必须的：jar 源的 `init` 可能有网络请求、ClassLoader 建立一次也要几十毫秒，
 * 每次翻页都重建一遍的话列表会卡得没法用。
 */
class SiteClientFactory(context: Context) : SiteClients {

    private val appContext = context.applicationContext
    private val cache = ConcurrentHashMap<String, SiteClient>()

    /*
     * 本地代理服务的派发口 —— 服务本身**懒启动**：只有真的要用到 jar 站点时才起。
     *
     * 拿到端口之后不能停：jar 已经把 `http://127.0.0.1:<port>/proxy?…` 这样的地址
     * 交给过播放器，播放器随时会回来取。参考宿主也是常驻的。
     *
     * ⚠️ `spiderOf` 必须写成**具名实参**。派发器还有第二个参数
     * `staticProxies: () -> List<Method>`，写成尾随 lambda 的话那个 lambda 会
     * 绑到 `staticProxies` 上 —— 报错是 "Return type mismatch: expected
     * List<Method>, actual Spider?"，看着像类型系统在闹脾气，其实是位置错位。
     */
    private val proxyHandler = CatVodProxyDispatcher(
        spiderOf = { key -> (cache[key] as? SpiderSiteClient)?.spider },
        /*
         * `do=js` 且不带 siteKey 的请求（`Global.getProxy()` 拼出的裸地址）
         * 交给"最近用过的那个 JS 源"。
         *
         * ⚠️ 必须写成**具名实参**：派发器第三个参数是 `staticProxies`，
         * 把 lambda 写成尾随的话会绑到它上面（同下面 `spiderOf` 的坑）。
         *
         * 拿到的 `spider` 与 jar 源是同一个类型（`JsSpider` 继承
         * `com.github.catvod.crawler.Spider`），所以这里不需要认识 JS 包的任何类型
         * —— 这也正是 JS 引擎能"零成本"接进现有派发链的原因。
         */
        jsProxy = { params -> proxyOfRecentJs(params) },
    )

    /**
     * 把请求交给"最近用过的 JS 源"。
     *
     * 找不到就返回 `null`（= 没人接手 → `LocalProxyServer` 回 502）。
     * **不要**退回"随便挑一个 JS 源"：JS 源的 `proxy` 是靠各自的站点上下文
     * 取流的，挑错了会拿到**另一个站点的内容**，而播放器完全看不出来。
     */
    @Suppress("UNCHECKED_CAST")
    private fun proxyOfRecentJs(params: Map<String, String>): Array<Any?>? {
        val key = jsLoader.currentRecent() ?: return null
        val client = cache[key] as? SpiderSiteClient ?: return null
        return client.spider.proxy(params) as Array<Any?>?
    }

    /** JS 引擎（原生库初始化 + "最近用过的源"）。一个工厂一份，与 [cache] 同生命周期。 */
    private val jsLoader = JsLoader()

    /** 传给爬虫的能力对象。本地代理起来了就是真实现，没起来才退回空实现。 */
    private val spiderApi: SpiderApi = ServerSpiderApi()

    @Volatile
    private var proxyUp = false

    /**
     * 把造好的 spider 装成可用的站点。
     *
     * `initSpider` 这支 lambda 是**宿主上下文与可测性之间的那条缝**：
     * `spider.init` 要 `Context`，而 [SpiderHost] 故意不认识 `Context`，
     * 于是「`siteKey` 必须在 `init` 之前」这条顺序契约能在纯 JVM 单测里钉住。
     */
    private val spiderHost = SpiderHost(
        spiderApi = spiderApi,
        proxyReady = { proxyUp },
        initSpider = { spider, ext -> spider.init(appContext, ext) },
    )

    override suspend fun client(siteKey: String, config: CatVodConfig): SiteClient {
        val site = config.sites.firstOrNull { it.key == siteKey }
            ?: throw CatVodException("配置里找不到站点 $siteKey")
        cache[siteKey]?.let { return it }
        val created = create(site, config)
        cache[siteKey] = created
        return created
    }

    private suspend fun create(site: SiteConfig, config: CatVodConfig): SiteClient =
        when (site.type) {
            SiteType.JSON -> JsonSiteClient(site)
            SiteType.XML -> XmlSiteClient(site)
            SiteType.SPIDER, SiteType.API -> createDynamicClient(site, config)
        }

    /**
     * `type=3/4` —— 需要"运行引擎"的站点。
     *
     * `api` 字段决定用哪个引擎，**判据与顺序都照搬参照实现**
     * （FongMi `BaseLoader.getSpider`）：
     * ```
     * .py  → Python 引擎        .js  → JS 引擎
     * csp_ → DexClassLoader     其它 → 空实现
     * ```
     * 顺序有讲究：`.py` 排在 `.js` 前面。两种后缀同时出现（`xxx.js.py`）
     * 在实践中不存在，但顺序与参照实现不一致的话，将来加 Python 支持时
     * 会先在分派这里产生一个说不清的行为差异。
     */
    private suspend fun createDynamicClient(site: SiteConfig, config: CatVodConfig): SiteClient {
        val api = site.api
        when {
            api.contains(".py") -> throw CatVodException(
                "站点「${site.name}」是 Python 爬虫（api = $api），" +
                    "需要 Chaquopy 提供 Python 运行时，当前版本不支持。",
            )
            api.contains(".js") -> return createJsClient(site, config)
            !api.startsWith("csp_") -> throw CatVodException(
                "站点「${site.name}」的 api 无法识别：$api（既不是 csp_ 类名，也不是 .js / .py 爬虫）。",
            )
        }
        return createJarClient(site, config)
    }

    private suspend fun createJarClient(site: SiteConfig, config: CatVodConfig): SiteClient {
        /*
         * 代理服务必须在 jar **有机会拿到端口之前**起来。
         *
         * jar 会把 `http://127.0.0.1:<port>/proxy?…` 这种自指地址直接交给播放器，
         * 端口在那一刻就被定死了。晚一步起来的话，jar 读到的是初始值 -1，
         * 播放页报 `MalformedURLException: invalid port: -1` —— 而那时候
         * 再启动服务已经没用，地址已经在播放器手里了。
         */
        if (!proxyUp) proxyUp = LocalProxyServer.ensureStarted(proxyHandler)

        // 站点自己没带 jar 时用顶层 spider 指的那个 —— 这是绝大多数配置的形态：
        // 一个总 jar 里装了几十个 csp_* 类，sites 只写 api: "csp_Xxx"
        val spec = site.jar.ifEmpty { config.spider }
        if (spec.isEmpty()) {
            throw CatVodException("站点「${site.name}」需要 jar，但配置里既没有站点 jar 也没有顶层 spider")
        }
        val (jarUrl, md5) = parseJarSpec(spec)
        val jarFile = DexJarLoader.ensureJar(appContext, jarUrl, md5)

        /*
         * 类名兜底校验。
         *
         * `api` 写错（写成 URL、写成站点名）时，拼出来的"类名"里会带 `/` 或 `:`，
         * 而 `DexClassLoader` 只会抛 `ClassNotFoundException` ——
         * 报错是一长串「找不到类 com.github.catvod.spider.http://…」，
         * 完全看不出问题出在 `api` 字段上。这里先拦一道，把话说清楚。
         */
        val className = "com.github.catvod.spider.${site.spiderClassName}"
        if (className.any { it == '/' || it == ':' }) {
            throw CatVodException(
                "站点「${site.name}」的 api 字段不是类名：${site.api}。" +
                    "spider 源的 api 应写成 csp_ 加 jar 里的类名。",
            )
        }

        val spider = DexJarLoader.newSpider(
            context = appContext,
            jarFile = jarFile,
            className = className,
        )

        // 标记"最近用过的 jar"：多数 `/proxy` 请求不带 siteKey，宿主只能挨个试，
        // 而这个几乎总是刚在用的那个（参考实现 JarLoader.recent 的用途）。
        DexJarLoader.markRecent(jarFile)

        /*
         * ⚠️ `siteKey` 必须在 `init` **之前**赋值。
         *
         * 原版 `JarLoader.getSpider` 的顺序就是：
         * ```java
         * Spider spider = (Spider) loader.loadClass(…).newInstance();
         * spider.siteKey = key;
         * spider.init(App.get(), ext);
         * ```
         * 顺序有意义：一个 jar 里的同一个类被配置成十几个站点是常态
         * （`csp_AppYs` 配了"南府追剧""HG影视""瑞丰资源"…），
         * 爬虫靠 `siteKey` 区分自己这次该用哪套 ext / header。
         * 放到 init 之后就晚了 —— init 里已经把站点相关的状态定下来了。
         */
        /*
         * 装配（siteKey → init → initApi）交给 [SpiderHost]：那三步的顺序是**承重的**，
         * 而这条链以前在 jar 与 JS 两边各写了一遍。理由见它的说明。
         */
        return spiderHost.host(site, spider, config.flags, logTag = "CatVodJar")
    }

    /**
     * `.js` 爬虫（drpy 系）的站点 —— 走 [com.cycling.beevideo.data.source.vod.js.JsSpider]。
     *
     * 与 [createJarClient] 的流程**几乎一样**，差别只在"造 spider"那一步：
     * jar 是 `DexClassLoader` + 反射 new，JS 是建 QuickJS 上下文 + 求值源码。
     * 之后的 `siteKey` → `init` → `initApi` 三步一字不差，理由也完全相同。
     *
     * 返回的 [SpiderSiteClient] 对 jar 与 JS 是**同一个类** —— 它服务的是任何
     * `com.github.catvod.crawler.Spider`，JS 引擎能"零成本"接进来靠的就是这一点。
     */
    private suspend fun createJsClient(site: SiteConfig, config: CatVodConfig): SiteClient {
        // 与 jar 同一条理由：JS 的 `getProxy()` 同样会把端口写进交给播放器的地址，
        // 晚一步起来就是 `127.0.0.1:-1`
        if (!proxyUp) proxyUp = LocalProxyServer.ensureStarted(proxyHandler)

        /*
         * 可选：把配置里的 jar 交给 JS 引擎。
         *
         * 它唯一的用途是 `com.github.catvod.js.Function` —— 一个**额外**的宿主
         * 钩子，由主 spider.jar 提供（见 `JsSpider.createFun`）。绝大多数 drpy 源
         * 用不到它，所以：
         *   - 配置里没有 jar → 传 null，完全正常；
         *   - 有 jar 但下载 / 加载失败 → **吞掉**，只记一行日志。
         * 为了一个可选钩子让整个站点建不起来，是明显更差的失败模式。
         *
         * ⚠️ 代价说清楚：配置里有顶层 `spider` 时，**每个 JS 站点都会触发一次
         * jar 下载**（有本地缓存则只查一次文件）。这是参照实现的行为 ——
         * 它同样无条件 `BaseLoader.get().dex(jar)`。
         */
        val dex = runCatching { optionalJarLoader(site, config) }
            .onFailure { Log.w("CatVodJs", "站点「${site.name}」的可选 jar 钩子不可用：$it") }
            .getOrNull()

        val spider = jsLoader.newSpider(site.api, dex)

        val client = spiderHost.host(site, spider, config.flags, logTag = "CatVodJs")

        // 标记"最近用过的 JS 源"：不带 siteKey 的裸 `?do=js` 代理请求只能靠它派发
        jsLoader.markRecent(site.key)

        return client
    }

    /**
     * 取配置里那个 jar 的 ClassLoader，供 JS 引擎的可选钩子用。
     *
     * 返回 `null` 是**正常路径**（配置里根本没有 jar），不是错误。
     */
    private suspend fun optionalJarLoader(site: SiteConfig, config: CatVodConfig): DexClassLoader? {
        val spec = site.jar.ifEmpty { config.spider }
        if (spec.isEmpty()) return null
        val (jarUrl, md5) = parseJarSpec(spec)
        val jarFile = DexJarLoader.ensureJar(appContext, jarUrl, md5)
        return DexJarLoader.loader(appContext, jarFile)
    }

    /*
     * ─── ⚠️ 关于 `ext`：**不下载、原样交给爬虫**（2026-09-16 更正）─────────
     *
     * 这里原来有一个 `fetchExtIfUrl`：`ext` 以 `http` 开头时宿主先 GET 下来、
     * 把**内容**当 ext。它引的是参考宿主的 `Site.fetchExt()`，但**引错了范围**——
     * 参考实现里 `fetchExt()` **只在一处被调用**：
     * ```java
     * // SiteApi.homeContent —— 注意分支
     * if (isSpider(site)) { … }                    // type==3，**不** fetchExt
     * else if (site.getType() == 4) { call(site.fetchExt(), params); }   // 只有 type==4
     * ```
     * `isSpider(site)` 就是 `type == 3`。也就是说 **type=3（jar / .js / .py 爬虫）
     * 拿到的 `ext` 永远是配置里那一串原文**，只有 type=4 才下载。
     * 本项目的参考分析文档 §2.3 记的也是这个（"3 → 交给 `Spider.init(Context, String)`"）。
     *
     * 这个差别**有实际后果**，不是洁癖。实测用户那份配置里 type=3 有 9 个站点的
     * `ext` 以 `http` 开头，分两类，**下载内容两类都错**：
     * ```
     * csp_ZxzjGuard   ext=https://www.zxzjhd.com/          ← 站点**根地址**
     * (drpy2)         ext=…/jrk.js                         ← 规则脚本的**地址**
     * ```
     * 前者爬虫要的是那个 URL（它自己拼 XPath、自己带 header 去请求），
     * 给它一坨首页 HTML 等于把 ext 弄坏；后者 drpy 拿它去 `import`/请求，
     * 给一段源码文本同样接不住。
     *
     * 相对路径（`./js/xxx.js`）不在这里管 —— 那由
     * [CatVodConfigDecoder] 在**解析之前**统一改成绝对地址。
     * 两者是配套的：先把相对路径变成绝对的，再原样交给爬虫。
     */

    /** 换配置 / 改了设置时调用。 */
    override fun clear() {
        // 走 seam 上的 close()，不再向下转型成具体实现 —— 见 SiteClient.close 的说明
        cache.values.forEach { it.close() }
        cache.clear()
        DexJarLoader.clear()
        // JS 侧还有两块进程级状态：模块源码缓存、以及"最近用过的源"标记。
        // 不清的话，换配置后裸 `?do=js` 的请求会去找**上一份配置**里的站点。
        jsLoader.clear()
    }
}
