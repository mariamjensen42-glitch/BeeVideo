package com.cycling.beevideo.ui.detail

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bookmark
import androidx.compose.material.icons.outlined.BookmarkBorder
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.MediumFlexibleTopAppBar
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.rememberTopAppBarState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.cycling.beevideo.R
import com.cycling.beevideo.domain.repository.ContentRepository
import com.cycling.beevideo.domain.repository.LibraryRepository
import com.cycling.beevideo.ui.components.BeeBackButton
import com.cycling.beevideo.ui.components.LoadState
import com.cycling.beevideo.ui.components.beeTopAppBarColors
import com.cycling.beevideo.ui.components.metaLine
import com.cycling.beevideo.ui.components.scorePart
import com.cycling.beevideo.ui.components.skeletonSemantics
import com.cycling.beevideo.ui.theme.BeeDimens

/** 详情页。详情是异步取的，所以收 `vodId` 而不是整个 Vod（传对象会让界面拿列表里那份不完整的数据渲染）。 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun DetailScreen(
    content: ContentRepository,
    library: LibraryRepository,
    vodId: String,
    /**
     * 无痕会话里收藏按钮停用。
     *
     * 写入那一步在仓储里**已经**被拦掉了，这个标志只管"让用户看得出按不动"：
     * 一个点了没反应的按钮比一个灰掉的按钮更让人困惑。
     */
    keepEnabled: Boolean,
    onBack: () -> Unit,
    onPlay: (lineIndex: Int, episodeIndex: Int) -> Unit,
) {
    // 挂 ViewModel 是因为主题切换会重建 Activity，而选中的线路必须活下来
    val viewModel: DetailViewModel = viewModel(
        factory = viewModelFactory {
            initializer { DetailViewModel(content, library, vodId) }
        },
    )
    val state = viewModel.state

    val vodState by state.vod.collectAsStateWithLifecycle()
    // 局部变量：by 委托出来的值不能智能转换
    val currentVodState = vodState
    val vod = (vodState as? LoadState.Ready)?.value

    // 每次进页面都重读：从播放页返回时那一页刚写过库。
    // 不订阅数据库是有意的 —— 播放时每秒一次的写入会让返回栈底部这一页跟着重组
    LaunchedEffect(Unit) { state.refreshProgress() }
    val progressState by state.progress.collectAsStateWithLifecycle()
    val progress = (progressState as? LoadState.Ready)?.value

    // 收藏相反：它会被本页的按钮改动，必须订阅才能立刻反映到图标上
    val isKept by state.isKept.collectAsStateWithLifecycle()

    val lineIndex = state.lineIndex
    val notFoundText = stringResource(R.string.detail_not_found)

    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior(
        state = rememberTopAppBarState(),
    )

    Scaffold(
        containerColor = MaterialTheme.colorScheme.surface,
        // nestedScroll 挂 Scaffold：flexible 顶栏的测量高度随滚动真的变小，内容跟着被顶上来
        modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        topBar = {
            MediumFlexibleTopAppBar(
                title = {
                    Text(
                        // 加载中/失败时不占位：正文里有更准确的说明
                        text = when (currentVodState) {
                            is LoadState.Ready -> vod?.name ?: notFoundText
                            else -> ""
                        },
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                },
                subtitle = {
                    if (vod != null) {
                        // 用 metaLine 而不是格式串：没有评分字段时会留下一个没有数值的「分 · 2026」
                        val sub = metaLine(scorePart(vod.score), vod.year)
                        if (sub.isNotEmpty()) Text(text = sub)
                    }
                },
                navigationIcon = { BeeBackButton(onBack) },
                actions = {
                    // 详情到手后才出现：收藏要存片名、封面这些快照字段
                    if (vod != null) {
                        KeepAction(
                            // 无痕时 isKept 恒为 false（仓储那条流），图标自然回到描边态
                            kept = isKept,
                            enabled = keepEnabled,
                            onClick = { state.toggleKeep(vod) },
                        )
                    }
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
            currentVodState is LoadState.Loading -> DetailSkeleton(
                modifier = bodyModifier.skeletonSemantics(
                    stringResource(R.string.detail_loading)
                ),
            )

            currentVodState is LoadState.Failed -> Box(bodyModifier, Alignment.Center) {
                Text(
                    text = stringResource(R.string.detail_load_failed, currentVodState.message),
                    modifier = Modifier.padding(horizontal = BeeDimens.gapHuge),
                    textAlign = TextAlign.Center,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.bodyMedium,
                )
            }

            vod == null -> Box(bodyModifier, Alignment.Center) {
                Text(
                    text = notFoundText,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.bodyMedium,
                )
            }

            else -> DetailBody(
                vod = vod,
                progress = progress,
                lineIndex = lineIndex,
                onSelectLine = state::selectLine,
                onPlay = onPlay,
                modifier = bodyModifier,
            )
        }
    }
}

/** 收藏开关：实心 = 已收藏、描边 = 未收藏。状态变化不能只靠颜色（色觉障碍看不到颜色差）。 */
@Composable
private fun KeepAction(kept: Boolean, enabled: Boolean, onClick: () -> Unit) {
    IconButton(onClick = onClick, enabled = enabled) {
        Icon(
            imageVector = if (kept) Icons.Filled.Bookmark else Icons.Outlined.BookmarkBorder,
            contentDescription = stringResource(
                when {
                    !enabled -> R.string.cd_keep_disabled
                    kept -> R.string.cd_keep_remove
                    else -> R.string.cd_keep_add
                }
            ),
        )
    }
}
