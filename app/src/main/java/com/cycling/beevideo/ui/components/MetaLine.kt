package com.cycling.beevideo.ui.components

import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import com.cycling.beevideo.R

/**
 * 元信息拼接 —— 把若干**可能为空**的片段拼成一行。
 *
 * ─── 为什么不用 `"%1$s · %2$s · %3$s"` 这类格式串 ────────────────────
 * 自配源缺字段是常态，不是异常。实测 type=0 的 XML 源就没有评分字段，
 * 格式串会忠实渲染出：
 *
 *     分 · 2026 · 更新至 12
 *
 * 一个**没有数值的单位**。它不算崩、也不算空，但读者只会认为界面坏了 ——
 * 这类"数据缺一半时排版散架"的问题，比整块报错更难被发现。
 *
 * 这里空片段连同它自己的分隔符一起去掉；全空时返回空串，调用方整行不画。
 */
internal fun metaLine(vararg parts: String?): String =
    parts.filter { !it.isNullOrBlank() }.joinToString(" · ")

/**
 * 评分片段：有分数才是「8.4 分」，没有就给空串交给 [metaLine] 丢掉。
 *
 * ⚠️ 判断必须建在**原始值**上。写成
 * `metaLine(stringResource(R.string.vod_score_suffix, score), …)` 是不行的 ——
 * 空分数格式化之后会变成 `" 分"`，一个非空文本，反而更躲不掉。
 */
@Composable
internal fun scorePart(score: String): String =
    if (score.isBlank()) "" else stringResource(R.string.vod_score_suffix, score)
