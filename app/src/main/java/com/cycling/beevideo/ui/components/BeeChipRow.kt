package com.cycling.beevideo.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.ButtonGroupDefaults
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.ToggleButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.cycling.beevideo.data.demo.DemoContent
import com.cycling.beevideo.ui.theme.BeeDimens
import com.cycling.beevideo.ui.theme.BeeVideoTheme

/**
 * 横向单选行 —— M3 Expressive 的「连接式按钮组」。
 *
 * 相比普通一排独立按钮，这里做三件事：
 * 1. 用 `ToggleButton` 而非 `FilterChip`（后者是标准 M3 老样式，观感平）
 * 2. 按位置套用 connectedLeading / Middle / Trailing 形状，
 *    让整排按钮拼成一条连续的胶囊，这是 Expressive 最标志性的形态
 * 3. 间距用 `ButtonGroupDefaults.ConnectedSpaceBetween`，让相邻按钮真正贴合
 *
 * 泛型化：[label] 决定每一项长什么样；用下标而非业务 id 作为选中标识。
 */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun <T> BeeChipRow(
    items: List<T>,
    selectedIndex: Int,
    onSelect: (Int) -> Unit,
    modifier: Modifier = Modifier,
    // 横向留白由调用方给：贴屏幕边缘时传 screenMargin，已经在一个容器块里就传 0
    contentPadding: PaddingValues = PaddingValues(0.dp),
    label: @Composable (T) -> Unit = {
        Text(text = it.toString(), style = MaterialTheme.typography.labelLarge)
    },
) {
    LazyRow(
        modifier = modifier,
        contentPadding = contentPadding,
        horizontalArrangement = Arrangement.spacedBy(ButtonGroupDefaults.ConnectedSpaceBetween),
    ) {
        itemsIndexed(items) { index, item ->
            ToggleButton(
                checked = index == selectedIndex,
                onCheckedChange = { onSelect(index) },
                shapes = when {
                    items.size == 1 -> ButtonGroupDefaults.connectedLeadingButtonShapes()
                    index == 0 -> ButtonGroupDefaults.connectedLeadingButtonShapes()
                    index == items.lastIndex -> ButtonGroupDefaults.connectedTrailingButtonShapes()
                    else -> ButtonGroupDefaults.connectedMiddleButtonShapes()
                },
                content = { label(item) },
            )
        }
    }
}

// ------------------------------------------------------------------ 预览

@Preview(
    name = "分类按钮组 · 选中第二项",
    group = "组件",
    showBackground = true,
    backgroundColor = 0xFF0B0A08,
    widthDp = 411,
)
@Composable
private fun BeeChipRowPreview() {
    BeeVideoTheme(darkTheme = true) {
        BeeChipRow(
            items = DemoContent.categories,
            selectedIndex = 1,
            onSelect = {},
            contentPadding = PaddingValues(horizontal = BeeDimens.gapMedium),
        ) { category ->
            Text(text = category.name, style = MaterialTheme.typography.labelLarge)
        }
    }
}

@Preview(
    name = "分类按钮组 · 单项",
    group = "组件",
    showBackground = true,
    backgroundColor = 0xFF0B0A08,
    widthDp = 200,
)
@Composable
private fun BeeChipRowSinglePreview() {
    BeeVideoTheme(darkTheme = true) {
        BeeChipRow(
            items = listOf("全部"),
            selectedIndex = 0,
            onSelect = {},
            contentPadding = PaddingValues(horizontal = BeeDimens.gapMedium),
            modifier = Modifier.padding(vertical = BeeDimens.gapSmall),
        ) { name ->
            Text(text = name, style = MaterialTheme.typography.labelLarge)
        }
    }
}
