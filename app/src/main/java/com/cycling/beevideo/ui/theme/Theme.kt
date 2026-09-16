package com.cycling.beevideo.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialExpressiveTheme
import androidx.compose.material3.MotionScheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.runtime.Composable
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp

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

/** 官方刻度 + 品牌字体槽。全 App 唯一的排版真相。 */
private val BeeTypography: Typography = Typography().withBrandFont()

/**
 * Expressive 动效方案：所有组件动画默认走弹簧，带轻微回弹。
 */
private val BeeMotionScheme = MotionScheme.expressive()

/**
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
@Composable
fun BeeVideoTheme(
    /**
     * 默认**跟随系统**的深浅色设置。
     *
     * 显式暴露成参数是为了让 `@Preview` 能各自锁一种：
     * `BeeVideoTheme(darkTheme = false)` 出浅色稿，不用改系统设置或跑两台机器。
     */
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    val colorScheme: ColorScheme = if (darkTheme) BeeDarkScheme else BeeLightScheme
    MaterialExpressiveTheme(
        colorScheme = colorScheme,
        motionScheme = BeeMotionScheme,
        shapes = BeeShapes,
        typography = BeeTypography,
        content = content,
    )
}
