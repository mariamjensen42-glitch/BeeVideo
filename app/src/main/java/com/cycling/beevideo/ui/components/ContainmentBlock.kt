package com.cycling.beevideo.ui.components

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.cycling.beevideo.ui.theme.BeeDimens

/**
 * 容器块：28dp 圆角的 `surface container low` + 16dp 内边距。
 *
 * M3 的 **containment** 手法 —— 相关信息各归一块，而不是靠分割线切。
 * 详情页与播放页整页的层级都由它撑起来。
 *
 * ─── 为什么要共享 ──────────────────────────────────────────────────────
 * 它在详情页与播放页各被抄了一份，而两份**并不相同**：播放页那份多一个
 * `padding(horizontal = screenMargin)`，详情页那份没有（详情页由调用点自己加边距）。
 * 同一个名字、同一套圆角与配色、两种外边距 —— 也就是说改一次得记得改两处，
 * 而且漏掉一处不会报错、只会让某一个页面的块看起来宽窄不一样。
 *
 * **外边距不进参数**：那样只是把"两种行为"从两个函数挪进一个布尔值。
 * 需要留边的调用点自己传 `Modifier.padding(...)` —— 组件的职责是形状与配色。
 */
@Composable
fun ContainmentBlock(
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    Surface(
        shape = MaterialTheme.shapes.extraLarge,
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        modifier = modifier.fillMaxWidth(),
    ) {
        Column(modifier = Modifier.padding(BeeDimens.gapMedium)) {
            content()
        }
    }
}
