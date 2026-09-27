package com.github.catvod.utils

import android.content.Context
import android.content.SharedPreferences
import com.github.catvod.Init

/**
 * 默认 SharedPreferences 的静态门面 —— **本项目自带的兼容层**，对齐参考宿主的
 * `Prefers.java`。JS 爬虫的 `local` 对象走它（键会被加 `cache_<rule>_` 前缀），
 * 于是 **JS 爬虫的缓存与宿主自己的设置共用同一个 prefs 文件**（参考实现的行为）。
 *
 * ⚠️ 不引 `androidx.preference`：参考实现是 `PreferenceManager.getDefaultSharedPreferences()`，
 * 那要为一个函数拉一整个库。这里直接照抄它的文件命名规则
 * （`packageName + "_preferences"`），落盘位置与参考实现完全一致。
 *
 * ⚠️ 正因为它是"默认"文件，和本项目自己的设置**不是同一份** —— 各 store 用了
 * `beevideo.appearance` / `beevideo.playback` 这样的独立文件名，所以清内容源不会
 * 顺手抹掉爬虫缓存。
 *
 * 没接上 Context 时全部退化成"读默认值 / 写丢弃"，**不抛**：纯 JVM 单测里没有
 * Application，这些方法必须还能调用。
 */
object Prefers {

    // ⚠️ 会被 JS 在任意线程上调，而 Init.context() 可能在启动早期还没设好，
    // 所以双重检查锁让"首次取 SharedPreferences"只发生一次
    @Volatile
    private var prefs: SharedPreferences? = null

    @JvmStatic
    fun getPrefers(): SharedPreferences? {
        prefs?.let { return it }
        synchronized(this) {
            prefs?.let { return it }
            val context = Init.context() ?: return null
            val name = context.applicationContext.packageName + "_preferences"
            val created = context.applicationContext.getSharedPreferences(name, Context.MODE_PRIVATE)
            prefs = created
            return created
        }
    }

    @JvmStatic
    fun getString(key: String): String = getString(key, "")

    @JvmStatic
    fun getString(key: String, defaultValue: String): String = try {
        getPrefers()?.getString(key, defaultValue) ?: defaultValue
    } catch (_: Exception) {
        defaultValue
    }

    @JvmStatic
    fun getInt(key: String): Int = getInt(key, 0)

    @JvmStatic
    fun getInt(key: String, defaultValue: Int): Int = try {
        getPrefers()?.getInt(key, defaultValue) ?: defaultValue
    } catch (_: Exception) {
        defaultValue
    }

    @JvmStatic
    fun getLong(key: String): Long = getLong(key, 0L)

    @JvmStatic
    fun getLong(key: String, defaultValue: Long): Long = try {
        getPrefers()?.getLong(key, defaultValue) ?: defaultValue
    } catch (_: Exception) {
        defaultValue
    }

    @JvmStatic
    fun getFloat(key: String): Float = getFloat(key, 0f)

    @JvmStatic
    fun getFloat(key: String, defaultValue: Float): Float = try {
        getPrefers()?.getFloat(key, defaultValue) ?: defaultValue
    } catch (_: Exception) {
        defaultValue
    }

    @JvmStatic
    fun getBoolean(key: String): Boolean = getBoolean(key, false)

    @JvmStatic
    fun getBoolean(key: String, defaultValue: Boolean): Boolean = try {
        getPrefers()?.getBoolean(key, defaultValue) ?: defaultValue
    } catch (_: Exception) {
        defaultValue
    }

    /**
     * 按**运行时类型**分派写入。`Number` 那两条分支在本项目里基本不触发（JS 侧传的
     * 几乎总是字符串），保留是为了与参考实现一致 —— 真实 jar 会调 `Prefers.put(key, 数字)`。
     * `null` 直接忽略（参考实现如此）。
     */
    @JvmStatic
    fun put(key: String, obj: Any?) {
        val editor = getPrefers()?.edit() ?: return
        when (obj) {
            null -> return
            is String -> editor.putString(key, obj)
            is Boolean -> editor.putBoolean(key, obj)
            is Float -> editor.putFloat(key, obj)
            is Int -> editor.putInt(key, obj)
            is Long -> editor.putLong(key, obj)
            is Number -> {
                // 有小数点就当浮点，否则当整数 —— 与参考实现一致
                if (obj.toString().contains(".")) editor.putFloat(key, obj.toFloat())
                else editor.putInt(key, obj.toInt())
            }
            else -> editor.putString(key, obj.toString())
        }
        editor.apply()
    }

    @JvmStatic
    fun remove(key: String) {
        getPrefers()?.edit()?.remove(key)?.apply()
    }
}
