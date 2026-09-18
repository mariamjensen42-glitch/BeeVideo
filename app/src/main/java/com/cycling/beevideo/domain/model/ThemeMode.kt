package com.cycling.beevideo.domain.model

/**
 * 深浅色的三态。
 *
 * 为什么要三态而不是一个布尔开关：**「跟随系统」是一个选项，不是初始值**。
 * 布尔开关一旦被用户碰过就永远回不到跟随系统 —— 系统入夜自动变深色时，
 * App 还硬撑着白底，用户只能再去点一次。三态里 `SYSTEM` 是可以一直选着的。
 *
 * 三态的取法对齐 Android 平台自己的 `MODE_NIGHT_AUTO / NO / YES`
 * （UI_MODE_NIGHT_FOLLOW_SYSTEM / NOTNIGHT / NIGHT）。
 *
 * ─── 为什么在 domain 而不是 data/settings ─────────────────────────────
 * 它是**用户的一个偏好**，不是存储细节。以前它和 `ThemeSettings` 一起住在
 * `data/settings/`，于是 `ui/theme/Theme.kt`、`SettingsScreen`、`BeeNavHost`
 * 三处都必须 `import com.cycling.beevideo.data.*` 才能说出这个概念 ——
 * 而依赖方向是 `ui → domain ← data`。枚举本身是纯 Kotlin，没有理由住在数据层。
 *
 * 落盘的读写仍是数据层的事（`data/settings/ThemeSettings`）。
 */
enum class ThemeMode {
    SYSTEM,
    LIGHT,
    DARK,
    ;

    companion object {

        /** 没有存过任何值时用这个。新装的 App 本来就该跟系统走。 */
        val DEFAULT = SYSTEM

        /**
         * 从落盘的字符串还原。
         *
         * 认不出来一律回落到 [DEFAULT]，**不抛异常**：这个值只有本进程会写，
         * 但用户可能从备份里恢复出一份旧版本的数据、或者手工改过 prefs。
         * 为了一个偏好设置让 App 起不来，是不成比例的。
         * （同 `PrefsPlaybackSettings.cacheQuotaBytes` 的校验，理由一样。）
         */
        fun fromKey(key: String?): ThemeMode =
            entries.firstOrNull { it.name == key } ?: DEFAULT
    }
}
