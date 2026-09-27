package com.cycling.beevideo.ui.home

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.cycling.beevideo.R
import kotlinx.coroutines.flow.distinctUntilChanged
import com.cycling.beevideo.domain.model.PlayProgress
import com.cycling.beevideo.domain.model.Vod
import com.cycling.beevideo.domain.model.isFinished
import com.cycling.beevideo.domain.repository.ContentRepository
import com.cycling.beevideo.domain.repository.LibraryRepository
import com.cycling.beevideo.ui.components.BeeCenteredNotice
import com.cycling.beevideo.ui.components.BeeChipRow
import com.cycling.beevideo.ui.components.LoadState
import com.cycling.beevideo.ui.components.PosterCard
import com.cycling.beevideo.ui.components.SkeletonChipRow
import com.cycling.beevideo.ui.components.SkeletonPosterGrid
import com.cycling.beevideo.ui.components.skeletonSemantics
import com.cycling.beevideo.ui.theme.BeeDimens

/** 海报墙骨架的行数。一屏正好看得见两行。 */
private const val HOME_SKELETON_ROWS = 2

/** 触底预取提前多少项发请求 —— 一行。等真正见底才发，用户会先看到一段空白。 */
private val PRELOAD_ITEMS = BeeDimens.posterColumns

/** 微光错峰相位：分类行 [0,3]、海报墙从 3 起。两段不能重叠，重叠会同相。 */
private const val CHIP_STAGGER_SPAN = 3

