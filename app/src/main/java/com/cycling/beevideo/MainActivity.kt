package com.cycling.beevideo

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import com.cycling.beevideo.ui.nav.BeeNavHost
import com.cycling.beevideo.ui.theme.BeeVideoTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        // 从 Application 取，不在这里 new —— 理由见 BeeApplication 的说明
        val content = (application as BeeApplication).content
        setContent {
            BeeVideoTheme {
                BeeNavHost(content = content, sources = content)
            }
        }
    }
}
