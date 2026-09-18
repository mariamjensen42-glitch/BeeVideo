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
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.core.view.WindowCompat
import com.cycling.beevideo.domain.model.ThemeMode

/**
 * Expressive 形状：M3 的十档圆角刻度，按「想要的圆润程度」取用。
 *
 *   0 / 4 / 8 / 12 / 16 / **20** / 28 / **32** / **48** / full
 *   加粗三档（20 / 32 / 48）是 Expressive 新增的，用来拉开层级差距。
 *
 * 全部用刻度原值，不写 18.dp、24.dp 这类中间值 —— 一旦开了口子，
 * 圆角就会像字号一样失控。
 */
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

/**
 * 排版：**只换字体族，不动刻度**。
 *
 * M3 的 type scale 有**两个字体槽**（typography.md「Brand vs plain typeface」）：
 *   - **Brand** —— display + headline，承担「表达」
 *   - **Plain** —— body + label，承担「可读」
 * Roboto 是两个槽的默认值，**换成别的字体族本身就被 M3 鼓励**
 * （原话：Replacing Roboto boosts brand expression）。
 *
 * 本项目把 brand 槽换成衬线：`FontFamily.Serif` 在 Android 上解析到
 * `NotoSerif-Regular` / `NotoSerifCJK-Regular`（已在装机上确认
 * `/system/etc/fonts.xml` 里有 `fallbackFor="serif"` 的 NotoSerifCJK 条目，
 * 中文能真的换出宋体字形，不是静默回退到黑体）。
 *
 * 为什么是衬线：影视内容天然带「影评 / 刊物」的气质，衬线大字压上去立刻有
 * 版面的感觉；而正文继续用黑体保证小字号可读。M3 允许 baseline 与 emphasized
 * 用**不同**字体族，这里两个集合统一用衬线，省得同一句话在不同强调档里换脸。
 *
 * 尺度和字重一律沿用官方 token —— typography.md 明确说
 * 「Avoid changing type size — it affects how components render and reflow」。
 * 所以这里只是 `copy(fontFamily = ...)`，不是重写 type scale。
 *
 * `titleLarge` 也归进 brand 槽：`MediumFlexibleTopAppBar` 的展开态取
 * `headlineMedium`、收起态取 `titleLarge`，两者之间做插值；而 Compose 的
 * `TextStyle.lerp` 对**不同字体族不做插值**，会在折叠过半时硬跳一下。
 * 两端同族才不会跳。
 *
 * 对同 module 公开（`internal`）是为了让**媒体内容层**也能取到它：
 * 海报上的片名要跟 Hero 刊头同一族，才像同一本刊物。注意用法只能是
 * `style.copy(fontFamily = BeeBrandFont)` —— 仍然不许改 size。
 * （`title` 档按 M3 的划分属于 plain 槽，全 App 默认不换；只有海报这个
 * 明确的「媒体层」才局部借用 brand 字体。）
 */
internal val BeeBrandFont = FontFamily.Serif

private fun Typography.withBrandFont(): Typography = copy(
    displayLarge = displayLarge.copy(fontFamily = BeeBrandFont),
    displayMedium = displayMedium.copy(fontFamily = BeeBrandFont),
    displaySmall = displaySmall.copy(fontFamily = BeeBrandFont),
    displayLargeEmphasized = displayLargeEmphasized.copy(fontFamily = BeeBrandFont),
    displayMediumEmphasized = displayMediumEmphasized.copy(fontFamily = BeeBrandFont),
    displaySmallEmphasized = displaySmallEmphasized.copy(fontFamily = BeeBrandFont),
    headlineLarge = headlineLarge.copy(fontFamily = BeeBrandFont),
    headlineMedium = headlineMedium.copy(fontFamily = BeeBrandFont),
    headlineSmall = headlineSmall.copy(fontFamily = BeeBrandFont),
    headlineLargeEmphasized = headlineLargeEmphasized.copy(fontFamily = BeeBrandFont),
    headlineMediumEmphasized = headlineMediumEmphasized.copy(fontFamily = BeeBrandFont),
    headlineSmallEmphasized = headlineSmallEmphasized.copy(fontFamily = BeeBrandFont),
    titleLarge = titleLarge.copy(fontFamily = BeeBrandFont),
    titleLargeEmphasized = titleLargeEmphasized.copy(fontFamily = BeeBrandFont),
)

