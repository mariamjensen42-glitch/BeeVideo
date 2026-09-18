package com.github.catvod.utils

import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import org.json.JSONArray
import org.json.JSONObject

/**
 * JSON 工具 —— **本项目自带的兼容层**，逐条对齐参考宿主的
 * `catvod/src/main/java/com/github/catvod/utils/Json.java`。
 *
 * ─── 这里为什么同时有 Gson 和 org.json ────────────────────────────────
 * 不是历史包袱，是**两种判据本来就不一样**：
 *   - [isObj] / [isArray] 要的是「这段文本是不是**合法** JSON」——`org.json`
 *     严格按文法解析，`{}` / `[]` 都能给出确定的答案；
 *   - [toMap] 要的是「把 JsonElement 摊平成字符串表」——那必须走 Gson，
 *     因为入参本来就是 Gson 的 `JsonElement`（见 `Req.headers` 的类型）。
 *
 * 参考实现就是这么分工的，照搬。
 *
 * ─── 与参考实现的差异：不依赖 `android.text.TextUtils` ────────────────
 * 参考实现用 `TextUtils.isEmpty`。换成 Kotlin 的 `isNullOrEmpty()`／`isEmpty()`：
 * 行为**完全一致**（空引用/空串），但纯 JVM 单测里不会碰到 android.jar 的桩
 * ——`TextUtils` 在单测里是 `Stub!`，调用即抛（见 app/build.gradle.kts 里那条
 * `isReturnDefaultValues` 的注释）。
 */
object Json {

    /** 宽松解析。解析不了时 Gson 会给出 `JsonNull`，**不抛**。 */
    @JvmStatic
    fun parse(json: String): JsonElement = try {
        JsonParser.parseString(json)
    } catch (_: Throwable) {
        JsonParser.parseString(json)
    }

    @JvmStatic
    fun isObj(text: String?): Boolean = try {
        if (text.isNullOrEmpty()) false else {
            JSONObject(text)
            true
        }
    } catch (_: Exception) {
        false
    }

    @JvmStatic
    fun isArray(text: String?): Boolean = try {
        if (text.isNullOrEmpty()) false else {
            JSONArray(text)
            true
        }
    } catch (_: Exception) {
        false
    }

    @JvmStatic
    fun isEmpty(obj: JsonObject, key: String): Boolean {
        if (!obj.has(key)) return true
        val element = obj.get(key) ?: return true
        if (element.isJsonNull) return true
        if (element.isJsonArray) return element.asJsonArray.isEmpty
        if (element.isJsonPrimitive && element.asJsonPrimitive.isString) {
            return element.asString.trim().isEmpty()
        }
        // 注意：对象、数字、布尔都算"非空" —— 与参考实现一致
        return true
    }

    @JvmStatic
    fun safeString(obj: JsonObject, key: String): String = try {
        obj.getAsJsonPrimitive(key).asString.trim()
    } catch (_: Exception) {
        ""
    }

    @JvmStatic
    fun safeListString(obj: JsonObject, key: String): List<String> {
        val result = ArrayList<String>()
        if (!obj.has(key)) return result
        val element = obj.get(key)
        // 兼容"该是数组的位置写成了单个对象"——真实配置里出现过
        if (element.isJsonObject) result.add(safeString(obj, key))
        else element.asJsonArray.forEach { result.add(it.asString) }
        return result
    }

    @JvmStatic
    fun safeListElement(obj: JsonObject, key: String): List<JsonElement> {
        val result = ArrayList<JsonElement>()
        if (!obj.has(key)) return result
        val element = obj.get(key)
        if (element.isJsonObject) result.add(element.asJsonObject)
        else element.asJsonArray.forEach { result.add(it.asJsonObject) }
        return result
    }

    /**
     * 把"可能是 JSON 字符串、也可能已经是对象"的东西统一成对象。
     *
     * primitive 会**再解析一次**它的字符串值 —— 这是真实配置里常见的双层编码
     * （`headers` 的值本身是一段 JSON 文本）。
     */
    @JvmStatic
    fun safeObject(element: JsonElement?): JsonObject {
        if (element == null) return JsonObject()
        return try {
            val e = if (element.isJsonPrimitive) parse(element.asJsonPrimitive.asString) else element
            e.asJsonObject
        } catch (_: Exception) {
            JsonObject()
        }
    }

    /**
     * @return **可能是 null**（入参为空时），这一点与参考实现一致。
     *   `Req.getHeader()` / `Res.getHeader()` 直接把它喂给 `Headers.of(...)`。
     */
    @JvmStatic
    fun toMap(json: String?): Map<String, String>? =
        if (json.isNullOrEmpty()) null else toMap(parse(json))

    @JvmStatic
    fun toMap(element: JsonElement?): Map<String, String> {
        val map = HashMap<String, String>()
        val obj = safeObject(element)
        for (entry in obj.entrySet()) map[entry.key] = safeString(obj, entry.key)
        return map
    }
}
