package com.cycling.beevideo.ui.preview

import com.cycling.beevideo.domain.model.KeepItem
import com.cycling.beevideo.domain.model.PlayProgress
import com.cycling.beevideo.domain.repository.LibraryRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map

/**
 * 预览与 JVM 测试用的假 [LibraryRepository]，**不在任何业务路径上**
 * （真机跑的是 `RoomLibraryRepository`）。
 *
 * 写它是因为 Room 建库要 Context，预览环境没有；而预览要的是**确定的样子**：
 * 收藏页得能看到"有内容"的状态、详情页得能看到进度条。
 *
 * 收藏是**可变**的：预览里点一下心形图标能真的变，这比一个只读的假状态更有用。
 *
 * @param progress 传 null 就是"没看过"
 * @param keepsFromDemo 默认 false —— "空态"本身也是一个需要被预览到的状态
 */
class FakeLibraryRepository(
    /** 可写：单测要表达"这一次读到的进度和上一次不一样"（例如从播放页返回后重读）。 */
    var progress: PlayProgress? = null,
    keepsFromDemo: Boolean = false,
) : LibraryRepository {

    private val current = MutableStateFlow(
        if (keepsFromDemo) {
            PreviewVods.vods.take(DEMO_KEEP_COUNT).mapIndexed { index, vod ->
                KeepItem(
                    vodId = vod.id,
                    name = vod.name,
                    pic = vod.pic,
                    score = vod.score,
                    remarks = vod.remarks,
                    // 倒序排列靠这段时间戳，所以让下标越小的越"新"
                    createdAt = DEMO_BASE_TIME - index,
                )
            }
        } else {
            emptyList()
        }
    )

    override suspend fun progressOf(vodId: String): PlayProgress? =
        progress?.takeIf { it.vodId == vodId }

    /**
     * 每次 [saveProgress] 的记录（进度 + `force`）。
     *
     * 预览用不上，**单测靠它断言上报时机** —— "切集前先强制落一次""暂停时不上报"
     * 这类判据全都要看这里记了什么、`force` 是真是假。
     */
    val savedProgress = mutableListOf<SavedProgress>()

    data class SavedProgress(val progress: PlayProgress, val force: Boolean)

    override fun saveProgress(progress: PlayProgress, force: Boolean) {
        savedProgress += SavedProgress(progress, force)
    }

    override val keeps: Flow<List<KeepItem>> = current

    override fun isKept(vodId: String): Flow<Boolean> =
        current.map { list -> list.any { it.vodId == vodId } }

    override suspend fun toggleKeep(item: KeepItem): Boolean {
        val list = current.value
        val exists = list.any { it.vodId == item.vodId }
        current.value = if (exists) list.filterNot { it.vodId == item.vodId } else list + item
        return !exists
    }

    private companion object {
        const val DEMO_KEEP_COUNT = 6
        const val DEMO_BASE_TIME = 1_700_000_000_000L
    }
}
