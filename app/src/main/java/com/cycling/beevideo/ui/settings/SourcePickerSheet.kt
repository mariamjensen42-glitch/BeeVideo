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
 * 搜索是这里的主要手段：空白时铺全部（可浏览），输入即过滤。**没有做「最近使用」** ——
 * 那要在 `PlaybackSettings` 之外再加一份持久化状态，为一步点击不值得。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SourcePickerSheet(
    sources: List<ContentSource>,
    activeId: String,
    onSelect: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    var query by rememberSaveable { mutableStateOf("") }

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
                    ) { source ->
                        Text(source.name, style = MaterialTheme.typography.labelLarge)
                    }
                    // 弹层底部留白：最后一行贴着手势条不好点
                    Spacer(Modifier.height(BeeDimens.gapLarge))
                }
            }
        }
    }
}
