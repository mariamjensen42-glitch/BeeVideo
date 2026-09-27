package com.cycling.beevideo.ui.home

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.setValue
import com.cycling.beevideo.domain.model.Category
import com.cycling.beevideo.domain.model.Vod
import com.cycling.beevideo.domain.repository.ContentRepository
import com.cycling.beevideo.ui.components.LoadState
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * 首页内容区的状态持有者 —— 两次加载以及它们之间那条**依赖**。
 *
 * ─── 它存在解决的问题 ──────────────────────────────────────────────────
 * 以前这两次 `loadState` 写在 composable 里，而它们的关系是：
 *
 * ```
 * categories()  ──→  选中项决定 listByCategory 的 tid
 * ```
 *
 * 这条依赖**只写在注释里**（"分类还没到（或为空）时先按「推荐」请求"），
 * 靠 `loadState` 的 key 变化隐式触发第二发。后果：
 *
 *   1. 顺序与回退规则（分类没到 / 分类为空 / 分类加载失败时都按「推荐」）
 *      无法被验证，只能靠真机点；
 *   2. 选中的分类在 Activity 重建后归零 —— 而分类 id 在不同站点之间会**撞车**
 *      （家家都有个 `tid=1`），所以"归零"不是回到推荐，是回到**第一个分类**；
 *   3. 两次加载没有页面级取消点。
 *
 * ─── 换来源为什么要有显式入口 ──────────────────────────────────────────
 * 持有者挂在 ViewModel 上，**不随来源变化重建**（ViewModel 的作用域是导航栈那一条，
 * 不是组合子树）。所以换来源必须显式 [setSource]：它重置选中分类并重新拉一遍。
 * 漏掉这一步的表现是"换了源，分类还是上一个源的" —— 也就是当初 `key(activeSourceId)`
 * 要解决的问题，只是换了个地方要解决。
 */
