package com.cycling.beevideo.ui.nav

import android.net.Uri
import androidx.compose.animation.AnimatedContentTransitionScope
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bookmarks
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.outlined.Bookmarks
import androidx.compose.material.icons.outlined.Home
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.ShortNavigationBar
import androidx.compose.material3.ShortNavigationBarItem
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.IntOffset
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavBackStackEntry
import androidx.navigation.NavDestination.Companion.hierarchy
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.cycling.beevideo.R
import com.cycling.beevideo.domain.repository.ContentRepository
import com.cycling.beevideo.domain.repository.ContentSourceRepository
import com.cycling.beevideo.domain.repository.IncognitoMode
import com.cycling.beevideo.domain.repository.LibraryRepository
import com.cycling.beevideo.domain.repository.MediaCache
import com.cycling.beevideo.domain.repository.PlaybackSettings
import com.cycling.beevideo.domain.repository.SearchHistoryRepository
import com.cycling.beevideo.domain.repository.ThemeSettings
import com.cycling.beevideo.ui.detail.DetailScreen
import com.cycling.beevideo.ui.history.HistoryRoute
import com.cycling.beevideo.ui.home.HomeScreen
import com.cycling.beevideo.ui.keep.KeepRoute
import com.cycling.beevideo.ui.player.PlayerScreen
import com.cycling.beevideo.ui.search.SearchRoute
import com.cycling.beevideo.ui.settings.SettingsScreen

object Routes {
    const val HOME = "home"
    const val KEEP = "keep"
    const val SETTINGS = "settings"

    // ⚠️ 搜索与历史都是**入栈**的一层（从首页 action 进入），不是第四个底栏项：
    // 手机竖屏下四项中文标签会被挤到换行；它们是"任务"而不是"区域"
    const val SEARCH = "search"
    const val HISTORY = "history"
    const val DETAIL = "detail/{vodId}"
    const val PLAYER = "player/{vodId}/{lineIndex}/{episodeIndex}"

    /**
     * ⚠️ vodId **必须 Uri.encode**，否则点进去直接闪退回桌面。
     *
     * vodId 是 `siteKey:sourceId`，sourceId 常带斜杠；而导航图把 `{vodId}` 编译成
     * `([^/]*?|)`，吃不下斜杠 → 匹配失败抛 `IllegalArgumentException: cannot be found`。
     *
     * ⚠️ 读取端**不要再 decode**：NavDeepLink 取 path 参数时已经 decode 过一次，
     * 手补一次只会把原文里的 `%` 当转义吃掉。
     */
    fun detail(vodId: String): String = "detail/${Uri.encode(vodId)}"

    fun player(vodId: String, lineIndex: Int, episodeIndex: Int): String =
        "player/${Uri.encode(vodId)}/$lineIndex/$episodeIndex"
}

/** 顶层目的地三个，落在 M3 对导航栏「3–5 个」的要求里。 */
private enum class TopDestination(val route: String, val labelRes: Int) {
    HOME(Routes.HOME, R.string.nav_home),
    KEEP(Routes.KEEP, R.string.nav_keep),
    SETTINGS(Routes.SETTINGS, R.string.nav_settings),
}

private val TAB_ROUTES: Set<String> =
    TopDestination.entries.mapTo(mutableSetOf()) { it.route }

/** 前进时新页面从右侧滑入的位移 = 屏宽 / 6。M3 的过渡是小位移，不是整屏横推。 */
private const val PUSH_OFFSET_DIVISOR = 6

