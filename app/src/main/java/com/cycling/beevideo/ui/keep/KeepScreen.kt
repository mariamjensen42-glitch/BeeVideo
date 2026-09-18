package com.cycling.beevideo.ui.keep

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Bookmarks
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.MediumFlexibleTopAppBar
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.rememberTopAppBarState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.cycling.beevideo.R
import com.cycling.beevideo.domain.model.KeepItem
import com.cycling.beevideo.domain.repository.LibraryRepository
import com.cycling.beevideo.ui.components.PosterCard
import com.cycling.beevideo.ui.preview.FakeLibraryRepository
import com.cycling.beevideo.ui.components.SkeletonPosterGrid
import com.cycling.beevideo.ui.components.beeTopAppBarColors
import com.cycling.beevideo.ui.components.skeletonSemantics
import com.cycling.beevideo.ui.theme.BeeDimens
import com.cycling.beevideo.ui.theme.BeeVideoTheme

/** 未知期的海报墙骨架行数。3 行 9 张正好铺满一屏，与真实网格 3 列同形。 */
private const val KEEP_SKELETON_ROWS = 3

/**
 * 收藏页。
 *
 * **三个状态，不是两个**：「还没读到」和「读到了、但是空的」必须分开。合成"列表为空就显示
 * 空态"的话，进这一页第一帧会闪一下「还没有收藏」再冒出内容 —— 本地库只要几毫秒，但恰恰
 * 是用户盯着屏幕的那几毫秒，看起来像"收藏丢了"。所以初始值是 `null`（未知）。
 *
 * 未知期画的是**海报墙骨架**而不是转圈：它和真实网格同形，所以即使只闪一两帧，
 * 读起来也是"版式已经在了、内容正在填"，而不是"先出现一个圈、再换成一堵墙"
 * —— 后者那种形状突变比闪一下更刺眼。
 *
 * 用 Flow 订阅而不是读一次：用户可能在这一页停留时从别处改了收藏，靠 Flow 那张卡才会
 * 立刻消失，不需要下拉刷新。
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun KeepScreen(
    library: LibraryRepository,
    onVodClick: (String) -> Unit,
) {
    // null = 还不知道（区别于"读到了、是空的"）
    val keeps: List<KeepItem>? by library.keeps.collectAsStateWithLifecycle(initialValue = null)

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
            keeps == null -> SkeletonPosterGrid(
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

            keeps.isNullOrEmpty() -> KeepEmptyState(bodyModifier)

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
                items(keeps.orEmpty(), key = { it.vodId }) { item ->
                    // 用字段版重载：收藏夹里是快照，不该为了显示而去凑一个完整 Vod
                    PosterCard(
                        id = item.vodId,
                        name = item.name,
                        pic = item.pic,
                        score = item.score,
                        remarks = item.remarks,
                        onClick = { onVodClick(item.vodId) },
                    )
                }
            }
        }
    }
}

/** 空态：图标放进一个圆形容器做视觉锚点，标题用强调排版，说明文字退回基准 body medium。 */
@Composable
private fun KeepEmptyState(modifier: Modifier = Modifier) {
    Box(modifier = modifier, contentAlignment = Alignment.Center) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier
                .padding(horizontal = BeeDimens.screenMargin)
                .widthIn(max = 320.dp),
        ) {
            Surface(
                shape = CircleShape,
                // 用中性的容器色做锚点，不抢 primary 的注意力
                color = MaterialTheme.colorScheme.secondaryContainer,
                contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
                modifier = Modifier.size(96.dp),
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(
                        imageVector = Icons.Outlined.Bookmarks,
                        contentDescription = null,
                        // 40dp：Material Symbols 的尺寸档只有 20 / 24 / 40 / 48，36dp 不在刻度上
                        modifier = Modifier.size(40.dp),
                    )
                }
            }
            Spacer(Modifier.height(BeeDimens.gapLarge))
            Text(
                text = stringResource(R.string.keep_empty_title),
                // 空态标题是这一屏唯一的排版主体
                style = MaterialTheme.typography.headlineSmallEmphasized,
                color = MaterialTheme.colorScheme.onSurface,
                textAlign = TextAlign.Center,
            )
            Spacer(Modifier.height(BeeDimens.gapTiny))
            Text(
                text = stringResource(R.string.keep_empty_body),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
        }
    }
}

// ------------------------------------------------------------------ 预览

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
            library = FakeLibraryRepository(keepsFromDemo = true),
            onVodClick = {},
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
        KeepScreen(library = FakeLibraryRepository(), onVodClick = {})
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
            library = FakeLibraryRepository(keepsFromDemo = true),
            onVodClick = {},
        )
    }
}
