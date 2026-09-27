package com.cycling.beevideo.ui.player

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.media.AudioManager
import android.provider.Settings
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import kotlin.math.roundToInt

/**
 * 亮度与音量的读写口子。
 *
 * 两者都是**系统状态**：亮度挂在当前 Activity 的窗口属性上，音量挂在 `AudioManager` 上。
 * 它们不落库、不跟会话走 —— 换个视频还应该是刚才的亮度。所以既不进 domain，
 * 也不进 [PlayerPlaybackState]。
 *
 * 收成接口是为了让调用点能拿到 `null`（拿不到 Activity 时），而不是到处判空。
 */
internal interface PlayerSystemControl {
    /** 0f..1f */
    fun current(): Float

    fun set(value: Float)
}

/**
 * 窗口亮度。
 *
 * ⚠️ 只在**这个 App 的窗口**上改，不写 `Settings.System` —— 后者要 `WRITE_SETTINGS`
 * 特殊权限，而且会改掉整机的亮度，用户切出去看别的 App 会发现屏幕莫名其妙变了。
 */
internal class WindowBrightnessControl(private val activity: Activity) : PlayerSystemControl {

    override fun current(): Float {
        val window = activity.window ?: return DEFAULT_BRIGHTNESS
        val current = window.attributes.screenBrightness
        /*
         * -1 = 跟随系统。此时必须**读系统的值**当起点：直接返回 1f 的话，用户第一次
         * 往下滑会从最亮开始，屏幕上会看到一次大跳变。
         */
        return if (current >= 0f) current else systemBrightness()
    }

    override fun set(value: Float) {
        val window = activity.window ?: return
        // 不允许滑到 0：窗口亮度 0 是真的全黑，用户会以为播放器崩了
        window.attributes = window.attributes.apply {
            screenBrightness = value.coerceIn(MIN_BRIGHTNESS, 1f)
        }
    }

    private fun systemBrightness(): Float {
        val raw = Settings.System.getInt(
            activity.contentResolver,
            Settings.System.SCREEN_BRIGHTNESS,
            (DEFAULT_BRIGHTNESS * SYSTEM_BRIGHTNESS_MAX).toInt(),
        )
        return (raw.toFloat() / SYSTEM_BRIGHTNESS_MAX).coerceIn(MIN_BRIGHTNESS, 1f)
    }

    private companion object {
        const val DEFAULT_BRIGHTNESS = 0.5f
        const val MIN_BRIGHTNESS = 0.01f
        const val SYSTEM_BRIGHTNESS_MAX = 255f
    }
}

/**
 * 媒体音量。
 *
 * ⚠️ `setStreamVolume` 的 flags 传 **0**：带 `FLAG_SHOW_UI` 的话系统会弹出自己的音量条，
 * 而我们画了自己的 HUD，两条并排出现。
 */
internal class MusicVolumeControl(context: Context) : PlayerSystemControl {

    private val audio: AudioManager? = context.getSystemService(AudioManager::class.java)

    override fun current(): Float {
        val manager = audio ?: return 0f
        val max = manager.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
        if (max <= 0) return 0f
        return manager.getStreamVolume(AudioManager.STREAM_MUSIC).toFloat() / max
    }

    override fun set(value: Float) {
        val manager = audio ?: return
        val max = manager.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
        if (max <= 0) return
        /*
         * 音量是**整数档**（本机 15 档）。不去重的话，手指在同一档内移动会反复调
         * 同一个值 —— 每次调用都会打断/重放系统音效，滑起来是一串咔哒声。
         */
        val target = (value.coerceIn(0f, 1f) * max).roundToInt().coerceIn(0, max)
        if (target != manager.getStreamVolume(AudioManager.STREAM_MUSIC)) {
            manager.setStreamVolume(AudioManager.STREAM_MUSIC, target, 0)
        }
    }
}

/** 两个口子一起取。任一项拿不到时是 `null`，手势层按 null 直接不响应那一路。 */
internal class PlayerSystemControls(
    val brightness: PlayerSystemControl?,
    val volume: PlayerSystemControl?,
)

@Composable
internal fun rememberPlayerSystemControls(): PlayerSystemControls {
    val context = LocalContext.current
    return remember(context) {
        PlayerSystemControls(
            brightness = context.findActivity()?.let(::WindowBrightnessControl),
            // 音量走 application context：AudioManager 是系统服务，不需要 Activity
            volume = MusicVolumeControl(context.applicationContext),
        )
    }
}

/**
 * 从任意 Context 往上找 Activity。
 *
 * 组合里拿到的是 `ContextWrapper` 链（主题包装、TintContextWrapper 之类），
 * 必须一路 `baseContext` 找上去；直接 `as Activity` 会抛 `ClassCastException`。
 */
internal fun Context.findActivity(): Activity? {
    var current: Context? = this
    while (current is ContextWrapper) {
        if (current is Activity) return current
        current = current.baseContext
    }
    return null
}
