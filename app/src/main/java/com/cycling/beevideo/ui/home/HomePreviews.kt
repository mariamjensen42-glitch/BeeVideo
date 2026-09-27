package com.cycling.beevideo.ui.home

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import com.cycling.beevideo.R
import com.cycling.beevideo.ui.preview.FakeContentRepository
import com.cycling.beevideo.ui.preview.FakeLibraryRepository
import com.cycling.beevideo.ui.preview.FakeSourceRepository
import com.cycling.beevideo.ui.preview.PreviewViewModelStoreOwner
import com.cycling.beevideo.ui.theme.BeeVideoTheme

@Preview(
    name = "首页 · 手机 411",
    group = "页面",
    showBackground = true,
    backgroundColor = 0xFF0B0A08,
    widthDp = 411,
    heightDp = 891,
)
@Composable
private fun HomeScreenPreview() {
    BeeVideoTheme(darkTheme = true) {
        PreviewViewModelStoreOwner {
            HomeScreen(
                content = FakeContentRepository(),
                sources = FakeSourceRepository.singleSourceReady(),
                // 有历史：这一稿要连「继续观看」模块一起看
                library = FakeLibraryRepository(historyFromDemo = true),
                onVodClick = {},
                onResume = {},
                onOpenHistory = {},
                onOpenSettings = {},
                onOpenSearch = {},
            )
        }
    }
}

@Preview(
    name = "首页 · 浅色",
    group = "页面",
    showBackground = true,
    backgroundColor = 0xFFFFF9EF,
    widthDp = 411,
    heightDp = 891,
)
@Composable
private fun HomeScreenLightPreview() {
    BeeVideoTheme(darkTheme = false) {
        PreviewViewModelStoreOwner {
            HomeScreen(
                content = FakeContentRepository(),
                sources = FakeSourceRepository.singleSourceReady(),
                // 没有历史：用来看"少一个模块"时首屏的节奏
                library = FakeLibraryRepository(),
                onVodClick = {},
                onResume = {},
                onOpenHistory = {},
                onOpenSettings = {},
                onOpenSearch = {},
            )
        }
    }
}

@Preview(
    name = "首页 · 未配置内容源",
    group = "页面",
    showBackground = true,
    backgroundColor = 0xFF0B0A08,
    widthDp = 411,
    heightDp = 700,
)
@Composable
private fun HomeScreenNoSourcePreview() {
    BeeVideoTheme(darkTheme = true) {
        SourceNotice(
            modifier = Modifier.fillMaxSize(),
            title = stringResource(R.string.home_no_source_title),
            body = stringResource(R.string.home_no_source_body),
            actionLabel = stringResource(R.string.home_go_settings),
            onAction = {},
        )
    }
}
