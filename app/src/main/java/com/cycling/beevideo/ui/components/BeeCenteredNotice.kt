package com.cycling.beevideo.ui.components

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import com.cycling.beevideo.ui.theme.BeeDimens

/**
 * 居中一行说明。用于"加载中 / 出错 / 空列表"这类没有内容的时刻。
 *
 * ─── 为什么共享，以及为什么带一个 `fillHeight` ──────────────────────────
 * 它以前在首页和搜索页各定义了一份，**同名而行为不同**：首页那份是
 * `Text(fillMaxWidth())`（给网格里的某一行用），搜索页那份是
 * `Box(fillMaxSize())`（给整屏的空态用）。
 *
 * 这不是"两处重复"，是**两处已经分叉**：同一个名字、两套尺寸行为，
 * 改一处另一处不会跟着变，而且没人会注意到。
 *
 * 合并的方式是**把这个差别说出来**，而不是选一个行为然后用错地方：
 *
 * @param fillHeight `true` = 自己撑满可用高度并垂直居中（整屏空态）；
 *   `false` = 只占自己那一行（网格里的一行说明）。
 *   ⚠️ 别在网格里传 `true`：那会把网格顶到屏幕外。
 */
@Composable
fun BeeCenteredNotice(
    text: String,
    modifier: Modifier = Modifier,
    fillHeight: Boolean = false,
) {
    if (fillHeight) {
        Box(modifier = modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            NoticeText(text, Modifier.padding(horizontal = BeeDimens.gapHuge))
        }
    } else {
        NoticeText(text, modifier.fillMaxWidth())
    }
}

@Composable
private fun NoticeText(text: String, modifier: Modifier) {
    Text(
        text = text,
        modifier = modifier,
        textAlign = TextAlign.Center,
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}
