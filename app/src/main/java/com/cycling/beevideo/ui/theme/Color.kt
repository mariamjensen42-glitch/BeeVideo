package com.cycling.beevideo.ui.theme

import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.ui.graphics.Color

/*
 * 颜色按 M3 的「角色」组织，深/浅两套共用同一条暖色带：primary 是蜂蜜琥珀，
 * 中性色取它的色相同源、彩度压到很低，于是表面色永远带一点暖味。
 *
 * 明暗是 M3 的镜像：primary 深 80 / 浅 40，onPrimary 深 20 / 浅 100，
 * primaryContainer 深 30 / 浅 84，surface 深 6 / 浅 98，onSurface 深 90 / 浅 10。
 * 所有「容器 + on 容器」组合都逐对验过对比度，正文级 ≥4.5:1。
 *
 * 唯一的例外是文件末尾四张「媒体色」—— 它们跟主题无关，两套共用。
 */

// ================================================================ 深色

val BeeDarkScheme = darkColorScheme(
    // 主色组（蜂蜜琥珀）
    primary = Color(0xFFFFC107),
    onPrimary = Color(0xFF241A00),
    primaryContainer = Color(0xFF5C4200),
    onPrimaryContainer = Color(0xFFFFDF9E),

    // 副色组（暖中性灰）：承担"不需要立即注意"的元素。取暖中性灰而不是冷蓝灰 ——
    // 冷蓝会和暖底打架，而副色本来就该是压低的中性，不该自己抢一个色相
    secondary = Color(0xFFC6BFAE),
    onSecondary = Color(0xFF302C22),
    secondaryContainer = Color(0xFF4A4436),
    onSecondaryContainer = Color(0xFFE2DBCA),

    // 第三色组（暖橙红）：评分角标等小的强调元素。与 primary 是邻近色，不会散
    tertiary = Color(0xFFFFB4A2),
    onTertiary = Color(0xFF5A1809),
    tertiaryContainer = Color(0xFF6E2A1B),
    onTertiaryContainer = Color(0xFFFFDAD6),

    error = Color(0xFFFFB4AB),
    onError = Color(0xFF690005),
    errorContainer = Color(0xFF93000A),
    onErrorContainer = Color(0xFFFFDAD6),

    background = Color(0xFF0B0A08),
    onBackground = Color(0xFFF4F1EA),
    surface = Color(0xFF0B0A08),
    onSurface = Color(0xFFF4F1EA),
    surfaceVariant = Color(0xFF48433A),
    onSurfaceVariant = Color(0xFFC3BAA9),

    // 五级表面容器，用来做区块层级（containment 块 = containerLow）
    surfaceContainerLowest = Color(0xFF060605),
    surfaceContainerLow = Color(0xFF151311),
    surfaceContainer = Color(0xFF1C1915),
    surfaceContainerHigh = Color(0xFF272420),
    surfaceContainerHighest = Color(0xFF332F29),
    surfaceDim = Color(0xFF0B0A08),
    surfaceBright = Color(0xFF3F3A32),

    outline = Color(0xFF978F80),
    outlineVariant = Color(0xFF48433A),

    inverseSurface = Color(0xFFF4F1EA),
    inverseOnSurface = Color(0xFF1D1A16),
    inversePrimary = Color(0xFF8A6200),
    scrim = Color(0xFF000000),
)

// ================================================================ 浅色

val BeeLightScheme = lightColorScheme(
    // 浅色下 primary 必须深到 tone 40，否则琥珀当文字色读不出来；亮琥珀退回 container
    primary = Color(0xFF7A5900),
    onPrimary = Color(0xFFFFFFFF),
    // ⚠️ 不用 M3 默认的 tone 90：黄色系的 tone 90（#FFDF9E）亮度极高，压在 surface
    // (tone 98) 上对比度只有 1.23 —— 选中态的剧集格等于看不出来。取 tone 84 后
    // 对 surface 分离度 1.49，onPrimaryContainer 仍有 10.9:1
    primaryContainer = Color(0xFFF7C94F),
    onPrimaryContainer = Color(0xFF261A00),

    secondary = Color(0xFF635C4C),
    onSecondary = Color(0xFFFFFFFF),
    // 同理，比 tone 90 深一档，否则导航栏指示器在浅色底上看不见
    secondaryContainer = Color(0xFFDED0AC),
    onSecondaryContainer = Color(0xFF1F1B0E),

    tertiary = Color(0xFF7F5539),
    onTertiary = Color(0xFFFFFFFF),
    tertiaryContainer = Color(0xFFFFDCC6),
    onTertiaryContainer = Color(0xFF301400),

    error = Color(0xFFBA1A1A),
    onError = Color(0xFFFFFFFF),
    errorContainer = Color(0xFFFFDAD6),
    onErrorContainer = Color(0xFF410002),

    background = Color(0xFFFFF9EF),
    onBackground = Color(0xFF1E1B13),
    surface = Color(0xFFFFF9EF),
    onSurface = Color(0xFF1E1B13),
    surfaceVariant = Color(0xFFEAE1CC),
    onSurfaceVariant = Color(0xFF4C4639),

    // 五级表面容器。浅色下是"越靠前越深"，与深色相反
    surfaceContainerLowest = Color(0xFFFFFFFF),
    surfaceContainerLow = Color(0xFFFBF2E4),
    surfaceContainer = Color(0xFFF5EDDD),
    surfaceContainerHigh = Color(0xFFEFE7D7),
    surfaceContainerHighest = Color(0xFFE9E1D1),
    surfaceDim = Color(0xFFE0D8C8),
    surfaceBright = Color(0xFFFFF9EF),

    outline = Color(0xFF7E7667),
    outlineVariant = Color(0xFFCFC5B2),

    inverseSurface = Color(0xFF33302A),
    inverseOnSurface = Color(0xFFF6F0E2),
    inversePrimary = Color(0xFFFFC107),
    scrim = Color(0xFF000000),
)

// ================================================================ 媒体色（与主题无关）

/*
 * 下面四个**不进 ColorScheme**，两种主题共用。
 * 它们是贴在「媒体」上的颜色，不是界面色：
 *   - 播放器画面底永远黑（浅色主题下一块白底夹着 16:9 画面很刺眼）；
 *   - 海报是封面图，图中文字必须自带对比度，不能跟着主题翻转。
 */
val PlayerSurface = Color(0xFF000000)

val PosterTextPrimary = Color(0xF0FFFFFF)
val PosterTextSecondary = Color(0xC7FFFFFF)

/** 海报底部压暗层，保证叠在图上的文字可读（M3 要求图上文字加 scrim） */
val PosterScrim = Color(0x99000000)
