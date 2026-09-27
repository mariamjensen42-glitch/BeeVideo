package com.github.catvod.crawler

import android.content.Context
import com.github.catvod.net.OkHttp
import okhttp3.Dns
import okhttp3.OkHttpClient

/**
 * CatVod spider 基类 —— **本项目自带的兼容层**，逐条对齐原版 `Spider.java`。
 *
 * ⚠️ 包名和类名一个字都不能改：jar 只带子类（`com.github.catvod.spider.Xxx extends
 * com.github.catvod.crawler.Spider`），父类指望从宿主找，改了就是 `NoClassDefFoundError`。
 *
 * ⚠️ 签名不是"我觉得该这样"，是 ABI：jar 是预编译的，引用写死在 DEX 的 method_ids /
 * field_ids 表里，少一个成员或静态写成实例 → 运行时 `NoSuchMethodError`，**编译期零提示**。
 * 本文件成员是按 `dex_probe.py` 反查 7 个真实 jar 逐条核对过的。
 *
 * ⚠️ 这个类里不要**调用**任何 Android API（它会在任意线程上被 jar 调用，必须保持"轻"）。
 * 唯一的例外是 `android.content.Context`，它只作为 `init` 的参数**类型**出现。
 */
open class Spider {

    /**
     * ⚠️ **必须是真的公开字段**，所以用 `@JvmField`：普通 Kotlin `var` 会生成私有字段
     * + getter/setter，jar 里编译好的 `putfield Spider.siteKey` 会以 `IllegalAccessError`
     * 收场。宿主在调 `init` **之前**赋值，不少 spider 读它区分"同一个类配了多个站点"。
     */
    @JvmField
    var siteKey: String = ""

    open fun init(context: Context) {
    }

    /**
     * ⚠️ 这个实现不能改：原版就是委托给 [init] 的，而且它是真实 jar 覆写的那一个。
     *
     * 曾经这里写成了"再调一个自造的 `init(String)`"，而原版根本没有那两个重载 ——
     * 多出来的重载有隐蔽风险：某个 jar 恰好自己定义了一个用途无关的 `init(String)`，
     * 它会"意外地"变成对基类的覆写并被委托链调到。
     */
    open fun init(context: Context, extend: String?) {
        init(context)
    }

    /**
     * 宿主向爬虫注入本地代理 / 解析能力的入口（更新的 catvod 才有的成员）。
     * 存在的意义是让那些覆写它并 `super.initApi(api)` 的 jar 不至于 `NoSuchMethodError`。
     */
    open fun initApi(api: SpiderApi?) {
    }

    /** 分类列表与首页推荐。`filter` 表示要不要同时返回筛选条件。 */
    open fun homeContent(filter: Boolean): String = ""

    /**
     * 首页**推荐位**，与 [homeContent] 是两条独立的调用。
     * 只调 [homeContent] 的话，那些把轮播/推荐单独放在这里的站点首页会**没有精选**，
     * 而且不报任何错。
     */
    open fun homeVideoContent(): String = ""

    /**
     * 分类内容。
     *
     * ⚠️ `extend` 的类型必须是 **`HashMap`** 而不是 `Map`：子类按 `HashMap` 覆写，
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
     * ⚠️ 原版是第一页走这个两参版，只有 `page != "1"` 才走三参版 —— 而只实现两参版的
     * 老 jar（官方 SPIDER.md 记载的就是这个）才是最普遍的，无脑三参会落到基类空实现上。
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
     * ⚠️ 名字就是 `proxy` —— 曾经叫 `proxyLocal`，那是**纯粹的错误**：jar 覆写的是
     * `proxy`，名字不同就匹配不上，覆写退化成没人调用的普通方法。
     */
    open fun proxy(params: Map<String, String>?): Array<Any>? = null

    /** 自定义动作入口。本项目没有触发它的 UI，恒返回 null。 */
    open fun action(action: String?): String? = null

    open fun destroy() {
    }

    companion object {

        /**
         * ⚠️ **必须是静态方法**：原版是 `public static OkHttpClient client()`，
         * 而 `custom_spider.jar` / `pg*.jar` 实测就是按 `invokestatic` 调的。
         * 写成实例方法（没有 `@JvmStatic`）→ `NoSuchMethodError`。
         */
        @JvmStatic
        fun client(): OkHttpClient = OkHttp.client()

        /**
         * 同理，静态。返回类型必须是 `okhttp3.Dns`（实测 jar 引用的描述符就是
         * `()Lokhttp3/Dns;`）—— 原版返回 `OkDns`，我们不引入那个内部实现类。
         */
        @JvmStatic
        fun safeDns(): Dns = OkHttp.dns()
    }
}