class HomeFeedState(
    private val content: ContentRepository,
    /** 加载用的作用域，**由宿主提供**（生产是 `viewModelScope`）。 */
    private val scope: CoroutineScope,
) {

    private val _categories = MutableStateFlow<LoadState<List<Category>>>(LoadState.Loading)
    val categories: StateFlow<LoadState<List<Category>>> = _categories.asStateFlow()

    private val _vods = MutableStateFlow<LoadState<List<Vod>>>(LoadState.Loading)
    val vods: StateFlow<LoadState<List<Vod>>> = _vods.asStateFlow()

    /** 当前选中的分类下标。放这里而不是 `remember` —— 主题切换会重建 Activity。 */
    var selectedIndex: Int by mutableIntStateOf(0)
        private set

    /**
     * 追加页的状态。与首屏那份 [vods] 分开：首屏是「加载中 / 好 / 坏」三态，
     * 而追加同时要表达三件互不排斥的事 —— 在拉、上一发失败、后面还有没有。
     */
    private val _more = MutableStateFlow(MorePages())
    val more: StateFlow<MorePages> = _more.asStateFlow()

    private var sourceId: String? = null
    private var categoriesJob: Job? = null
    private var vodsJob: Job? = null
    private var moreJob: Job? = null

    /** 已加载到第几页。只由 [reloadVods] 与 [loadMore] 改写。 */
    private var page = 1

    /**
     * 当前要拉的分类 id。
     *
     * 分类**还没到、为空、或加载失败**时一律按「推荐」：那一个一定存在
     * （`VodContentRepository.categories` 会在首位插入它），所以不必等分类列表就能
     * 先出内容。首页第一屏因此从"一串筛选按钮"变成"有东西可看"。
     */
    val selectedCategoryId: String
        get() = readyCategories()?.getOrNull(selectedIndex)?.id
            ?: ContentRepository.CATEGORY_RECOMMEND

    /** 换来源。**必须**在来源变化时调用，见类注释。 */
    fun setSource(id: String) {
        if (sourceId == id) return
        sourceId = id
        // 分类 id 在不同站点之间会撞车，换源后选中项必须归零
        selectedIndex = 0
        reloadCategories()
    }

    fun selectCategory(index: Int) {
        if (selectedIndex == index) return
        selectedIndex = index
        reloadVods()
    }

    /** 拉分类；到手之后接着拉内容 —— 这条依赖是本类存在的理由。 */
    fun reloadCategories() {
        if (sourceId == null) return
        categoriesJob?.cancel()
        categoriesJob = scope.launch {
            _categories.value = LoadState.Loading
            _categories.value = load { content.categories() }
            // 分类到手才知道该拉哪个分类的内容。以前这一步是 loadState 的 key 变化隐式做的
            reloadVods()
        }
    }

    /** 拉当前分类的**第一页**。 */
    fun reloadVods() {
        if (sourceId == null) return
        val categoryId = selectedCategoryId
        // 取消上一次：快速连点分类时，先发的那个可能后到，把新分类的内容盖回旧的
        vodsJob?.cancel()
        // 追加那一路也要停：留着的话上一分类的第 2 页会追加到新分类下面
        moreJob?.cancel()
        page = 1
        _more.value = MorePages()
        vodsJob = scope.launch {
            _vods.value = LoadState.Loading
            when (val result = load { content.listByCategory(categoryId) }) {
                is LoadState.Ready -> {
                    _vods.value = LoadState.Ready(result.value.vods)
                    _more.value = MorePages(hasMore = result.value.hasMoreAfter(1))
                }
                // 首屏就失败时不再摆出「加载更多」—— 界面已经在整页报错了
                is LoadState.Failed -> _vods.value = result
                LoadState.Loading -> Unit
            }
        }
    }

    /**
     * 追加下一页。正在拉、或者已经没有下一页时是**空操作** ——
     * 界面的触底事件会连着调好几次，去重放在这里比放在界面里可靠。
     */
    fun loadMore() {
        if (sourceId == null) return
        val current = _more.value
        if (current.loading || !current.hasMore) return

        val categoryId = selectedCategoryId
        val next = page + 1
        moreJob?.cancel()
        moreJob = scope.launch {
            _more.value = current.copy(loading = true, error = null)
            when (val result = load { content.listByCategory(categoryId, next) }) {
                is LoadState.Ready -> {
                    // ⚠️ 这一发飞了这么久，分类可能已经换了：整发丢弃。不丢的话上一分类的
                    // 第 2 页会追加到新分类的列表下面，而 page 也跟着串了
                    if (categoryId != selectedCategoryId) return@launch
                    page = next
                    val grew = append(result.value.vods)
                    _more.value = MorePages(
                        // 一条新条目都没进来也算到底：源拿重复项充数时靠这句收尾，否则会一直拉
                        hasMore = result.value.hasMoreAfter(next) && grew,
                    )
                }
                is LoadState.Failed -> {
                    if (categoryId != selectedCategoryId) return@launch
                    // 保留 hasMore：界面靠它给重试入口，失败不该把后面的内容一并作废
                    _more.value = _more.value.copy(loading = false, error = result.message)
                }
                LoadState.Loading -> Unit
            }
        }
    }

    /**
     * 追加一页，按 id 去重。返回**有没有新条目** —— 源翻页时常有重复项，
     * 而 LazyGrid 的 key 一旦撞车是直接崩，不是显示两张。
     */
    private fun append(more: List<Vod>): Boolean {
        val current = (_vods.value as? LoadState.Ready)?.value ?: return false
        val merged = (current + more).distinctBy { it.id }
        if (merged.size == current.size) return false
        _vods.value = LoadState.Ready(merged)
        return true
    }

    private fun readyCategories(): List<Category>? =
        (_categories.value as? LoadState.Ready)?.value

    /**
     * 成败都在这里收敛。失败**只转成状态**、不抛出去 —— 界面每一处都要处理失败；
     * 取消原样重抛，因为它不是失败，是"这次不算数"。
     */
    private suspend fun <T> load(block: suspend () -> T): LoadState<T> = try {
        LoadState.Ready(block())
    } catch (e: CancellationException) {
        throw e
    } catch (e: Throwable) {
        LoadState.Failed(e.message ?: "加载失败")
    }
}

/**
 * 追加页的三件事。**分成三个字段而不是一个枚举**：拉取中失败可以同时为真
 * （上一发挂了、用户又滑到底触发了一次），而"还有没有下一页"与两者都不冲突。
 *
 * @param hasMore 后面还有内容。false 时界面在网格末尾交代一句「已显示全部」，首屏也是 false（还没问出来）。
 * @param error 上一发追加的失败原因。**不影响已显示的列表** —— 只是网格末尾多一行重试。
 */
data class MorePages(
    val loading: Boolean = false,
    val error: String? = null,
    val hasMore: Boolean = false,
)
