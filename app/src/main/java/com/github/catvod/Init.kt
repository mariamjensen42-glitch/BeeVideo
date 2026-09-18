package com.github.catvod

import android.content.Context
import java.lang.ref.WeakReference

/**
 * 全局 Context 持有者 —— **本项目自带的兼容层**，逐条对齐参考宿主的
 * `catvod/src/main/java/com/github/catvod/Init.java`（26 行）。
 *
 * ─── 它解决什么 ──────────────────────────────────────────────────────
 * `com.github.catvod.utils` 里的工具类（[com.github.catvod.utils.Asset]、
 * [com.github.catvod.utils.Prefers]、[com.github.catvod.utils.Util]）都是**静态方法**，
 * 而它们要用的 Context 只能从外面塞进来一次。参考实现就是这么做的。
 *
 * 用 `WeakReference` 而不是强引用：这是个 Application 级的单例，持有强引用没有实际
 * 泄漏风险，但参考实现用的是弱引用，且**弱引用在这里更安全** —— 若哪天有人误传了
 * Activity，强引用会直接泄漏它，弱引用不会。
 *
 * ⚠️ **别和 `com.github.catvod.spider.Init` 搞混**：那是 jar **自己**带的类
 * （实测 `device.jar` 里有 `com/github/catvod/spider/Init.init(Context)`、
 * `.context()`、`.loader()`、`.getSpider(String)`），和宿主这份是两个不同的类。
 * 包名差一段 `.spider`，看错了会以为"同类重名"。
 *
 * ─── 谁调用 [set] ────────────────────────────────────────────────────
 * `BeeApplication.onCreate`。**只传 applicationContext**。
 */
object Init {

    @Volatile
    private var reference: WeakReference<Context>? = null

    /** 由 Application 调用一次。 */
    @JvmStatic
    fun set(context: Context) {
        reference = WeakReference(context.applicationContext)
    }

    /**
     * 取全局 Context。
     *
     * ⚠️ 返回值可空：单测（纯 JVM，没有 Application）里没调过 [set]，
     * 强行断言非空会让每个用到它的单测都要先搭一个假 Application。
     * 参考实现在没初始化时是直接抛 `NullPointerException`（`context.get()` 返回 null）——
     * 我们把"没接上"变成**返回 null**，让调用方各自决定：静默降级还是报错。
     */
    @JvmStatic
    fun context(): Context? = reference?.get()
}
