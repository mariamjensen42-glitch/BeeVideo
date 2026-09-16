package com.cycling.beevideo.ui.home

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.LoadingIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.MediumFlexibleTopAppBar
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.rememberTopAppBarState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.cycling.beevideo.R
import com.cycling.beevideo.data.repository.DemoContentRepository
import com.cycling.beevideo.domain.model.Category
import com.cycling.beevideo.domain.model.ContentSource
import com.cycling.beevideo.domain.model.SourcePhase
import com.cycling.beevideo.domain.model.SourceStatus
import com.cycling.beevideo.domain.model.Vod
import com.cycling.beevideo.domain.repository.ContentRepository
import com.cycling.beevideo.domain.repository.ContentSourceRepository
import com.cycling.beevideo.ui.components.BeeChipRow
import com.cycling.beevideo.ui.components.HeroCarousel
import com.cycling.beevideo.ui.components.LoadState
import com.cycling.beevideo.ui.components.PosterCard
import com.cycling.beevideo.ui.components.beeTopAppBarColors
import com.cycling.beevideo.ui.components.loadState
import com.cycling.beevideo.ui.theme.BeeDimens
import com.cycling.beevideo.ui.theme.BeeVideoTheme
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch

/**
 * 首页。
 *
 * ─── 结构：先按内容源状态分支，再是一个滚动容器 ────────────────────────
 * 最外层按**内容源的状态**分支（没配 / 装载中 / 装载失败 / 就绪），
 * 就绪之后才是内容页。这几支不是"顺手加的兜底" —— 本项目不内置内容源，
 * 未配置时 App 就该是一页清楚的说明加一个入口，而不是一片空白或者一份假数据。
 *
 * 内容页整页只有**一个** `LazyVerticalGrid`，刊头 / 筛选行 / 版块标题
 * 都是它的整行项。三条理由（都不是审美问题）：
 *   1. 放进外侧的 Column 就等于把它们钉在屏幕上不走了，刊头会永久吃掉
 *      200dp 的垂直空间，海报网格只剩一半；
 *   2. `nestedScroll` 的驱动来源必须是唯一的 —— 两个滚动容器抢顶栏的
 *      折叠进度会互相打架；
 *   3. 顺序上**刊头在最前**：第一眼看的是内容，不是一行筛选胶囊。
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun HomeScreen(
    content: ContentRepository,
    sources: ContentSourceRepository,
    onVodClick: (Vod) -> Unit,
    onOpenSettings: () -> Unit,
) {
    val status by sources.status.collectAsState()
    val scope = rememberCoroutineScope()

    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior(
        state = rememberTopAppBarState(),
    )

    // 顶栏副标题显示当前来源名。空着比写"演示数据"诚实 —— 没配来源时
    // 页面上本来就有整页的说明。
    val activeName = status.sources
        .firstOrNull { it.id == status.activeSourceId }
        ?.name
        .orEmpty()

    // body 区用 surface。顶栏容器色也必须是同一个 surface（`beeTopAppBarColors()`），
    // 否则滚动时会看到顶栏切换成 surfaceContainer —— 一条贴在页面顶端的色带。
    Scaffold(
        containerColor = MaterialTheme.colorScheme.surface,
        modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        topBar = {
            MediumFlexibleTopAppBar(
                title = { Text(text = stringResource(R.string.home_brand)) },
                subtitle = {
                    if (activeName.isNotEmpty()) {
                        Text(text = activeName, maxLines = 1, overflow = TextOverflow.Ellipsis)
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

        when (status.phase) {
            SourcePhase.EMPTY -> SourceNotice(
                modifier = bodyModifier,
                title = stringResource(R.string.home_no_source_title),
                body = stringResource(R.string.home_no_source_body),
                actionLabel = stringResource(R.string.home_go_settings),
                onAction = onOpenSettings,
            )

            SourcePhase.LOADING -> Box(
                modifier = bodyModifier,
                contentAlignment = Alignment.Center,
            ) {
                LoadingIndicator(color = MaterialTheme.colorScheme.primary)
            }

            SourcePhase.FAILED -> SourceNotice(
                modifier = bodyModifier,
                title = stringResource(R.string.home_source_failed_title),
                body = status.message,
                actionLabel = stringResource(R.string.home_retry),
                onAction = { scope.launch { sources.applyConfig(status.configUrl) } },
                secondaryLabel = stringResource(R.string.home_go_settings),
                onSecondary = onOpenSettings,
            )

            SourcePhase.READY -> key(status.activeSourceId) {
                /*
                 * key 用 activeSourceId：换来源时整棵子树重建，翻页位置、
                 * 选中的分类、已加载的列表全部跟着重置。不重建的话会出现
                 * "换了源但还停在上一个源的第三个分类上"这种串数据。
                 *
                 * ⚠️ `bodyModifier` 必须传下去。漏掉它不会报错、也不会崩，
                 * 只会让整页内容从屏幕最顶端开始铺 —— 也就是**首屏被顶栏盖住**，
                 * 刊头永远差一截露不出来。装机验证时踩过：滚动到底再回到顶部，
                * 精选卡的上半截还是压在顶栏底下，看着像"滚动没到顶"。
                 */
                HomeFeed(
                    modifier = bodyModifier,
                    content = content,
                    activeSourceId = status.activeSourceId,
                    onVodClick = onVodClick,
                )
            }
        }
    }
}

