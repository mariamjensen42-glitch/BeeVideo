package com.github.catvod.utils

import com.github.catvod.Init
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.nio.charset.StandardCharsets

/**
 * 读 APK 内置 assets —— **本项目自带的兼容层**，行为对齐参考宿主的
 * `catvod/src/main/java/com/github/catvod/utils/Asset.java`。
 *
 * ─── 谁在用 ──────────────────────────────────────────────────────────
 * JS 爬虫引擎：`js/lib/http.js`、`js/lib/spider.js` 都从这里读
 * （见 `JsEngine.createContext`）。所以 **`app/src/main/assets/js/lib/` 下的文件
 * 必须随包发布**，少一个就是 `evaluate("")` —— 报错是 JS 语法错，指不到"文件没打进去"。
 *
 * ─── 与参考实现的一处差异 ────────────────────────────────────────────
 * 参考实现是 `Path.read(open(fileName))`，而 `Path` 是它整个文件系统工具包
 * （302 行：备份目录、字体目录、mpv 缓存、chmod、StatFs…）。这里只保留读流这一件事，
 * 不把那一整包搬过来 —— 用不到的东西搬进来只是多一份要维护的代码。
 * **行为一致**：UTF-8 解码，失败返回空串。
 */
object Asset {

    private const val PREFIX = "assets://"
    private const val BUFFER_SIZE = 16384

    /**
     * 打开 assets 里的文件。失败（不存在 / assets 没打进去）返回 `null`。
     *
     * `assets://` 前缀会被剥掉：CatVod 配置里 ext 写成 `assets://xxx` 是常见形态，
     * 而 `AssetManager.open` 只认裸路径。
     */
    fun open(fileName: String): InputStream? = try {
        Init.context()?.assets?.open(fileName.replace(PREFIX, ""))
    } catch (_: Exception) {
        null
    }

    /** 读成 UTF-8 字符串。读不到就返回空串，**不抛**。 */
    fun read(fileName: String): String = try {
        read(open(fileName))
    } catch (_: Exception) {
        ""
    }

    private fun read(input: InputStream?): String {
        if (input == null) return ""
        return try {
            input.use {
                val output = ByteArrayOutputStream()
                val buffer = ByteArray(BUFFER_SIZE)
                while (true) {
                    val count = it.read(buffer)
                    if (count == -1) break
                    output.write(buffer, 0, count)
                }
                String(output.toByteArray(), StandardCharsets.UTF_8)
            }
        } catch (_: Exception) {
            ""
        }
    }
}
