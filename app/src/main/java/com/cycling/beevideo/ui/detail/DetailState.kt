package com.cycling.beevideo.ui.detail

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.setValue
import com.cycling.beevideo.domain.model.KeepItem
import com.cycling.beevideo.domain.model.PlayProgress
import com.cycling.beevideo.domain.model.Vod
import com.cycling.beevideo.domain.repository.ContentRepository
import com.cycling.beevideo.domain.repository.LibraryRepository
import com.cycling.beevideo.ui.components.LoadState
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/**
 * 详情页的状态持有者 —— 这一页的加载与编排。
 *
 * ─── 它存在解决的问题 ──────────────────────────────────────────────────
 * 以前这些全在 `DetailScreen` 的函数体里：两次 `loadState`、一条 `isKept` 订阅、
 * 一个 `remember` 出来的线路号。三件事的实际后果：
 *
 *   1. **线路号在主题切换后归零**。`AndroidManifest` 的 `configChanges` 不含
 *      `uiMode`，这个 App 自己的主题切换会重建 Activity —— 回来时用户选的线路没了，
 *      而按线路标记的进度条一起消失（`resumePositionMs` 要求线路名也对得上）。
 *   2. **"进度是进来时读一次、不订阅"这条取舍只写在注释里**，没人测得到它。
 *   3. 加载**没有页面级取消点**：页面走了，请求还在飞。
 *
 * ─── 与播放页持有者的分工一样 ──────────────────────────────────────────
 * 加载与编排在这里，跨重建存活由薄薄一层 [DetailViewModel] 负责；
 * 作用域由外面给，所以单测能用 `TestScope.backgroundScope` 驱动它，不必替换
 * `Dispatchers.Main`。
 */
class DetailState(
    private val content: ContentRepository,
    private val library: LibraryRepository,
    private val vodId: String,
    /** 加载用的作用域，**由宿主提供**（生产是 `viewModelScope`）。 */
    private val scope: CoroutineScope,
    /** 取当前时间。注入是为了让收藏记录里的 `createdAt` 在测试里确定。 */
    private val now: () -> Long = System::currentTimeMillis,
) {

    private val _vod = MutableStateFlow<LoadState<Vod?>>(LoadState.Loading)
    val vod: StateFlow<LoadState<Vod?>> = _vod.asStateFlow()

    private val _progress = MutableStateFlow<LoadState<PlayProgress?>>(LoadState.Loading)
    val progress: StateFlow<LoadState<PlayProgress?>> = _progress.asStateFlow()

    /**
     * 选中的线路。
     *
     * 放在持有者里而不是 `remember` —— 主题切换会重建 Activity。这条不只是"少点一次"：
     * 进度是按「线路名 + 集号」定位的，线路丢了，进度条也就对不上了。
     */
    var lineIndex: Int by mutableIntStateOf(0)
        private set

    /**
     * 收藏状态**订阅**而不是读一次：本页的按钮会改它，必须立刻反映到图标上。
     * （与进度相反 —— 进度是"进来时看到哪"，播放时每秒的写入不该让这一页反复重组。）
     */
    val isKept: StateFlow<Boolean> =
        library.isKept(vodId).stateIn(scope, SharingStarted.Eagerly, initialValue = false)

    private var vodJob: Job? = null
    private var progressJob: Job? = null

    init {
        // 详情是**不变的**，进来加载一次就够（列表项字段不全，必须再问一次来源）
        vodJob = scope.launch { _vod.value = load { content.detail(vodId) } }
    }

    /**
     * 重读进度。
     *
     * 从播放页返回时由界面调用：那一页刚往库里写过，这里不重读就还是进来时那一份。
     * 「不订阅」这条取舍的代价就是"要在正确的时刻主动读一次"—— 那就把它做成一个
     * 有名字的动作，而不是散在重组里的副作用。
     */
    fun refreshProgress() {
        // 取消上一次：快速来回时，先发的那个可能后到，把新读的值盖回旧的
        progressJob?.cancel()
        progressJob = scope.launch { _progress.value = load { library.progressOf(vodId) } }
    }

    fun selectLine(index: Int) {
        lineIndex = index
    }

    /** 收藏 / 取消收藏。`vod` 是快照字段的来源（见 `KeepItem` 的说明）。 */
    fun toggleKeep(vod: Vod) {
        scope.launch {
            library.toggleKeep(
                KeepItem(
                    vodId = vod.id,
                    name = vod.name,
                    pic = vod.pic,
                    score = vod.score,
                    remarks = vod.remarks,
                    createdAt = now(),
                ),
            )
        }
    }

    /**
     * 加载的成败都在这里收敛。
     *
     * 失败**只转成状态**、不抛出去：界面每一处都要处理失败，抛出去等于逼每个调用点
     * 写一遍 try。取消原样重抛 —— 它不是失败，是"这次不算数"。
     */
    private suspend fun <T> load(block: suspend () -> T): LoadState<T> = try {
        LoadState.Ready(block())
    } catch (e: CancellationException) {
        throw e
    } catch (e: Throwable) {
        LoadState.Failed(e.message ?: "加载失败")
    }
}