/** 内容页：整页只有**一个**网格，继续观看 / 筛选行 / 版块标题都是它的整行项。 */
@Composable
internal fun HomeFeed(
    content: ContentRepository,
    library: LibraryRepository,
    activeSourceId: String,
    onVodClick: (Vod) -> Unit,
    onResume: (PlayProgress) -> Unit,
    onOpenHistory: () -> Unit,
    modifier: Modifier = Modifier,
) {
    // 挂 ViewModel 是因为主题切换会重建 Activity，而选中的分类必须活下来
    val viewModel: HomeFeedViewModel = viewModel(
        factory = viewModelFactory { initializer { HomeFeedViewModel(content) } },
    )
    val state = viewModel.state

    // 持有者不随来源变化重建，换来源要显式说一声
    LaunchedEffect(activeSourceId) { state.setSource(activeSourceId) }

    val categoriesState by state.categories.collectAsStateWithLifecycle()
    val categories = (categoriesState as? LoadState.Ready)?.value.orEmpty()

    val vodsState by state.vods.collectAsStateWithLifecycle()
    val vods = (vodsState as? LoadState.Ready)?.value.orEmpty()

    // 局部变量：by 委托出来的值不能智能转换，而下面多处要按类型分支
    val currentCategoriesState = categoriesState
    val currentVodsState = vodsState

    val selectedIndex = state.selectedIndex

    val gridState = rememberLazyGridState()

    /*
     * 触底预取。判据是「最后可见项的下标」而不是滚动偏移 —— 这一页的项高不等
     * （整行项与网格项混排），偏移量换算不出"还剩几行"。
     * `loadMore` 自己会挡掉"正在拉 / 已经到底"，所以这里不必再去重。
     */
    LaunchedEffect(gridState) {
        snapshotFlow {
            val info = gridState.layoutInfo
            (info.visibleItemsInfo.lastOrNull()?.index ?: -1) to info.totalItemsCount
        }
            .distinctUntilChanged()
            .collect { (lastVisible, total) ->
                if (total > 0 && lastVisible >= total - 1 - PRELOAD_ITEMS) state.loadMore()
            }
    }

    // 已看完的不进「继续观看」（那是"接着看"的反面），但仍留在历史页里。
    // ⚠️ 取数必须在这一层做完：下面的 LazyVerticalGrid { } lambda 不是 @Composable 上下文，
    // 在里头调 collectAsStateWithLifecycle 编译不过
    val resumeAll: List<PlayProgress>? by
        library.progressList.collectAsStateWithLifecycle(initialValue = null)
    val resumeItems = remember(resumeAll) {
        resumeAll.orEmpty().filterNot { it.isFinished() }.take(BeeDimens.resumeMaxCount)
    }
    // 「今天 / 昨天」的参照时刻，列表一变就重算
    val resumeNow = remember(resumeItems) { System.currentTimeMillis() }

    LazyVerticalGrid(
        columns = GridCells.Fixed(BeeDimens.posterColumns),
        modifier = modifier,
        state = gridState,
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
         * ⚠️ 筛选行**无条件**进网格，且必须恒为第 0 项。
         *
         * LazyGrid 的滚动位置是「首个可见项的 key + 偏移」而不是偏移量：锚点若落在条件项上，
         * 该项出现/消失时整页会被重新钉回顶端，进首页白跳一截。恒存在的项只有筛选行，
         * 所以由它领头；「继续观看」是条件项，只能排在它之后。
         */
        // 横向留白传 0：网格项本身已缩进 screenMargin，再传一次就是两倍
        item(key = "categories", span = { GridItemSpan(maxLineSpan) }, contentType = "row") {
            if (currentCategoriesState is LoadState.Loading) {
                SkeletonChipRow(staggerIndex = 0)
            } else {
                BeeChipRow(
                    items = categories,
                    selectedIndex = selectedIndex,
                    onSelect = state::selectCategory,
                    contentPadding = PaddingValues(0.dp),
                ) { category -> CategoryLabel(category) }
            }
        }

        // 位置在筛选行之后：它回答"我上次看到哪"，但不是第一眼就该看到的
        if (resumeItems.isNotEmpty()) {
            item(key = "resume", span = { GridItemSpan(maxLineSpan) }, contentType = "row") {
                ResumeSection(
                    items = resumeItems,
                    now = resumeNow,
                    onOpen = onResume,
                    onOpenAll = onOpenHistory,
                )
            }
        }

        item(key = "section", span = { GridItemSpan(maxLineSpan) }, contentType = "row") {
            SectionHeader(
                title = categoryTitle(categories.getOrNull(selectedIndex)),
                count = if (currentVodsState is LoadState.Ready) vods.size else null,
            )
        }

        when {
            currentVodsState is LoadState.Loading ->
                item(key = "loading", span = { GridItemSpan(maxLineSpan) }, contentType = "row") {
                    // 那句「正在读取…」转成了 contentDescription：骨架块没有文字节点，
                    // 不给读屏一句说明的话加载期间只会念出空白
                    SkeletonPosterGrid(
                        modifier = Modifier.skeletonSemantics(
                            stringResource(R.string.home_loading)
                        ),
                        rows = HOME_SKELETON_ROWS,
                        startStaggerIndex = CHIP_STAGGER_SPAN,
                    )
                }

            currentVodsState is LoadState.Failed ->
                item(key = "error", span = { GridItemSpan(maxLineSpan) }, contentType = "row") {
                    BeeCenteredNotice(
                        text = stringResource(R.string.home_load_failed, currentVodsState.message),
                        modifier = Modifier.padding(vertical = BeeDimens.gapHuge),
                    )
                }

            vods.isEmpty() ->
                item(key = "empty", span = { GridItemSpan(maxLineSpan) }, contentType = "row") {
                    BeeCenteredNotice(
                        text = stringResource(R.string.home_category_empty),
                        modifier = Modifier.padding(vertical = BeeDimens.gapHuge),
                    )
                }

            // contentType 按形态分开：整行项与海报项的高度差一个数量级，混在同一个
            // 复用池里会让每次滚动都重新测量一次子项
            else -> items(vods, key = { it.id }, contentType = { "poster" }) { vod ->
                PosterCard(vod = vod, onClick = { onVodClick(vod) })
            }
        }

        // 追加页的状态行，整行排在所有网格项之后。首屏失败/空态时不摆 —— 那两种已经有整页提示了
        if (vods.isNotEmpty()) {
            item(key = "more", span = { GridItemSpan(maxLineSpan) }, contentType = "footer") {
                // ⚠️ 状态在**这里**读，不提到函数顶层：翻页状态每变一次（开始拉 / 拉完 / 到底）
                // 都只重组这一项；提上去就是整页连十几张海报一起重组
                val more by state.more.collectAsStateWithLifecycle()
                MoreFooter(more = more, onRetry = state::loadMore)
            }
        }
    }
}
