package com.cycling.beevideo.ui.components

import androidx.compose.runtime.staticCompositionLocalOf

/**
 * 当前是否处于无痕会话。给封面图用：无痕期间**只进内存缓存、不写磁盘**。
 *
 * 用 CompositionLocal 而不是逐层传参：图片是在十几处卡片与详情页里画的，
 * 为一个只影响缓存策略的标志去改每一条调用链不值得。
 *
 * 默认 `false` —— 忘了提供也只是照常缓存，且无痕那一路本身还有收尾清理兜着。
 */
val LocalIncognito = staticCompositionLocalOf { false }