/** 内容页。 */
@Composable
private fun HomeFeed(
    content: ContentRepository,
    activeSourceId: String,
    onVodClick: (Vod) -> Unit,
    modifier: Modifier = Modifier,
) {
    val categoriesState = loadState(activeSourceId) { content.categories() }
    val categories = (categoriesState as? LoadState.Ready)?.value.orEmpty()

    var selectedIndex by remember { mutableIntStateOf(0) }
    // 分类还没到（或为空）时先按「推荐」请求 —— 那一个一定存在，不必等分类列表
    val selectedId = categories.getOrNull(selectedIndex)?.id
        ?: ContentRepository.CATEGORY_RECOMMEND

    val vodsState = loadState(activeSourceId, selectedId) {
        content.listByCategory(selectedId)
    }
    val vods = (vodsState as? LoadState.Ready)?.value.orEmpty()

    /*
     * 精选：从**当前筛选结果**里按评分取前几张。
     *
     * 不用全量数据取 —— 用户切到「动漫」却看到一张剧集的精选卡，那是在跟筛选打架。
     * 评分缺失（相当一部分源不给 `vod_score`）时全部按 0 排，`sortedByDescending`
     * 是稳定排序，于是实际退化成"取前几张"，顺序仍然是源给的顺序。
     */
    val featured = remember(vods) {
        vods.sortedByDescending { it.score.toDoubleOrNull() ?: 0.0 }
            .take(BeeDimens.featuredMaxCount)
    }

    LazyVerticalGrid(
        columns = GridCells.Fixed(BeeDimens.posterColumns),
        modifier = modifier,
        contentPadding = PaddingValues(
            start = BeeDimens.screenMargin,
            end = BeeDimens.screenMargin,
            top = BeeDimens.gapSmall,
            bottom = BeeDimens.gapHuge,
        ),
        horizontalArrangement = Arrangement.spacedBy(BeeDimens.gapSmall),
        verticalArrangement = Arrangement.spacedBy(BeeDimens.gapMedium),
    ) {
        /*
         * ⚠️ 刊头与筛选行**无条件**进网格，即使对应的列表还是空的。
         *
         * 这不是省一个 `if` —— 是为了让网格的**项结构从第一帧起就固定**。
         *
         * LazyGrid 的滚动位置不是「偏移量」，而是「首个可见项的 **key** + 偏移」。
         * 加载期间网格里只有版块标题和加载提示，锚点落在 `section` 上；数据到达后
         * 刊头与筛选行插到它前面，锚点逻辑会**按 key 把 `section` 重新钉回顶端**，
         * 整页于是被向上顶 264dp（刊头 200 + 两个 16dp 间距 + 筛选行 32）。
         * 后果是：**首页最该看到的刊头与筛选行直接滚出屏外**，用户不主动下拉
         * 就永远看不到精选横滑。
         *
         * 装机实测特征很好认：海报、分类、评分、封面全对，唯独最上面少两行；
         * `uiautomator` 里网格的第 0 个语义子节点就是版块标题（正常应是刊头）。
         * 排查时先怀疑"数据没到"，会白绕很久 —— 数据早就到了。
         *
         * 现在锚点是 `hero`（恒为第 0 项），插入内容不改变它的下标，偏移量就不动。
         * `HeroCarousel` 对空列表直接 return、空 `LazyRow` 高 0，所以空列表时不占地方。
         */
        item(key = "hero", span = { GridItemSpan(maxLineSpan) }) {
            HeroCarousel(vods = featured, onVodClick = onVodClick)
        }

        /*
         * 分类筛选。横向留白传 0 —— 网格项本身已经缩进了 screenMargin，
         * 再传一次就会缩进两倍（`BeeChipRow` 的文档里写明了这条）。
         */
        item(key = "categories", span = { GridItemSpan(maxLineSpan) }) {
            BeeChipRow(
                items = categories,
                selectedIndex = selectedIndex,
                onSelect = { selectedIndex = it },
                contentPadding = PaddingValues(0.dp),
            ) { category -> CategoryLabel(category) }
        }

        item(key = "section", span = { GridItemSpan(maxLineSpan) }) {
            SectionHeader(
                title = categoryTitle(categories.getOrNull(selectedIndex)),
                count = if (vodsState is LoadState.Ready) vods.size else null,
            )
        }

        when {
            vodsState is LoadState.Loading ->
                item(key = "loading", span = { GridItemSpan(maxLineSpan) }) {
                    CenteredNotice(
                        text = stringResource(R.string.home_loading),
                        modifier = Modifier.padding(vertical = BeeDimens.gapHuge),
                    )
                }

            vodsState is LoadState.Failed ->
                item(key = "error", span = { GridItemSpan(maxLineSpan) }) {
                    CenteredNotice(
                        text = stringResource(R.string.home_load_failed, vodsState.message),
                        modifier = Modifier.padding(vertical = BeeDimens.gapHuge),
                    )
                }

            vods.isEmpty() ->
                item(key = "empty", span = { GridItemSpan(maxLineSpan) }) {
                    CenteredNotice(
                        text = stringResource(R.string.home_category_empty),
                        modifier = Modifier.padding(vertical = BeeDimens.gapHuge),
                    )
                }

            else -> items(vods, key = { it.id }) { vod ->
                PosterCard(vod = vod, onClick = { onVodClick(vod) })
            }
        }
    }
}

