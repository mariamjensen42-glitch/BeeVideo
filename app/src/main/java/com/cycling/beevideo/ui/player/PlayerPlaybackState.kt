package com.cycling.beevideo.ui.player

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.setValue
import com.cycling.beevideo.domain.model.PlayProgress
import com.cycling.beevideo.domain.model.PlayTarget
import com.cycling.beevideo.domain.model.PlaybackState
import com.cycling.beevideo.domain.model.Vod
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
 * 职责三段：会话管内核事实（位置 / 时长 / 状态），本类管策略（何时上报进度），
 * 仓储管节流（多久落一次库）。不用 ViewModel 是因为它需要构造期就拿到 `CoroutineScope`，
 * 且作用域由外面给才好测（单测用虚拟时间）；跨配置变化存活交给 [PlayerViewModel]。
 *
 * 它存在的原因：状态以前住在播放页的 `remember` 里，而 configChanges 不含 uiMode ——
 * 主题切换会重建 Activity，集号退回路由参数那一集，与库里的进度对不上。
 */
class PlayerPlaybackState(
    /** 会话由宿主造好交进来（它要 Context / ExoPlayer），本类只负责用它。 */
    val session: PlaybackSession,
    private val library: LibraryRepository,
    private val vodId: String,
    initialEpisodeIndex: Int,
    initialLineIndex: Int = 0,
    /** 一集播完要不要接下一集。进页面时读一次，与缓存配额同样的取舍。 */
    private val autoPlayNext: Boolean = true,
    /** 周期上报用的作用域，由宿主提供。 */
    scope: CoroutineScope,
    /** 上报间隔。抽成参数是为了单测能跑虚拟时间。 */
    private val reportIntervalMs: Long = PROGRESS_REPORT_INTERVAL_MS,
    /** 取当前时间。注入是为了让记录里的 updatedAt 在测试里确定。 */
    private val now: () -> Long = System::currentTimeMillis,
) {

    /** 当前集号。它必须活过 Activity 重建 —— 是 resumePositionMs 判定"这条进度是不是这一集的"的依据。 */
    var episodeIndex: Int by mutableIntStateOf(initialEpisodeIndex)
        private set

    /**
     * ⚠️ 当前线路号**不跟着路由参数走**：导航参数进页面就定死，靠它换线路要重新导航，
     * 结果是整个会话连同播放器重建（黑一下 + 落一次库 + 重开解码器）。
     */
    var lineIndex: Int by mutableIntStateOf(initialLineIndex)
        private set

    val state: StateFlow<PlaybackState> = session.state

    /** 位置与时长快照，供控件周期读取。住在持有者里，快照才不会在重建后先画 00:00 再跳一下。 */
    var positionMs: Long by mutableLongStateOf(0L)
        private set

    var durationMs: Long by mutableLongStateOf(0L)
        private set

    /** 当前倍速。初值从会话读 —— 它是内核级设置，会话可能活得比本类久。 */
    var speed: Float by mutableFloatStateOf(session.speed())
        private set

    /** 线路名与剧集名，落进度要用，由界面推进来。 */
    private var lineName = ""
    private var episodeName = ""

    /** 影片快照。落库时一起写进历史 —— 历史页必须在来源不可用时也能列出片名与封面。 */
    private var vodName = ""
    private var vodPic = ""
    private var vodScore = ""
    private var vodRemarks = ""

    private var episodeCount = 0

    // 周期上报。去重是仓储的事（5 秒窗口），这里只管如实汇报；只在真的在播时上报
    private val reportingJob = scope.launch {
        while (isActive) {
            delay(reportIntervalMs)
            if (session.state.value == PlaybackState.Playing) save(force = false)
        }
    }

    // 进度快照的刷新比上报密：进度条要跟手，落库 1 秒一次已经够。两个循环不合并，
    // 否则调进度条的密度就等于顺手改了写库频率
    private val progressJob = scope.launch {
        while (isActive) {
            delay(POSITION_TICK_MS)
            positionMs = session.positionMs()
            durationMs = session.durationMs()
        }
    }

    /**
     * 已经为哪一集触发过连播，`-1` = 没触发过。
     *
     * ⚠️ 触发点必须是**状态转移**而不是"当前是不是 Ended"：Ended 会一直保持到新集真的起播
     * （取地址还要一次网络往返），按状态推的话一口气能把整季跳完。
     * 离开 Ended 才重新武装，否则第二集播完就停在那儿了。
     */
    private var advancedForEpisode = -1

    private val advanceJob = scope.launch {
        session.state.collect { state ->
            if (state !is PlaybackState.Ended) {
                advancedForEpisode = -1
                return@collect
            }
            if (advancedForEpisode == episodeIndex) return@collect
            advancedForEpisode = episodeIndex
            onEpisodeEnded()
        }
    }

    /** 一集播完。最后一集也要落一次库：周期上报只在"在播"时跑，片尾那一下不会自己记上。 */
    private fun onEpisodeEnded() {
        val next = autoNextEpisode(PlaybackState.Ended, episodeIndex, episodeCount, autoPlayNext)
        // selectEpisode 自己会先强制落库再改集号
        if (next != null) selectEpisode(next) else save(force = true)
    }

    /** 界面把"现在是哪部片、哪条线路、哪一集"推过来。四个快照字段必须成套写入，所以传整个 Vod。 */
    fun bindContext(vod: Vod?, lineName: String, episodeName: String) {
        this.lineName = lineName
        this.episodeName = episodeName
        this.vodName = vod?.name.orEmpty()
        this.vodPic = vod?.pic.orEmpty()
        this.vodScore = vod?.score.orEmpty()
        this.vodRemarks = vod?.remarks.orEmpty()
    }

    /** 剧集列表变了。集号**在这里**夹取：界面夹的话，落进库的仍是指向不存在剧集的越界号。 */
    fun onEpisodesChanged(count: Int) {
        episodeCount = count
        if (count > 0 && episodeIndex > count - 1) episodeIndex = count - 1
    }

    // ──────────────────────────────────────────── 播放期控件（透传）

    // 透传但不是空壳：它们顺带让快照当场跟上，否则要等下个 tick（最多 250ms），
    // 滑块会先弹回原位再跳过去

    fun togglePlayPause() = session.togglePlayPause()

    fun seekTo(positionMs: Long) {
        this.positionMs = positionMs
        session.seekTo(positionMs)
    }

    /** ⚠️ 不叫 `setSpeed` —— 那会和 `var speed` 的合成 setter 撞 JVM 签名。 */
    fun selectSpeed(value: Float) {
        speed = value
        session.setSpeed(value)
    }

    fun open(target: PlayTarget, resumeAtMs: Long) {
        session.open(target, resumeAtMs)
    }

    /** 切集。**先落库再切**：切完会话已经在新集上，那时读位置拿到的是新集的 0。 */
    fun selectEpisode(index: Int) {
        save(force = true)
        episodeIndex = index
    }

    /**
     * 换线路。集号不动（第 3 集换条线路还是第 3 集），越界由 [onEpisodesChanged] 夹取。
     * ⚠️ 同样先落库再切，而且更不能反：进度记录带的是**线路名**，而线路名是重组之后才由
     * [bindContext] 推过来的 —— 顺序反了会把刚看的时长记到新线路名下。
     */
    fun selectLine(index: Int) {
        save(force = true)
        lineIndex = index
    }

    /** 退到后台。系统随后可能回收进程，那几秒进度不能丢。 */
    fun onStop() {
        save(force = true)
    }

    /** 落一次进度。@param force 切集 / 退出 / 退到后台传 true，那一刻的位置不被节流窗口吞掉。 */
    fun save(force: Boolean) {
        val positionMs = session.positionMs()
        // 位置为 0 表示还没起播，没有可记的东西
        if (positionMs <= 0L) return
        library.saveProgress(
            PlayProgress(
                vodId = vodId,
                lineIndex = lineIndex,
                lineName = lineName,
                episodeIndex = episodeIndex,
                episodeName = episodeName,
                positionMs = positionMs,
                // 来源没给时长时是 0（domain 里 0 = 不知道时长）
                durationMs = session.durationMs(),
                updatedAt = now(),
                name = vodName,
                pic = vodPic,
                score = vodScore,
                remarks = vodRemarks,
            ),
            force = force,
        )
    }

    /** 收场：先落一次进度再停上报、释放内核 —— "先落再关"读的是活的位置，不是最后一份快照。 */
    fun release() {
        save(force = true)
        reportingJob.cancel()
        progressJob.cancel()
        advanceJob.cancel()
        session.close()
    }

    private companion object {
        /** 如实汇报的粒度，不是写库频率 —— 真正写库由仓储按 5 秒窗口折叠。 */
        const val PROGRESS_REPORT_INTERVAL_MS = 1_000L

        /** 位置快照的刷新粒度。再密肉眼没差别，再疏进度条会一格一格跳。 */
        const val POSITION_TICK_MS = 250L
    }
}
