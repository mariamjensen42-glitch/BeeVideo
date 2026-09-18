package com.cycling.beevideo.data.source.vod.js

import android.util.Log
import com.whl.quickjs.wrapper.QuickJSContext

/**
 * JS 侧 `console.log/info/warn/error` 的落点
 * —— 对齐参考宿主的 `quickjs/src/main/java/com/fongmi/quickjs/method/Console.java`。
 *
 * ─── 与参考实现唯一的差异：日志后端 ──────────────────────────────────
 * 参考实现用的是 `com.orhanobut.logger.Logger`。本项目**不引那个库**：
 * 它只为一行格式化日志存在，而 `android.util.Log` 本来就是全 App 的统一出口
 * （见 `CatVodHttp` / `LocalProxyServer` 用的也是它）。混用两套日志后端的结果是
 * 排查时要在两个 tag 之间来回切。
 *
 * ─── ⚠️ 用 logcat 看 JS 日志之前先扩容缓冲 ──────────────────────────
 * JS 爬虫的 `console.log` 输出量不小，而 logcat 默认 256KB、很容易被
 * 播放器/解码器的日志冲掉，表现为"JS 里明明打了日志，logcat 里一条也没有"。
 * 先 `adb logcat -G 16M`。这是本项目已经踩过一次的坑。
 */
class JsConsole : QuickJSContext.Console {

    override fun log(info: String?) {
        Log.d(TAG, info.orEmpty())
    }

    override fun info(info: String?) {
        Log.i(TAG, info.orEmpty())
    }

    override fun warn(info: String?) {
        Log.w(TAG, info.orEmpty())
    }

    override fun error(info: String?) {
        Log.e(TAG, info.orEmpty())
    }

    private companion object {
        const val TAG = "quickjs"
    }
}
