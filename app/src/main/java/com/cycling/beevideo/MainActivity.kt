package com.cycling.beevideo

import android.content.res.Configuration
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.cycling.beevideo.ui.components.LocalIncognito
import com.cycling.beevideo.ui.nav.BeeNavHost
import com.cycling.beevideo.ui.theme.BeeVideoTheme
import com.cycling.beevideo.ui.theme.isDark

class MainActivity : ComponentActivity() {

    /**
     * 现在是不是在小窗里。
     *
     * ⚠️ **必须在 Activity 这一层收**：PiP 缩的是整个窗口，而本 App 是单 Activity，
     * 所以"只剩画面"这件事得由最外层决定 —— 靠 `LocalConfiguration` 之类反推不可靠。
     */
    private val inPip = mutableStateOf(false)

    /**
     * 小窗结束过（展开**或**关掉）。
     *
     * ⚠️ 两者都走 [onPictureInPictureModeChanged]`(false)`，只能靠"随后回到前台没有"来分 ——
     * 展开会 `onStart`，关掉不会。所以这里只记事实，判定交给 [onStop]。
     */
    private var pipExited = false

    private val mainHandler = Handler(Looper.getMainLooper())

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        // 从 Application 取，不在这里 new —— 理由见 BeeApplication 的说明
        val app = application as BeeApplication
        setContent {
            /*
             * 深浅色在**这一层**定，往下都是 `MaterialTheme.colorScheme` 的事。
             *
             * 三态 → 布尔的解析（`isDark()`）只此一处，也是全 App 唯一读系统
             * uiMode 的地方。页面里任何一处再去自己判断"现在是不是深色"，
             * 都会在「用户强制浅色、系统是深色」时判反 —— 主题层已经把答案
             * 放进 colorScheme 了，组件要判断就按它判。
             */
            val mode by app.theme.mode.collectAsStateWithLifecycle()
            val incognito by app.incognito.enabled.collectAsStateWithLifecycle()
            BeeVideoTheme(darkTheme = mode.isDark()) {
                /*
                 * 封面图靠这个环境值决定"写不写磁盘缓存"。它是**会话级**事实而不是外观，
                 * 所以不进主题、也不该随配色重建，只在这里往下铺一层。
                 */
                CompositionLocalProvider(LocalIncognito provides incognito) {
                    BeeNavHost(
                        content = app.content,
                        sources = app.content,
                        library = app.library,
                        searchHistory = app.searchHistory,
                        settings = app.playback,
                        mediaCache = app.mediaCache,
                        theme = app.theme,
                        incognito = app.incognito,
                        playbackCoordinator = app.playbackCoordinator,
                        decoderMonitor = app.decoderMonitor,
                        inPipMode = inPip.value,
                    )
                }
            }
        }
    }

    override fun onPictureInPictureModeChanged(
        isInPictureInPictureMode: Boolean,
        newConfig: Configuration,
    ) {
        super.onPictureInPictureModeChanged(isInPictureInPictureMode, newConfig)
        inPip.value = isInPictureInPictureMode
        if (!isInPictureInPictureMode) pipExited = true
    }

    /** 回到前台 = 刚才那次是小窗**展开**，不是关掉。 */
    override fun onStart() {
        super.onStart()
        pipExited = false
    }

    /**
     * 小窗被关掉 = 用户收场：落一次进度再暂停。
     *
     * ⚠️ 三条约束，缺一条就会误停或漏停：
     *  1. **不能直接用 `isInPictureInPictureMode`**：关小窗那一刻它可能还是 true，
     *     判定要放到下一条消息里（此刻不看"刚刚"，只看"现在"）。
     *  2. **不能只靠 `onStop` 之前的标记**：`onStop` 与 PiP 回调谁先谁后系统没保证
     *     （实测关掉小窗时任务还在，Activity 只是被移到后台）。所以延时读标记。
     *  3. **`CREATED` 才停**：`onStop` 之后 lifecycle 落到 CREATED；展开的那次 `onStop`
     *     根本不会来。这一条同时把"按 Home 退到后台"排除在外 —— 那不是收场，
     *     通知栏还在，正是后台播放存在的理由。
     */
    override fun onStop() {
        super.onStop()
        mainHandler.postDelayed(
            {
                if (pipExited &&
                    !isInPictureInPictureMode &&
                    lifecycle.currentState == Lifecycle.State.CREATED
                ) {
                    pipExited = false
                    (application as BeeApplication).playbackCoordinator.pauseForExit()
                }
            },
            PIP_EXIT_SETTLE_MS,
        )
    }

    private companion object {
        /** 等生命周期走完再判，见 [onStop] 的第 2 条。 */
        const val PIP_EXIT_SETTLE_MS = 300L
    }
}
