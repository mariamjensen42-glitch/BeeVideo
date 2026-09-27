package com.cycling.beevideo.ui.preview

import com.cycling.beevideo.domain.model.KeepItem

/**
 * 演示用的收藏快照 —— 有两处要用它：`@Preview` 直接喂 `KeepUiState`（界面已经不认识
 * 仓储了），`FakeLibraryRepository` 还要靠它撑出"有内容"的那一版。
 */
object PreviewKeeps {

    /** 倒序排列靠时间戳，所以下标越小的越"新"。 */
    val items: List<KeepItem> = PreviewVods.vods.take(COUNT).mapIndexed { index, vod ->
        KeepItem(
            vodId = vod.id,
            name = vod.name,
            pic = vod.pic,
            score = vod.score,
            remarks = vod.remarks,
            createdAt = BASE_TIME - index,
        )
    }

    private const val COUNT = 6
    private const val BASE_TIME = 1_700_000_000_000L
}
