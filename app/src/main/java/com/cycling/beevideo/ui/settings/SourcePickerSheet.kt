package com.cycling.beevideo.ui.settings

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import com.cycling.beevideo.R
import com.cycling.beevideo.domain.model.ContentSource
import com.cycling.beevideo.ui.components.BeeChipGrid
import com.cycling.beevideo.ui.theme.BeeDimens

/**
 * 来源选择器。两处入口共用：首页顶栏（日常切换）与设置页那一行（配置时找）。
 *
 * 做成弹层而不是就地铺开：一份配置里 86 个站点是实测值，就地铺是四十多行、每行 2 个，
 * 整页被它撑爆。做成路由页面则每次切换要多一次返回，所以用弹层 —— 选完即走。
 *
 * 长按一枚 chip 可以设「不参与搜索」或置顶。**排除只影响聚合搜索**：被排除的站点
 * 照样能切成当前来源单独浏览。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SourcePickerSheet(
    sources: List<ContentSource>,
    activeId: String,
    excludedIds: Set<String>,
    pinnedIds: List<String>,
    onSelect: (String) -> Unit,
    onToggleExcluded: (String) -> Unit,
    onTogglePin: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    var query by rememberSaveable { mutableStateOf("") }
    var menuFor by remember { mutableStateOf<String?>(null) }

    val shown = remember(sources, query) {
        val q = query.trim()
        if (q.isEmpty()) sources else sources.filter { it.name.contains(q, ignoreCase = true) }
    }
    val activeIndex = shown.indexOfFirst { it.id == activeId }

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState) {
        Column(modifier = Modifier.padding(horizontal = BeeDimens.gapMedium)) {
            Text(
                text = stringResource(R.string.settings_source_picker_title),
                style = MaterialTheme.typography.titleMediumEmphasized,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Spacer(Modifier.height(BeeDimens.gapSmall))

            OutlinedTextField(
                value = query,
                onValueChange = { query = it },
                modifier = Modifier.fillMaxWidth(),
                placeholder = { Text(stringResource(R.string.settings_source_search)) },
                leadingIcon = {
                    Icon(imageVector = Icons.Filled.Search, contentDescription = null)
                },
                singleLine = true,
                // 只用来过滤，不提交；给个 Search 键让键盘上的回车不至于看着是"没作用"
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
            )

            Spacer(Modifier.height(BeeDimens.gapTiny))
            // 长按是隐藏操作，不写出没人会去试（ADR-0006 已经为"隐式入口"记过一次教训）
            Text(
                text = stringResource(R.string.settings_source_long_press_hint),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(BeeDimens.gapTiny))
            Text(
                text = stringResource(R.string.settings_source_count, shown.size),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(BeeDimens.gapTiny))

            /*
             * weight(1f) 撑满弹层剩余高度，让**搜索框留在原地**、只有网格滚 ——
             * 两者放同一个 scroll 里的话，滚到第 30 行想改关键词得先滚回顶部。
             */
            Box(
                modifier = Modifier
                    .weight(1f)
                    .verticalScroll(rememberScrollState()),
            ) {
                if (shown.isEmpty()) {
                    Text(
                        text = stringResource(R.string.settings_source_no_match),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.fillMaxWidth().padding(vertical = BeeDimens.gapLarge),
                    )
                } else {
                    BeeChipGrid(
                        items = shown,
                        selectedIndex = activeIndex,
                        onSelect = { onSelect(shown[it].id) },
                        onLongPress = { menuFor = shown[it].id },
                        longPressLabel = stringResource(R.string.settings_source_long_press),
                        menu = { index ->
                            val source = shown[index]
                            SourceMenu(
                                expanded = menuFor == source.id,
                                excluded = source.id in excludedIds,
                                pinned = source.id in pinnedIds,
                                onDismiss = { menuFor = null },
                                onToggleExcluded = {
                                    menuFor = null
                                    onToggleExcluded(source.id)
                                },
                                onTogglePin = {
                                    menuFor = null
                                    onTogglePin(source.id)
                                },
                            )
                        },
                    ) { source ->
                        Text(
                            text = source.name,
                            style = MaterialTheme.typography.labelLarge,
                            // 排除态只能从 label 表达：chip 的颜色由 ToggleButton 的
                            // 选中态控制，调用方改不了。只用删除线，不覆盖内容色 ——
                            // 覆盖了会和选中态的配色打架
                            textDecoration = if (source.id in excludedIds) {
                                TextDecoration.LineThrough
                            } else {
                                null
                            },
                        )
                    }
                    // 弹层底部留白：最后一行贴着手势条不好点
                    Spacer(Modifier.height(BeeDimens.gapLarge))
                }
            }
        }
    }
}

/** 一枚站点上的两个开关。文案随当前状态变，用户不用猜点了会怎样。 */
@Composable
private fun SourceMenu(
    expanded: Boolean,
    excluded: Boolean,
    pinned: Boolean,
    onDismiss: () -> Unit,
    onToggleExcluded: () -> Unit,
    onTogglePin: () -> Unit,
) {
    DropdownMenu(expanded = expanded, onDismissRequest = onDismiss) {
        DropdownMenuItem(
            text = {
                Text(
                    stringResource(
                        if (excluded) {
                            R.string.settings_source_include
                        } else {
                            R.string.settings_source_exclude
                        }
                    )
                )
            },
            onClick = onToggleExcluded,
        )
        DropdownMenuItem(
            text = {
                Text(
                    stringResource(
                        if (pinned) {
                            R.string.settings_source_unpin
                        } else {
                            R.string.settings_source_pin
                        }
                    )
                )
            },
            onClick = onTogglePin,
        )
    }
}
