package com.cycling.beevideo.ui.theme

import android.app.Activity
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialExpressiveTheme
import androidx.compose.material3.MotionScheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.dp
import androidx.core.view.WindowCompat
import com.cycling.beevideo.domain.model.ThemeMode

// M3 十档圆角刻度（20 / 32 / 48 是 Expressive 新增的加粗档）。
// ⚠️ 只用刻度原值，不写 18.dp 这类中间值 —— 开了口子圆角就会像字号一样失控
private val BeeShapes = Shapes(
    extraSmall = RoundedCornerShape(4.dp),
    small = RoundedCornerShape(8.dp),
    medium = RoundedCornerShape(12.dp),
    large = RoundedCornerShape(16.dp),
    largeIncreased = RoundedCornerShape(20.dp),
    extraLarge = RoundedCornerShape(28.dp),
    extraLargeIncreased = RoundedCornerShape(32.dp),
    extraExtraLarge = RoundedCornerShape(48.dp),
)

/** 官方默认刻度，不做任何字体族替换 —— 全 App 只有一个字体（系统默认）。 */
private val BeeTypography: Typography = Typography()

private val BeeMotionScheme = MotionScheme.expressive()

/**
 * 三态模式 → 这一帧该不该用深色。
 *
 * ⚠️ **全 App 唯一读 `isSystemInDarkTheme()` 的地方**。散开写就会出现"设置里选了浅色，
 * 但状态栏图标还是白的"这类症状。组件内部要判深浅，一律按当前 `colorScheme`
 * 的实际亮度判（见 `SkeletonBlock`），**不许再调这个函数**。
 */
@Composable
fun ThemeMode.isDark(): Boolean = when (this) {
    ThemeMode.SYSTEM -> isSystemInDarkTheme()
    ThemeMode.LIGHT -> false
    ThemeMode.DARK -> true
}

/**
 * ⚠️ [darkTheme] **没有默认值，这是有意的**：以前默认 `isSystemInDarkTheme()`，
 * 漏传参数就会静默跟随系统，同一个 App 里出现两套深浅色。现在漏传是编译错误。
 * 生产入口只有 `MainActivity` 一处；`@Preview` 一律显式传值。
 */
@Composable
fun BeeVideoTheme(
    darkTheme: Boolean,
    content: @Composable () -> Unit,
) {
    ApplySystemBarAppearance(darkTheme)

    val colorScheme: ColorScheme = if (darkTheme) BeeDarkScheme else BeeLightScheme
    MaterialExpressiveTheme(
        colorScheme = colorScheme,
        motionScheme = BeeMotionScheme,
        shapes = BeeShapes,
        typography = BeeTypography,
        content = content,
    )
}

/**
 * 让状态栏 / 导航栏图标跟着**应用自己的**深浅色走，而不是系统的 uiMode。
 *
 * ⚠️ 不做这件事：系统深色 + 用户强制浅色时，状态栏图标是白的、压在浅色页面上看不见。
 * 图标明暗是 `!darkTheme`（深色背景要浅图标），别写反。
 * 预览环境里 `view.context` 不是 Activity，直接跳过。
 */
@Composable
private fun ApplySystemBarAppearance(darkTheme: Boolean) {
    val view = LocalView.current
    DisposableEffect(darkTheme, view) {
        val window = (view.context as? Activity)?.window
        if (window != null) {
            val controller = WindowCompat.getInsetsController(window, view)
            controller.isAppearanceLightStatusBars = !darkTheme
            controller.isAppearanceLightNavigationBars = !darkTheme
        }
        onDispose { }
    }
}
