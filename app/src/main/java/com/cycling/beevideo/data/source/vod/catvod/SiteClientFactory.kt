package com.cycling.beevideo.data.source.vod.catvod

import android.content.Context
import android.util.Log
import com.cycling.beevideo.data.proxy.LocalProxyServer
import com.cycling.beevideo.data.source.vod.js.JsLoader
import com.github.catvod.crawler.SpiderApi
import dalvik.system.DexClassLoader
import java.util.concurrent.ConcurrentHashMap

/** [SiteClientFactory] 的接口面：仓储只要「取一个客户端」和「全部作废」。 */
interface SiteClients {

    suspend fun client(siteKey: String, config: CatVodConfig): SiteClient

    fun clear()
}

/** 按站点类型造 [SiteClient] 并按 key 缓存。缓存是必须的：jar 的 `init` 可能有网络请求。 */
class SiteClientFactory(context: Context) : SiteClients {

    private val appContext = context.applicationContext
    private val cache = ConcurrentHashMap<String, SiteClient>()

    // 代理服务懒启动；起来之后不能停（jar 已经把自指地址交给过播放器）
    //
    // ⚠️ spiderOf / jsProxy 都必须写成**具名实参**：派发器还有一个
    // `staticProxies: () -> List<Method>` 参数，写成尾随 lambda 会绑到它上面
    private val proxyHandler = CatVodProxyDispatcher(
        spiderOf = { key -> (cache[key] as? SpiderSiteClient)?.spider },
        jsProxy = { params -> proxyOfRecentJs(params) },
    )

    /**
     * `do=js` 且不带 siteKey 的裸地址（`Global.getProxy()` 拼的）交给"最近用过的 JS 源"。
     *
     * ⚠️ 找不到就返回 `null`（502），**不要**退回随便挑一个：JS 源的 proxy 靠各自的站点
     * 上下文取流，挑错了会拿到另一个站点的内容，而播放器看不出来。
     */
    @Suppress("UNCHECKED_CAST")
    private fun proxyOfRecentJs(params: Map<String, String>): Array<Any?>? {
        val key = jsLoader.currentRecent() ?: return null
        val client = cache[key] as? SpiderSiteClient ?: return null
        return client.spider.proxy(params) as Array<Any?>?
    }

    private val jsLoader = JsLoader()

    private val spiderApi: SpiderApi = ServerSpiderApi()

    @Volatile
    private var proxyUp = false

    // initSpider 是宿主上下文与可测性之间的缝：SpiderHost 故意不认识 Context
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
     * `type=3/4` —— 需要运行引擎的站点。判据与顺序照搬参照实现（FongMi `BaseLoader.getSpider`）：
     * `.py` → Python 引擎、`.js` → JS 引擎、`csp_` → DexClassLoader、其它 → 抛错。
     *
     * ⚠️ 没有"静默空实现"这一档：api 认不出来就报错，否则只是把问题推迟到运行期。
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
        // ⚠️ 必须在 jar 拿到端口之前起来：晚一步 jar 读到 -1，播放页报 invalid port: -1，
        // 而那时地址已经在播放器手里了，再启动也没用
        if (!proxyUp) proxyUp = LocalProxyServer.ensureStarted(proxyHandler)

        // 站点没带 jar 就用顶层 spider 那个：绝大多数配置是一个总 jar 装几十个 csp_* 类
        val spec = site.jar.ifEmpty { config.spider }
        if (spec.isEmpty()) {
            throw CatVodException("站点「${site.name}」需要 jar，但配置里既没有站点 jar 也没有顶层 spider")
        }
        val (jarUrl, md5) = parseJarSpec(spec)
        val jarFile = DexJarLoader.ensureJar(appContext, jarUrl, md5)

        // api 写错（写成 URL / 站点名）时拼出的"类名"会带 / 或 :，DexClassLoader 只会抛
        // 一长串 ClassNotFoundException，看不出问题在 api 字段上
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

        // 多数 /proxy 请求不带 siteKey，宿主只能挨个试，而几乎总是刚在用的那个
        DexJarLoader.markRecent(jarFile)

        // ⚠️ siteKey 必须在 init 之前赋值：同一个类被配成十几个站点是常态，
        // init 里已经用到了站点相关状态，放到后面就晚了（顺序钉在 SpiderHost 里）
        return spiderHost.host(site, spider, config.flags, logTag = "CatVodJar")
    }

    /**
     * `.js` 爬虫（drpy 系），走 `JsSpider`。与 [createJarClient] 只差"造 spider"那一步，
     * 之后 siteKey → init → initApi 三步一字不差。
     */
    private suspend fun createJsClient(site: SiteConfig, config: CatVodConfig): SiteClient {
        if (!proxyUp) proxyUp = LocalProxyServer.ensureStarted(proxyHandler)

        // 可选：把配置里的 jar 交给 JS 引擎，唯一用途是 com.github.catvod.js.Function 这个
        // 额外钩子。没有 jar 是正常路径，下载/加载失败则吞掉 —— 为可选钩子让站点建不起来更差。
        val dex = runCatching { optionalJarLoader(site, config) }
            .onFailure { Log.w("CatVodJs", "站点「${site.name}」的可选 jar 钩子不可用：$it") }
            .getOrNull()

        val spider = jsLoader.newSpider(site.api, dex)

        val client = spiderHost.host(site, spider, config.flags, logTag = "CatVodJs")

        // 不带 siteKey 的裸 ?do=js 代理请求只能靠这个标记派发
        jsLoader.markRecent(site.key)

        return client
    }

    /** 取配置里那个 jar 的 ClassLoader。返回 `null` 是正常路径（配置里没有 jar）。 */
    private suspend fun optionalJarLoader(site: SiteConfig, config: CatVodConfig): DexClassLoader? {
        val spec = site.jar.ifEmpty { config.spider }
        if (spec.isEmpty()) return null
        val (jarUrl, md5) = parseJarSpec(spec)
        val jarFile = DexJarLoader.ensureJar(appContext, jarUrl, md5)
        return DexJarLoader.loader(appContext, jarFile)
    }

    // ⚠️ `ext` **不下载、原样交给爬虫**（2026-09-16 更正）。参考宿主的 `Site.fetchExt()`
    // 只在 type==4 时调用；type=3（jar / .js / .py）拿到的永远是配置里的原文。
    // 实测 type=3 有站点的 ext 是站点根地址或规则脚本地址，下载内容两类都错。
    // 相对路径由 CatVodConfigDecoder 在解析之前统一改成绝对地址。

    override fun clear() {
        cache.values.forEach { it.close() }
        cache.clear()
        DexJarLoader.clear()
        // 进程级状态也要清，否则换配置后裸 ?do=js 会去找上一份配置里的站点
        jsLoader.clear()
    }
}
