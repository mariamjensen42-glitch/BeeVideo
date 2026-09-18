package com.cycling.beevideo.data.source.vod.js

import com.whl.quickjs.wrapper.JSArray
import com.whl.quickjs.wrapper.JSObject
import com.whl.quickjs.wrapper.QuickJSContext

/**
 * Kotlin ↔ JS 的类型搬运 —— 逐行对齐参考宿主的
 * `quickjs/src/main/java/com/fongmi/quickjs/utils/JSUtil.java`。
 *
 * 只有三件事：`List<String>` → JSArray、`ByteArray` → JSArray、`Map` → JSObject。
 * **方向都是单向的（Java → JS）**：反向（把 JS 值取回 Java）一律走
 * `JSObject.getProperty` / `stringify()`，不在这里。
 *
 * ⚠️ **`ByteArray` 版本会传成"有符号"的整数数组**：参考实现写的是
 * `array.set((int) bytes[i], i)`，Java 的 `byte` 是有符号的，所以 `0xFF` 进去、
 * JS 侧读到的是 `-1`。这不是笔误，是**必须保持**的行为 —— JS 侧拿它构造
 * `Uint8Array`/做位运算时依赖这个符号约定（见 `http.js` 的 `buffer` 用法）。
 * 改成 `and 0xFF` 会让 JS 收到的字节全变，而症状是"内容乱码"这种很难归因的现象。
 */
object JSUtil {

    fun toArray(ctx: QuickJSContext, items: List<String>?): JSArray {
        val array = ctx.createNewJSArray()
        if (items.isNullOrEmpty()) return array
        for (i in items.indices) array.set(items[i], i)
        return array
    }

    fun toArray(ctx: QuickJSContext, bytes: ByteArray?): JSArray {
        val array = ctx.createNewJSArray()
        if (bytes == null || bytes.isEmpty()) return array
        for (i in bytes.indices) array.set(bytes[i].toInt(), i)
        return array
    }

    fun toObject(ctx: QuickJSContext, map: Map<String, String>?): JSObject {
        val obj = ctx.createNewJSObject()
        if (map.isNullOrEmpty()) return obj
        for ((key, value) in map) obj.setProperty(key, value)
        return obj
    }
}
