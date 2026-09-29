package com.cycling.beevideo.data.repository

import android.os.SystemClock
import com.cycling.beevideo.data.local.BeeDatabase
import com.cycling.beevideo.data.local.HistoryEntity
import com.cycling.beevideo.data.local.KeepEntity
import com.cycling.beevideo.domain.model.KeepItem
import com.cycling.beevideo.domain.model.PlayProgress
import com.cycling.beevideo.domain.repository.IncognitoMode
import com.cycling.beevideo.domain.repository.LibraryRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch

/**
 * [LibraryRepository] 的 Room 实现。
 *
 * 实体（`HistoryEntity` / `KeepEntity`）不出这个包，domain 只认 `PlayProgress` / `KeepItem`。
 * 两者字段现在几乎一样，看着像白写了一层映射；但正因为一样才必须隔开 —— entity 一旦
 * 被界面拿到手，以后给表加一列就会牵动界面。
 *
 * ─── 无痕模式为什么拦在这一层而不是各个页面 ────────────────────────────
 * 因为读方有四个（历史页、首页「继续观看」、详情页的续播与收藏、播放页的续播位置），
 * 写方有两个（进度上报、收藏切换）。在页面里各判一次，漏掉任何一处都不会报错 ——
 * 只会表现为"无痕了但某一块还在记"。这里是唯一的收口点。
 */
class RoomLibraryRepository(
    database: BeeDatabase,
    private val incognito: IncognitoMode,
    clock: () -> Long = SystemClock::elapsedRealtime,
) : LibraryRepository {

    private val dao = database.library()

    /** 5 秒：比任何有用的精度都细，又足以把每秒一次的上报压成每 5 秒一次真实写入。 */
    private val gate = ProgressWriteGate(windowMs = PROGRESS_WRITE_WINDOW_MS, now = clock)

    /**
     * 进度写入用的协程作用域，**必须活得比调用方久**：最后一次上报发生在播放页正在销毁时，
     * 界面自己的 `rememberCoroutineScope()` 已/即将被取消。挂在仓储上（= App 生命周期）。
     *
     * `limitedParallelism(1)` 串行化写入 —— 两次写入并发跑的话完成顺序不保证，极端情况下
     * "较早的位置"后落盘，进度就倒退回去了。
     */
    private val writer = CoroutineScope(SupervisorJob() + Dispatchers.IO.limitedParallelism(1))

    // ------------------------------------------------------------ 观看进度

    override suspend fun progressOf(vodId: String): PlayProgress? {
        // 无痕会话里连"上次看到哪"都不给：续播会把一条旧记录的位置带进来，
        // 而"这次什么都不知道"才是这个模式要的东西
        if (incognito.enabled.value) return null
        return dao.history(vodId)?.toDomain()
    }

    override fun saveProgress(progress: PlayProgress, force: Boolean) {
        /*
         * ⚠️ 拦在闸门**之前**。放在之后的话，被丢弃的那次调用仍会刷新闸门的时间戳，
         * 于是关掉无痕后的第一次上报会被"刚才好像写过"吞掉 —— 表现为"关了无痕，
         * 前 5 秒的进度还是没记上"。
         */
        if (incognito.enabled.value) return
        // 先在闸门这边拦掉：被拦下的调用连一次协程都不用起
        if (!gate.allow(force)) return
        writer.launch { dao.putHistory(progress.toEntity()) }
    }

    /**
     * ⚠️ 用 `combine` 把开关并进来，而不是让界面自己判：
     * 「无痕打开 → 列表立刻变空；关掉 → 原样回来」由同一条流保证。
     */
    override val progressList: Flow<List<PlayProgress>> = combine(
        dao.histories(),
        incognito.enabled,
    ) { rows, on -> if (on) emptyList() else rows.map { it.toDomain() } }

    override suspend fun deleteProgress(vodId: String) {
        // 无痕期间历史页看不到任何记录，这个入口理应够不着；挡一下是为了不留下
        // "某个入口仍能改动用户数据"的例外
        if (incognito.enabled.value) return
        dao.deleteHistory(vodId)
    }

    override suspend fun clearProgress() {
        if (incognito.enabled.value) return
        dao.clearHistory()
    }

    // ---------------------------------------------------------------- 收藏

    override val keeps: Flow<List<KeepItem>> = combine(
        dao.keeps(),
        incognito.enabled,
    ) { rows, on -> if (on) emptyList() else rows.map { it.toDomain() } }

    override fun isKept(vodId: String): Flow<Boolean> = combine(
        dao.isKept(vodId),
        incognito.enabled,
    ) { kept, on -> kept && !on }

    override suspend fun toggleKeep(item: KeepItem): Boolean {
        // 无痕期间收藏按钮是禁用态（界面侧），这一层再挡一次：界面的禁用只是显示，
        // 而写入是真会留下的
        if (incognito.enabled.value) return false
        return dao.toggleKeep(item.toEntity())
    }

    override suspend fun removeKeep(vodId: String) {
        // 无痕期间收藏页看不到任何条目，这个入口理应够不着；挡一下是为了不留
        // "某个入口仍能改动用户数据"的例外（同 deleteProgress）
        if (incognito.enabled.value) return
        dao.deleteKeep(vodId)
    }

    private companion object {
        const val PROGRESS_WRITE_WINDOW_MS = 5_000L
    }
}

// -------------------------------------------------------------------- 映射
// 纯字段搬运，写成扩展函数是为了让上面读起来只剩"取/存"这个动作。

private fun HistoryEntity.toDomain() = PlayProgress(
    vodId = vodId,
    lineIndex = lineIndex,
    lineName = lineName,
    episodeIndex = episodeIndex,
    episodeName = episodeName,
    positionMs = positionMs,
    durationMs = durationMs,
    updatedAt = updatedAt,
    name = name,
    pic = pic,
    score = score,
    remarks = remarks,
)

private fun PlayProgress.toEntity() = HistoryEntity(
    vodId = vodId,
    lineIndex = lineIndex,
    lineName = lineName,
    episodeIndex = episodeIndex,
    episodeName = episodeName,
    positionMs = positionMs,
    durationMs = durationMs,
    updatedAt = updatedAt,
    name = name,
    pic = pic,
    score = score,
    remarks = remarks,
)

private fun KeepEntity.toDomain() = KeepItem(
    vodId = vodId,
    name = name,
    pic = pic,
    score = score,
    remarks = remarks,
    createdAt = createdAt,
)

private fun KeepItem.toEntity() = KeepEntity(
    vodId = vodId,
    name = name,
    pic = pic,
    score = score,
    remarks = remarks,
    createdAt = createdAt,
)
