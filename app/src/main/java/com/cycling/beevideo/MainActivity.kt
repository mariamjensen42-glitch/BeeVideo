package com.cycling.beevideo

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.cycling.beevideo.ui.nav.BeeNavHost
import com.cycling.beevideo.ui.theme.BeeVideoTheme
import com.cycling.beevideo.ui.theme.isDark

class MainActivity : ComponentActivity() {
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
            BeeVideoTheme(darkTheme = mode.isDark()) {
                BeeNavHost(
                    content = app.content,
                    sources = app.content,
                    library = app.library,
                    settings = app.playback,
                    mediaCache = app.mediaCache,
                    theme = app.theme,
                )
            }
        }
    }
}
