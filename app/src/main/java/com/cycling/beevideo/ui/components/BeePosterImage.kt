package com.cycling.beevideo.ui.components

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import coil.compose.AsyncImage
import coil.request.ImageRequest

/**
 * 封面图。
 *
 * ─── 为什么共享 ────────────────────────────────────────────────────────
 * 同样七行 `ImageRequest.Builder(context).data(pic).crossfade(true).build()`
 * 在卡片、刊头、详情页各写了一遍。真正的问题不是重复本身，而是**这三处必须
 * 一起改**：换缓存策略、加占位、加失败重试，任何一项都要求改三遍，
 * 而漏掉一处不会报错 —— 只会让某一个位置的封面比别处慢一拍或闪一下。
 *
 * ─── `pic` 为空时什么都不画 ────────────────────────────────────────────
 * 这是**被设计过的一条路径**，不是异常兜底：相当一部分来源不给封面
 * （聚合类源尤其常见），所以调用点在它底下铺一层渐变占位，这里直接返回。
 *
 * 用 Coil 的 `placeholder` 也能做，但那要再维护一套加载状态 ——
 * 把渐变留在底下更少一层，也更好看（渐变是设计的一部分，不是"图没来"的提示）。
 *
 * `Crop`：海报比例千奇百怪，统一裁切才能让一面墙的卡片边缘对齐。
 */
@Composable
fun BeePosterImage(
    pic: String,
    contentDescription: String? = null,
    modifier: Modifier = Modifier.fillMaxSize(),
) {
    if (pic.isEmpty()) return
    val context = LocalContext.current
    AsyncImage(
        // remember(pic)：换图时才重建请求；每次重组新建会让 Coil 每帧重下一次
        model = remember(pic) {
            ImageRequest.Builder(context)
                .data(pic)
                .crossfade(true)
                .build()
        },
        contentDescription = contentDescription,
        contentScale = ContentScale.Crop,
        modifier = modifier,
    )
}
