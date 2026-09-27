package com.cycling.beevideo.ui.player

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import com.cycling.beevideo.ui.preview.PreviewVods
import com.cycling.beevideo.ui.theme.BeeVideoTheme
import com.cycling.beevideo.ui.theme.PlayerSurface

// 播放器在预览环境跑不起来，画面一律用纯色块代替。

@Preview(
    name = "播放页 · 布局（无播放器）",
    group = "页面",
    showBackground = true,
    backgroundColor = 0xFF0B0A08,
    widthDp = 411,
    heightDp = 891,
)
@Composable
private fun PlayerScaffoldPreview() {
    val vod = PreviewVods.vods.first()
    val line = vod.lines.first()
    BeeVideoTheme(darkTheme = true) {
        PlayerScaffold(
            state = PlayerUiState(
                title = vod.name,
                lineName = line.name,
                episodeName = line.episodes[2].name,
                episodes = line.episodes,
                currentIndex = 2,
                statusText = "就绪",
                urlText = line.episodes[2].url,
                isBuffering = false,
                lines = vod.lines,
                currentLineIndex = 0,
            ),
            onSelectLine = {},
            onSelectEpisode = {},
            onPrev = {},
            onNext = {},
            onBack = {},
            onInfoClick = {},
            isFullscreen = false,
            player = { modifier -> Box(modifier.background(PlayerSurface)) },
        )
    }
}

@Preview(
    name = "播放页 · 横屏全屏",
    group = "页面",
    showBackground = true,
    backgroundColor = 0xFF0B0A08,
    widthDp = 891,
    heightDp = 411,
)
@Composable
private fun PlayerScaffoldFullscreenPreview() {
    val vod = PreviewVods.vods.first()
    val line = vod.lines.first()
    BeeVideoTheme(darkTheme = true) {
        PlayerScaffold(
            state = PlayerUiState(
                title = vod.name,
                lineName = line.name,
                episodeName = line.episodes[2].name,
                episodes = line.episodes,
                currentIndex = 2,
                statusText = "就绪",
                urlText = line.episodes[2].url,
                isBuffering = false,
                lines = vod.lines,
                currentLineIndex = 0,
            ),
            onSelectLine = {},
            onSelectEpisode = {},
            onPrev = {},
            onNext = {},
            onBack = {},
            onInfoClick = {},
            isFullscreen = true,
            player = { modifier -> Box(modifier.background(PlayerSurface)) },
        )
    }
}

@Preview(
    name = "播放页 · 无可播放剧集",
    group = "页面",
    showBackground = true,
    backgroundColor = 0xFF0B0A08,
    widthDp = 411,
    heightDp = 700,
)
@Composable
private fun PlayerScaffoldEmptyPreview() {
    BeeVideoTheme(darkTheme = true) {
        PlayerScaffold(
            state = PlayerUiState(
                title = "空态",
                lineName = "",
                episodeName = "无可播放剧集",
                episodes = emptyList(),
                currentIndex = 0,
                statusText = "这个源没有给出可播放的地址",
                urlText = "—",
                isBuffering = false,
            ),
            onSelectLine = {},
            onSelectEpisode = {},
            onPrev = {},
            onNext = {},
            onBack = {},
            onInfoClick = {},
            isFullscreen = false,
            player = { modifier -> Box(modifier.background(PlayerSurface)) },
        )
    }
}

@Preview(
    name = "播放信息 · 全屏对话框",
    group = "页面",
    showBackground = true,
    backgroundColor = 0xFF0B0A08,
    widthDp = 411,
    heightDp = 891,
)
@Composable
private fun PlayInfoDialogPreview() {
    BeeVideoTheme(darkTheme = true) {
        PlayInfoDialog(
            title = "播放信息",
            lineName = "线路 1",
            episodeName = "第 3 集",
            url = "https://example.com/demo.m3u8",
            state = "就绪",
            onDismiss = {},
        )
    }
}
