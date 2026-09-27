package com.cycling.beevideo.data.source.vod.js

import android.util.Log
import com.github.catvod.utils.Asset
import com.github.catvod.utils.UriUtil
import com.whl.quickjs.wrapper.QuickJSContext

/** QuickJS 宿主相关日志的统一 tag。 */
internal const val JS_LOG_TAG = "QuickJS"

private const val HTTP_JS = "js/lib/http.js"

/**
 * 建 QuickJS 上下文，装好 console（日志进 logcat）、`http.js`（全局 http()/req()）、
 * `local`（本地键值缓存）和模块加载器（把 import 的模块名解析成绝对地址）。
 *
 * ⚠️ `setProperty("local", Local::class.java)` 传的是 **Class**：捆绑库会 `newInstance()`
 * 之后按实例方法反射登记，所以 Local 必须是普通 class，写成 object 会抛 NPE。
 *
 * ⚠️ `http.js` 的 evaluate 失败不抛异常，后果是 JS 侧没有 http 函数（每个源都会
 * `http is not defined`），所以读不到要明确记日志，否则看起来像"源写错了"。
 */
internal fun createJsContext(): QuickJSContext {
    val context = QuickJSContext.create()
    context.setConsole(JsConsole())
    val httpJs = Asset.read(HTTP_JS)
    if (httpJs.isEmpty()) {
        Log.e(JS_LOG_TAG, "读不到 $HTTP_JS —— assets 没打进去？JS 侧会没有 http() 函数")
    }
    context.evaluate(httpJs)
    context.globalObject.setProperty("local", Local::class.java)
    context.setModuleLoader(object : QuickJSContext.BytecodeModuleLoader() {

        /** 按**当前模块的地址**把 import 的相对路径解析成绝对地址，否则 drpy 源加载全废。 */
        override fun moduleNormalizeName(baseModuleName: String, moduleName: String): String =
            UriUtil.resolve(baseModuleName, moduleName)

        /**
         * 取模块源码并预编译成字节码（返回 null = 取不到，QuickJS 会报 could not load module）。
         * ⚠️ 语法错误必须让它抛出去：吞成 null 的话 JS 侧只看到"模块加载失败"，
         * 真正的 `SyntaxError: Unexpected token` 就永远看不到了。
         */
        override fun getModuleBytecode(moduleName: String): ByteArray? {
            val code = JsModule.get().fetch(moduleName)
            if (code == null) {
                Log.w(JS_LOG_TAG, "模块取不到：$moduleName")
                return null
            }
            return try {
                context.compileModule(code, moduleName)
            } catch (e: Throwable) {
                Log.e(JS_LOG_TAG, "模块编译失败：$moduleName", e)
                throw e
            }
        }
    })
    return context
}
