package com.cycling.beevideo.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.ButtonGroupDefaults
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.LocalMinimumInteractiveComponentSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.ToggleButton
import androidx.compose.material3.ToggleButtonDefaults
import androidx.compose.material3.ToggleButtonShapes
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.cycling.beevideo.ui.preview.PreviewSites
import com.cycling.beevideo.ui.preview.PreviewVods
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
 *
 * ⚠️ 选项多（几十上百，如站点）时改用 [BeeChipGrid] —— 这是 `LazyRow`，滑过的就看不见了。
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
    /*
     * 每个按钮**自身**的内边距。`null` = 用 M3 规范值（`ToggleButton` 内部会按尺寸档算）。
     *
     * ⚠️ 只有「一屏必须放下」时才收窄，且只收**水平**方向（高度由 M3 的最小高度兜着，
     * `vertical = 0` 不会把它压扁）。实测播放器那 6 档倍速按规范值是 6×71dp = 426dp，
     * 411dp 的屏放不下 —— 最右的 `2.0×` 被推出屏幕，用户以为没有这一档。
     * ⚠️ 别指望 `buttonSize = ToggleButtonSize.Small` 解决这件事：它只改高度。
     */
    chipContentPadding: PaddingValues? = null,
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
                shapes = connectedButtonShapes(index, items.size),
                contentPadding = chipContentPadding
                    ?: ToggleButtonDefaults.contentPaddingFor(ToggleButtonDefaults.size),
                content = { label(item) },
            )
        }
    }
}

/**
 * 网格单选 —— [BeeChipRow] 的换行版，一次铺完全部选项。用于站点列表这种长单选组。
 *
 * ⚠️ **不要直接铺在页面里**：一份配置实测 86 个站点、每行只排得下 2 个，那就是 43 行。
 * 它现在的家在 `SourcePickerSheet` —— 长列表要有自己的容器。理由见 ADR-0006。
 *
 * ⚠️ 用 `FlowRow` 而非 `LazyVerticalGrid`：调用点整页是 `Column + verticalScroll`，
 * 懒网格在无限高约束下会抛异常。代价是**不懒加载** —— N 项一次全部组合测量，
 * 只适合弹层/设置页这种不随帧重组的地方。
 * ⚠️ 形状按**全局下标**算（照抄官方 `...WithFlowLayoutSample`），换行后第二行起首项
 * 是"中间"形状、贴左边缘。官方就是这么渲染的，真机上觉得别扭就改成独立胶囊。
 *
 * 不给 `contentPadding`：那是给可滚动行防切断用的，网格的内缩由所在容器负责。
 */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun <T> BeeChipGrid(
    items: List<T>,
    selectedIndex: Int,
    onSelect: (Int) -> Unit,
    modifier: Modifier = Modifier,
    label: @Composable (T) -> Unit = {
        Text(text = it.toString(), style = MaterialTheme.typography.labelLarge)
    },
) {
    /*
     * 关掉「最小触摸目标」的额外留白。ToggleButton 视觉高 40dp，Material 会替它补到 48dp；
     * 单行里那 8dp 看不见（所以 `BeeChipRow` 一直没事），一换行就变成行距 10dp、列距仍是
     * 2dp —— 竖横不对称，网格是散的。设 0.dp 后两个方向都回到 ConnectedSpaceBetween。
     * 代价：热区就是这枚 40dp 的 chip，不再是 48dp（行距 10.4dp 是真机实测，见 ADR-0005）。
     */
    CompositionLocalProvider(LocalMinimumInteractiveComponentSize provides 0.dp) {
        FlowRow(
            // 撑满容器：不撑满时 FlowRow 自身宽度只到"最宽那一行"，这一块的边界会随内容长短变化
            modifier = modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(ButtonGroupDefaults.ConnectedSpaceBetween),
            // 行距引同一个 token（官方示例里写死的 2dp 就是它）
            verticalArrangement = Arrangement.spacedBy(ButtonGroupDefaults.ConnectedSpaceBetween),
        ) {
            items.forEachIndexed { index, item ->
                ToggleButton(
                    checked = index == selectedIndex,
                    onCheckedChange = { onSelect(index) },
                    shapes = connectedButtonShapes(index, items.size),
                    content = { label(item) },
                )
            }
        }
    }
}

/** 第 [index] 项的形状（共 [count] 项）。行与网格共用，免得两处 `when` 分叉。 */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun connectedButtonShapes(index: Int, count: Int): ToggleButtonShapes = when {
    // 只有一项时它既是首也是尾，取 leading（两端都圆）
    count <= 1 || index == 0 -> ButtonGroupDefaults.connectedLeadingButtonShapes()
    index == count - 1 -> ButtonGroupDefaults.connectedTrailingButtonShapes()
    else -> ButtonGroupDefaults.connectedMiddleButtonShapes()
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
            items = PreviewVods.categories,
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

/** 24 项看换行与形状取舍，单项看 `count <= 1` 的两端圆角。 */
@Preview(
    name = "站点网格 · 24 项换行",
    group = "组件",
    showBackground = true,
    backgroundColor = 0xFF0B0A08,
    widthDp = 411,
)
@Composable
private fun BeeChipGridPreview() {
    BeeVideoTheme(darkTheme = true) {
        BeeChipGrid(
            items = PreviewSites.manyNames,
            selectedIndex = 7,
            onSelect = {},
            modifier = Modifier.padding(horizontal = BeeDimens.gapMedium),
        ) { name ->
            Text(text = name, style = MaterialTheme.typography.labelLarge)
        }
    }
}

@Preview(
    name = "站点网格 · 单项",
    group = "组件",
    showBackground = true,
    backgroundColor = 0xFFFFF9EF,
    widthDp = 200,
)
@Composable
private fun BeeChipGridSinglePreview() {
    BeeVideoTheme(darkTheme = false) {
        BeeChipGrid(
            items = listOf("唯一站点"),
            selectedIndex = 0,
            onSelect = {},
            modifier = Modifier.padding(horizontal = BeeDimens.gapMedium),
        ) { name ->
            Text(text = name, style = MaterialTheme.typography.labelLarge)
        }
    }
}
