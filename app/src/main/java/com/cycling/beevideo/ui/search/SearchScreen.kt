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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.cycling.beevideo.R
import com.cycling.beevideo.domain.model.SearchOutcome
import com.cycling.beevideo.domain.model.Vod
import com.cycling.beevideo.domain.repository.ContentRepository
import com.cycling.beevideo.ui.components.BeeBackButton
import com.cycling.beevideo.ui.components.BeeCenteredNotice
import com.cycling.beevideo.ui.components.LoadState
import com.cycling.beevideo.ui.preview.FakeContentRepository
import com.cycling.beevideo.ui.components.PosterCard
import com.cycling.beevideo.ui.components.SkeletonPosterGrid
import com.cycling.beevideo.ui.components.beeTopAppBarColors
import com.cycling.beevideo.ui.components.loadState
import com.cycling.beevideo.ui.components.skeletonSemantics
import com.cycling.beevideo.ui.theme.BeeDimens
import com.cycling.beevideo.ui.theme.BeeVideoTheme

/** 结果海报墙的骨架行数（一屏看得见两行；同 `HomeFeed`）。 */
private const val SEARCH_SKELETON_ROWS = 2

/**
 * 搜索页。
 *
 * ─── 为什么是"提交式"而不是"边打边搜" ──────────────────────────────────
 * 这是**跨源**搜索：一次提交会同时打向当前来源里所有可搜索的站点（上限见
 * [com.cycling.beevideo.data.repository.VodContentRepository]）。跟随输入的
 * 即时搜索会在每个字符上发起一整轮跨源请求 —— 打"庆余年"三个字就是三轮，
 * 而前两轮的结果注定被丢掉。
 *
 * 所以状态拆成两个：`input`（输入框里的字）与 `submitted`（真正搜过的词）。
 * 只有 `submitted` 是 [loadState] 的键，敲键盘不会触发任何请求。
 *
 * ─── 三态 ────────────────────────────────────────────────────────────
 * 没搜过（`submitted` 为空 → Ready(null)）/ 搜索中 / 有结果或无结果。
 * 没搜过与搜了没结果是**两句不同的话**：前者要告诉用户怎么开始，
 * 后者要说清搜的是哪个词 —— 否则用户会怀疑是不是自己没按下去。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SearchScreen(
    content: ContentRepository,
    onVodClick: (Vod) -> Unit,
    onBack: () -> Unit,
) {
    var input by rememberSaveable { mutableStateOf("") }
    var submitted by rememberSaveable { mutableStateOf("") }

    val focusRequester = remember { FocusRequester() }
    val focusManager = LocalFocusManager.current

    /*
     * 键是 `submitted`（已提交的词），不是 `input`。
     * `submitted` 为空串时返回 null —— 用 null 表达"还没搜"，而不是空列表：
     * 空列表会被界面读成"搜了，没有结果"。
     */
    val state = loadState(submitted) {
        if (submitted.isBlank()) null else content.search(submitted)
    }

    val submit: () -> Unit = {
        val q = input.trim()
        if (q.isNotEmpty()) {
            submitted = q
            focusManager.clearFocus()
        }
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
                navigationIcon = { BeeBackButton(onBack) },
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
                value = input,
                onValueChange = { input = it },
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
                    if (input.isNotEmpty()) {
                        IconButton(onClick = { input = "" }) {
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
                when (state) {
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

                    is LoadState.Failed -> BeeCenteredNotice(fillHeight = true, 
                        text = stringResource(R.string.search_failed, state.message),
                    )

                    is LoadState.Ready -> {
                        val outcome = state.value
                        if (outcome == null) {
                            // 还没搜过
                            BeeCenteredNotice(fillHeight = true, text = stringResource(R.string.search_hint))
                        } else {
                            SearchResults(
                                outcome = outcome,
                                keyword = submitted,
                                onVodClick = onVodClick,
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
             * "一个源都没有" 与 "搜了但没找到" 是**两回事**，文案不能共用。
             *
             * 数据层在 `searchable` 为空时返回的是 `SearchOutcome.EMPTY`
             * （两个计数都是 0），而不是抛异常 —— 因为"这个配置里没有可搜索的站点"
             * 不是错误，是配置事实。但如果这里照旧说「没有找到「XX」」，
             * 用户会去改关键词，而改多少次都不会有结果。
             */
            if (outcome.searchableSources == 0) {
                BeeCenteredNotice(fillHeight = true, text = stringResource(R.string.search_no_searchable))
            } else {
                BeeCenteredNotice(fillHeight = true, text = stringResource(R.string.search_no_result, keyword))
            }
            if (outcome.truncated) {
                CoverageLine(outcome)
            }
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
        item(key = "summary", span = { GridItemSpan(maxLineSpan) }) {
            Column {
                Text(
                    text = stringResource(R.string.search_result_count, outcome.vods.size),
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (outcome.truncated) CoverageLine(outcome)
            }
        }
        items(outcome.vods, key = { it.id }) { vod ->
            PosterCard(vod = vod, onClick = { onVodClick(vod) })
        }
    }
}

/**
 * 「已搜索 N / M 个源」。
 *
 * 只在**真的被截断**时出现。跨源搜索有站点上限（一个上百站点的合集全量并行
 * 会瞬间打出上百个请求），但截断不能是静默的：搜不到时用户无法区分
 * "所有源都没有"和"只搜了一部分"。详见 [SearchOutcome]。
 */
@Composable
private fun CoverageLine(outcome: SearchOutcome) {
    Text(
        text = stringResource(
            R.string.search_coverage,
            outcome.searchedSources,
            outcome.searchableSources,
        ),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(top = BeeDimens.gapTight),
    )
}

// ------------------------------------------------------------------ 预览

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
        SearchScreen(content = FakeContentRepository(), onVodClick = {}, onBack = {})
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
        SearchScreen(content = FakeContentRepository(), onVodClick = {}, onBack = {})
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
private fun SearchResultsPreview() {
    BeeVideoTheme(darkTheme = true) {
        SearchResults(
            outcome = SearchOutcome(
                vods = com.cycling.beevideo.ui.preview.PreviewVods.vods,
                searchedSources = 10,
                searchableSources = 86,
            ),
            keyword = "示例",
            onVodClick = {},
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
