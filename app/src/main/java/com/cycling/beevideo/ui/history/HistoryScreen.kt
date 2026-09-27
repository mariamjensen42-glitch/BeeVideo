package com.cycling.beevideo.ui.history

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bookmark
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.outlined.BookmarkBorder
import androidx.compose.material.icons.outlined.DeleteOutline
import androidx.compose.material.icons.outlined.DeleteSweep
import androidx.compose.material.icons.outlined.History
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.MediumFlexibleTopAppBar
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.rememberTopAppBarState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.cycling.beevideo.R
import com.cycling.beevideo.ui.components.BeeBackButton
import com.cycling.beevideo.ui.components.BeeEmptyState
import com.cycling.beevideo.ui.components.ContainmentBlock
import com.cycling.beevideo.ui.components.SkeletonBlock
import com.cycling.beevideo.ui.components.beeTopAppBarColors
import com.cycling.beevideo.ui.components.skeletonSemantics
import com.cycling.beevideo.ui.preview.PreviewHistory
import com.cycling.beevideo.ui.theme.BeeDimens
import com.cycling.beevideo.ui.theme.BeeVideoTheme

/** 未知期的骨架行数。4 行 128dp 正好铺满一屏，与真实列表同形。 */
private const val HISTORY_SKELETON_ROWS = 4

