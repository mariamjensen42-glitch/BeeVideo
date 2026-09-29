package com.cycling.beevideo.data.settings

import android.content.Context
import com.cycling.beevideo.domain.model.DecoderPreference
import com.cycling.beevideo.domain.repository.PlaybackSettings
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * [PlaybackSettings] 的 SharedPreferences 实现。
 *
 * ─── 为什么和 [ContentSourceStore] 分开文件 ────────────────────────────
 * 两者都是 SharedPreferences，但**失效的时机完全不同**：内容源那份在用户
 * 点「清除」时会被整份删掉（`clear()` 里调了 `prefs.edit().clear()`），
 * 混在一起写的话，清一次内容源会把缓存配额一起清掉 —— 用户不会认为这是
 * 同一个操作。文件名分开，互不牵连。
 *
 * ─── 为什么配额要交给用户决定 ──────────────────────────────────────────
 * 磁盘缓存消耗的是**用户自己的存储**，而且点播源的码率动辄 1–5 MB/s，
 * 看两小时就能吃掉好几个 G。给一个开关和一个配额，而不是替他决定 ——
 * 这是这个 App 唯一会持续占用用户存储的地方。
 */
class PrefsPlaybackSettings(context: Context) : PlaybackSettings {

    private val prefs = context.applicationContext
        .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    override var cacheEnabled: Boolean
        get() = prefs.getBoolean(KEY_CACHE_ENABLED, true)
        set(value) = prefs.edit().putBoolean(KEY_CACHE_ENABLED, value).apply()

    /**
     * 落盘的值在这里**校验**：读出 `0` 或不在档位里的值时回落到默认值。
     *
     * 校验放在实现里而不是接口调用方：它是"脏值不能让缓存失效"这条语义的
     * 落地点（接口上写明了这条约束），而"值存在 prefs 里"只有这一层知道。
     */
    override var cacheQuotaBytes: Long
        get() = prefs.getLong(KEY_CACHE_QUOTA, PlaybackSettings.DEFAULT_QUOTA_BYTES)
            .takeIf { it in PlaybackSettings.QUOTA_CHOICES }
            ?: PlaybackSettings.DEFAULT_QUOTA_BYTES
        set(value) = prefs.edit().putLong(KEY_CACHE_QUOTA, value).apply()

    override var autoPlayNext: Boolean
        get() = prefs.getBoolean(KEY_AUTO_NEXT, true)
        set(value) = prefs.edit().putBoolean(KEY_AUTO_NEXT, value).apply()

    /**
     * 枚举以**名字字符串**落盘：脏值（换了版本改了枚举名）回落 [DecoderPreference.AUTO]，
     * 不让一条未知字符串把设置页的 chip 行顶崩。
     */
    override var decoderPreference: DecoderPreference
        get() = prefs.getString(KEY_DECODER, null)
            ?.let { name -> DecoderPreference.entries.firstOrNull { it.name == name } }
            ?: DecoderPreference.AUTO
        set(value) = prefs.edit().putString(KEY_DECODER, value.name).apply()

    /**
     * 唯一做成 Flow 的一项（理由见接口注释）。初值在构造期读一次 ——
     * 之后以内存里这份为准，写的时候两边一起写；不然别处改了 prefs，
     * 这条流就永远停在构造时的那个值上。
     */
    private val _pictureInPicture = MutableStateFlow(prefs.getBoolean(KEY_PICTURE_IN_PICTURE, false))
    override val pictureInPicture: StateFlow<Boolean> = _pictureInPicture.asStateFlow()

    override fun setPictureInPicture(enabled: Boolean) {
        _pictureInPicture.value = enabled
        prefs.edit().putBoolean(KEY_PICTURE_IN_PICTURE, enabled).apply()
    }

    override var customHeaderText: String
        get() = prefs.getString(KEY_CUSTOM_HEADERS, "").orEmpty()
        set(value) = prefs.edit().putString(KEY_CUSTOM_HEADERS, value).apply()

    private companion object {
        const val PREFS_NAME = "beevideo.playback"
        const val KEY_CACHE_ENABLED = "cache_enabled"
        const val KEY_CACHE_QUOTA = "cache_quota_bytes"
        const val KEY_AUTO_NEXT = "auto_play_next"
        const val KEY_DECODER = "decoder_preference"
        const val KEY_CUSTOM_HEADERS = "custom_headers_text"
        const val KEY_PICTURE_IN_PICTURE = "picture_in_picture"
    }
}
