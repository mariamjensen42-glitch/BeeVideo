package com.cycling.beevideo.data.local

import android.content.Context
import android.util.Log
import java.io.File

/**
 * [ConfigDiskCache] 的接口面 —— 同 [com.cycling.beevideo.data.settings.SourceStore]，
 * 只为让装载逻辑能在纯 JVM 单测里跑（它要碰文件系统）。
 */
interface ConfigCache {

    /** 读 [url] 对应的副本，没有或对不上返回 `null`。 */
    fun read(url: String): String?

    /** 覆盖写入。 */
    fun write(url: String, text: String)

    fun clear()
}

/**
 * 配置文件的本地副本。
 *
 * ─── 为什么要有它 ──────────────────────────────────────────────────────
 * 用户填的那个配置地址是**唯一入口**：拉不到它，整个 App 就只剩设置页 ——
 * 首页、分类、搜索、详情、播放全部失效。而它偏偏是最容易失败的一个请求：
 * 很多配置挂在 GitHub Pages、小站后台、或者干脆是台自建服务器，手机在
 * 地铁里/弱网下打不开是常态。
 *
 * 但**上一次成功拉到的内容**完全可以复用：配置描述的是「有哪些站点、各自的
 * api 地址」，它不会因为地铁里没信号就变。所以失败时回落到本地副本，
 * 让用户至少还能继续用上次配好的源。
 *
 * ─── 为什么不直接当缓存用（每次都先读本地）────────────────────────────
 * 那是另一种行为：配置**会**被站点方更新（换域名、加站点），先读本地就意味着
 * 用户永远看不到更新，除非加一层 TTL —— 而 TTL 又要用户等。这里只在
 * **网络失败**时回落，正常情况永远用最新的一份。
 */
class ConfigDiskCache(context: Context) : ConfigCache {

    /**
     * 放在 `filesDir` 而不是 `cacheDir`：缓存目录会被系统在存储紧张时清掉，
     * 而这份副本的存在意义恰恰是「没网/出问题时的兜底」—— 不能同时依赖
     * 另一个可能被清掉的东西。它的体积是一个文本文件（几十到几百 KB），
     * 不值得为它冒这个险。
     */
    private val dir = File(context.applicationContext.filesDir, DIR_NAME)
    private val textFile = File(dir, FILE_TEXT)
    private val urlFile = File(dir, FILE_URL)

    /**
     * 读 [url] 对应的副本，没有或对不上返回 `null`。
     *
     * 地址必须**逐字相符**才认：副本是给某个具体地址兜底的，另一个地址的副本
     * 顶上来就是拿别人的数据当自己的用，比直接报错更糟。
     */
    override fun read(url: String): String? = runCatching {
        if (!textFile.isFile || !urlFile.isFile) return null
        if (urlFile.readText().trim() != url) return null
        textFile.readText().takeIf { it.isNotBlank() }
    }.getOrElse {
        Log.w(TAG, "读取配置副本失败：${it.message}")
        null
    }

    /**
     * 覆盖写入。
     *
     * **顺序有意为之**：先正文后地址。中途被杀掉时地址要么缺失、要么还是旧的，
     * 两种情况 [read] 都判为「不可用」，于是退化成「没有副本」—— 保守的那个
     * 方向。反过来写的话，会出现「地址是新的、正文是旧的」，那是**静默地拿旧
     * 配置当新配置用**，用户完全无从察觉。
     */
    override fun write(url: String, text: String) {
        runCatching {
            if (text.isBlank()) return
            if (!dir.isDirectory && !dir.mkdirs()) return
            textFile.writeText(text)
            urlFile.writeText(url)
        }.onFailure { Log.w(TAG, "写入配置副本失败：${it.message}") }
    }

    override fun clear() {
        runCatching { dir.deleteRecursively() }
            .onFailure { Log.w(TAG, "清除配置副本失败：${it.message}") }
    }

    private companion object {
        const val TAG = "BeeSource"
        const val DIR_NAME = "config"
        const val FILE_TEXT = "config.json"
        const val FILE_URL = "config.url"
    }
}
