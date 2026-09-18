package com.cycling.beevideo.data.source.vod.js

import com.whl.quickjs.wrapper.JSCallFunction
import com.whl.quickjs.wrapper.JSFunction
import com.whl.quickjs.wrapper.JSObject
import java.util.concurrent.CompletableFuture

/**
 * 把 JS 的 **Promise** 桥接成 Java 的 `CompletableFuture`
 * —— 逐行对齐参考宿主的 `quickjs/src/main/java/com/fongmi/quickjs/utils/Async.java`。
 *
 * ─── 它解决什么 ──────────────────────────────────────────────────────
 * JS 爬虫的 `home` / `category` / `detail` / `search` / `play` 这些入口**几乎全是
 * `async` 函数**（drpy 的约定），调用它们拿到的是一个 Promise 对象，不是结果。
 * 宿主侧要用阻塞语义（`Future.get()`）把它们变回"一次调用一个返回值"，
 * 于是有了这个类：
 * ```
 * func.call(args)
 *   ├─ 返回 JSObject 且有 then → 挂 success/error 回调，等 Promise settle
 *   └─ 其它（同步返回值 / null）→ 立即 complete
 * ```
 *
 * ─── ⚠️ 三个必须照搬的细节 ────────────────────────────────────────────
 * 1. **`func.release()` 在 `finally` 里**。QuickJS 绑定的 `JSFunction` 是**引用计数**
 *    对象（见 `JSFunction.hold/release`），漏一次 release 就是一次泄漏，
 *    而且是 native 侧的 —— 表现是跑几十个站点之后内存不回收。
 * 2. **`catch` 的是 `Throwable`**。JS 抛出的异常经由 JNI 回来可能是
 *    `QuickJSException`，也可能是任意 `Error`；漏一种就是**永久挂起**
 *    （future 永远不 complete，`get()` 卡死线程）。
 * 3. **`then` 存在、`catch` 不存在是合法的**（JS 侧可以只写 `.then`）。
 *    [consume] 对 null 直接返回，不做任何事。
 *
 * ⚠️ `catch` 在 Kotlin 里是**关键字**，所以回调字段只能叫 `error`
 * （参考实现里那个 `JSCallFunction` 就叫 `error`，名字没变）。
 *
 * ─── 线程模型 ────────────────────────────────────────────────────────
 * 这个类**不做任何线程调度**，`run` 必须在持有 ctx 的那个线程上调用
 * （QuickJS 的 ctx 绑定创建线程，跨线程调用直接抛
 * "Must be call same thread in QuickJSContext.create!"）。
 * 调度由 [JsSpider] 的单线程 executor 负责。
 */
class Async private constructor() {

    // ⚠️ 声明顺序有意义：`success` / `error` 两个 lambda 引用 `future`，
    // 所以 `future` 必须**先**初始化（Kotlin 与 Java 一样按声明顺序跑初始化器）。
    private val future = CompletableFuture<Any?>()

    private val success = JSCallFunction { args ->
        future.complete(if (args != null && args.isNotEmpty()) args[0] else null)
        null
    }

    private val error = JSCallFunction { args ->
        val msg = if (args != null && args.isNotEmpty() && args[0] != null) args[0].toString() else ""
        future.completeExceptionally(Exception(msg))
        null
    }

    private fun call(obj: JSObject, name: String, args: Array<out Any?>): CompletableFuture<Any?> {
        val func = obj.getJSFunction(name) ?: return empty()
        call(func, args)
        return future
    }

    private fun empty(): CompletableFuture<Any?> {
        future.complete(null)
        return future
    }

    private fun call(func: JSFunction, args: Array<out Any?>) {
        try {
            val result = func.call(*args)
            // 只有 JSObject 才可能是 Promise；其余（字符串/数字/null）是同步返回值
            if (result is JSObject) then(result) else future.complete(result)
        } catch (e: Throwable) {
            future.completeExceptionally(e)
        } finally {
            func.release()
        }
    }

    private fun then(promise: JSObject) {
        val then = promise.getJSFunction("then")
        if (then == null) {
            // 有 then 才是 Promise；没有的话它就是普通对象，直接当结果
            future.complete(promise)
        } else {
            consume(then, success)
            consume(promise.getJSFunction("catch"), error)
        }
    }

    private fun consume(func: JSFunction?, callback: JSCallFunction) {
        if (func == null) return
        try {
            func.call(callback)
        } finally {
            func.release()
        }
    }

    companion object {

        /**
         * 调用 `object` 上名为 `name` 的方法并等它 settle。
         *
         * ⚠️ 方法不存在时 future **立即 complete(null)**，而不是抛 ——
         * 这是 JS 爬虫的常态：站点没实现 `action` / `isVideo` 之类的可选入口时，
         * 基类 `spider.js` 生成的对象上就没有那个键。
         * 抛异常的话每个可选入口都要调用方自己 try，而调用方根本分不清
         * "没实现"和"实现了但炸了"。
         */
        @JvmStatic
        fun run(obj: JSObject, name: String, vararg args: Any?): CompletableFuture<Any?> =
            Async().call(obj, name, args)
    }
}
