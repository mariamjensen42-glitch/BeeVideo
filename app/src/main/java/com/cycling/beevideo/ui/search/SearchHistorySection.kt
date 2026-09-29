package com.cycling.beevideo.ui.search

import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.ripple
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.cycling.beevideo.R
import com.cycling.beevideo.ui.theme.BeeDimens

/**
 * 搜索页空态里的「最近搜索」。
 *
 * ⚠️ **不复用 `BeeChipGrid`**：它内部是 `ToggleButton`（单选语义），而这里每一枚都是
 * "点一下就开始搜"的动作按钮，没有"当前选中哪一枚"这个概念。硬套会把那个不存在的
 * 状态带进来，而且 `ToggleButton` 的点击会和长按抢 pointer 事件。
 *
 * chip 自绘而不是用 `AssistChip`：后者只收 `onClick`，要加长按就得把点击挪到外层，
 * 于是内外两套点击语义（正是要避开的坑）。这里从零画一枚，点击只有一层。
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun SearchHistorySection(
    keywords: List<String>,
    onUse: (String) -> Unit,
    onRemove: (String) -> Unit,
    onClear: () -> Unit,
    modifier: Modifier = Modifier,
) {
    // 长按菜单锚在哪一枚上。与历史页的 menuFor 同一种做法
    var menuFor by remember { mutableStateOf<String?>(null) }
    var confirmClear by remember { mutableStateOf(false) }

    Column(modifier = modifier.padding(horizontal = BeeDimens.screenMargin)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = stringResource(R.string.search_history_title),
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.weight(1f),
            )
            // 页面上给一个显式入口：长按是隐藏操作，只在菜单里放等于没人发现
            TextButton(onClick = { confirmClear = true }) {
                Text(
                    text = stringResource(R.string.search_history_clear),
                    style = MaterialTheme.typography.labelLarge,
                )
            }
        }

        FlowRow(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(BeeDimens.gapTiny),
            verticalArrangement = Arrangement.spacedBy(BeeDimens.gapTiny),
        ) {
            keywords.forEach { keyword ->
                KeywordChip(
                    keyword = keyword,
                    onUse = { onUse(keyword) },
                    onLongPress = { menuFor = keyword },
                    menu = {
                        DropdownMenu(
                            expanded = menuFor == keyword,
                            onDismissRequest = { menuFor = null },
                        ) {
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.search_history_remove)) },
                                onClick = {
                                    menuFor = null
                                    onRemove(keyword)
                                },
                            )
                            HorizontalDivider()
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.search_history_clear)) },
                                onClick = {
                                    menuFor = null
                                    confirmClear = true
                                },
                            )
                        }
                    },
                )
            }
        }
    }

    if (confirmClear) {
        AlertDialog(
            onDismissRequest = { confirmClear = false },
            title = { Text(stringResource(R.string.search_history_clear_title)) },
            text = { Text(stringResource(R.string.search_history_clear_body)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        confirmClear = false
                        onClear()
                    },
                ) {
                    Text(stringResource(R.string.search_history_clear_confirm))
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
 * 一枚关键词。
 *
 * 高度锁 32dp 是 M3 chip 的规范值，不靠"行高 + 上下内边距"凑出来 ——
 * 后者会随排版 token 变，而 chip 的高度不该跟着 label 的字号走。
 */
@Composable
private fun KeywordChip(
    keyword: String,
    onUse: () -> Unit,
    onLongPress: () -> Unit,
    menu: @Composable () -> Unit,
) {
    val shape = MaterialTheme.shapes.small
    val longPressLabel = stringResource(R.string.search_history_long_press)

    Box {
        Box(
            modifier = Modifier
                // clip 打在点击层外面：水波纹按节点边界裁，不裁的话圆角上会露出直角涟漪
                .clip(shape)
                .background(MaterialTheme.colorScheme.surfaceContainerHighest)
                .combinedClickable(
                    // null = 用内部 remember 的那个；只有水波纹必须显式给
                    interactionSource = null,
                    indication = ripple(),
                    onClick = onUse,
                    onLongClick = onLongPress,
                    onLongClickLabel = longPressLabel,
                )
                .height(CHIP_HEIGHT)
                // 12dp 是 chip 的水平内边距规范值；垂直由上面那行锁死
                .padding(horizontal = BeeDimens.gapSmall),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = keyword,
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                // 长关键词不能把整行撑爆，超宽交给省略号
                modifier = Modifier.widthIn(max = KEYWORD_MAX_WIDTH),
            )
        }
        menu()
    }
}

private val CHIP_HEIGHT = 32.dp

/** 单枚关键词的最大宽度。超过它就没有"扫一眼"的意义了。 */
private val KEYWORD_MAX_WIDTH = 200.dp
