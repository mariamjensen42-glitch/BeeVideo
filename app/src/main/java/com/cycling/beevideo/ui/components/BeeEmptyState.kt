package com.cycling.beevideo.ui.components

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.cycling.beevideo.ui.theme.BeeDimens

/**
 * 整页空态：一个圆形容器里的图标 + 一句标题 + 一段说明。
 *
 * ─── 为什么要共享 ──────────────────────────────────────────────────────
 * 收藏页与观看历史页各要一份，而这两页的空态**必须长得一样** —— 它们说的是同一件事：
 * 「你还没攒下东西，以及怎么攒」。分成两份实现之后，改一处忘一处不会报错，
 * 只会让两个页面看起来像两个人做的。
 *
 * 图标用中性容器色（`secondaryContainer`）而不是 primary：空态是**说明**，不是行动，
 * 抢走 primary 会让这一屏看起来有一个并不存在的主按钮。
 *
 * 图标尺寸取 40dp 而不是 36dp —— Material Symbols 的刻度只有 20/24/40/48。
 */
@Composable
fun BeeEmptyState(
    icon: ImageVector,
    title: String,
    body: String,
    modifier: Modifier = Modifier,
) {
    Box(modifier = modifier, contentAlignment = Alignment.Center) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier
                .padding(horizontal = BeeDimens.screenMargin)
                .widthIn(max = 320.dp),
        ) {
            Surface(
                shape = CircleShape,
                color = MaterialTheme.colorScheme.secondaryContainer,
                contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
                modifier = Modifier.size(96.dp),
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(
                        imageVector = icon,
                        contentDescription = null,
                        modifier = Modifier.size(40.dp),
                    )
                }
            }
            Spacer(Modifier.height(BeeDimens.gapLarge))
            Text(
                text = title,
                // 空态标题是这一屏唯一的排版主体
                style = MaterialTheme.typography.headlineSmallEmphasized,
                color = MaterialTheme.colorScheme.onSurface,
                textAlign = TextAlign.Center,
            )
            Spacer(Modifier.height(BeeDimens.gapTiny))
            Text(
                text = body,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
        }
    }
}
