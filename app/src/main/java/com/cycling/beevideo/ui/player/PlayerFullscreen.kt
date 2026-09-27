package com.cycling.beevideo.ui.player

import android.content.pm.ActivityInfo
import android.content.res.Configuration
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat

/**
 * 横屏全屏的进出。
 *
 * ⚠️ **播放页是全产品唯一允许横屏的页面**（`AndroidManifest` 里 MainActivity 钉的是
 * `portrait`），所以退出时必须显式还原竖屏 —— 靠"忘了设置就会自己回去"是不成立的，
 * 系统会一直保持在最后一次 `requestedOrientation` 上。
 *
 * ⚠️ 转屏**不会重建 Activity**：`configChanges` 已经含 `orientation|screenSize`。
 * 这正是"退出全屏回原进度且不重建播放器"能成立的原因 —— 会话住在 ViewModel 里，
 * 重建也活得下来，但不重建连恢复都不需要。
 * 代价是系统**不会播转屏过渡动画**（那次动画只在重建时才有），所以观感靠布局配合，
 * 见 [PlayerScreen] 里"渲染判据用真实方向而不是点击意图"那段。
 *
 * ⚠️ `requestedOrientation` 是**异步**的：这里返回时方向还没变，别拿它当渲染判据。
 *
 * 用法：`PlayerFullscreenEffect(wantsFullscreen)`，放在播放页顶层组合一次。
 */
@Composable
internal fun PlayerFullscreenEffect(wantsFullscreen: Boolean) {
    val context = LocalContext.current
    val activity = remember(context) { context.findActivity() }
    val window = activity?.window
    val isLandscape =
        LocalConfiguration.current.orientation == Configuration.ORIENTATION_LANDSCAPE

    /*
     * 请求方向 + 收起系统栏。
     *
     * 两者**同时**做是刻意的：进全屏时系统栏是在**竖屏下**消失的，那次尺寸变化正好被随后的
     * 转屏吃掉；改成"转屏完成后再藏"就等于让用户看两下变化。
     */
    LaunchedEffect(window, wantsFullscreen) {
        val controller = window?.let { WindowCompat.getInsetsController(it, it.decorView) }
        if (wantsFullscreen) {
            // 划出边缘临时显示系统栏，随后自动藏回去 —— 否则在全屏里连返回手势都摸不到位置
            controller?.systemBarsBehavior =
                WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            controller?.hide(WindowInsetsCompat.Type.systemBars())
        }
        // ⚠️ 上面这行返回时方向**还没变**，异步的（见文件头）
        activity?.requestedOrientation = if (wantsFullscreen) {
            ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
        } else {
            ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
        }
    }

    /*
     * 退全屏时系统栏要等**真的转回竖屏**再放出来。跟请求方向放一起的话，横屏下会先冒出
     * 状态栏、画面缩一下，然后才转屏 —— 又是一次多余的跳变。
     */
    LaunchedEffect(window, wantsFullscreen, isLandscape) {
        if (!wantsFullscreen && !isLandscape) {
            window?.let { WindowCompat.getInsetsController(it, it.decorView) }
                ?.show(WindowInsetsCompat.Type.systemBars())
        }
    }

    // 离开这一页（返回上一页 / 进程还被留着）时还原，否则回到首页会横着放
    DisposableEffect(window) {
        onDispose {
            activity?.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
            window?.let { WindowCompat.getInsetsController(it, it.decorView) }
                ?.show(WindowInsetsCompat.Type.systemBars())
        }
    }
}
