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

    private var sourceId: String? = null
    private var categoriesJob: Job? = null
    private var vodsJob: Job? = null

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

    /** 拉当前分类的内容。 */
    fun reloadVods() {
        if (sourceId == null) return
        val categoryId = selectedCategoryId
        // 取消上一次：快速连点分类时，先发的那个可能后到，把新分类的内容盖回旧的
        vodsJob?.cancel()
        vodsJob = scope.launch {
            _vods.value = LoadState.Loading
            _vods.value = load { content.listByCategory(categoryId) }
        }
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
