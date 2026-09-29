package com.cycling.beevideo.ui.preview

import com.cycling.beevideo.domain.model.DecoderPreference
import com.cycling.beevideo.domain.repository.PlaybackSettings
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * 预览与 JVM 测试用的假播放设置。
 *
 * 值只在内存里，写回是空操作 —— 预览环境不该碰 SharedPreferences，
 * 而单测要的正是"可随意设定的初值 + 不落盘"。
 */
class FakePlaybackSettings(
    override var cacheEnabled: Boolean = true,
    override var cacheQuotaBytes: Long = PlaybackSettings.DEFAULT_QUOTA_BYTES,
    override var autoPlayNext: Boolean = true,
    override var decoderPreference: DecoderPreference = DecoderPreference.AUTO,
    pictureInPicture: Boolean = false,
    override var customHeaderText: String = "",
) : PlaybackSettings {

    private val _pictureInPicture = MutableStateFlow(pictureInPicture)
    override val pictureInPicture: StateFlow<Boolean> = _pictureInPicture.asStateFlow()

    override fun setPictureInPicture(enabled: Boolean) {
        _pictureInPicture.value = enabled
    }
}