/**
 * 分类 chip 的文案。
 *
 * 「推荐」这个位置的**名称由界面提供**，数据层返回的 name 是空串
 * （见 `VodContentRepository.categories`）—— 中文文案不该写死在数据层，
 * 否则换语言、或者把这个位置改成别的语义，都要去动数据层。
 */
@Composable
private fun CategoryLabel(category: Category) {
    Text(
        text = categoryTitle(category),
        style = MaterialTheme.typography.labelLarge,
    )
}

@Composable
private fun categoryTitle(category: Category?): String =
    if (category == null || category.id == ContentRepository.CATEGORY_RECOMMEND) {
        stringResource(R.string.home_category_recommend)
    } else {
        category.name
    }

/**
 * 版块标题。
 *
 * 之前海报网格上方什么都没有 —— 内容直接开始铺，读者不知道这是哪个分类、
 * 一共几条。刊物的做法是先给一行栏目名，再上内容。
 *
 * 这里是全 App 唯一一处**两种字体族直接相邻**的地方：栏目名走衬线
 * （brand 槽，`headlineSmall`），条数走无衬线（plain 槽，`labelLarge`）。
 * M3 把字体分成这两个槽，本来就是为了让它们各司其职地出现在同一版面上。
 *
 * [count] 为 null 表示还没数出来（加载中）—— 这时不显示，好过显示一个
 * 先跳 0 再跳 N 的数字。
 *
 * 没有加分割线：本项目的层级一律由 `surface` 五级容器色承担，
 * 不靠线切（见 DetailScreen 的说明）。这里只用字重和字号拉开。
 */
