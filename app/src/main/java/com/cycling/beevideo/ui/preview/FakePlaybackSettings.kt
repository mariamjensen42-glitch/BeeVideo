package com.cycling.beevideo.ui.preview

import com.cycling.beevideo.domain.repository.PlaybackSettings

/**
 * 预览与 JVM 测试用的假播放设置。
 *
 * 值只在内存里，写回是空操作 —— 预览环境不该碰 SharedPreferences，
 * 而单测要的正是"可随意设定的初值 + 不落盘"。
 */
class FakePlaybackSettings(
    override var cacheEnabled: Boolean = true,
    override var cacheQuotaBytes: Long = PlaybackSettings.DEFAULT_QUOTA_BYTES,
) : PlaybackSettings
