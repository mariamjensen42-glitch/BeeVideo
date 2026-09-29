package com.cycling.beevideo.ui.search

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.cycling.beevideo.R
import com.cycling.beevideo.domain.model.SearchOutcome
import com.cycling.beevideo.domain.model.Vod
import com.cycling.beevideo.ui.components.BeeBackButton
import com.cycling.beevideo.ui.components.BeeCenteredNotice
import com.cycling.beevideo.ui.components.LoadState
import com.cycling.beevideo.ui.components.PosterCard
import com.cycling.beevideo.ui.components.SkeletonPosterGrid
import com.cycling.beevideo.ui.components.beeTopAppBarColors
import com.cycling.beevideo.ui.components.skeletonSemantics
import com.cycling.beevideo.ui.preview.PreviewVods
import com.cycling.beevideo.ui.theme.BeeDimens
import com.cycling.beevideo.ui.theme.BeeVideoTheme

/** 结果海报墙的骨架行数（一屏看得见两行；同 `HomeFeed`）。 */
private const val SEARCH_SKELETON_ROWS = 2

/**
 * 搜索页。纯渲染 —— 数据全从 [uiState] 来，动作全走 [onIntent]。
 *
 * "提交式而不是边打边搜"这条取舍住在 [SearchState]；这里只管把 `input` 与 `submitted`
 * 画成该有的样子，以及聚焦与收键盘这两个**局部 UI 关注点**。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SearchScreen(
    uiState: SearchUiState,
    onIntent: (SearchIntent) -> Unit,
) {
    val focusRequester = remember { FocusRequester() }
    val focusManager = LocalFocusManager.current

    val submit: () -> Unit = {
        onIntent(SearchIntent.OnSubmit)
        focusManager.clearFocus()
    }

    // 进来就聚焦：这是搜索页，用户点它就是为了打字。
    // 用 clearFocus 收键盘而不是操作 IME —— 后者要 `LocalSoftwareKeyboardController`，
    // 而 clearFocus 在提交、返回、点结果时都适用。
    LaunchedEffect(Unit) { runCatching { focusRequester.requestFocus() } }

    Scaffold(
        // 与顶栏容器色同源（beeTopAppBarColors 里也是 surface），
        // 否则滚动时顶栏会露出另一档容器色。
        containerColor = MaterialTheme.colorScheme.surface,
        topBar = {
            TopAppBar(
                title = { Text(text = stringResource(R.string.search_title)) },
                navigationIcon = { BeeBackButton { onIntent(SearchIntent.OnBack) } },
                colors = beeTopAppBarColors(),
            )
        },
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding),
        ) {
            TextField(
                value = uiState.input,
                onValueChange = { onIntent(SearchIntent.OnInputChange(it)) },
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(
                        start = BeeDimens.screenMargin,
                        end = BeeDimens.screenMargin,
                        top = BeeDimens.gapSmall,
                        bottom = BeeDimens.gapMedium,
                    )
                    .focusRequester(focusRequester),
                singleLine = true,
                // 搜索框的形态对齐 M3：容器形状取 SearchBarTokens 那一档（cornerExtraLarge），
                // 也就是 `shapes.extraLarge`。颜色不传 —— 默认就是 surfaceContainerHighest，
                // 正好是"输入控件"该有的那一档（本项目不靠描边区分层级）。
                shape = MaterialTheme.shapes.extraLarge,
                placeholder = { Text(text = stringResource(R.string.search_placeholder)) },
                leadingIcon = {
                    Icon(
                        imageVector = Icons.Filled.Search,
                        contentDescription = null,
                    )
                },
                trailingIcon = {
                    if (uiState.input.isNotEmpty()) {
                        IconButton(onClick = { onIntent(SearchIntent.OnClearInput) }) {
                            Icon(
                                imageVector = Icons.Outlined.Close,
                                contentDescription = stringResource(R.string.search_clear),
                            )
                        }
                    }
                },
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                keyboardActions = KeyboardActions(onSearch = { submit() }),
            )

            // 结果区必须有独立的高度约束：外面是 Column，直接给 fillMaxSize 的
            // 网格会和键盘抢空间（网格在 Column 里量到的是"剩余高度"，
            // 用 weight 才能把它钉在剩余空间上，否则要么溢出要么塌成 0）。
            Box(modifier = Modifier.weight(1f)) {
                when (val result = uiState.result) {
                    is LoadState.Loading -> SkeletonPosterGrid(
                        /*
                         * ⚠️ 横向留白**必须自己给**。
                         *
                         * `SkeletonPosterGrid` 内部只有 `fillMaxWidth()`，横向缩进
                         * 一律由调用方负责 —— 首页那边它挂在带 `contentPadding`
                         * 的网格项里，所以那儿不用管。这里它直接就躺在 `Box` 里，
                         * 漏掉这两行的话骨架会比真实结果宽两个 `screenMargin`，
                         * 数据到达时整墙同时向内收缩，看着像"跳了一下"。
                         *
                         * 纵向的 `gapSmall` 是跟着 `SearchResults` 里那一行说明
                         * 的高度来的，不求逐像素对齐 —— 骨架只需要填满首屏。
                         */
                        modifier = Modifier
                            .skeletonSemantics(stringResource(R.string.search_loading))
                            .padding(
                                start = BeeDimens.screenMargin,
                                end = BeeDimens.screenMargin,
                                top = BeeDimens.gapSmall,
                            ),
                        rows = SEARCH_SKELETON_ROWS,
                    )

                    is LoadState.Failed -> BeeCenteredNotice(
                        fillHeight = true,
                        text = stringResource(R.string.search_failed, result.message),
                    )

                    is LoadState.Ready -> {
                        val outcome = result.value
                        when {
                            outcome != null -> SearchResults(
                                outcome = outcome,
                                keyword = uiState.submitted,
                                onVodClick = { onIntent(SearchIntent.OnOpenVod(it)) },
                            )

                            // 有历史就铺历史。**没历史才给那句提示** —— 新用户看到的
                            // 一个字节都没变，而老用户不必再读一遍怎么用
                            uiState.history.isNotEmpty() -> SearchHistorySection(
                                keywords = uiState.history,
                                onUse = { onIntent(SearchIntent.OnUseHistory(it)) },
                                onRemove = { onIntent(SearchIntent.OnRemoveHistory(it)) },
                                onClear = { onIntent(SearchIntent.OnClearHistory) },
                                modifier = Modifier.padding(top = BeeDimens.gapSmall),
                            )

                            else -> BeeCenteredNotice(
                                fillHeight = true,
                                text = stringResource(R.string.search_hint),
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun SearchResults(
    outcome: SearchOutcome,
    keyword: String,
    onVodClick: (Vod) -> Unit,
) {
    if (outcome.vods.isEmpty()) {
        // 覆盖情况在这一支同样要说：搜了 1 个源和搜了 100 个源都没结果，
        // 是完全不同的两个结论。
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            /*
             * 三种"没有结果"必须分得开，文案不能共用：
             *
             * 1. 配置里本来就没有可搜索的站点 —— 改关键词多少次都不会有结果，
             *    得让他去配置里找。
             * 2. 有可搜索的站点，但**全被用户自己设成了「不参与搜索」** ——
             *    这时候说"没有可搜索的站点"是撒谎，他会去翻配置却找不到问题。
             * 3. 真的搜了、但没搜到 —— 这才是可以换关键词的那种。
             */
            when {
                outcome.searchableSources == 0 && outcome.disabledSources > 0 ->
                    BeeCenteredNotice(
                        fillHeight = true,
                        text = stringResource(
                            R.string.search_all_excluded,
                            outcome.disabledSources,
                        ),
                    )

                outcome.searchableSources == 0 -> BeeCenteredNotice(
                    fillHeight = true,
                    text = stringResource(R.string.search_no_searchable),
                )

                else -> BeeCenteredNotice(
                    fillHeight = true,
                    text = stringResource(R.string.search_no_result, keyword),
                )
            }
            CoverageLine(outcome)
        }
        return
    }

    LazyVerticalGrid(
        columns = GridCells.Fixed(BeeDimens.posterColumns),
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(
            start = BeeDimens.screenMargin,
            end = BeeDimens.screenMargin,
            bottom = BeeDimens.gapHuge,
        ),
        horizontalArrangement = Arrangement.spacedBy(BeeDimens.gapSmall),
        verticalArrangement = Arrangement.spacedBy(BeeDimens.gapMedium),
    ) {
        item(key = "summary", span = { GridItemSpan(maxLineSpan) }, contentType = "row") {
            Column {
                Text(
                    text = stringResource(R.string.search_result_count, outcome.vods.size),
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                CoverageLine(outcome)
            }
        }
        items(outcome.vods, key = { it.id }, contentType = { "poster" }) { vod ->
            PosterCard(vod = vod, onClick = { onVodClick(vod) })
        }
    }
}

/**
 * 覆盖情况："只搜了一部分"与"有一部分被自己排除了"。
 *
 * 两者都只在**真的发生**时才出现（内部各自判断）。跨源搜索有站点上限（一个上百站点的
 * 合集全量并行会瞬间打出上百个请求），而排除是用户自己设的 —— 但两件事都不能静默：
 * 搜不到时用户无法区分"所有源都没有"和"只搜了一部分"。详见 [SearchOutcome]。
 */
@Composable
private fun CoverageLine(outcome: SearchOutcome) {
    val lines = buildList {
        if (outcome.truncated) {
            add(
                stringResource(
                    R.string.search_coverage,
                    outcome.searchedSources,
                    outcome.searchableSources,
                )
            )
        }
        if (outcome.disabledSources > 0) {
            add(stringResource(R.string.search_excluded_sources, outcome.disabledSources))
        }
    }
    if (lines.isEmpty()) return

    Column {
        lines.forEach { line ->
            Text(
                text = line,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = BeeDimens.gapTight),
            )
        }
    }
}

// ------------------------------------------------------------------ 预览

/** 演示结果：故意截断，好让「已搜索 10 / 86 个源」那一行在预览里出现。 */
private val truncatedOutcome = SearchOutcome(
    vods = PreviewVods.vods,
    searchedSources = 10,
    searchableSources = 86,
)

@Preview(
    name = "搜索 · 空态",
    group = "页面",
    showBackground = true,
    backgroundColor = 0xFF0B0A08,
    widthDp = 411,
    heightDp = 891,
)
@Composable
private fun SearchScreenPreview() {
    BeeVideoTheme(darkTheme = true) {
        SearchScreen(uiState = SearchUiState(), onIntent = {})
    }
}

@Preview(
    name = "搜索 · 浅色",
    group = "页面",
    showBackground = true,
    backgroundColor = 0xFFFFF9EF,
    widthDp = 411,
    heightDp = 891,
)
@Composable
private fun SearchScreenLightPreview() {
    BeeVideoTheme(darkTheme = false) {
        SearchScreen(uiState = SearchUiState(), onIntent = {})
    }
}

/** 有历史时的空态：看的是换行、长按提示与「清空」那行的关系。 */
@Preview(
    name = "搜索 · 有历史",
    group = "页面",
    showBackground = true,
    backgroundColor = 0xFF0B0A08,
    widthDp = 411,
    heightDp = 891,
)
@Composable
private fun SearchScreenHistoryPreview() {
    BeeVideoTheme(darkTheme = true) {
        SearchScreen(
            uiState = SearchUiState(
                history = listOf("庆余年", "繁花", "三体 第一季", "流浪地球", "漫长的季节"),
            ),
            onIntent = {},
        )
    }
}

@Preview(
    name = "搜索 · 结果（含截断提示）",
    group = "页面",
    showBackground = true,
    backgroundColor = 0xFF0B0A08,
    widthDp = 411,
    heightDp = 891,
)
@Composable
private fun SearchScreenResultsPreview() {
    BeeVideoTheme(darkTheme = true) {
        SearchScreen(
            uiState = SearchUiState(
                input = "示例",
                submitted = "示例",
                result = LoadState.Ready(truncatedOutcome),
            ),
            onIntent = {},
        )
    }
}

@Preview(
    name = "搜索 · 搜索中",
    group = "页面",
    showBackground = true,
    backgroundColor = 0xFF0B0A08,
    widthDp = 411,
    heightDp = 891,
)
@Composable
private fun SearchScreenLoadingPreview() {
    BeeVideoTheme(darkTheme = true) {
        SearchScreen(
            uiState = SearchUiState(input = "示例", submitted = "示例"),
            onIntent = {},
        )
    }
}

@Preview(
    name = "搜索 · 无结果",
    group = "页面",
    showBackground = true,
    backgroundColor = 0xFF0B0A08,
    widthDp = 411,
    heightDp = 500,
)
@Composable
private fun SearchResultsEmptyPreview() {
    BeeVideoTheme(darkTheme = true) {
        SearchResults(
            outcome = SearchOutcome(vods = emptyList(), searchedSources = 86, searchableSources = 86),
            keyword = "不存在的片名",
            onVodClick = {},
        )
    }
}
