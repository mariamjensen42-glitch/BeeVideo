package com.github.catvod.utils

import android.content.Context
import android.content.SharedPreferences
import com.github.catvod.Init

/**
 * 默认 SharedPreferences 的静态门面 —— **本项目自带的兼容层**，对齐参考宿主的
 * `catvod/src/main/java/com/github/catvod/utils/Prefers.java`。
 *
 * ─── 谁在用 ──────────────────────────────────────────────────────────
 * JS 爬虫的 `local` 对象（见 `Local`）：JS 侧写
 * `local.set(rule, key, value)` / `local.get(rule, key)`，键会被加上
 * `cache_<rule>_` 前缀（前缀规则在 [com.cycling.beevideo.data.source.vod.js.Local]）。
 * 于是 **JS 爬虫的缓存与宿主自己的设置共用同一个 prefs 文件** ——
 * 这是参考实现的行为，照搬。
 *
 * ─── ⚠️ 与参考实现的关键差异：不引 `androidx.preference` ──────────────
 * 参考实现是 `PreferenceManager.getDefaultSharedPreferences(context)`，
 * 那要拉一个 `androidx.preference` 依赖进来 —— **为一个函数引一整个库不划算**。
 *
 * 这里直接把那个库的**文件命名规则**照抄过来：
 * ```
 * getDefaultSharedPreferencesName(context) = context.packageName + "_preferences"
 * ```
 * 于是**落盘位置与参考实现完全一致**，行为不变，依赖不加。
 *
 * ⚠️ 顺带一提：正因为它是"默认"文件，**和本项目自己的设置不是同一份**
 * —— `ThemeSettings` / `PlaybackSettings` / `ContentSourceStore` 各自用了
 * `beevideo.appearance` / `beevideo.playback` / … 这样的独立文件名。
 * 所以清内容源不会顺手抹掉爬虫缓存，反之亦然。
 *
 * ─── 没接上 Context 时怎么办 ──────────────────────────────────────────
 * 全部退化成"读默认值 / 写丢弃"，**不抛**。纯 JVM 单测里没有 Application，
 * `Init.context()` 返回 null，那时这些方法必须还能调用 —— 否则每个碰到缓存的
 * 单测都得先搭一个假 Application。
 */
object Prefers {

    /**
     * 加锁是必要的：`Prefers` 会被 JS 在**任意线程**上调（`Global` 把
     * `local.set` 直接映射进 JS），`Init.context()` 又可能在启动早期还没设好。
     * 双重检查锁让"首次取 SharedPreferences"只发生一次。
     */
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
     * 按**运行时类型**分派写入。
     *
     * ⚠️ JS 传进来的值几乎总是字符串（JS 侧 `local.set` 的签名就是三参 String），
     * 所以走 `Number` 的那两条分支在本项目里基本不会被触发 —— 保留是为了与
     * 参考实现一致：真实 jar 会调 `Prefers.put(key, 数字)`，而 Java 的
     * `Object` 形参能吃下装过箱的 Integer/Long。
     *
     * `null` 直接忽略（参考实现如此）：JS 侧 `local.set(rule, key, null)`
     * 传进来的是字符串 "null"，不会走到这里。
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
