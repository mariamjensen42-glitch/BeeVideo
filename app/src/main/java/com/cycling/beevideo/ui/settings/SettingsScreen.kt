package com.cycling.beevideo.ui.settings

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.MediumFlexibleTopAppBar
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.rememberTopAppBarState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.cycling.beevideo.R
import com.cycling.beevideo.domain.model.ThemeMode
import com.cycling.beevideo.domain.repository.ContentSourceRepository
import com.cycling.beevideo.domain.repository.DecoderMonitor
import com.cycling.beevideo.domain.repository.MediaCache
import com.cycling.beevideo.domain.repository.PlaybackSettings
import com.cycling.beevideo.ui.components.beeTopAppBarColors
import com.cycling.beevideo.ui.theme.BeeDimens

/**
 * 设置页。每节一个 `surface container low` 容器（M3 的 containment 手法），
 * 圆角用 extraLarge —— 块级容器在 Expressive 里就该比里面的元素圆得多。
 *
 * 外观节不直接收 `ThemeSettings`，是"值进来、回调出去"：磁盘那一步由导航宿主接，
 * 这样预览不用为了一个枚举去构造它。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    sources: ContentSourceRepository,
    settings: PlaybackSettings,
    cache: MediaCache,
    decoderMonitor: DecoderMonitor,
    themeMode: ThemeMode,
    onThemeModeChange: (ThemeMode) -> Unit,
    /** 无痕开关的当前值。它进的是参数不是本页的 `remember`：真相在 App 级那条流上。 */
    incognito: Boolean,
    onIncognitoChange: (Boolean) -> Unit,
) {
    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior(
        state = rememberTopAppBarState(),
    )
    val status by sources.status.collectAsStateWithLifecycle()

    // 占用与 applying 交给持有者：applying 以前用裸 remember，主题切换重建 Activity 后会复活
    val viewModel: SettingsViewModel = viewModel(
        factory = viewModelFactory { initializer { SettingsViewModel(sources, cache) } },
    )
    val state = viewModel.state
    val applying by state.applying.collectAsStateWithLifecycle()
    val cacheUsed by state.usageBytes.collectAsStateWithLifecycle()

    // 输入框以用户敲进去的为准，只在已生效的地址变了时才跟随
    var input by rememberSaveable(status.configUrl) { mutableStateOf(status.configUrl) }

    // 判据是**已经生效**的 configUrl 而不是输入框里的字：地址刚敲进去还没点装载时 chip 不该先亮
    val selectedConfig = RECOMMENDED_CONFIGS.indexOfFirst { it.url == status.configUrl }

    // 设置对象从外面注入：两份实例会让"设置页改了配额、播放页还用旧值"变成必然
    var cacheEnabled by remember { mutableStateOf(settings.cacheEnabled) }
    var cacheQuota by remember { mutableStateOf(settings.cacheQuotaBytes) }
    var autoPlayNext by remember { mutableStateOf(settings.autoPlayNext) }
    // 这一项**不走本页的 remember**：真相在 App 级那条流上（播放页与小窗都要当场跟着变）
    val pictureInPicture by settings.pictureInPicture.collectAsStateWithLifecycle()
    var decoderPreference by remember { mutableStateOf(settings.decoderPreference) }
    var headerText by remember { mutableStateOf(settings.customHeaderText) }
    var confirmClear by remember { mutableStateOf(false) }
    var pickerOpen by rememberSaveable { mutableStateOf(false) }

    // 实际生效的解码器是进程级事实，不在本页持有
    val decoderInUse by decoderMonitor.inUse.collectAsStateWithLifecycle()

    Scaffold(
        containerColor = MaterialTheme.colorScheme.surface,
        modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        topBar = {
            MediumFlexibleTopAppBar(
                title = {
                    Text(text = stringResource(R.string.settings_title))
                },
                scrollBehavior = scrollBehavior,
                colors = beeTopAppBarColors(),
            )
        },
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .verticalScroll(rememberScrollState())
                .padding(
                    PaddingValues(
                        horizontal = BeeDimens.screenMargin,
                        vertical = BeeDimens.gapSmall,
                    )
                ),
            horizontalAlignment = Alignment.Start,
        ) {
            SourceSection(
                status = status,
                input = input,
                onInputChange = { input = it },
                applying = applying,
                selectedConfig = selectedConfig,
                onSelectConfig = { index ->
                    val config = RECOMMENDED_CONFIGS[index]
                    // 输入框跟着走：否则切换后它还留着旧地址，看着像没生效
                    input = config.url
                    state.applyConfig(config.url)
                },
                onApply = { state.applyConfig(input) },
                onClear = {
                    state.clearSource()
                    // 输入框也清掉，否则地址还显示着，用户会以为没生效
                    input = ""
                },
                onOpenPicker = { pickerOpen = true },
            )

            Spacer(Modifier.height(BeeDimens.gapSmall))

            // 顺序：内容源（必须做的）→ 外观（纯偏好）→ 播放 / 缓存。
            // 纯偏好夹在中间，找主题开关时不用先滚过一整块带破坏性按钮的缓存区
            AppearanceSection(
                themeMode = themeMode,
                onThemeModeChange = onThemeModeChange,
            )

            Spacer(Modifier.height(BeeDimens.gapSmall))

            // 播放独立成节：它属于播放行为不是磁盘策略，塞进缓存节会让"关掉缓存"看起来像会关掉连播
            PlaybackSection(
                autoPlayNext = autoPlayNext,
                onAutoPlayNextChange = { on ->
                    autoPlayNext = on
                    settings.autoPlayNext = on
                },
                pictureInPicture = pictureInPicture,
                onPictureInPictureChange = settings::setPictureInPicture,
                decoderPreference = decoderPreference,
                onDecoderPreferenceChange = { value ->
                    decoderPreference = value
                    settings.decoderPreference = value
                },
                decoderInUse = decoderInUse,
                headerText = headerText,
                onHeaderTextChange = { text ->
                    headerText = text
                    settings.customHeaderText = text
                },
            )

            Spacer(Modifier.height(BeeDimens.gapSmall))

            CacheSection(
                enabled = cacheEnabled,
                quota = cacheQuota,
                usedBytes = cacheUsed,
                onEnabledChange = { on ->
                    cacheEnabled = on
                    settings.cacheEnabled = on
                },
                onQuotaChange = { index ->
                    cacheQuota = PlaybackSettings.QUOTA_CHOICES[index]
                    settings.cacheQuotaBytes = cacheQuota
                },
                onClearClick = { confirmClear = true },
            )

            Spacer(Modifier.height(BeeDimens.gapSmall))

            // 摆在缓存之后：它俩最容易混（都跟"本机留下什么"有关），紧挨着才好对照
            // 上面那句"站点配置与已下载的播放缓存不受影响"
            IncognitoSection(
                enabled = incognito,
                onEnabledChange = onIncognitoChange,
            )

            Spacer(Modifier.height(BeeDimens.gapSmall))

            AboutSection()

            Spacer(Modifier.height(BeeDimens.gapHuge))
        }
    }

    if (pickerOpen && status.sources.isNotEmpty()) {
        SourcePickerSheet(
            sources = status.sources,
            activeId = status.activeSourceId,
            excludedIds = status.excludedSourceIds,
            pinnedIds = status.pinnedSourceIds,
            onSelect = { id ->
                sources.selectSource(id)
                // 选完即走：切站是"选一个就完了"的动作
                pickerOpen = false
            },
            // 改开关**不关弹层**：连着设好几个站点是常态
            onToggleExcluded = { id ->
                sources.setSourceExcluded(id, id !in status.excludedSourceIds)
            },
            onTogglePin = { id -> sources.togglePinSource(id) },
            onDismiss = { pickerOpen = false },
        )
    }

    // 清缓存要确认：删掉的是用户可能花过钱下载的东西，而且一次几个 G
    if (confirmClear) {
        AlertDialog(
            onDismissRequest = { confirmClear = false },
            title = { Text(stringResource(R.string.settings_cache_clear_title)) },
            text = { Text(stringResource(R.string.settings_cache_clear_body)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        confirmClear = false
                        // 读回真占用而不是乐观填 0：删不干净时要让用户看见
                        state.clearCache()
                    },
                ) {
                    Text(stringResource(R.string.settings_cache_clear_confirm))
                }
            },
            dismissButton = {
                TextButton(onClick = { confirmClear = false }) {
                    Text(stringResource(R.string.dialog_cancel))
                }
            },
        )
    }
}
