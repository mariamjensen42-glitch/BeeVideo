package com.cycling.beevideo.ui.settings

import androidx.compose.runtime.Composable
import androidx.compose.ui.tooling.preview.Preview
import com.cycling.beevideo.domain.model.ThemeMode
import com.cycling.beevideo.ui.preview.FakeDecoderMonitor
import com.cycling.beevideo.ui.preview.FakeMediaCache
import com.cycling.beevideo.ui.preview.FakePlaybackSettings
import com.cycling.beevideo.ui.preview.FakeSourceRepository
import com.cycling.beevideo.ui.preview.PreviewViewModelStoreOwner
import com.cycling.beevideo.ui.theme.BeeVideoTheme

@Preview(
    name = "设置 · 手机",
    group = "页面",
    showBackground = true,
    backgroundColor = 0xFF0B0A08,
    widthDp = 411,
    heightDp = 891,
)
@Composable
private fun SettingsScreenPreview() {
    BeeVideoTheme(darkTheme = true) {
        PreviewViewModelStoreOwner {
            SettingsScreen(
                sources = FakeSourceRepository.threeSourcesReady(),
                settings = FakePlaybackSettings(),
                // 给一个非零占用，才看得见"已用 1.2 GB"那一行的排版
                cache = FakeMediaCache(usage = 1_288_490_188L),
                decoderMonitor = FakeDecoderMonitor(),
                themeMode = ThemeMode.SYSTEM,
                onThemeModeChange = {},
                incognito = false,
                onIncognitoChange = {},
            )
        }
    }
}

@Preview(
    name = "设置 · 手机 · 浅色",
    group = "页面",
    showBackground = true,
    backgroundColor = 0xFFFFF9EF,
    widthDp = 411,
    heightDp = 891,
)
@Composable
private fun SettingsScreenLightPreview() {
    BeeVideoTheme(darkTheme = false) {
        PreviewViewModelStoreOwner {
            SettingsScreen(
                sources = FakeSourceRepository.threeSourcesReady(),
                settings = FakePlaybackSettings(),
                cache = FakeMediaCache(usage = 1_288_490_188L),
                decoderMonitor = FakeDecoderMonitor(),
                themeMode = ThemeMode.LIGHT,
                onThemeModeChange = {},
                incognito = false,
                onIncognitoChange = {},
            )
        }
    }
}

/** 上面两稿都是 3 个等长名字，看不出网格与横滑行的区别；这一稿 24 个才是要核对的形态。 */
@Preview(
    name = "设置 · 手机 · 站点网格（24 个站点）",
    group = "页面",
    showBackground = true,
    backgroundColor = 0xFF0B0A08,
    widthDp = 411,
    heightDp = 1400,
)
@Composable
private fun SettingsScreenManySourcesPreview() {
    BeeVideoTheme(darkTheme = true) {
        PreviewViewModelStoreOwner {
            SettingsScreen(
                sources = FakeSourceRepository.manySourcesReady(),
                settings = FakePlaybackSettings(),
                cache = FakeMediaCache(usage = 1_288_490_188L),
                decoderMonitor = FakeDecoderMonitor(),
                themeMode = ThemeMode.SYSTEM,
                onThemeModeChange = {},
                incognito = false,
                onIncognitoChange = {},
            )
        }
    }
}

/** 无痕开启：开关亮着，下面那句"关掉时会删掉什么"是这个模式最需要说清的一句话。 */
@Preview(
    name = "设置 · 手机 · 无痕开启",
    group = "页面",
    showBackground = true,
    backgroundColor = 0xFF0B0A08,
    widthDp = 411,
    heightDp = 891,
)
@Composable
private fun SettingsScreenIncognitoPreview() {
    BeeVideoTheme(darkTheme = true) {
        PreviewViewModelStoreOwner {
            SettingsScreen(
                sources = FakeSourceRepository.threeSourcesReady(),
                settings = FakePlaybackSettings(),
                cache = FakeMediaCache(usage = 1_288_490_188L),
                decoderMonitor = FakeDecoderMonitor(),
                themeMode = ThemeMode.SYSTEM,
                onThemeModeChange = {},
                incognito = true,
                onIncognitoChange = {},
            )
        }
    }
}
