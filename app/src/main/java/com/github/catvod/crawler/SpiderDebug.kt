package com.github.catvod.crawler

import android.util.Log

/**
 * CatVod 的调试日志工具 —— 本项目自带的兼容层。
 *
 * 大量现存 spider 里有 `SpiderDebug.log("…")` 这样的调用，且**很多是写死在
 * 正常流程里的**（不是包在 `if (isDebug())` 里）。所以这个类必须存在、
 * 且方法的静态签名必须对得上，否则整个站点一调用就崩。
 *
 * 同样必须放在 `com.github.catvod.crawler` 包下，理由见 `Spider.kt`。
 *
 * `@JvmStatic` 是**必需的**：Kotlin 的 `object` 方法在 Java 里默认长成
 * `SpiderDebug.INSTANCE.log(...)`，而 jar 里写的是静态调用 `SpiderDebug.log(...)`。
 * 没有 `@JvmStatic` 就是 `NoSuchMethodError`。
 */
object SpiderDebug {

    private const val TAG = "CatVodSpider"

    /**
     * 日志开关。默认关 —— 站点多的时候 spider 打日志非常吵，
     * 而且会拖慢列表加载（每条日志都要过一遍 logcat）。
     *
     * ⚠️ 这里**不能**写成 `var isDebug`：Kotlin 会给它生成 `setDebug(Z)V`，
     * 和下面手写的、jar 真正在调的那个静态 `setDebug` 签名撞车，
     * 编译期直接报 `Platform declaration clash`。
     * 所以字段私有 + 显式的 getter/setter 两个方法。
     */
    @Volatile
    private var debugEnabled: Boolean = false

    @JvmStatic
    fun setDebug(debug: Boolean) {
        debugEnabled = debug
    }

    @JvmStatic
    fun isDebug(): Boolean = debugEnabled

    @JvmStatic
    fun log(msg: String?) {
        if (debugEnabled) Log.d(TAG, msg.orEmpty())
    }

    /**
     * 两参版本。注意 CatVod 原版的参数顺序是 **(tag, msg)**，
     * 这里保持一致，不要"顺手"调过来。
     */
    @JvmStatic
    fun log(tag: String?, msg: String?) {
        if (debugEnabled) Log.d(tag ?: TAG, msg.orEmpty())
    }

    @JvmStatic
    fun log(e: Throwable?) {
        if (debugEnabled) Log.d(TAG, Log.getStackTraceString(e))
    }

    @JvmStatic
    fun log(tag: String?, e: Throwable?) {
        if (debugEnabled) Log.d(tag ?: TAG, Log.getStackTraceString(e))
    }
}
