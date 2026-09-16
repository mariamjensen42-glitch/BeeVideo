package com.cycling.beevideo.ui.keep

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Bookmarks
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.MediumFlexibleTopAppBar
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.cycling.beevideo.R
import com.cycling.beevideo.ui.components.beeTopAppBarColors
import com.cycling.beevideo.ui.theme.BeeDimens
import com.cycling.beevideo.ui.theme.BeeVideoTheme

/**
 * 收藏页 —— 目前是空态。
 *
 * 空态也要讲层级：图标放进一个「容器」里做视觉锚点（containment 手法），
 * 标题用强调排版，说明文字退回基准 body medium。
 *
 * 顶栏不传 scrollBehavior —— 这一页没有可滚动内容，传了也只是让它恒定展开，
 * 不如显式保持展开态（112dp），反而给空态一个稳的头部。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun KeepScreen() {
    Scaffold(
        containerColor = MaterialTheme.colorScheme.surface,
        topBar = {
            MediumFlexibleTopAppBar(
                title = {
                    Text(text = stringResource(R.string.keep_title))
                },
                colors = beeTopAppBarColors(),
            )
        },
    ) { innerPadding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding),
            contentAlignment = Alignment.Center,
        ) {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier = Modifier
                    .padding(horizontal = BeeDimens.screenMargin)
                    .widthIn(max = 320.dp),
            ) {
                Surface(
                    shape = CircleShape,
                    // 空态锚点用 secondary container —— 它是「中性的容器」，
                    // 不抢 primary 的注意力，又比 surface container highest 有存在感。
                    color = MaterialTheme.colorScheme.secondaryContainer,
                    contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
                    modifier = Modifier.size(96.dp),
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Icon(
                            imageVector = Icons.Outlined.Bookmarks,
                            contentDescription = null,
                            /*
                             * 40dp —— Material Symbols 的尺寸档只有 20 / 24 / 40 / 48，
                             * 36dp 不在刻度上。40dp 正是「需要被强调的当眼图形」那一档。
                             */
                            modifier = Modifier.size(40.dp),
                        )
                    }
                }
                Spacer(Modifier.height(BeeDimens.gapLarge))
                Text(
                    text = stringResource(R.string.keep_empty_title),
                    // 空态标题是这一屏唯一的排版主体，值一个 headline 档
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
}

// ------------------------------------------------------------------ 预览

@Preview(
    name = "收藏 · 空态",
    group = "页面",
    showBackground = true,
    backgroundColor = 0xFF0B0A08,
    widthDp = 411,
    heightDp = 891,
)
@Composable
private fun KeepScreenPreview() {
    BeeVideoTheme(darkTheme = true) {
        KeepScreen()
    }
}

@Preview(
    name = "收藏 · 空态 · 浅色",
    group = "页面",
    showBackground = true,
    backgroundColor = 0xFFFFF9EF,
    widthDp = 411,
    heightDp = 891,
)
@Composable
private fun KeepScreenLightPreview() {
    BeeVideoTheme(darkTheme = false) {
        KeepScreen()
    }
}
