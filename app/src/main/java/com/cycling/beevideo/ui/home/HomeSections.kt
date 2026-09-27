package com.cycling.beevideo.ui.home

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import com.cycling.beevideo.R
import com.cycling.beevideo.domain.model.Category
import com.cycling.beevideo.domain.model.PlayProgress
import com.cycling.beevideo.domain.repository.ContentRepository
import com.cycling.beevideo.ui.components.BeeCenteredNotice
import com.cycling.beevideo.ui.components.SkeletonPosterGrid
import com.cycling.beevideo.ui.history.HistoryRow
import com.cycling.beevideo.ui.theme.BeeDimens

/** 「继续观看」：标题 + 查看全部 + 一行横滑。卡片复用历史页的 [HistoryRow]，两处版式必须一致。 */
@Composable
internal fun ResumeSection(
    items: List<PlayProgress>,
    now: Long,
    onOpen: (PlayProgress) -> Unit,
    onOpenAll: () -> Unit,
) {
    Column(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = BeeDimens.gapTiny, bottom = BeeDimens.gapTight),
            horizontalArrangement = Arrangement.SpaceBetween,
            // 底对齐：24sp 的栏目名与按钮里的 14sp 标签坐在同一条基线上才不会飘
            verticalAlignment = Alignment.Bottom,
        ) {
            Text(
                text = stringResource(R.string.home_resume_title),
                style = MaterialTheme.typography.headlineSmallEmphasized,
                color = MaterialTheme.colorScheme.onSurface,
            )
            TextButton(onClick = onOpenAll) {
                Text(
                    text = stringResource(R.string.history_view_all),
                    style = MaterialTheme.typography.labelLarge,
                )
            }
        }

        LazyRow(horizontalArrangement = Arrangement.spacedBy(BeeDimens.gapSmall)) {
            items(items, key = { it.vodId }) { progress ->
                HistoryRow(
                    progress = progress,
                    // 首页这一版不显示来源：这里只回答"接着看哪一集"
                    sourceName = "",
                    now = now,
                    onClick = { onOpen(progress) },
                    modifier = Modifier.width(BeeDimens.resumeCardWidth),
                )
            }
        }
    }
}

/**
 * 版块标题。全 App 唯一一处两种字体族直接相邻：栏目名走衬线（brand 槽），条数走无衬线（plain 槽）。
 * [count] 为 null 表示还没数出来，这时不显示，好过显示一个先跳 0 再跳 N 的数字。
 */
@Composable
internal fun SectionHeader(title: String, count: Int?) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = BeeDimens.gapTiny, bottom = BeeDimens.gapTight),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.Bottom,
    ) {
        Text(
            text = title,
            style = MaterialTheme.typography.headlineSmallEmphasized,
            color = MaterialTheme.colorScheme.onSurface,
        )
        if (count != null) {
            Text(
                text = stringResource(R.string.home_section_count, count),
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
internal fun CategoryLabel(category: Category) {
    Text(
        text = categoryTitle(category),
        style = MaterialTheme.typography.labelLarge,
    )
}

/**
 * 网格末尾的追加页状态：在拉 → 一行骨架、失败 → 原因 + 重试、没得拉 → 一句交代。
 *
 * ⚠️ 三种形态都不能撑高：末尾那一块占一屏的话，滚到底看到的全是提示，
 * 而用户此时最想要的恰好是"下面还有没有内容"。
 */
@Composable
internal fun MoreFooter(more: MorePages, onRetry: () -> Unit) {
    when {
        more.loading -> SkeletonPosterGrid(rows = 1, startStaggerIndex = 0)

        more.error != null -> Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = BeeDimens.gapSmall),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            BeeCenteredNotice(stringResource(R.string.home_more_failed, more.error))
            TextButton(onClick = onRetry) {
                Text(
                    text = stringResource(R.string.action_retry),
                    style = MaterialTheme.typography.labelLarge,
                )
            }
        }

        !more.hasMore -> BeeCenteredNotice(
            text = stringResource(R.string.home_all_loaded),
            modifier = Modifier.padding(vertical = BeeDimens.gapSmall),
        )

        // 还在往下滑的路上，什么都不摆
        else -> Unit
    }
}

/** 「推荐」这一位的名称由界面提供，数据层返回空串 —— 中文文案不该写死在数据层。 */
@Composable
internal fun categoryTitle(category: Category?): String =
    if (category == null || category.id == ContentRepository.CATEGORY_RECOMMEND) {
        stringResource(R.string.home_category_recommend)
    } else {
        category.name
    }