/**
 * 观看历史页。纯渲染 —— 数据全从 [uiState] 来，动作全走 [onIntent]。
 *
 * 它不是"播放列表"，而是**按影片聚合的进度管理页**：一部剧一条，每条回答「上次看到哪」，
 * 点一下就从那儿接着播。聚合由主键保证（`history.vodId` 是主键），不是界面去分组。
 *
 * 未知期画的是**列表骨架**而不是转圈：它与真实行同形，于是读起来是"版式已经在了、
 * 内容正在填"，而不是"先出现一个圈、再换成一列"。
 *
 * ─── 留在本层的两处状态 ────────────────────────────────────────────────
 * `menuFor`（哪个长按菜单开着）与 `confirmClear`（清空确认框）是**局部 UI 状态**，
 * 不是业务状态：它们不参与数据流，重建后关掉才对。所以用 `remember` 而不是进 UiState。
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun HistoryScreen(
    uiState: HistoryUiState,
    onIntent: (HistoryIntent) -> Unit,
) {
    var menuFor by remember { mutableStateOf<String?>(null) }
    var confirmClear by rememberSaveable { mutableStateOf(false) }

    /*
     * 「今天 / 昨天」的参照时刻。列表变化时重算一次 —— 停在历史页跨过午夜之后，
     * 昨天看的那条应该跟着变成"昨天"，而不是一直停在进来时算出来的"今天"。
     */
    val now = remember(uiState.records) { System.currentTimeMillis() }

    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior(
        state = rememberTopAppBarState(),
    )

    Scaffold(
        containerColor = MaterialTheme.colorScheme.surface,
        modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        topBar = {
            MediumFlexibleTopAppBar(
                title = { Text(text = stringResource(R.string.history_title)) },
                navigationIcon = { BeeBackButton { onIntent(HistoryIntent.OnBack) } },
                actions = {
                    // 空列表时不画：一个点了没反应的按钮比没有按钮更让人困惑
                    if (uiState.records.isNotEmpty()) {
                        IconButton(onClick = { confirmClear = true }) {
                            Icon(
                                imageVector = Icons.Outlined.DeleteSweep,
                                contentDescription = stringResource(R.string.history_clear),
                            )
                        }
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
            uiState.loading -> HistorySkeleton(
                modifier = bodyModifier
                    .padding(
                        start = BeeDimens.screenMargin,
                        end = BeeDimens.screenMargin,
                        top = BeeDimens.gapSmall,
                    )
                    .skeletonSemantics(stringResource(R.string.history_loading)),
            )

            uiState.records.isEmpty() -> BeeEmptyState(
                icon = Icons.Outlined.History,
                title = stringResource(R.string.history_empty_title),
                body = stringResource(R.string.history_empty_body),
                modifier = bodyModifier,
            )

            else -> LazyColumn(
                modifier = bodyModifier,
                contentPadding = PaddingValues(
                    start = BeeDimens.screenMargin,
                    end = BeeDimens.screenMargin,
                    top = BeeDimens.gapSmall,
                    bottom = BeeDimens.gapHuge,
                ),
                verticalArrangement = Arrangement.spacedBy(BeeDimens.gapSmall),
            ) {
                items(uiState.records, key = { it.vodId }) { item ->
                    HistoryRow(
                        progress = item,
                        sourceName = uiState.sourceNames[item.vodId.substringBefore(':')].orEmpty(),
                        now = now,
                        onClick = { onIntent(HistoryIntent.OnContinue(item)) },
                        onLongClick = { menuFor = item.vodId },
                        menu = {
                            HistoryMenu(
                                expanded = menuFor == item.vodId,
                                kept = item.vodId in uiState.keptIds,
                                onDismiss = { menuFor = null },
                                onContinue = {
                                    menuFor = null
                                    onIntent(HistoryIntent.OnContinue(item))
                                },
                                onOpenDetail = {
                                    menuFor = null
                                    onIntent(HistoryIntent.OnOpenDetail(item.vodId))
                                },
                                onToggleKeep = {
                                    menuFor = null
                                    onIntent(HistoryIntent.OnToggleKeep(item))
                                },
                                onDelete = {
                                    menuFor = null
                                    onIntent(HistoryIntent.OnDeleteRecord(item.vodId))
                                },
                            )
                        },
                    )
                }
            }
        }
    }

    if (confirmClear) {
        AlertDialog(
            onDismissRequest = { confirmClear = false },
            title = { Text(stringResource(R.string.history_clear_title)) },
            text = { Text(stringResource(R.string.history_clear_body)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        confirmClear = false
                        onIntent(HistoryIntent.OnClearAll)
                    },
                ) {
                    Text(stringResource(R.string.history_clear_confirm))
                }
            },
            dismissButton = {
                TextButton(onClick = { confirmClear = false }) {
                    Text(stringResource(R.string.dialog_cancel))
                }
            },
        )
    }
}

/**
 * 长按菜单。
 *
 * 「收藏」用**记录里的快照**拼 `KeepItem`（在 `HistoryState` 里），不回源 —— 见那里的说明。
 */
@Composable
private fun HistoryMenu(
    expanded: Boolean,
    kept: Boolean,
    onDismiss: () -> Unit,
    onContinue: () -> Unit,
    onOpenDetail: () -> Unit,
    onToggleKeep: () -> Unit,
    onDelete: () -> Unit,
) {
    DropdownMenu(expanded = expanded, onDismissRequest = onDismiss) {
        DropdownMenuItem(
            text = { Text(stringResource(R.string.history_menu_continue)) },
            leadingIcon = { Icon(Icons.Filled.PlayArrow, contentDescription = null) },
            onClick = onContinue,
        )
        DropdownMenuItem(
            text = { Text(stringResource(R.string.history_menu_detail)) },
            leadingIcon = { Icon(Icons.Outlined.Info, contentDescription = null) },
            onClick = onOpenDetail,
        )
        DropdownMenuItem(
            text = {
                Text(
                    stringResource(
                        if (kept) R.string.cd_keep_remove else R.string.cd_keep_add
                    )
                )
            },
            leadingIcon = {
                Icon(
                    imageVector = if (kept) Icons.Filled.Bookmark else Icons.Outlined.BookmarkBorder,
                    contentDescription = null,
                )
            },
            onClick = onToggleKeep,
        )
        DropdownMenuItem(
            text = { Text(stringResource(R.string.history_menu_delete)) },
            leadingIcon = { Icon(Icons.Outlined.DeleteOutline, contentDescription = null) },
            onClick = onDelete,
        )
    }
}

/**
 * 列表骨架 —— 结构逐项对齐真实行：`ContainmentBlock` 容器 + 左侧 72dp 的 3:4 封面
 * + 右侧四条长短不一的文字条。
 *
 * 容器块直接复用 [ContainmentBlock]（而不是手画一个灰块）：骨架的"块感"本来就该来自
 * 真实的那个组件，不然内容到达时颜色深浅会变一次。
 */
@Composable
private fun HistorySkeleton(modifier: Modifier = Modifier) {
    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(BeeDimens.gapSmall),
    ) {
        repeat(HISTORY_SKELETON_ROWS) { row ->
            ContainmentBlock {
                Row {
                    SkeletonBlock(
                        modifier = Modifier
                            .width(BeeDimens.historyPosterWidth)
                            .aspectRatio(BeeDimens.detailPosterAspect),
                        shape = MaterialTheme.shapes.medium,
                        staggerIndex = row * SKELETON_BARS,
                    )
                    Spacer(Modifier.width(BeeDimens.gapSmall))
                    Column(
                        modifier = Modifier.weight(1f),
                        verticalArrangement = Arrangement.spacedBy(BeeDimens.gapTiny),
                    ) {
                        // 片名 / 集数 · 线路 / 进度 / 时间，逐条收短
                        SKELETON_BAR_FRACTIONS.forEachIndexed { index, fraction ->
                            SkeletonBlock(
                                modifier = Modifier
                                    .fillMaxWidth(fraction)
                                    .height(SKELETON_TEXT_BAR),
                                shape = MaterialTheme.shapes.extraSmall,
                                staggerIndex = row * SKELETON_BARS + index + 1,
                            )
                        }
                    }
                }
            }
        }
    }
}

