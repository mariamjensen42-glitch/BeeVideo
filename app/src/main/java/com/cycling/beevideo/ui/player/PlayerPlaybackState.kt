package com.cycling.beevideo.ui.player

import android.util.Log
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.cycling.beevideo.domain.model.Episode
import com.cycling.beevideo.domain.model.NowPlaying
import com.cycling.beevideo.domain.model.PlayLine
import com.cycling.beevideo.domain.model.PlayProgress
import com.cycling.beevideo.domain.model.PlayRequest
import com.cycling.beevideo.domain.model.PlayTarget
import com.cycling.beevideo.domain.model.PlaybackState
import com.cycling.beevideo.domain.model.Vod
import com.cycling.beevideo.domain.model.resumePositionMs
import com.cycling.beevideo.domain.repository.ContentException
import com.cycling.beevideo.domain.repository.ContentRepository
import com.cycling.beevideo.domain.repository.LibraryRepository
import com.cycling.beevideo.domain.repository.PlaybackSession
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * 播放的**策略**：什么时候上报进度、什么时候换集、播完了接不接下一集。
 *
 * 它现在**住在 App 级**（`PlaybackCoordinator`），不再跟着播放页生死 ——
 * 播放器搬进了服务，离开页面音频继续，换集与落进度也必须继续。页面退了它还在：
 * 自动连播在后台照常发生，进度照常落库。
 *
 * 「地址怎么来」也在这一层：拿剧集标识去站点兑换（一次网络往返）是换集的一部分，
 * 放在页面里的话，后台连播就没人取地址了。
 */
