package com.cycling.beevideo.ui.player

import android.app.PictureInPictureParams
import android.content.Context
import android.content.pm.PackageManager
import android.util.Rational
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import com.cycling.beevideo.domain.model.PlaybackState

/**
 * **系统**画中画：按 Home / 切到别的 App / 锁屏时，把画面缩成一块浮在桌面上的小窗。
 *
 * ─── 它不负责"返回" ────────────────────────────────────────────────────
 * 曾经把返回键也接到这里，结果是"按返回 → 整个 App 缩成小窗、背后就是手机桌面"。
 * 那对用户是"我被踢出 App 了"。两件事的分工现在是：
 *   - **返回键** → 退回 App 内的上一页，画面交给应用内小窗（`MiniPlayer`）；
 *   - **离开 App** → 系统小窗，由本文件这个开关管（`PlaybackSettings.pictureInPicture`）。
 *
 * ⚠️ 本 App 是**单 Activity**：进 PiP 缩的是整棵界面。所以 PiP 期间必须只画画面
 * （见 `PlayerScreen` 的 `inPipMode` 分支），否则顶栏与选集会被塞进巴掌大的窗口。
 */
internal object PlayerPip {

    /** 16:9。视频本身可能是别的比例，但小窗只求"像个视频窗口"，精确贴合要读内核实时的尺寸。 */
    private val ASPECT_RATIO = Rational(16, 9)

    /**
     * 系统有没有这个能力。
     *
     * ⚠️ 它**不够**：用户在系统设置里关掉「允许画中画」时这个 feature 仍然是 true，
     * 只有 `enterPictureInPictureMode` 返回 false 才知道进不去。
     */
    fun isSupported(context: Context): Boolean =
        context.packageManager.hasSystemFeature(PackageManager.FEATURE_PICTURE_IN_PICTURE)

    /** @param autoEnter 退到后台时系统**自动**进小窗。 */
    fun params(autoEnter: Boolean): PictureInPictureParams =
        PictureInPictureParams.Builder()
            .setAspectRatio(ASPECT_RATIO)
            // API 31+；minSdk 31，不用版本分支
            .setAutoEnterEnabled(autoEnter)
            .build()
}

/**
 * 什么时候允许系统**自动**进小窗。
 *
 * @param pipEnabled 用户在设置里开了画中画**且**设备支持。默认关 —— 于是默认行为是
 *   "退到后台就只是退到后台"，音频继续由通知栏管。
 *
 * 判据是**内核正在动**（在播或缓冲）：暂停着还缩成小窗没有意义，用户已经停下来了。
 * 缓冲中要进 —— 那一刻画面正常在转圈，掐掉比留着突兀。
 */
internal fun shouldAutoEnterPip(state: PlaybackState, pipEnabled: Boolean): Boolean =
    pipEnabled && (state is PlaybackState.Playing || state is PlaybackState.Buffering)

/**
 * 把"退到后台自动进小窗"这件事挂到 Activity 上。
 *
 * ⚠️ 只在**播放页**与**应用内小窗**里调，两处互斥（同一时刻只有一个在组合里），
 * 所以 `onDispose` 里的关闭不会互相打架。别提到导航宿主上 —— 那里只组合一次，
 * `remember` 拿到的是进设置页改之前那个旧值。
 */
@Composable
internal fun PipAutoEnterEffect(enabled: Boolean) {
    val context = LocalContext.current
    val activity = remember(context) { context.findActivity() }
    DisposableEffect(activity, enabled) {
        runCatching {
            activity?.setPictureInPictureParams(PlayerPip.params(autoEnter = enabled))
        }
        onDispose {
            runCatching { activity?.setPictureInPictureParams(PlayerPip.params(autoEnter = false)) }
        }
    }
}
