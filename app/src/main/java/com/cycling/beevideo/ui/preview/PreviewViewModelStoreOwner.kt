package com.cycling.beevideo.ui.preview

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.ViewModelStoreOwner
import androidx.lifecycle.viewmodel.compose.LocalViewModelStoreOwner

/**
 * 给 `@Preview` 补一个 [ViewModelStoreOwner]。
 *
 * 页面现在都经 `viewModel()` 拿状态持有者（见 `docs/adr/0004-state-holder-vs-viewmodel.md`），
 * 而 `viewModel()` 要求作用域里有一个 `ViewModelStoreOwner`。真机上是导航栈的
 * `NavBackStackEntry`；预览环境**不保证**提供，缺了就是渲染时抛
 * `IllegalStateException: No ViewModelStoreOwner was provided` —— 那是**渲染**期的错，
 * 编译检查抓不到，所以这里自己给一个。
 *
 * ⚠️ 只包预览，不进任何业务路径。
 */
@Composable
fun PreviewViewModelStoreOwner(content: @Composable () -> Unit) {
    val owner = remember {
        object : ViewModelStoreOwner {
            override val viewModelStore = ViewModelStore()
        }
    }
    CompositionLocalProvider(LocalViewModelStoreOwner provides owner, content = content)
}
