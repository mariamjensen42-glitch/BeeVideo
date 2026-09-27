package com.cycling.beevideo.data.source.vod.js

import com.whl.quickjs.wrapper.JSCallFunction
import com.whl.quickjs.wrapper.JSFunction
import com.whl.quickjs.wrapper.JSObject
import java.util.concurrent.CompletableFuture

/**
 * 把 JS 的 **Promise** 桥接成 Java 的 `CompletableFuture`，对齐参考宿主的 `Async.java`。
 *
 * JS 爬虫的 `home` / `category` / `detail` / `search` / `play` 几乎全是 `async` 函数
 * （drpy 的约定），调用它们拿到的是 Promise 而不是结果。宿主侧要用阻塞语义
 * （`Future.get()`）把它们变回"一次调用一个返回值"：返回带 `then` 的对象就挂回调
 * 等 settle，其它（同步返回值 / null）立即 complete。
 *
 * ⚠️ 三个必须照搬的细节：
 *  1. **`func.release()` 在 `finally` 里** —— `JSFunction` 是引用计数对象，漏一次就是
 *     一次 native 侧泄漏（跑几十个站点后内存不回收）。
 *  2. **`catch` 的是 `Throwable`** —— JS 抛出的异常经 JNI 回来可能是任意 `Error`，
 *     漏一种就是**永久挂起**（future 永不 complete，`get()` 卡死线程）。
 *  3. **`then` 存在、`catch` 不存在是合法的**，[consume] 对 null 直接返回。
 *
 * ⚠️ `catch` 是 Kotlin 关键字，所以回调字段只能叫 `error`（参考实现里也是这个名字）。
 *
 * 本类**不做任何线程调度**：`run` 必须在持有 ctx 的那个线程上调用（ctx 绑定创建线程），
 * 调度由 [JsSpider] 的单线程 executor 负责。
 */
class Async private constructor() {

    // ⚠️ 声明顺序有意义：下面两个 lambda 引用 future，Kotlin 按声明顺序跑初始化器
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
            // 没有 then 就说明它不是 Promise，是普通对象，直接当结果
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
         * ⚠️ 方法不存在时 future **立即 complete(null)**，而不是抛 —— 站点没实现
         * `action` / `isVideo` 这类可选入口是常态，抛的话每个可选入口都要调用方自己
         * try，而调用方根本分不清"没实现"和"实现了但炸了"。
         */
        @JvmStatic
        fun run(obj: JSObject, name: String, vararg args: Any?): CompletableFuture<Any?> =
            Async().call(obj, name, args)
    }
}
