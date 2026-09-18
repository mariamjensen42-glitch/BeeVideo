package com.cycling.beevideo.ui.components

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import com.cycling.beevideo.R

/**
 * 顶栏的返回按钮（图标 + 无障碍描述）。
 *
 * ─── 为什么要共享 ──────────────────────────────────────────────────────
 * 详情页、播放页、搜索页各抄了一遍这个 8 行块 —— 包括 `contentDescription` 取哪个字符串。
 * 三份逐字相同的代码里最要命的是**无障碍描述**：它是给读屏用户听的那句话，
 * 漏改一处不会报错、也不会有人看见。
 *
 * 文案统一取 `R.string.cd_back`，所以以后换措辞只改一处。
 */
@Composable
fun BeeBackButton(onClick: () -> Unit) {
    IconButton(onClick = onClick) {
        Icon(
            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
            contentDescription = stringResource(R.string.cd_back),
        )
    }
}
