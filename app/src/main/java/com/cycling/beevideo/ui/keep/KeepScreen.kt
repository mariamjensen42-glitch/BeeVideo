package com.cycling.beevideo.ui.keep

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Bookmarks
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.MediumFlexibleTopAppBar
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.rememberTopAppBarState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import com.cycling.beevideo.R
import com.cycling.beevideo.ui.components.BeeEmptyState
import com.cycling.beevideo.ui.components.PosterCard
import com.cycling.beevideo.ui.components.SkeletonPosterGrid
import com.cycling.beevideo.ui.components.beeTopAppBarColors
import com.cycling.beevideo.ui.components.skeletonSemantics
import com.cycling.beevideo.ui.preview.PreviewKeeps
import com.cycling.beevideo.ui.theme.BeeDimens
import com.cycling.beevideo.ui.theme.BeeVideoTheme

/** 未知期的海报墙骨架行数。3 行 9 张正好铺满一屏，与真实网格 3 列同形。 */
private const val KEEP_SKELETON_ROWS = 3

/**
 * 收藏页。纯渲染 —— 数据全从 [uiState] 来，动作全走 [onIntent]。
 *
 * 未知期画的是**海报墙骨架**而不是转圈：它和真实网格同形，所以即使只闪一两帧，
 * 读起来也是"版式已经在了、内容正在填"，而不是"先出现一个圈、再换成一堵墙"
 * —— 后者那种形状突变比闪一下更刺眼。
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun KeepScreen(
    uiState: KeepUiState,
    onIntent: (KeepIntent) -> Unit,
) {
    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior(
        state = rememberTopAppBarState(),
    )

    Scaffold(
        containerColor = MaterialTheme.colorScheme.surface,
        modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        topBar = {
            MediumFlexibleTopAppBar(
                title = {
                    Text(text = stringResource(R.string.keep_title))
                },
                scrollBehavior = scrollBehavior,
                colors = beeTopAppBarColors(),
            )
        },
    ) { innerPadding ->
        val bodyModifier = Modifier
            .fillMaxSize()
            .padding(innerPadding)

        when {
            uiState.loading -> SkeletonPosterGrid(
                modifier = bodyModifier
                    .padding(
                        start = BeeDimens.screenMargin,
                        end = BeeDimens.screenMargin,
                        top = BeeDimens.gapSmall,
                    )
                    // 骨架块没有文字节点，加载说明只能走 contentDescription，
                    // 否则读屏在这一页是一段静默
                    .skeletonSemantics(stringResource(R.string.keep_loading)),
                rows = KEEP_SKELETON_ROWS,
            )

            uiState.keeps.isEmpty() -> KeepEmptyState(bodyModifier)

            else -> LazyVerticalGrid(
                columns = GridCells.Fixed(BeeDimens.posterColumns),
                modifier = bodyModifier,
                contentPadding = PaddingValues(
                    start = BeeDimens.screenMargin,
                    end = BeeDimens.screenMargin,
                    top = BeeDimens.gapSmall,
                    bottom = BeeDimens.gapHuge,
                ),
                horizontalArrangement = Arrangement.spacedBy(BeeDimens.gapSmall),
                verticalArrangement = Arrangement.spacedBy(BeeDimens.gapMedium),
            ) {
                items(uiState.keeps, key = { it.vodId }) { item ->
                    // 用字段版重载：收藏夹里是快照，不该为了显示而去凑一个完整 Vod
                    PosterCard(
                        id = item.vodId,
                        name = item.name,
                        pic = item.pic,
                        score = item.score,
                        remarks = item.remarks,
                        onClick = { onIntent(KeepIntent.OnOpenVod(item.vodId)) },
                    )
                }
            }
        }
    }
}

/** 空态。图标放进一个圆形容器做视觉锚点，标题用强调排版，说明文字退回基准 body medium。 */
@Composable
private fun KeepEmptyState(modifier: Modifier = Modifier) {
    BeeEmptyState(
        icon = Icons.Outlined.Bookmarks,
        title = stringResource(R.string.keep_empty_title),
        body = stringResource(R.string.keep_empty_body),
        modifier = modifier,
    )
}

// ------------------------------------------------------------------ 预览

/*
 * 预览直接喂状态，不再需要假仓储 —— 这正是把界面从仓储上摘下来的收益。
 * 三个预览各自对应一件要看的事：有内容、空态、浅色下的配色。
 */

@Preview(
    name = "收藏 · 有内容",
    group = "页面",
    showBackground = true,
    backgroundColor = 0xFF0B0A08,
    widthDp = 411,
    heightDp = 891,
)
@Composable
private fun KeepScreenPreview() {
    BeeVideoTheme(darkTheme = true) {
        KeepScreen(
            uiState = KeepUiState(loading = false, keeps = PreviewKeeps.items),
            onIntent = {},
        )
    }
}

@Preview(
    name = "收藏 · 空态",
    group = "页面",
    showBackground = true,
    backgroundColor = 0xFF0B0A08,
    widthDp = 411,
    heightDp = 891,
)
@Composable
private fun KeepScreenEmptyPreview() {
    BeeVideoTheme(darkTheme = true) {
        KeepScreen(uiState = KeepUiState(loading = false), onIntent = {})
    }
}

@Preview(
    name = "收藏 · 浅色",
    group = "页面",
    showBackground = true,
    backgroundColor = 0xFFFFF9EF,
    widthDp = 411,
    heightDp = 891,
)
@Composable
private fun KeepScreenLightPreview() {
    BeeVideoTheme(darkTheme = false) {
        KeepScreen(
            uiState = KeepUiState(loading = false, keeps = PreviewKeeps.items),
            onIntent = {},
        )
    }
}

@Preview(
    name = "收藏 · 加载中",
    group = "页面",
    showBackground = true,
    backgroundColor = 0xFF0B0A08,
    widthDp = 411,
    heightDp = 891,
)
@Composable
private fun KeepScreenLoadingPreview() {
    BeeVideoTheme(darkTheme = true) {
        KeepScreen(uiState = KeepUiState(), onIntent = {})
    }
}
