package com.cycling.beevideo.data.source.vod.js

import android.util.Log
import com.whl.quickjs.wrapper.JSCallFunction
import com.whl.quickjs.wrapper.QuickJSContext

/**
 * 宿主内置的 DOM 规则函数 `pdfh` / `pdfa` / `pd` / `pdfl`（实现见 [DomParser]）。
 *
 * ⚠️ 必须在源的模块被求值**之前**注册：drpy2 的模块顶层就写 `const defaultParser = { pdfh }`，
 * 晚一步就是 `'pdfh' is not defined`（整批 .js 源一起报废）。
 * ⚠️ `pdfl` 不是可选项：drpy2 用 `typeof pdfl === "function"` 判版本，少一个是静默降级。
 */
internal class JsDomFunctions(private val context: QuickJSContext) {

    private val parser = DomParser()

    private var loggedFailure = false

    fun register() {
        val global = context.globalObject
        global.setProperty("pdfh", JSCallFunction { args ->
            domCall { parser.parseDomForUrl(args.arg(0), args.arg(1), "") }
        })
        global.setProperty("pd", JSCallFunction { args ->
            domCall { parser.parseDomForUrl(args.arg(0), args.arg(1), args.arg(2)) }
        })
        global.setProperty("pdfa", JSCallFunction { args ->
            domCall { JSUtil.toArray(context, parser.parseDomForArray(args.arg(0), args.arg(1))) }
        })
        global.setProperty("pdfl", JSCallFunction { args ->
            domCall {
                JSUtil.toArray(
                    context,
                    parser.parseDomForList(
                        args.arg(0), args.arg(1), args.arg(2), args.arg(3), args.arg(4),
                    ),
                )
            }
        })
    }

    /** 丢掉最后一段 html 的单槽引用。上下文销毁前调。 */
    fun clear() = parser.clear()

    /**
     * ⚠️ **必须吞成 null，不能让异常穿过 JNI**：抛出去在 JS 侧是一个 Error，而源几乎不会
     * try/catch 包住 pdfh —— 一个可选字段取不到就会让整个站点失效。
     * 日志只记一次：drpy 源里"这个字段本来就没有"是常态。
     */
    private fun domCall(block: () -> Any?): Any? = try {
        block()
    } catch (e: Throwable) {
        if (!loggedFailure) {
            loggedFailure = true
            Log.w(
                JS_LOG_TAG,
                "pdfh/pdfa 规则执行失败（同类错误只记这一次）：${e.javaClass.simpleName}: ${e.message}",
            )
        }
        null
    }
}
