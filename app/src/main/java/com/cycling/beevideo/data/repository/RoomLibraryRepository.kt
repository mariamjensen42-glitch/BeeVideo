package com.cycling.beevideo.data.repository

import android.os.SystemClock
import com.cycling.beevideo.data.local.BeeDatabase
import com.cycling.beevideo.data.local.HistoryEntity
import com.cycling.beevideo.data.local.KeepEntity
import com.cycling.beevideo.domain.model.KeepItem
import com.cycling.beevideo.domain.model.PlayProgress
import com.cycling.beevideo.domain.repository.LibraryRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

/**
 * [LibraryRepository] 的 Room 实现。
 *
 * 实体（`HistoryEntity` / `KeepEntity`）不出这个包，domain 只认 `PlayProgress` / `KeepItem`。
 * 两者字段现在几乎一样，看着像白写了一层映射；但正因为一样才必须隔开 —— entity 一旦
 * 被界面拿到手，以后给表加一列就会牵动界面。
 */
class RoomLibraryRepository(
    database: BeeDatabase,
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

    override suspend fun progressOf(vodId: String): PlayProgress? = dao.history(vodId)?.toDomain()

    override fun saveProgress(progress: PlayProgress, force: Boolean) {
        // 先在闸门这边拦掉：被拦下的调用连一次协程都不用起
        if (!gate.allow(force)) return
        writer.launch { dao.putHistory(progress.toEntity()) }
    }

    // ---------------------------------------------------------------- 收藏

    override val keeps: Flow<List<KeepItem>> =
        dao.keeps().map { rows -> rows.map { it.toDomain() } }

    override fun isKept(vodId: String): Flow<Boolean> = dao.isKept(vodId)

    override suspend fun toggleKeep(item: KeepItem): Boolean = dao.toggleKeep(item.toEntity())

    private companion object {
        const val PROGRESS_WRITE_WINDOW_MS = 5_000L
    }
}

// -------------------------------------------------------------------- 映射
// 纯字段搬运，写成扩展函数是为了让上面读起来只剩"取/存"这个动作。

private fun HistoryEntity.toDomain() = PlayProgress(
    vodId = vodId,
    lineName = lineName,
    episodeIndex = episodeIndex,
    episodeName = episodeName,
    positionMs = positionMs,
    durationMs = durationMs,
    updatedAt = updatedAt,
)

private fun PlayProgress.toEntity() = HistoryEntity(
    vodId = vodId,
    lineName = lineName,
    episodeIndex = episodeIndex,
    episodeName = episodeName,
    positionMs = positionMs,
    durationMs = durationMs,
    updatedAt = updatedAt,
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
