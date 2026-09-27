package com.cycling.beevideo.ui.home

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.outlined.History
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LoadingIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.MediumFlexibleTopAppBar
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.rememberTopAppBarState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.cycling.beevideo.R
import com.cycling.beevideo.domain.model.PlayProgress
import com.cycling.beevideo.domain.model.SourcePhase
import com.cycling.beevideo.domain.model.Vod
import com.cycling.beevideo.domain.repository.ContentRepository
import com.cycling.beevideo.domain.repository.ContentSourceRepository
import com.cycling.beevideo.domain.repository.LibraryRepository
import com.cycling.beevideo.ui.components.beeTopAppBarColors
import com.cycling.beevideo.ui.settings.SourcePickerSheet
import kotlinx.coroutines.launch

/** 首页。最外层按内容源的状态分支（未配置 / 装载中 / 失败 / 就绪），就绪之后才是内容页。 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun HomeScreen(
    content: ContentRepository,
    sources: ContentSourceRepository,
    library: LibraryRepository,
    onVodClick: (Vod) -> Unit,
    onResume: (PlayProgress) -> Unit,
    onOpenHistory: () -> Unit,
    onOpenSettings: () -> Unit,
    onOpenSearch: () -> Unit,
) {
    val status by sources.status.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()

    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior(
        state = rememberTopAppBarState(),
    )

    val activeName = status.sources
        .firstOrNull { it.id == status.activeSourceId }
        ?.name
        .orEmpty()

    // 副标题就是换源入口：把已经在显示的东西变成可点的，切源步骤为零
    var pickerOpen by rememberSaveable { mutableStateOf(false) }

    // 顶栏容器色必须与 body 同为 surface，否则滚动时会看到一条贴在页面顶端的色带
    Scaffold(
        containerColor = MaterialTheme.colorScheme.surface,
        modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        topBar = {
            MediumFlexibleTopAppBar(
                title = { Text(text = stringResource(R.string.home_brand)) },
                subtitle = {
                    if (activeName.isNotEmpty()) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.clickable { pickerOpen = true },
                        ) {
                            Text(
                                text = activeName,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.weight(1f, fill = false),
                            )
                            // 三角是必需品：副标题本来只是行文字，不画出来没人会去点它
                            Icon(
                                imageVector = Icons.Filled.ArrowDropDown,
                                contentDescription = stringResource(R.string.settings_source_change),
                            )
                        }
                    }
                },
                // 搜索与历史是"做一件事"，不是"我在哪块区域"，所以进 actions 而不是底栏 tab。
                // 历史尤其要在这一层：它是用户自己的数据，与来源状态无关
                actions = {
                    IconButton(onClick = onOpenHistory) {
                        Icon(
                            imageVector = Icons.Outlined.History,
                            contentDescription = stringResource(R.string.history_title),
                        )
                    }
                    IconButton(onClick = onOpenSearch) {
                        Icon(
                            imageVector = Icons.Filled.Search,
                            contentDescription = stringResource(R.string.search_action),
                        )
                    }
                },
                scrollBehavior = scrollBehavior,
                colors = beeTopAppBarColors(),
            )
        },
    ) { innerPadding ->
        val bodyModifier = Modifier
            .fillMaxSize()
            .padding(innerPadding)

        when (status.phase) {
            SourcePhase.EMPTY -> SourceNotice(
                modifier = bodyModifier,
                title = stringResource(R.string.home_no_source_title),
                body = stringResource(R.string.home_no_source_body),
                actionLabel = stringResource(R.string.home_go_settings),
                onAction = onOpenSettings,
            )

            SourcePhase.LOADING -> Box(
                modifier = bodyModifier,
                contentAlignment = Alignment.Center,
            ) {
                LoadingIndicator(color = MaterialTheme.colorScheme.primary)
            }

            SourcePhase.FAILED -> SourceNotice(
                modifier = bodyModifier,
                title = stringResource(R.string.home_source_failed_title),
                body = status.message,
                actionLabel = stringResource(R.string.home_retry),
                onAction = { scope.launch { sources.applyConfig(status.configUrl) } },
                secondaryLabel = stringResource(R.string.home_go_settings),
                onSecondary = onOpenSettings,
            )

            SourcePhase.READY -> key(status.activeSourceId) {
                // key 用 activeSourceId：换来源时整棵子树重建，否则会停在上一个源的分类上
                // ⚠️ bodyModifier 必须传下去，漏掉的话内容会从屏幕最顶端铺开、被顶栏盖住
                HomeFeed(
                    modifier = bodyModifier,
                    content = content,
                    library = library,
                    activeSourceId = status.activeSourceId,
                    onVodClick = onVodClick,
                    onResume = onResume,
                    onOpenHistory = onOpenHistory,
                )
            }
        }
    }

    if (pickerOpen && status.sources.isNotEmpty()) {
        SourcePickerSheet(
            sources = status.sources,
            activeId = status.activeSourceId,
            onSelect = { id ->
                sources.selectSource(id)
                pickerOpen = false
            },
            onDismiss = { pickerOpen = false },
        )
    }
}
