package com.github.catvod.utils

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.widget.Toast
import com.github.catvod.crawler.SpiderDebug

/**
 * CatVod 的提示气泡工具 —— **本项目自带的兼容层**。
 *
 * 真实 jar 里有 `Notify.show("…")` 这样的调用，且是**静态调用**：
 * ```
 * invoke-static {v1}, Lcom/github/catvod/utils/Notify;.show:(Ljava/lang/String;)V
 * ```
 * 所以必须是 `object` + `@JvmStatic`；写成普通 Kotlin object 方法是
 * `Notify.INSTANCE.show(…)`，对不上就是 `NoSuchMethodError`。
 *
 * ⚠️ 包名是 `com.github.catvod.utils`，**不是** `com.fongmi.android.tv.utils`
 * —— 后者是 FongMi App 自己的同名类（源码里的 `Notify.java` 就是那个），
 * 两者不是一个东西，别照抄错。
 *
 * ─── 为什么需要一个 context ──────────────────────────────────────────
 * Toast 需要 Context，而调用方（爬虫）在任意线程、也没有 Context。
 * 所以由 `BeeApplication.onCreate` 把 applicationContext 塞进来一次。
 *
 * 拿不到 context 时**静默丢弃**，不抛异常：爬虫里的提示多半是
 * "cookie 过期了"这类调试信息，为它崩掉整个站点不值得。
 */
object Notify {

    @Volatile
    private var appContext: Context? = null

    private val main = Handler(Looper.getMainLooper())

    /** 由 Application 调用一次。 */
    fun init(context: Context) {
        appContext = context.applicationContext
    }

    @JvmStatic
    fun show(msg: String?) {
        val text = msg?.trim().orEmpty()
        if (text.isEmpty()) return
        val ctx = appContext
        if (ctx == null) {
            // 没接上 context 也不能崩：至少留下一行日志
            SpiderDebug.log("[Notify] $text")
            return
        }
        // Toast 只能在主线程弹，而爬虫几乎一定在后台线程调进来
        main.post {
            runCatching { Toast.makeText(ctx, text, Toast.LENGTH_SHORT).show() }
        }
    }
}