private const val SKELETON_BARS = 4
private val SKELETON_BAR_FRACTIONS = listOf(0.62f, 0.86f, 1f, 0.48f)
private val SKELETON_TEXT_BAR = 12.dp

// ------------------------------------------------------------------ 预览

/** 演示记录。参照时刻取当下，好让「今天 / 昨天」这类文案在预览里是真的。 */
private val previewState: HistoryUiState = run {
    val records = PreviewHistory.records()
    HistoryUiState(
        loading = false,
        records = records,
        keptIds = setOf(records.first().vodId),
        sourceNames = mapOf("demo" to "示例来源"),
    )
}

@Preview(
    name = "历史 · 有记录",
    group = "页面",
    showBackground = true,
    backgroundColor = 0xFF0B0A08,
    widthDp = 411,
    heightDp = 891,
)
@Composable
private fun HistoryScreenPreview() {
    BeeVideoTheme(darkTheme = true) {
        HistoryScreen(uiState = previewState, onIntent = {})
    }
}

@Preview(
    name = "历史 · 空态",
    group = "页面",
    showBackground = true,
    backgroundColor = 0xFF0B0A08,
    widthDp = 411,
    heightDp = 891,
)
@Composable
private fun HistoryScreenEmptyPreview() {
    BeeVideoTheme(darkTheme = true) {
        HistoryScreen(uiState = HistoryUiState(loading = false), onIntent = {})
    }
}

@Preview(
    name = "历史 · 浅色",
    group = "页面",
    showBackground = true,
    backgroundColor = 0xFFFFF9EF,
    widthDp = 411,
    heightDp = 891,
)
@Composable
private fun HistoryScreenLightPreview() {
    BeeVideoTheme(darkTheme = false) {
        HistoryScreen(uiState = previewState, onIntent = {})
    }
}

@Preview(
    name = "历史 · 骨架",
    group = "页面",
    showBackground = true,
    backgroundColor = 0xFF0B0A08,
    widthDp = 411,
    heightDp = 891,
)
@Composable
private fun HistorySkeletonPreview() {
    BeeVideoTheme(darkTheme = true) {
        HistorySkeleton(
            modifier = Modifier.padding(
                start = BeeDimens.screenMargin,
                end = BeeDimens.screenMargin,
                top = BeeDimens.gapSmall,
            )
        )
    }
}
