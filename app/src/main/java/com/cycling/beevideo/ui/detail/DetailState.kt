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
 * 加载与编排在这里，跨重建存活由薄薄一层 [DetailViewModel] 负责；作用域由外面给，
 * 所以单测能用 `TestScope.backgroundScope` 驱动它，不必替换 `Dispatchers.Main`。
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
     * ⚠️ 选中的线路必须住在持有者里而不是 `remember`：主题切换会重建 Activity，
     * 而进度是按「线路名 + 集号」定位的，线路丢了进度条也就对不上了。
     */
    var lineIndex: Int by mutableIntStateOf(0)
        private set

    /**
     * 用户自己点过线路之后进度就不再改它。
     * ⚠️ 同样要住持有者里：这个标志丢了，用户手动选的线路会在下一次进度重读时被顶掉。
     */
    private var lineChosenByUser = false

    // ⚠️ 收藏状态**订阅**而不是读一次：本页按钮会改它，必须立刻反映到图标上。
    // （进度相反 —— 进度是"进来时看到哪"，播放时每秒的写入不该让这一页反复重组。）
    val isKept: StateFlow<Boolean> =
        library.isKept(vodId).stateIn(scope, SharingStarted.Eagerly, initialValue = false)

    private var vodJob: Job? = null
    private var progressJob: Job? = null

    init {
        // 详情是不变的，进来加载一次就够（列表项字段不全，必须再问一次来源）
        vodJob = scope.launch {
            _vod.value = load { content.detail(vodId) }
            // 详情到手才知道"线路名 → 线路号"这个映射，反过来换不了
            applyProgressLine()
        }
    }

    /**
     * 重读进度。从播放页返回时由界面调用：那一页刚往库里写过。
     * 「不订阅」这条取舍的代价就是"要在正确的时刻主动读一次"。
     */
    fun refreshProgress() {
        // 取消上一次：快速来回时先发的那个可能后到，把新读的值盖回旧的
        progressJob?.cancel()
        progressJob = scope.launch {
            _progress.value = load { library.progressOf(vodId) }
            applyProgressLine()
        }
    }

    fun selectLine(index: Int) {
        lineChosenByUser = true
        lineIndex = index
    }

    /**
     * 用观看进度里记的那条线路来选中线路。
     *
     * 详情与进度是**两个异步**，谁先到不确定，所以两边各自到齐后都调一次。
     * ⚠️ 找不到同名线路时什么都不做：源改过线路名的话，按序号硬套会把用户带到
     * 另一条线路上，界面上表现为"进度对不上"。
     */
    private fun applyProgressLine() {
        if (lineChosenByUser) return
        val lines = (_vod.value as? LoadState.Ready)?.value?.lines ?: return
        val progress = (_progress.value as? LoadState.Ready)?.value ?: return
        val index = lines.indexOfFirst { it.name == progress.lineName }
        if (index >= 0) lineIndex = index
    }

    /** 收藏 / 取消收藏。`vod` 是快照字段的来源。 */
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
     * 加载的成败都在这里收敛。失败**只转成状态**、不抛出去（界面每处都要处理失败，
     * 抛出去等于逼每个调用点写一遍 try）。取消原样重抛 —— 它不是失败，是"这次不算数"。
     */
    private suspend fun <T> load(block: suspend () -> T): LoadState<T> = try {
        LoadState.Ready(block())
    } catch (e: CancellationException) {
        throw e
    } catch (e: Throwable) {
        LoadState.Failed(e.message ?: "加载失败")
    }
}