@Composable
private fun SectionHeader(title: String, count: Int?) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = BeeDimens.gapTiny, bottom = BeeDimens.gapTight),
        horizontalArrangement = Arrangement.SpaceBetween,
        // 底对齐：24sp 的栏目名和 14sp 的条数坐在同一条基线上，才不会飘
        verticalAlignment = Alignment.Bottom,
    ) {
        Text(
            text = title,
            style = MaterialTheme.typography.headlineSmallEmphasized,
            color = MaterialTheme.colorScheme.onSurface,
        )
        if (count != null) {
            Text(
                text = stringResource(R.string.home_section_count, count),
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/** 居中一行说明。用于网格里的加载中 / 出错 / 空列表。 */
@Composable
private fun CenteredNotice(text: String, modifier: Modifier = Modifier) {
    Text(
        text = text,
        modifier = modifier.fillMaxWidth(),
        textAlign = TextAlign.Center,
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

/**
 * 整页的状态提示（未配置来源 / 装载失败）。
 *
 * 不是"空态组件"—— 它只出现在这一个页面，而且**必须带一个动作**：
 * 一页说"还没有内容源"却不给入口，等于把用户丢在死路上。
 */
@Composable
private fun SourceNotice(
    title: String,
    body: String,
    actionLabel: String,
    onAction: () -> Unit,
    modifier: Modifier = Modifier,
    secondaryLabel: String? = null,
    onSecondary: (() -> Unit)? = null,
) {
    Box(modifier = modifier, contentAlignment = Alignment.Center) {
        Column(
            modifier = Modifier.padding(horizontal = BeeDimens.gapHuge),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleLargeEmphasized,
                color = MaterialTheme.colorScheme.onSurface,
                textAlign = TextAlign.Center,
            )
            Spacer(Modifier.height(BeeDimens.gapSmall))
            Text(
                text = body,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
            Spacer(Modifier.height(BeeDimens.gapLarge))
            Button(onClick = onAction) {
                Text(
                    text = actionLabel,
                    style = MaterialTheme.typography.labelLargeEmphasized,
                )
            }
            if (secondaryLabel != null && onSecondary != null) {
                TextButton(onClick = onSecondary) {
                    Text(text = secondaryLabel, style = MaterialTheme.typography.labelLarge)
                }
            }
        }
    }
}

// ------------------------------------------------------------------ 预览

/**
 * 预览用的假来源状态。
 *
 * 预览环境跑不了网络、也不可能装出一棵配置好的来源树，所以给一个"永远就绪"的
 * 假状态。[com.cycling.beevideo.data.repository.DemoContentRepository] 负责另一半
 * （内容数据）—— 这两个类现在都只为预览存在，见各自的说明。
 */
private class PreviewSourceRepository : ContentSourceRepository {
    override val status = MutableStateFlow(
        SourceStatus(
            phase = SourcePhase.READY,
            configUrl = "",
            sources = listOf(ContentSource(id = "demo", name = "示例来源")),
            activeSourceId = "demo",
            message = "",
        )
    )

    override suspend fun restore() = Unit
    override suspend fun applyConfig(url: String): String? = null
    override fun selectSource(sourceId: String) = Unit
    override suspend fun clear() = Unit
}

@Preview(
    name = "首页 · 手机 411",
    group = "页面",
    showBackground = true,
    backgroundColor = 0xFF0B0A08,
    widthDp = 411,
    heightDp = 891,
)
@Composable
private fun HomeScreenPreview() {
    BeeVideoTheme(darkTheme = true) {
        HomeScreen(
            content = DemoContentRepository(),
            sources = PreviewSourceRepository(),
            onVodClick = {},
            onOpenSettings = {},
        )
    }
}

@Preview(
    name = "首页 · 浅色",
    group = "页面",
    showBackground = true,
    backgroundColor = 0xFFFFF9EF,
    widthDp = 411,
    heightDp = 891,
)
@Composable
private fun HomeScreenLightPreview() {
    BeeVideoTheme(darkTheme = false) {
        HomeScreen(
            content = DemoContentRepository(),
            sources = PreviewSourceRepository(),
            onVodClick = {},
            onOpenSettings = {},
        )
    }
}

@Preview(
    name = "首页 · 未配置内容源",
    group = "页面",
    showBackground = true,
    backgroundColor = 0xFF0B0A08,
    widthDp = 411,
    heightDp = 700,
)
@Composable
private fun HomeScreenNoSourcePreview() {
    BeeVideoTheme(darkTheme = true) {
        SourceNotice(
            modifier = Modifier.fillMaxSize(),
            title = stringResource(R.string.home_no_source_title),
            body = stringResource(R.string.home_no_source_body),
            actionLabel = stringResource(R.string.home_go_settings),
            onAction = {},
        )
    }
}
