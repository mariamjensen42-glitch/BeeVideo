package com.cycling.beevideo.data.settings

import android.content.Context
import com.cycling.beevideo.domain.model.ThemeMode
import com.cycling.beevideo.domain.repository.ThemeSettings
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * [ThemeSettings] 的 SharedPreferences 实现。
 *
 * ─── 为什么单独一个 prefs 文件 ─────────────────────────────────────────
 * 和 [PrefsPlaybackSettings] 分开的理由一样：失效时机不同。用户在设置页点
 * 「清除」内容源时，那份 prefs 会被整份清空（`ContentSourceStore.clear()`）；
 * 混在一起的话，清一次内容源会把外观设置一起重置 —— 用户不会认为这是同一个操作。
 */
class PrefsThemeSettings(context: Context) : ThemeSettings {

    private val prefs = context.applicationContext
        .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    private val _mode = MutableStateFlow(ThemeMode.fromKey(prefs.getString(KEY_MODE, null)))

    override val mode: StateFlow<ThemeMode> = _mode.asStateFlow()

    /**
     * 改模式并落盘。
     *
     * 用 `apply()` 而不是 `commit()`：后者是同步写磁盘，在点击回调里就是主线程
     * 等一次 I/O。这个值的丢失代价是「重开 App 回到跟随系统」，不值得为它阻塞一帧。
     *
     * 相同的值直接返回，不写盘也不发射 —— `MutableStateFlow` 自己也会去重，
     * 但先去重能省掉一次无意义的 prefs 编辑。
     */
    override fun set(mode: ThemeMode) {
        if (_mode.value == mode) return
        prefs.edit().putString(KEY_MODE, mode.name).apply()
        _mode.value = mode
    }

    private companion object {
        const val PREFS_NAME = "beevideo.appearance"
        const val KEY_MODE = "theme_mode"
    }
}
