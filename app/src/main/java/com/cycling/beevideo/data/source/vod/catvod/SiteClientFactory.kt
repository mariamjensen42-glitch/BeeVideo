package com.cycling.beevideo.data.source.vod.catvod

import android.content.Context
import android.util.Log
import com.github.catvod.crawler.SpiderApi
import java.util.concurrent.ConcurrentHashMap

/**
 * 按站点类型造出对应的 [SiteClient]，并按站点 key 缓存。
 *
 * 缓存是必须的：jar 源的 `init` 可能有网络请求、ClassLoader 建立一次也要几十毫秒，
 * 每次翻页都重建一遍的话列表会卡得没法用。
 */
class SiteClientFactory(context: Context) {

    private val appContext = context.applicationContext
    private val cache = ConcurrentHashMap<String, SiteClient>()

    suspend fun client(siteKey: String, config: CatVodConfig): SiteClient {
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
            SiteType.SPIDER, SiteType.API -> createJarClient(site, config)
        }

    private suspend fun createJarClient(site: SiteConfig, config: CatVodConfig): SiteClient {
        rejectUnsupportedEngine(site)

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
        spider.siteKey = site.key

        // init 必须在任何一次查询之前跑完，而且多数 spider 就是在 init 里解析 ext 的；
        // 某些实现把它写成异步预热的，那就让它自己飞，不在这里等。
        //
        // ⚠️ 调的是**两参版本** —— 真实 jar 覆写的就是这一个（见 `Spider.init`）。
        // 只调一参版的话，真实爬虫的覆写会变成没人调用的普通方法：站点照常加载、
        // 结果永远为空、且不报任何错。
        runCatching { spider.init(appContext, fetchExtIfUrl(site)) }
            .onFailure { throw CatVodException("站点「${site.name}」初始化失败：${it.message}", it) }

        /*
         * 可选钩子：把宿主的能力对象（本地代理 / 服务端解析）交给爬虫。
         *
         * 原版更新的 catvod 会在这里传一个真实现；我们**没有**本地代理服务，
         * 所以传的是空实现 —— 见 `SpiderApi`。坚持传一个非 null 对象、
         * 而不是干脆不调，是为了让那些把参数存进字段、过一会儿才用的爬虫
         * 不至于在若干秒后的某次查询里抛 NPE（那个失败点完全指不到这里）。
         *
         * 失败不阻断站点：这是个可选能力，爬虫不认它也不该影响加载。
         */
        runCatching { spider.initApi(SpiderApi.noop) }
            .onFailure { Log.w("CatVodJar", "站点「${site.name}」initApi 失败：$it") }

        return JarSiteClient(site, spider, config.flags)
    }

    /**
     * 按 `api` 判断这个站点需要哪种运行引擎，不支持的当场拒绝。
     *
     * 真实配置里 `type: 3` 的站点，`api` 并不都是 `csp_Xxx`：还有大量 `.js`
     * （drpy 系的 JS 爬虫）和少量 `.py`。参照实现（FongMi `BaseLoader.getSpider`）
     * 就是按前缀分派的：
     *
     * ```
     * .py  → Python 引擎        .js  → JS 引擎
     * csp_ → DexClassLoader     其它 → 空实现（静默返回空结果）
     * ```
     *
     * 本项目只有 DexClassLoader 这一条路，所以另外两类必须**在这里**明确拒绝。
     *
     * 为什么不能放着不管：这些 `.js` 站点的 api 写得完全正确，让它们一路走到
     * 类名兜底校验那里，用户看到的是「api 字段不是类名」—— 报错指向一个根本没写错
     * 的字段，比直接说"不支持"糟得多。**误导性报错比不支持更难排查。**
     */
    private fun rejectUnsupportedEngine(site: SiteConfig) {
        val api = site.api
        // 顺序与参照实现一致（先 py 再 js）
        when {
            api.contains(".py") -> throw CatVodException(
                "站点「${site.name}」是 Python 爬虫（api = $api），需要 Python 运行时，当前版本不支持。",
            )
            api.contains(".js") -> throw CatVodException(
                "站点「${site.name}」是 JS 爬虫（api = $api），需要 JS 引擎，当前版本不支持。",
            )
            !api.startsWith("csp_") -> throw CatVodException(
                "站点「${site.name}」的 api 无法识别：$api（既不是 csp_ 类名，也不是 .js / .py 爬虫）。",
            )
        }
    }

    /**
     * `ext` 以 `http` 开头时，**由宿主先下载下来，把内容当 ext** 交给爬虫。
     *
     * 这是参照实现的行为（FongMi `Site.fetchExt()`：`OkHttp.string(getExt())`
     * 之后 `setExt(内容)`）。不这么做的话，只能指望爬虫自己去下载 ——
     * 而真实爬虫分两派：catvod 的 `XPath` 会自己判断 `startsWith("http")` 再下，
     * 有些则把 ext 直接当 JSON 解析。同一份配置在两种爬虫上表现不一致，
     * 且失败时报的是"JSON 解析失败"，指向不到 ext 其实是个地址。
     *
     * 下载失败就**退回原始 URL**，让"自己去下"的那类爬虫还有机会，不把路堵死。
     *
     * 相对路径（`./json/xxx.json`）在这里**不处理**：参照实现的 `UrlUtil.convert()`
     * 也只重写 `assets:`/`proxy:`/`file:` 三种 scheme，不做相对路径绝对化。
     * 那就不是我该替爬虫决定的事。
     */
    private suspend fun fetchExtIfUrl(site: SiteConfig): String {
        val ext = site.ext
        if (!ext.startsWith("http")) return ext
        return runCatching { CatVodHttp.getText(ext) }.getOrDefault(ext)
    }

    /** 换配置 / 改了设置时调用。 */
    fun clear() {
        cache.values.forEach { (it as? JarSiteClient)?.destroy() }
        cache.clear()
        DexJarLoader.clear()
    }
}
