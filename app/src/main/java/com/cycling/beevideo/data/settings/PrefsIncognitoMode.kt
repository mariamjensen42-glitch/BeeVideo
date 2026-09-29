package com.cycling.beevideo.data.settings

import android.content.Context
import com.cycling.beevideo.domain.repository.IncognitoMode
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * [IncognitoMode] 的 SharedPreferences 实现。
 *
 * ─── 为什么单独一个 prefs 文件 ─────────────────────────────────────────
 * 与 [PrefsThemeSettings] / [PrefsPlaybackSettings] 分开的理由一样：失效时机不同。
 * 用户在设置页点「清除」内容源时那份 prefs 会被整份删掉，混在一起会让清一次源
 * 顺手把无痕开关重置掉 —— 而用户不会认为这是同一个操作。
 *
 * ─── 开关本身为什么必须落盘 ────────────────────────────────────────────
 * 它是**用户的选择**，不是会话数据。所以它不该像媒体分片那样在退出时被清掉：
 * 用户开着无痕关掉 App，下次打开仍然是无痕，直到他自己拨回去。
 *
 * @param onExit 从「开」拨到「关」时调一次。会话期间真正落到磁盘的东西（独立目录里的
 *               媒体分片、只进内存的封面）由它收尾。本类不碰文件系统，也不管线程。
 */
class PrefsIncognitoMode(
    context: Context,
    private val onExit: () -> Unit,
) : IncognitoMode {

    private val prefs = context.applicationContext
        .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    private val _enabled = MutableStateFlow(prefs.getBoolean(KEY_ENABLED, false))

    override val enabled: StateFlow<Boolean> = _enabled.asStateFlow()

    /**
     * 顺序有意为之：**先落盘、再发射、最后收尾**。
     *
     * 收尾是删除动作（几百毫秒起），放在发射之前会让界面卡一拍才变；
     * 放在落盘之前则可能出现"开关没记住但东西已经删了"。
     */
    override fun set(enabled: Boolean) {
        if (_enabled.value == enabled) return
        prefs.edit().putBoolean(KEY_ENABLED, enabled).apply()
        _enabled.value = enabled
        // 只有关闭这一个方向有东西要清：开启时还没有任何会话数据
        if (!enabled) onExit()
    }

    private companion object {
        const val PREFS_NAME = "beevideo.incognito"
        const val KEY_ENABLED = "incognito_enabled"
    }
}
