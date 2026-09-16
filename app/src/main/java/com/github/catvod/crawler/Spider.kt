package com.github.catvod.crawler

import android.content.Context
import com.github.catvod.net.OkHttp
import okhttp3.Dns
import okhttp3.OkHttpClient

/**
 * CatVod spider 基类 —— **本项目自带的兼容层**，逐条对齐原版
 * `FongMi/TV` 的 `catvod/src/main/java/com/github/catvod/crawler/Spider.java`。
 *
 * ─── ⚠️ 这个文件为什么必须放在 `com.github.catvod.crawler` 包下 ──────────
 * 用户配置里 `type=3` 的站点，实现都在一个 jar 里，类名形如
 * `com.github.catvod.spider.` + `api` 字段去掉 `csp_` 前缀。
 * 这些类**无一例外**都写成：
 * ```java
 * public class MySite extends com.github.catvod.crawler.Spider { … }
 * ```
 * 也就是说，jar 只带「子类」，父类指望从宿主 App 里找。我们用 `DexClassLoader`
 * 加载 jar 时把 App 自己的 ClassLoader 设为 parent，子类解析父类引用时会委托上来，
 * 于是找到了这个文件。
 *
 * **包名和类名一个字都不能改**，改了就是 `NoClassDefFoundError`。
 *
 * ─── 签名不是"我觉得该这样"，是 ABI ─────────────────────────────────
 * jar 是**预编译**的，它对基类的引用写死在 DEX 的 `method_ids` / `field_ids` 表里。
 * 少一个成员、或者成员是实例方法而 jar 按静态调 → 运行时 `NoSuchMethodError` /
 * `NoSuchFieldError`，**编译期一点提示都没有**。表现是"站点能加载、一取数据就炸"。
 *
 * 本文件的成员是用 `.workbuddy/scripts/dex_probe.py` 反查真实 jar 的 DEX
 * 逐条核对过的。实测 7 个真实 jar（`spider.jar` / `XBPQ` / `pg` / `XYQ` /
 * `custom_spider` / `fty` …）对宿主的引用只有下面这些，全部覆盖：
 *
 * ```
 * Spider.<init>()V                             ✓ 隐式
 * Spider.init(Context)V                        ✓
 * Spider.init(Context,String)V                 ✓
 * Spider.client()Lokhttp3/OkHttpClient;        ✓ 静态！（曾被写成实例方法）
 * Spider.safeDns()Lokhttp3/Dns;                ✓ 静态
 * Spider.homeVideoContent()String              ✓
 * Spider.categoryContent(String,String,Z,HashMap)String  ✓
 * Spider.destroy()V                            ✓
 * Spider.initApi(SpiderApi)V                   ✓
 * SpiderDebug.log(String) / log(Throwable)     ✓ 见 SpiderDebug.kt
 * ```
 *
 * ─── 这个类里不要"用" Android API ─────────────────────────────────────
 * 它会被 jar 里的代码在**任意线程**上调用，且必须在 `DexClassLoader` 的委托链上
 * 保持"轻"。任何 Android API **调用**都可能让类初始化在这一层失败。
 * 唯一的例外是 `android.content.Context` —— 它是 `init` 契约的一部分，
 * 只作为**参数类型**出现，不调用它任何方法。
 */
open class Spider {

    /**
     * ⚠️ **必须是真的公开字段**，所以用 `@JvmField`。
     *
     * 原版是 `public String siteKey;`，由宿主在调 `init` **之前**赋值
     * （见原版 `JarLoader.getSpider`：`spider.siteKey = key; spider.init(…)`）。
     * 不少 spider 会读它来区分"同一个类被配置成了多个站点"。
     *
     * 普通 Kotlin `var` 会生成 **私有** 字段 + getter/setter；jar 里编译好的
     * `putfield Spider.siteKey` 就会以 `IllegalAccessError` 收场。
     * `@JvmField` 才能生成真正的 public 字段。
     */
    @JvmField
    var siteKey: String = ""

    open fun init(context: Context) {
    }

    /**
     * ⚠️ 这个实现**不能改**：原版就是委托给 [init] 的
     * （`public void init(Context context, String extend) { init(context); }`），
     * 而且它是真实 jar 覆写的那一个。
     *
     * 曾经这里写成了"再调一个自造的 `init(String)`"，而原版**根本没有**
     * `init()` / `init(String)` 这两个重载。多出来的重载会带来一个隐蔽风险：
     * 若某个 jar 恰好自己定义了一个用途无关的 `init(String)`，
     * 它会"意外地"变成对基类的覆写，被基类的委托链调用到。
     * 删掉自造重载 = 消除这个可能。
     */
    open fun init(context: Context, extend: String?) {
        init(context)
    }

