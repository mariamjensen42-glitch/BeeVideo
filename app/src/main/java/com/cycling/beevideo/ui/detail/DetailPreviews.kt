package com.cycling.beevideo.ui.detail

import androidx.compose.runtime.Composable
import androidx.compose.ui.tooling.preview.Preview
import com.cycling.beevideo.domain.model.PlayProgress
import com.cycling.beevideo.ui.preview.FakeContentRepository
import com.cycling.beevideo.ui.preview.FakeLibraryRepository
import com.cycling.beevideo.ui.preview.PreviewViewModelStoreOwner
import com.cycling.beevideo.ui.theme.BeeVideoTheme

// 预览用 ui/preview 里的假实现：真实仓储要 context + 已配置的来源，预览环境两样都没有。

private val previewContent = FakeContentRepository()

/** 线路名必须与预览数据一致 —— 判据是「线路名 + 集号同时对上」，写错的话预览里什么都看不到。 */
private val previewLibraryWithProgress = FakeLibraryRepository(
    progress = PlayProgress(
        vodId = "v01",
        lineName = "线路一 · 演示",
        episodeIndex = 2,
        episodeName = "第 03 集",
        positionMs = 600_000L,
        durationMs = 2_700_000L,
        updatedAt = 1_700_000_000_000L,
    ),
)

@Preview(
    name = "详情 · 手机",
    group = "页面",
    showBackground = true,
    backgroundColor = 0xFF0B0A08,
    widthDp = 411,
    heightDp = 891,
)
@Composable
private fun DetailScreenPreview() {
    BeeVideoTheme(darkTheme = true) {
        PreviewViewModelStoreOwner {
            DetailScreen(
                content = previewContent,
                library = previewLibraryWithProgress,
                vodId = "v01",
                keepEnabled = true,
                onBack = {},
                onPlay = { _, _ -> },
            )
        }
    }
}

@Preview(
    name = "详情 · 条目不存在",
    group = "页面",
    showBackground = true,
    backgroundColor = 0xFF0B0A08,
    widthDp = 411,
    heightDp = 300,
)
@Composable
private fun DetailScreenNotFoundPreview() {
    BeeVideoTheme(darkTheme = true) {
        PreviewViewModelStoreOwner {
            DetailScreen(
                content = previewContent,
                library = FakeLibraryRepository(),
                vodId = "not-exist",
                keepEnabled = true,
                onBack = {},
                onPlay = { _, _ -> },
            )
        }
    }
}

@Preview(
    name = "详情 · 浅色",
    group = "页面",
    showBackground = true,
    backgroundColor = 0xFFFFF9EF,
    widthDp = 411,
    heightDp = 891,
)
@Composable
private fun DetailScreenLightPreview() {
    BeeVideoTheme(darkTheme = false) {
        DetailScreen(
            content = previewContent,
            library = previewLibraryWithProgress,
            vodId = "v01",
            keepEnabled = true,
            onBack = {},
            onPlay = { _, _ -> },
        )
    }
}

/**
 * 无痕会话：收藏按钮是禁用态（灰掉的书签）。
 *
 * 这一稿刻意配**空**的仓储：无痕下 `isKept` 恒为 false、续播位置也不给，
 * 用带进度的那份假数据来看会与真实行为对不上。
 */
@Preview(
    name = "详情 · 无痕（收藏停用）",
    group = "页面",
    showBackground = true,
    backgroundColor = 0xFF0B0A08,
    widthDp = 411,
    heightDp = 891,
)
@Composable
private fun DetailScreenIncognitoPreview() {
    BeeVideoTheme(darkTheme = true) {
        PreviewViewModelStoreOwner {
            DetailScreen(
                content = previewContent,
                library = FakeLibraryRepository(),
                vodId = "v01",
                keepEnabled = false,
                onBack = {},
                onPlay = { _, _ -> },
            )
        }
    }
}