/** 选中用实心图标、未选中用描边 —— M3 的明确要求。 */
@Composable
private fun destinationIcon(destination: TopDestination, selected: Boolean): ImageVector =
    when (destination) {
        TopDestination.HOME ->
            if (selected) Icons.Filled.Home else Icons.Outlined.Home

        TopDestination.KEEP ->
            if (selected) Icons.Filled.Bookmarks else Icons.Outlined.Bookmarks

        TopDestination.SETTINGS ->
            if (selected) Icons.Filled.Settings else Icons.Outlined.Settings
    }

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BeeNavHost(
    content: ContentRepository,
    sources: ContentSourceRepository,
    library: LibraryRepository,
    searchHistory: SearchHistoryRepository,
    settings: PlaybackSettings,
    mediaCache: MediaCache,
    theme: ThemeSettings,
    incognito: IncognitoMode,
    navController: NavHostController = rememberNavController(),
) {
    val backStackEntry by navController.currentBackStackEntryAsState()
    val destination = backStackEntry?.destination
    val currentTop = TopDestination.entries.firstOrNull { top ->
        destination?.hierarchy?.any { it.route == top.route } == true
    }

    // 放导航宿主而不是某个页面：三个 tab 都可能用数据，挂首页会出现"先进设置页就没加载"
    LaunchedEffect(sources) { sources.restore() }

    Scaffold(
        bottomBar = {
            if (currentTop != null) {
                // ⚠️ ShortNavigationBar 而不是 NavigationBar：二者 item token 与颜色完全一样，
                // 只差容器高度（64dp vs 80dp）。本项目锁竖屏 + 三 tab，正面命中它的适用场景
                ShortNavigationBar {
                    TopDestination.entries.forEach { top ->
                        val selected = currentTop == top
                        // 颜色与字型都不传：默认值就是规范值
                        ShortNavigationBarItem(
                            selected = selected,
                            onClick = { navController.navigateTopLevel(top) },
                            icon = {
                                Icon(
                                    imageVector = destinationIcon(top, selected),
                                    contentDescription = null,
                                )
                            },
                            label = {
                                Text(
                                    text = stringResource(top.labelRes),
                                    maxLines = 1,
                                )
                            },
                        )
                    }
                }
            }
        },
    ) { innerPadding ->
        // ⚠️ 必须接 MotionScheme：不接拿到的是 navigation-compose 自己的默认 spec，
        // 弹簧手感与主题里的 MotionScheme.expressive() 对不上
        val motion = MaterialTheme.motionScheme
        val spatial = motion.defaultSpatialSpec<IntOffset>()
        val effects = motion.defaultEffectsSpec<Float>()

        val tabEnter = fadeIn(effects)
        val tabExit = fadeOut(effects)
        val pageEnter = slideInHorizontally(spatial) { it / PUSH_OFFSET_DIVISOR } + fadeIn(effects)
        val pageExit = slideOutHorizontally(spatial) { -it / PUSH_OFFSET_DIVISOR } + fadeOut(effects)
        val pagePopEnter = slideInHorizontally(spatial) { -it / PUSH_OFFSET_DIVISOR } + fadeIn(effects)
        val pagePopExit = slideOutHorizontally(spatial) { it / PUSH_OFFSET_DIVISOR } + fadeOut(effects)

        // 两端都是顶层 tab 才算「切 tab」；详情 → 首页是返回，不是切 tab
        val isTabSwitch: AnimatedContentTransitionScope<NavBackStackEntry>.() -> Boolean = {
            initialState.destination.route in TAB_ROUTES &&
                targetState.destination.route in TAB_ROUTES
        }

        NavHost(
            navController = navController,
            startDestination = Routes.HOME,
            // ⚠️ 外层 Scaffold 已把系统栏安全区折进 innerPadding，必须再 consume 一次：
            // 否则里面每个页面的 TopAppBar 会把同一份 inset 再加一遍，顶上多出一条空白
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .consumeWindowInsets(innerPadding),
            enterTransition = { if (isTabSwitch()) tabEnter else pageEnter },
            exitTransition = { if (isTabSwitch()) tabExit else pageExit },
            popEnterTransition = { if (isTabSwitch()) tabEnter else pagePopEnter },
            popExitTransition = { if (isTabSwitch()) tabExit else pagePopExit },
        ) {
            composable(Routes.HOME) {
                HomeScreen(
                    content = content,
                    sources = sources,
                    library = library,
                    onVodClick = { vod -> navController.navigate(Routes.detail(vod.id)) },
                    // 直接进播放页：线路号与集号都在记录里，来源挂掉时照样跳得过去
                    onResume = { progress ->
                        navController.navigate(
                            Routes.player(progress.vodId, progress.lineIndex, progress.episodeIndex)
                        )
                    },
                    onOpenHistory = { navController.navigate(Routes.HISTORY) },
                    onOpenSettings = { navController.navigateTopLevel(TopDestination.SETTINGS) },
                    onOpenSearch = { navController.navigate(Routes.SEARCH) },
                )
            }

            composable(Routes.KEEP) {
                KeepRoute(
                    library = library,
                    onVodClick = { vodId -> navController.navigate(Routes.detail(vodId)) },
                )
            }

            composable(Routes.SETTINGS) {
                // 设置页只收一个值和一支回调。这里读到的 mode 与 MainActivity 是同一条流，
                // 点一下 → set() 写流 → 顶层换配色 → 本页重组拿到新选中态，不用乐观更新
                val mode by theme.mode.collectAsStateWithLifecycle()
                // 无痕同理：本页只拨开关，状态的真身在 App 级那条流上
                val incognitoOn by incognito.enabled.collectAsStateWithLifecycle()
                SettingsScreen(
                    sources = sources,
                    settings = settings,
                    cache = mediaCache,
                    themeMode = mode,
                    onThemeModeChange = theme::set,
                    incognito = incognitoOn,
                    onIncognitoChange = incognito::set,
                )
            }

            // ⚠️ 用 navigate（入栈）而不是 navigateTopLevel，也不在 TAB_ROUTES 里：
            // 首页 → 搜索要走 pageEnter（小位移横滑），否则转场退化成纯淡入、看不出层级
            composable(Routes.SEARCH) {
                SearchRoute(
                    content = content,
                    history = searchHistory,
                    onVodClick = { vod -> navController.navigate(Routes.detail(vod.id)) },
                    onBack = { navController.popBackStack() },
                )
            }

            composable(Routes.HISTORY) {
                HistoryRoute(
                    library = library,
                    sources = sources,
                    incognito = incognito,
                    onBack = { navController.popBackStack() },
                    onContinue = { progress ->
                        navController.navigate(
                            Routes.player(progress.vodId, progress.lineIndex, progress.episodeIndex)
                        )
                    },
                    onOpenDetail = { vodId -> navController.navigate(Routes.detail(vodId)) },
                )
            }

            composable(
                route = Routes.DETAIL,
                arguments = listOf(navArgument("vodId") { type = NavType.StringType }),
            ) { entry ->
                // 已解码，别再加 Uri.decode
                val vodId = entry.arguments?.getString("vodId").orEmpty()
                // 无痕状态在这里读：它只决定收藏按钮按不按得动，
                // 记录本身早就被仓储那条流换成了空的
                val incognitoOn by incognito.enabled.collectAsStateWithLifecycle()
                DetailScreen(
                    content = content,
                    library = library,
                    vodId = vodId,
                    keepEnabled = !incognitoOn,
                    onBack = { navController.popBackStack() },
                    onPlay = { lineIndex, episodeIndex ->
                        navController.navigate(Routes.player(vodId, lineIndex, episodeIndex))
                    },
                )
            }

            composable(
                route = Routes.PLAYER,
                arguments = listOf(
                    navArgument("vodId") { type = NavType.StringType },
                    navArgument("lineIndex") { type = NavType.IntType },
                    navArgument("episodeIndex") { type = NavType.IntType },
                ),
            ) { entry ->
                val vodId = entry.arguments?.getString("vodId").orEmpty()
                val lineIndex = entry.arguments?.getInt("lineIndex") ?: 0
                val episodeIndex = entry.arguments?.getInt("episodeIndex") ?: 0
                PlayerScreen(
                    content = content,
                    library = library,
                    settings = settings,
                    incognito = incognito,
                    vodId = vodId,
                    lineIndex = lineIndex,
                    episodeIndex = episodeIndex,
                    onBack = { navController.popBackStack() },
                )
            }
        }
    }
}

private fun NavHostController.navigateTopLevel(destination: TopDestination) {
    navigate(destination.route) {
        popUpTo(graph.findStartDestination().id) {
            saveState = true
        }
        launchSingleTop = true
        restoreState = true
    }
}
