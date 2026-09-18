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
import com.cycling.beevideo.domain.repository.LibraryRepository
import com.cycling.beevideo.domain.repository.MediaCache
import com.cycling.beevideo.domain.repository.PlaybackSettings
import com.cycling.beevideo.domain.repository.ThemeSettings
import com.cycling.beevideo.ui.detail.DetailScreen
import com.cycling.beevideo.ui.home.HomeScreen
import com.cycling.beevideo.ui.keep.KeepScreen
import com.cycling.beevideo.ui.player.PlayerScreen
import com.cycling.beevideo.ui.search.SearchScreen
import com.cycling.beevideo.ui.settings.SettingsScreen

object Routes {
    const val HOME = "home"
    const val KEEP = "keep"
    const val SETTINGS = "settings"

    /**
     * 搜索。**不是**顶层 tab —— 它从首页顶栏的 action 进入，返回即回到首页。
     *
     * 不加成第四个底栏项：`ShortNavigationBar` 的官方口径是 3–5 项，
     * 但手机竖屏下四项的中文标签会被挤到换行/截断。搜索是"任务"而不是"区域"，
     * 按 M3 的做法应该由入口 action 打开。
     */
    const val SEARCH = "search"
    const val DETAIL = "detail/{vodId}"
    const val PLAYER = "player/{vodId}/{lineIndex}/{episodeIndex}"

    /*
     * vodId **必须 Uri.encode 进路由**，否则点进去直接闪退回桌面。
     *
     * vodId 的格式是 `siteKey:sourceId`，冒号是格式的一部分；sourceId 又常是
     * `/vod-detail-id-95012.html` 这种带斜杠的路径。而导航图把 `{vodId}` 编译成
     * 的正则是 `([^/]*?|)`（见 NavDeepLink.PATH_REGEX）—— 吃不下斜杠，于是
     * `navigate("detail/糯米:/vod-detail-id-95012.html")` 匹配失败，抛
     * `IllegalArgumentException: Navigation destination ... cannot be found`，
     * 主线程当场崩。报错信息里那句「cannot be found」看着像路由没注册，
     * 其实路由好好的，是**值**把路径撑破了。
     *
     * 编码后只剩 `[A-Za-z0-9_-!.~'()*%]`，不含斜杠、冒号、`?`、`#`，必然匹配。
     *
     * ⚠️ 读取的那一端**不要再 decode 一次**：NavDeepLink 取 path 参数时会走
     * NavUriUtils.decode（= android.net.Uri.decode）再放进 Bundle，值已经是原文。
     * 手抖补一次 decode，只会把原文里的 `%` 当转义吃掉 —— 双重解码比不编码还糟。
     */
    fun detail(vodId: String): String = "detail/${Uri.encode(vodId)}"

    fun player(vodId: String, lineIndex: Int, episodeIndex: Int): String =
        "player/${Uri.encode(vodId)}/$lineIndex/$episodeIndex"
}

/**
 * 顶层目的地。三个，正好落在 M3 对导航栏「3–5 个」的要求里。
 * 少于 3 个应该用 tabs，多于 5 个就得换成可展开的侧栏。
 */
private enum class TopDestination(val route: String, val labelRes: Int) {
    HOME(Routes.HOME, R.string.nav_home),
    KEEP(Routes.KEEP, R.string.nav_keep),
    SETTINGS(Routes.SETTINGS, R.string.nav_settings),
}

/** 顶层路由集合：判断一次转场是不是「切 tab」。 */
private val TAB_ROUTES: Set<String> =
    TopDestination.entries.mapTo(mutableSetOf()) { it.route }

/**
 * 前进时新页面从右侧滑入的位移量 = 屏宽 / 这个除数。
 *
 * 不是整屏横推 —— M3 的页面转场是「小位移 + 淡入」，整屏横推是 iOS 的手感，
 * 位移太大反而显得笨。6 是本项目取值，M3 未规定比例。
 */
private const val PUSH_OFFSET_DIVISOR = 6


