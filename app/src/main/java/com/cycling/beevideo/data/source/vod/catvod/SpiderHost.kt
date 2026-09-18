package com.cycling.beevideo.data.source.vod.catvod

import android.util.Log
import com.github.catvod.crawler.Spider
import com.github.catvod.crawler.SpiderApi

/**
 * 把一个**已经造好的** [Spider] 装成可用的站点客户端。
 *
 * ─── 它为什么存在 ──────────────────────────────────────────────────────
 * jar 与 `.js` 两条装配链以前各写了一遍「赋 `siteKey` → `init` → `initApi`」，
 * 而这三步的**顺序是承重的**（见下）。同一份顺序契约留两个副本，就是其中一份
 * 将来静默漂移的方式 —— 而漂移的表现是「站点能加载、结果永远为空、不报任何错」。
 *
 * 现在顺序只写在这里；两条链的差别缩成"怎么造 spider"一件事：
 * jar 是 `DexClassLoader` + 反射 new，JS 是建 QuickJS 上下文 + 求值源码。
 *
 * ─── 为什么 `init` 是一支函数而不是直接调 ────────────────────────────────
 * `spider.init(context, ext)` 要 `Context`，而本类**故意不认识 `Context`** ——
 * 于是这套顺序契约能在纯 JVM 单测里钉住（见 `SpiderHostTest`：假 spider 记录
 * 调用顺序，断言 `siteKey` 在 `init` 之前）。宿主上下文由工厂关进 lambda 里。
 *
 * 这是本项目第二次用这个手法，与 `VodContentRepository.fetchTextWithUrl` 同一个理由：
 * **把不可测的边界收进宿主，把可测的编排留给单测。**
 */
internal class SpiderHost(
    /** 交给爬虫的宿主能力对象（本地代理 / 服务端解析）。 */
    private val spiderApi: SpiderApi,
    /** 本地代理是否已在监听。没起来时给 [SpiderApi.noop]，见 [host] 里的说明。 */
    private val proxyReady: () -> Boolean,
    /**
     * `spider.init(context, ext)`。
     *
     * ⚠️ 必须是**两参版本** —— 真实 jar 覆写的就是这一个（见 `Spider.init`）。
     * 只调一参版的话，真实爬虫的覆写会变成没人调用的普通方法：
     * 站点照常加载、结果永远为空、且不报任何错。
     */
    private val initSpider: (spider: Spider, ext: String) -> Unit,
) {

    /**
     * @param logTag `initApi` 失败时用的日志 tag。jar 与 JS 各用各的
     *   （`CatVodJar` / `CatVodJs`），保持既有习惯 —— 排查时按来源分开看有用。
     */
    fun host(site: SiteConfig, spider: Spider, flags: List<String>, logTag: String): SiteClient {
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

        runCatching { initSpider(spider, site.ext) }
            .onFailure { throw CatVodException("站点「${site.name}」初始化失败：${it.message}", it) }

        /*
         * 可选钩子：把宿主的能力对象交给爬虫。
         *
         * 传的是**真实现** —— 它的 `getAddress`/`getPort` 指向真的在监听的本地代理服务。
         * 只有代理**没起来**（端口全被占）时才退回空实现：那时给一个空地址，
         * 至少失败点在同一处、行为可预期；给 null 的话，失败点在若干秒后的某次查询里，
         * 表现成 `NullPointerException`，完全指不到这里。
         *
         * 失败不阻断站点：这是个可选能力，爬虫不认它也不该影响加载。
         */
        runCatching { spider.initApi(if (proxyReady()) spiderApi else SpiderApi.noop) }
            .onFailure { Log.w(logTag, "站点「${site.name}」initApi 失败：$it") }

        return SpiderSiteClient(site, spider, flags)
    }
}
