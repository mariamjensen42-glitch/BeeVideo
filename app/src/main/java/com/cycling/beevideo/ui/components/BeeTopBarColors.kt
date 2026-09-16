package com.cycling.beevideo.ui.components

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.TopAppBarColors
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable

/**
 * 顶栏容器色恒等于页面底色，滚动前后不变。
 *
 * M3 给顶栏的默认配色是「静止 surface → 有内容滚过时 surfaceContainer + Level2 高度」
 * （token 表里写死的就是这一对：`AppBarTokens.ContainerColor = Surface`、
 * `OnScrollContainerColor = SurfaceContainer`，容器 elevation 反而是 Level0，
 * 也就是说那次变化纯粹靠换底色来表达，没有任何真实的高度/阴影差别）。
 *
 * BeeVideo 不用这套。原因：Scaffold 的 topBar 与 body 是上下相邻的两块，
 * body 的滚动区有自己的裁剪边界，内容本来就不会真的压到顶栏底下 ——
 * 于是那次变色不传达任何信息，只剩下一条横在页面顶端的色带：
 * 深色 `#0B0A08` → `#1C1915`，浅色 `#FFF9EF` → `#F5EDDD`，一眼就看得出来。
 *
 * 只覆盖 `containerColor` / `scrolledContainerColor`，图标、标题、副标题色继续吃默认值：
 * `appBarContainerColor` 是在这两个色之间做 lerp 的，两端取同一个值就等于恒定单色，
 * 官方那套滚动插值动画也就不用管了。
 */
@Composable
fun beeTopAppBarColors(): TopAppBarColors =
    TopAppBarDefaults.topAppBarColors(
        // 显式给出，而不是依赖 token 解析 —— 目标就是和 `Scaffold(containerColor = ...)`
        // 那个 surface 一模一样，两边写同一个表达式才不会有一天各自漂移。
        containerColor = MaterialTheme.colorScheme.surface,
        scrolledContainerColor = MaterialTheme.colorScheme.surface,
    )
