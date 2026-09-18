package com.cycling.beevideo.ui.settings

import com.cycling.beevideo.domain.repository.ContentSourceRepository
import com.cycling.beevideo.domain.repository.MediaCache
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * 设置页的状态持有者：**缓存占用**、**装载是否在进行中**，以及几个动作。
 *
 * ─── 它存在解决的问题 ──────────────────────────────────────────────────
 * 以前这三个都在 composable 里：
 *
 *   1. **`applying` 用裸 `remember`** —— 主题切换会重建 Activity（`configChanges`
 *      不含 `uiMode`），于是"正在装载"这个标志被清掉、按钮中途复活。
 *      （装载本身已经不会被取消了 —— 它跑在配置会话自己的作用域里，见 ADR-0003 ——
 *      所以这里丢掉的是**界面上的真相**，不是请求。）
 *   2. **占用是 `LaunchedEffect(usageTick)` + 一个自增计数器** 刷新的：刷新要靠
 *      调用方记得去 `tick++`。现在它是 [refreshUsage] / [clearCache] 两个有名字的动作。
 *   3. 设置项的读写直接落在具体实现上（`data.settings.PlaybackSettings`、
 *      `player.MediaCacheProvider`），`ui` 因此在编译期依赖 `data` 与 `player`。
 *
 * 作用域由外面给（见 ADR-0004），所以这些判据能在纯 JVM 单测里钉住。
 */
class SettingsState(
    private val sources: ContentSourceRepository,
    private val cache: MediaCache,
    private val scope: CoroutineScope,
) {

    /**
     * 缓存占用（字节）。
     *
     * 初值 0 而不是"还没读到"：界面显示的是"已用 xx MB"，读到之前显示 0 是对的。
     */
    private val _usageBytes = MutableStateFlow(0L)
    val usageBytes: StateFlow<Long> = _usageBytes.asStateFlow()

    /**
     * 装载是否在进行中。
     *
     * 放持有者里而不是 `remember` —— 理由见类注释：主题切换会重建 Activity，
     * 而那不该让"正在装载"变成"没在装载"。
     */
    private val _applying = MutableStateFlow(false)
    val applying: StateFlow<Boolean> = _applying.asStateFlow()

    init {
        refreshUsage()
    }

    /** 重读磁盘占用。进页面时读一次，清空之后再读一次。 */
    fun refreshUsage() {
        scope.launch { _usageBytes.value = cache.usageBytes() }
    }

    /** 清空缓存。清完**立刻重读**：遍历文件系统才是"真实占用"的唯一来源。 */
    fun clearCache() {
        scope.launch {
            cache.clear()
            _usageBytes.value = cache.usageBytes()
        }
    }

    /**
     * 用给定地址装载配置。
     *
     * 失败**不在这里处理**：原因由 `sources.status.message` 给出，页面上本来就有一处
     * 显示它，返回值和它会是同一句话。这里只负责 `applying` 的真假。
     */
    fun applyConfig(url: String) {
        scope.launch {
            _applying.value = true
            try {
                sources.applyConfig(url)
            } finally {
                // `finally` 而不是顺序执行：中途抛了（含作用域被取消）也要复位，
                // 否则按钮会永久禁用
                _applying.value = false
            }
        }
    }

    /** 清空内容源。用户点「清除」时，配置副本、站点客户端、合并窗口一起作废。 */
    fun clearSource() {
        scope.launch { sources.clear() }
    }
}