/** 选中用实心图标、未选中用描边图标 —— M3 的明确要求，别两个都用描边。 */
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
    settings: PlaybackSettings,
    mediaCache: MediaCache,
    theme: ThemeSettings,
    navController: NavHostController = rememberNavController(),
) {
    val backStackEntry by navController.currentBackStackEntryAsState()
    val destination = backStackEntry?.destination
    val currentTop = TopDestination.entries.firstOrNull { top ->
        destination?.hierarchy?.any { it.route == top.route } == true
    }

    /*
     * 恢复上次配置。
     *
     * 放在导航宿主而不是某个页面上：三个 tab 都可能用到数据，挂在首页会
     * 出现"先进设置页就没加载"的洞。`restore()` 本身幂等，重组多少次都只装载一次。
     */
    LaunchedEffect(sources) { sources.restore() }

    // 导航区恒用 surface container —— 这正是 ShortNavigationBar 的默认容器色
    // （NavigationBarTokens.ContainerColor = surfaceContainer），所以不用显式传。
    Scaffold(
        bottomBar = {
            if (currentTop != null) {
                /*
                 * 用 ShortNavigationBar 而不是 NavigationBar。
                 *
                 * 两者长相接近，差别在**高度**，而高度是能一眼看出来的：
                 *   NavigationBar      容器取 NavigationBarTokens.TallContainerHeight = 80dp
                 *   ShortNavigationBar 容器取 NavigationBarTokens.ContainerHeight     = 64dp
                 * 内部的 item token（56×32 指示器、cornerFull 形状、labelMedium 字、
                 * MotionScheme 的 defaultEffects / fastSpatial）两边**完全一样**，
                 * 颜色 token 也同一套。所以这不是换皮，是换高度。
                 *
                 * 官方源码对 ShortNavigationBar 的说明写得很直白：
                 * 「窄屏放 3–5 项，arrangement 用 EqualWeight；iconPosition 用 Top
                 *   的场景是**竖屏手机**，用 Start 的是横屏手机。」
                 * 本项目锁定竖屏、三个 tab —— 正面命中，选它。
                 */
                ShortNavigationBar {
                    TopDestination.entries.forEach { top ->
                        val selected = currentTop == top
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
                            /*
                             * 颜色与字型都不传 —— 默认值就是规范值，已逐个核对：
                             *   指示器 secondaryContainer / 选中图标 onSecondaryContainer
                             *   选中标签 secondary（Expressive 从 onSurfaceVariant 改的）
                             *   未选中图标与标签 onSurfaceVariant
                             *   标签字型 labelMedium，由组件内部 ProvideContentColorTextStyle 下发
                             * 之前这里把五个颜色手抄了一遍，抄对了，但抄的正是默认值。
                             */
                        )
                    }
                }
            }
        },
    ) { innerPadding ->
        /*
         * 页面转场显式接 MotionScheme。
         *
         * 不接的话拿到的是 navigation-compose 自己的默认 spec —— 那套跟主题里
         * `MotionScheme.expressive()` 是两回事，弹簧手感对不上。
         *
         * 规则：
         *   tab 之间切换 → 纯淡入淡出（同层级平级切换，不该有方向感）
         *   进详情 / 播放 → 横向滑入 + 淡入，位移只有屏宽的 1/6
         *   返回        → 反向滑回
         */
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
            // 外层 Scaffold 已经把系统栏安全区折算进 innerPadding，
            // 必须再 consumeWindowInsets 一次：否则里面每个页面自己的
            // Scaffold / TopAppBar 会把同一份顶部 inset 再加一遍，
            // 结果是每个页面顶栏上方白白多出一整条状态栏高度的空白。
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
                    onVodClick = { vod -> navController.navigate(Routes.detail(vod.id)) },
                    onOpenSettings = { navController.navigateTopLevel(TopDestination.SETTINGS) },
                    onOpenSearch = { navController.navigate(Routes.SEARCH) },
                )
            }

            composable(Routes.KEEP) {
                KeepScreen(
                    library = library,
                    onVodClick = { vodId -> navController.navigate(Routes.detail(vodId)) },
                )
            }

            composable(Routes.SETTINGS) {
                /*
                 * 外观模式的「订阅 → 传值 / 写回」落在这一层。
                 *
                 * 设置页只收一个值和一支回调（理由见 `SettingsScreen` 的说明）：
                 * 它不需要知道这东西存在磁盘上、也不需要知道谁在监听。
                 *
                 * 这里读到的 `mode` 和 `MainActivity` 读的是**同一条流**，
                 * 所以点一下选项 → `set()` 写流 → 顶层的 `BeeVideoTheme`
                 * 换配色 → 本页重组拿到新选中态，一个来回就收敛了，不用
                 * 在本页做乐观更新。
                 */
                val mode by theme.mode.collectAsStateWithLifecycle()
                SettingsScreen(
                    sources = sources,
                    settings = settings,
                    cache = mediaCache,
                    themeMode = mode,
                    onThemeModeChange = theme::set,
                )
            }

            /*
             * 搜索页。
             *
             * 用 `navigate`（入栈）而不是 `navigateTopLevel` —— 它是首页之上的
             * 一层，返回键回到首页，而不是在三个 tab 之间跳。
             *
             * 也因此它**不在** TAB_ROUTES 里：`isTabSwitch` 会判定首页 → 搜索
             * 是"前进"，于是走 `pageEnter`（小位移横滑 + 淡入），返回时反向。
             * 如果漏了这一步、让它落进 TAB_ROUTES 的判据，转场会退化成纯淡入，
             * 看不出层级关系。
             */
            composable(Routes.SEARCH) {
                SearchScreen(
                    content = content,
                    onVodClick = { vod -> navController.navigate(Routes.detail(vod.id)) },
                    onBack = { navController.popBackStack() },
                )
            }

            composable(
                route = Routes.DETAIL,
                arguments = listOf(navArgument("vodId") { type = NavType.StringType }),
            ) { entry ->
                // 这里拿到的已经是解码后的原文，别再加 Uri.decode（见 Routes 的注释）。
                val vodId = entry.arguments?.getString("vodId").orEmpty()
                DetailScreen(
                    content = content,
                    library = library,
                    vodId = vodId,
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
                // 同上：已解码。
                val vodId = entry.arguments?.getString("vodId").orEmpty()
                val lineIndex = entry.arguments?.getInt("lineIndex") ?: 0
                val episodeIndex = entry.arguments?.getInt("episodeIndex") ?: 0
                PlayerScreen(
                    content = content,
                    library = library,
                    settings = settings,
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
