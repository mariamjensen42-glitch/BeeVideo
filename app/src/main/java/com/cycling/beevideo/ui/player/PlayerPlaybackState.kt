package com.cycling.beevideo.ui.player

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.setValue
import com.cycling.beevideo.domain.model.PlayProgress
import com.cycling.beevideo.domain.model.PlayTarget
import com.cycling.beevideo.domain.model.PlaybackState
import com.cycling.beevideo.domain.repository.LibraryRepository
import com.cycling.beevideo.domain.repository.PlaybackSession
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * 播放页的状态持有者 —— 播放的**策略**那一半。
 *
 * ─── 职责边界（三段，各管各的）─────────────────────────────────────────
 * - [PlaybackSession]：内核事实。位置、时长、状态、起播与释放。
 * - 本类：**策略**。什么时候上报进度 —— 周期 / 退到后台 / 切集前 / 收场时。
 * - [LibraryRepository]：节流。多久落一次库是存储侧的知识（见 `saveProgress` 的说明）。
 *
 * ─── 为什么不是 ViewModel ─────────────────────────────────────────────
 * 它需要一个 [CoroutineScope] 来跑周期上报，而 `viewModelScope` 在构造期拿不到
 * （它是 ViewModel 的扩展属性）。更实际的理由是**可测**：作用域由外面给，
 * 单测就能用 `TestScope.backgroundScope` 跑虚拟时间，不必替换 `Dispatchers.Main`。
 * 跨配置变化存活由薄薄一层 [PlayerViewModel] 负责 —— 那件事只有 ViewModel 能做。
 *
 * ─── 它存在解决的问题 ─────────────────────────────────────────────────
 * 以前播放的全部状态都住在 `PlayerScreen` 的 `remember` 里，而 `AndroidManifest`
 * 的 `configChanges` **不含 `uiMode`** —— 也就是说**这个 App 自己的主题切换**
 * 会重建 Activity。重建后集号回到路由参数那一集，而库里的进度属于另一集，
 * `resumePositionMs` 要求集号严格相等于是返回 0：看到第 8 集 12:40，
 * 去设置页选个深色，回来停在第 1 集 0:00 —— 没有报错、没有日志。
 */
class PlayerPlaybackState(
    /** 会话由宿主造好交进来（它要 `Context` / ExoPlayer），本类只负责用它。 */
    val session: PlaybackSession,
    private val library: LibraryRepository,
    private val vodId: String,
    initialEpisodeIndex: Int,
    /** 周期上报用的作用域，**由宿主提供**（生产是 `viewModelScope`）。 */
    scope: CoroutineScope,
    /** 上报间隔。抽成参数是为了单测能用虚拟时间，不必真等一秒。 */
    private val reportIntervalMs: Long = PROGRESS_REPORT_INTERVAL_MS,
    /** 取当前时间。注入是为了让记录里的 `updatedAt` 在测试里确定。 */
    private val now: () -> Long = System::currentTimeMillis,
) {

    /**
     * 当前集号。
     *
     * 放在持有者里而不是 `remember` —— 主题切换会重建 Activity，而集号必须活下来：
     * 它是 `resumePositionMs` 判定"这条进度是不是这一集的"的依据。
     */
    var episodeIndex: Int by mutableIntStateOf(initialEpisodeIndex)
        private set

    val state: StateFlow<PlaybackState> = session.state

    /** 线路名与剧集名。详情加载完 / 切集时由界面推进来 —— 落进度要用它们。 */
    private var lineName = ""
    private var episodeName = ""

    /*
     * 周期上报。
     *
     * **不在这里去重** —— 去重是仓储的事（5 秒窗口）。持有者只管如实汇报；
     * 自己掐表的话，哪天仓储改了窗口就得同时改两处。
     *
     * 只在**真的在播**时上报：暂停时位置不动，写进去的是一模一样的行。
     */
    private val reportingJob = scope.launch {
        while (isActive) {
            delay(reportIntervalMs)
            if (session.state.value == PlaybackState.Playing) save(force = false)
        }
    }

    /** 界面把"现在是哪条线路、哪一集"推过来。它们随详情加载与切集变化。 */
    fun bindContext(lineName: String, episodeName: String) {
        this.lineName = lineName
        this.episodeName = episodeName
    }

    /**
     * 剧集列表变了（换线路 / 详情刚加载完）时调用。
     *
     * 集号**在这里夹取**而不是在界面里：界面夹取的话，持有者记账用的还是越界那个，
     * 落进库里的就是一条指向不存在剧集的进度。
     */
    fun onEpisodesChanged(count: Int) {
        if (count > 0 && episodeIndex > count - 1) episodeIndex = count - 1
    }

    fun open(target: PlayTarget, resumeAtMs: Long) {
        session.open(target, resumeAtMs)
    }

    /**
     * 切集。
     *
     * **先落库再切**：切完会话已经在新集上，那时读位置拿到的是新集的 0 ——
     * 这是历史上真实踩过的顺序错误。
     */
    fun selectEpisode(index: Int) {
        save(force = true)
        episodeIndex = index
    }

    /** 退到后台。系统随后可能回收进程，那几秒进度不能丢。 */
    fun onStop() {
        save(force = true)
    }

    /**
     * 落一次进度。
     *
     * @param force 切集 / 退出 / 退到后台传 true，那一刻的位置必须落库、不被节流窗口吞掉
     */
    fun save(force: Boolean) {
        val positionMs = session.positionMs()
        // 位置为 0 表示还没起播，没有可记的东西
        if (positionMs <= 0L) return
        library.saveProgress(
            PlayProgress(
                vodId = vodId,
                lineName = lineName,
                episodeIndex = episodeIndex,
                episodeName = episodeName,
                positionMs = positionMs,
                // 来源没给时长时是 0（domain 里 0 = 不知道时长）
                durationMs = session.durationMs(),
                updatedAt = now(),
            ),
            force = force,
        )
    }

    /**
     * 收场：先落一次进度，再停上报、释放内核。
     *
     * 顺序上"先落再关"是刻意的 —— 虽然 [PlaybackSession.positionMs] 保证关掉之后
     * 还读得到，但那时读到的只是最后一份快照；这里读的是活的位置。
     */
    fun release() {
        save(force = true)
        reportingJob.cancel()
        session.close()
    }

    private companion object {
        /** 如实汇报的粒度，不是写库频率 —— 真正写库由仓储按 5 秒窗口折叠。 */
        const val PROGRESS_REPORT_INTERVAL_MS = 1_000L
    }
}