/**
 * 官方刻度 + 品牌字体槽。全 App 唯一的排版真相。
 *
 * 排版**不覆盖刻度**，只在官方 `Typography()` 之上换 brand 槽的字体族。
 *
 * 2026-09-15 删除过一次手写的 15 档基准 `Typography` 和 `BeeEmphasized`：
 * `androidx.compose.material3.Typography` 在 alpha28 里已经公开了全部 15 个
 * `*Emphasized` 角色（`headlineMediumEmphasized` …），是稳定 API、不需要 opt-in，
 * 手抄那份等于把官方刻度复制一遍。
 *
 * 用法：正文取 `MaterialTheme.typography.bodyMedium`，
 * 需要强调的那一处取 `MaterialTheme.typography.bodyMediumEmphasized`。
 */
private val BeeTypography: Typography = Typography().withBrandFont()

/**
 * Expressive 动效方案：所有组件动画默认走弹簧，带轻微回弹。
 */
private val BeeMotionScheme = MotionScheme.expressive()

/**
 * 把用户选的三态模式解析成「现在这一帧该不该用深色」。
 *
 * **这里是全 App 唯一读 `isSystemInDarkTheme()` 的地方。**
 *
 * 为什么必须收成一束：这个判断一共被两种"深色"牵着 —— 系统设置、以及用户在
 * 设置页选的那一项。散开写就会各写各的，典型症状是"设置里选了浅色，但状态栏
 * 图标还是白的"（某一处漏了、或者顺序错了）。
 *
 * 组件内部要判断深浅时**不许**再调 `isSystemInDarkTheme()`，而是按当前
 * `colorScheme` 的实际亮度判 —— 见 `HeroCarousel.heroFills()` 与 `Shimmer`
 * 的说明：读系统设置会把「用户强制浅色但系统是深色」这条路径判反。
 */
@Composable
fun ThemeMode.isDark(): Boolean = when (this) {
    ThemeMode.SYSTEM -> isSystemInDarkTheme()
    ThemeMode.LIGHT -> false
    ThemeMode.DARK -> true
}

/**
 * Expressive 主题。
 *
 * [darkTheme] **没有默认值，这是有意的**。
 *
 * 以前它默认 `isSystemInDarkTheme()`，看着方便，但那正是上面那条约束的漏洞：
 * 用户已经在设置里选了「深色」，某个调用点漏传参数就会静默地跟随系统，
 * 于是同一个 App 里出现两套深浅色（`BeeVideoTheme` 一层、页面里另一层）。
 * 现在漏传是**编译错误**，不是线上 bug。
 *
 * 生产入口只有 `MainActivity` 一处：`mode.isDark()`。
 * `@Preview` 一律显式传值，正好也是"不用改系统设置就能出浅色稿"的做法。
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
 * 让状态栏 / 导航栏的图标跟着**应用自己的**深浅色走。
 *
 * 不做这件事的后果很具体：`MainActivity` 里 `enableEdgeToEdge()` 判定图标明暗
 * 用的是**系统**的 uiMode。用户在系统是深色的机器上把 App 强制成浅色之后，
 * 系统仍然认为"现在是夜间"，于是把状态栏图标画成白色 —— 白图标压在浅色的
 * 页面上，**直接看不见**（电量、时间、信号一起消失）。
 *
 * `WindowCompat.getInsetsController` 里的 "Compat" 是必要的：API 30 之前
 * 那两行标志要通过旧的 `Window.setStatusBarColor` + `SYSTEM_UI_FLAG_LIGHT_*`
 * 组合生效，`WindowCompat` 负责抹平这个差别。本项目 minSdk 31，走的已是新路径，
 * 但保持一致总比依赖"我们永远不降 minSdk"更稳。
 *
 * 预览环境里 `view.context` 不是 Activity（是工具链的 BridgeContext），
 * 于是拿到 null 直接跳过 —— 预览本来也不需要真的改窗口。
 */
@Composable
private fun ApplySystemBarAppearance(darkTheme: Boolean) {
    val view = LocalView.current
    DisposableEffect(darkTheme, view) {
        val window = (view.context as? Activity)?.window
        if (window != null) {
            val controller = WindowCompat.getInsetsController(window, view)
            // 深色 → 图标要浅（false）；浅色 → 图标要深（true）。取反，别写反
            controller.isAppearanceLightStatusBars = !darkTheme
            controller.isAppearanceLightNavigationBars = !darkTheme
        }
        onDispose { }
    }
}