    /**
     * 宿主向爬虫注入本地代理 / 解析能力的入口（原版更新的 catvod 才有的成员）。
     *
     * 本项目**不实现**本地代理与解析服务，所以这里不做任何事；
     * 它的存在是为了让那些覆写它、并在开头 `super.initApi(api)` 的 jar
     * 不至于 `NoSuchMethodError`。真要用到代理的站点会退化 —— 见 `SpiderApi`。
     */
    open fun initApi(api: SpiderApi?) {
    }

    /** 分类列表与首页推荐。`filter` 表示要不要同时返回筛选条件。 */
    open fun homeContent(filter: Boolean): String = ""

    /**
     * 首页**推荐位**，与 [homeContent] 是两条独立的调用。
     *
     * 原版 `SiteApi.homeContent` 是两个都调、然后合并：
     * ```java
     * Result result = Result.fromJson(spider.homeContent(true));
     * List<Vod> list = Result.fromJson(spider.homeVideoContent()).getList();
     * if (!list.isEmpty()) result.setList(list);
     * ```
     * 只调 [homeContent] 的话，那些把轮播/推荐单独放在这里的站点
     * 首页会**没有精选** —— 而且不报任何错。
     */
    open fun homeVideoContent(): String = ""

    /**
     * 分类内容。
     *
     * `extend` 的类型是 **`HashMap`** 而不是 `Map`：子类按 `HashMap` 覆写，
     * 基类写成 `Map` 就匹配不上（生成的描述符不同）。
     */
    open fun categoryContent(
        tid: String,
        pg: String,
        filter: Boolean,
        extend: HashMap<String, String>,
    ): String = ""

    open fun detailContent(ids: List<String>): String = ""

    /**
     * 搜索（经典两参版）。
     *
     * ⚠️ 原版 `SiteApi.searchContent` 的调度是：
     * ```java
     * boolean hasPage = !page.equals("1");
     * String s = hasPage ? spider.searchContent(key, quick, page)
     *                    : spider.searchContent(key, quick);
     * ```
     * 也就是说**第一页走的是这个两参版**。所以我们调它，而不是无脑调三参版 ——
     * 只实现了两参版的老 jar（官方 SPIDER.md 里记载的就是这个）才拿得到结果。
     */
    open fun searchContent(key: String, quick: Boolean): String = ""

    /** 带页码的搜索（新版重载）。原版默认返回空串，**不**委托给两参版。 */
    open fun searchContent(key: String, quick: Boolean, pg: String): String = ""

    open fun playerContent(flag: String?, id: String?, vipFlags: List<String>?): String = ""

    /** 直播。本项目范围是点播 + 本地播放，**不会调用**它；留着只为 ABI 完整。 */
    open fun liveContent(url: String?): String = ""

    open fun manualVideoCheck(): Boolean = false

    open fun isVideoFormat(url: String?): Boolean = false

    /**
     * 本地代理入口。返回 `[状态码, MIME, 响应头, 响应体]`，由宿主的本地 HTTP 服务转发。
     *
     * 名字就是 `proxy` —— 曾经这里叫 `proxyLocal`，那是**纯粹的错误**：
     * jar 覆写的是 `proxy`，名字不同就匹配不上，覆写退化成没人调用的普通方法。
     * 本项目第一版不起本地代理服务，所以恒返回 null（= 不处理）。
     */
    open fun proxy(params: Map<String, String>?): Array<Any>? = null

    /** 自定义动作入口。本项目没有触发它的 UI，恒返回 null。 */
    open fun action(action: String?): String? = null

    open fun destroy() {
    }

    companion object {

        /**
         * ⚠️ **必须是静态方法**。原版是
         * `public static OkHttpClient client() { return OkHttp.client(); }`
         * 而 `custom_spider.jar` / `pg*.jar` 实测就是按 `invokestatic` 调的。
         * 写成 Kotlin 的实例方法（没有 `@JvmStatic`）→ `NoSuchMethodError`。
         */
        @JvmStatic
        fun client(): OkHttpClient = OkHttp.client()

        /**
         * 同理，静态。返回类型必须是 `okhttp3.Dns`（实测 jar 引用的描述符就是
         * `()Lokhttp3/Dns;`）—— 原版具体返回 `OkDns`，那是个内部实现类，
         * 我们不引入它，直接给等价的 `Dns`。
         */
        @JvmStatic
        fun safeDns(): Dns = OkHttp.dns()
    }
}