class PlayerPlaybackState(
    /** 影片 id。同一部片复用同一个持有者（见 `PlaybackCoordinator.attach`）。 */
    val vodId: String,
    /** 会话由宿主造好交进来，本类只负责用它。 */
    val session: PlaybackSession,
    private val content: ContentRepository,
    private val library: LibraryRepository,
    initialEpisodeIndex: Int,
    initialLineIndex: Int = 0,
    /** 一集播完要不要接下一集。进页面时读一次，与缓存配额同样的取舍。 */
    private val autoPlayNext: Boolean = true,
    /** 周期上报用的作用域，由宿主提供。 */
    parentScope: CoroutineScope,
    /** 上报间隔。抽成参数是为了单测能跑虚拟时间。 */
    private val reportIntervalMs: Long = PROGRESS_REPORT_INTERVAL_MS,
    /** 取当前时间。注入是为了让记录里的 updatedAt 在测试里确定。 */
    private val now: () -> Long = System::currentTimeMillis,
) {

    private val scope: CoroutineScope = parentScope

    /** 当前集号。它是 resumePositionMs 判定"这条进度是不是这一集的"的依据。 */
    var episodeIndex: Int by mutableIntStateOf(initialEpisodeIndex)
        private set

    /**
     * ⚠️ 当前线路号**不跟路由参数走**：导航参数进页面就定死，靠它换线路要重新导航，
     * 结果是整个会话连同播放器重建（黑一下 + 落一次库 + 重开解码器）。
     */
    var lineIndex: Int by mutableIntStateOf(initialLineIndex)
        private set

    val state: StateFlow<PlaybackState> = session.state

    /** 位置与时长快照，供控件周期读取。快照才不会在重建后先画 00:00 再跳一下。 */
    var positionMs: Long by mutableLongStateOf(0L)
        private set

    var durationMs: Long by mutableLongStateOf(0L)
        private set

    /** 当前倍速。初值从会话读 —— 它是内核级设置，会话可能活得比本类久。 */
    var speed: Float by mutableFloatStateOf(session.speed())
        private set

    /** 影片快照。落库、通知栏、取地址都要用，由界面在详情就绪后推进来。 */
    private var vod: Vod? by mutableStateOf(null)

    private val line: PlayLine? get() = vod?.lines?.getOrNull(lineIndex)

    private val episode: Episode? get() = line?.episodes?.getOrNull(episodeIndex)

    /**
     * 应用内小窗要的那一行字：`片名 · 线路 · 集名`（缺哪段就少哪段）。
     *
     * 由 `bind` / 切集推过来，所以是**响应式**的 —— 后台自动连播换集，小窗上的字跟着走。
     */
    val nowPlayingLabel: String
        get() = listOf(vod?.name.orEmpty(), line?.name.orEmpty(), episode?.name.orEmpty())
            .filter { it.isNotEmpty() }
            .joinToString(" · ")

    // 已经为哪一集起播过。同键重复 ensurePlaying 必须是不动作 —— 否则每次重组都把片子重头放
    private var playingKey: Pair<Int, Int>? = null

    private var playJob: Job? = null

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
        val next = autoNextEpisode(PlaybackState.Ended, episodeIndex, episodeCount(), autoPlayNext)
        // selectEpisode 自己会先强制落库再改集号
        if (next != null) selectEpisode(next) else save(force = true)
    }

    private fun episodeCount(): Int = line?.episodes?.size ?: 0

    /**
     * 详情就绪。集号**在这里**夹取：界面夹的话，落进库的仍是指向不存在剧集的越界号。
     * 不在这里起播 —— 起播是 [ensurePlaying] 的显式动作，页面组装完再调。
     */
    fun bind(vod: Vod?) {
        this.vod = vod
        val count = episodeCount()
        if (count > 0 && episodeIndex > count - 1) episodeIndex = count - 1
    }

    /**
     * 起播当前这一集。同一集已经在放（或已在等地址）就什么都不做 —— 页面每次重组都会
     * 想调它，重放一次的结果是画面黑一下、进度归零。
     *
     * 解析失败不重试也不提示（播放页零反馈是拍板过的口径）；换一集再回来会重新尝试。
     */
    fun ensurePlaying() {
        val current = episode ?: return
        val key = lineIndex to episodeIndex
        if (key == playingKey) return
        playJob?.cancel()
        playJob = scope.launch {
            val lineName = line?.name.orEmpty()
            val target = try {
                content.playTarget(vodId, lineName, current.url)
            } catch (e: ContentException) {
                Log.w(TAG, "取播放目标失败：${e.message}")
                null
            }
            if (target == null) {
                // 空地址会被会话转成 Failed(NoAddress) —— 「源站确认没有」与「取不到」同归于此
                session.open(
                    PlayRequest(
                        target = PlayTarget(url = "", headers = emptyMap()),
                        resumeAtMs = 0L,
                        nowPlaying = NowPlaying(title = vod?.name.orEmpty()),
                    ),
                )
                return@launch
            }
            val progress = library.progressOf(vodId)
            playingKey = key
            session.open(
                PlayRequest(
                    target = target,
                    resumeAtMs = resumePositionMs(progress, lineName, episodeIndex),
                    nowPlaying = NowPlaying(
                        title = vod?.name.orEmpty(),
                        subtitle = listOf(lineName, current.name)
                            .filter { it.isNotEmpty() }
                            .joinToString(" · "),
                        artworkUri = vod?.pic?.takeIf { it.isNotEmpty() },
                    ),
                ),
            )
        }
    }

    /**
     * 路由带来的起点：用户是**从哪儿进来**的（详情页点「第 05 集」）。
     *
     * ⚠️ 和 [selectEpisode] 不是一回事，也不该在 `attach` 里做 —— 同一部片复用持有者
     * （见 `PlaybackCoordinator.attach`）时路由参数**不能无条件覆盖**：重进同一页会把
     * 正在放的重来一遍。所以只认"和当前不一样"那一种，重复调是空操作。
     *
     * ⚠️ 调用点必须在 `LaunchedEffect` 里：它会落库（`save` 是挂起前的同步写），
     * 在组合期调等于每次重组写一次库。
     */
    fun applyRoute(lineIndex: Int, episodeIndex: Int) {
        if (lineIndex == this.lineIndex && episodeIndex == this.episodeIndex) return
        // 先落库再改号：位置与线路名都还属于旧的那一集（同 selectLine / selectEpisode）
        save(force = true)
        this.lineIndex = lineIndex
        val count = episodeCount()
        // 详情还没到时数不出集数，交给 bind 夹；数得出就当场夹，否则那一集不存在
        this.episodeIndex =
            if (count > 0) episodeIndex.coerceIn(0, count - 1) else episodeIndex
        ensurePlaying()
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

    /** 切集。**先落库再切**：切完会话已经在新集上，那时读位置拿到的是新集的 0。 */
    fun selectEpisode(index: Int) {
        save(force = true)
        episodeIndex = index
        ensurePlaying()
    }

    /**
     * 换线路。集号不动（第 3 集换条线路还是第 3 集），**除非**新线路没这么长 ——
     * 那就必须当场夹掉，落库的越界集号是一条指向不存在剧集的进度。
     * ⚠️ 先落库再切，而且更不能反：进度记录带的是**线路名**，而线路名来自当前线路 ——
     * 顺序反了会把刚看的时长记到新线路名下。
     */
    fun selectLine(index: Int) {
        save(force = true)
        lineIndex = index
        val count = episodeCount()
        if (count > 0 && episodeIndex > count - 1) episodeIndex = count - 1
        ensurePlaying()
    }

    /** 退到后台。系统随后可能回收进程，那几秒进度不能丢。 */
    fun onStop() {
        save(force = true)
    }

    /**
     * 收场但不退役：落一次进度再暂停。
     *
     * 两个调用点都是"用户已经不看着画面了" —— 离开播放页而小窗又进不去，
     * 或者小窗被他关掉。不 `release`：持有者与会话都留着，回来看还接得上。
     */
    fun pauseAndSave() {
        save(force = true)
        session.pause()
    }

    /** 落一次进度。@param force 切集 / 退出 / 退到后台传 true，那一刻的位置不被节流窗口吞掉。 */
    fun save(force: Boolean) {
        val positionMs = session.positionMs()
        // 位置为 0 表示还没起播，没有可记的东西
        if (positionMs <= 0L) return
        val currentVod = vod
        library.saveProgress(
            PlayProgress(
                vodId = vodId,
                lineIndex = lineIndex,
                lineName = line?.name.orEmpty(),
                episodeIndex = episodeIndex,
                episodeName = episode?.name.orEmpty(),
                positionMs = positionMs,
                // 来源没给时长时是 0（domain 里 0 = 不知道时长）
                durationMs = session.durationMs(),
                updatedAt = now(),
                name = currentVod?.name.orEmpty(),
                pic = currentVod?.pic.orEmpty(),
                score = currentVod?.score.orEmpty(),
                remarks = currentVod?.remarks.orEmpty(),
            ),
            force = force,
        )
    }

    /**
     * 收场（切到另一部片）：先落一次进度，再停掉本片的上报与连播。
     * ⚠️ **不关会话** —— 会话是 App 级的，比任何一部片都活得久。
     */
    fun detach() {
        save(force = true)
        reportingJob.cancel()
        progressJob.cancel()
        advanceJob.cancel()
        playJob?.cancel()
    }

    /** 连会话一起收场。只属于进程收场（协调者 `release`）与单测。 */
    fun release() {
        detach()
        session.close()
    }

    private companion object {
        /** 如实汇报的粒度，不是写库频率 —— 真正写库由仓储按 5 秒窗口折叠。 */
        const val PROGRESS_REPORT_INTERVAL_MS = 1_000L

        /** 位置快照的刷新粒度。再密肉眼没差别，再疏进度条会一格一格跳。 */
        const val POSITION_TICK_MS = 250L

        const val TAG = "BeePlayer"
    }
}
