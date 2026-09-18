package com.cycling.beevideo.ui.settings

import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.LoadingIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.MediumFlexibleTopAppBar
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.rememberTopAppBarState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.tooling.preview.Preview
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.cycling.beevideo.R
import com.cycling.beevideo.domain.model.SourcePhase
import com.cycling.beevideo.domain.model.ThemeMode
import com.cycling.beevideo.domain.repository.ContentSourceRepository
import com.cycling.beevideo.domain.repository.MediaCache
import com.cycling.beevideo.domain.repository.PlaybackSettings
import com.cycling.beevideo.ui.components.BeeChipRow
import com.cycling.beevideo.ui.components.beeTopAppBarColors
import com.cycling.beevideo.ui.preview.FakeMediaCache
import com.cycling.beevideo.ui.preview.FakePlaybackSettings
import com.cycling.beevideo.ui.preview.FakeSourceRepository
import com.cycling.beevideo.ui.preview.PreviewViewModelStoreOwner
import com.cycling.beevideo.ui.theme.BeeDimens
import com.cycling.beevideo.ui.theme.BeeVideoTheme

/**
 * 设置页。
 *
 * 每一节是一个「容器」—— 用 `surface container low` 把相关内容框起来，
 * 这是 M3 的 containment 手法，比加分割线更符合当前规范。
 *
 * 圆角用 `extraLarge`（28dp）而不是 `medium`（12dp）：块级容器在 Expressive
 * 里就该比里面的元素圆得多，12dp 会让它看起来像个大按钮。
 *
 * 这一页**不做宽屏适配**：项目锁定手机竖屏，之前那句 widthIn(max = 640dp)
 * 是为平板写的，永远也不会生效，已删。
 *
 * ─── 内容源这一节是本项目唯一的"输入" ─────────────────────────────────
 * 产品定位是不内置任何内容源，所以这一节是全 App 唯一需要用户动手的地方。
 * 它必须做到三件事：**说清楚为什么要自己填**、**失败时说清楚哪一步错了**、
 * **成功前不要把旧输入清掉**（用户改一个字符重试是常态，清空等于惩罚他）。
 *
 * ─── 外观这一节为什么不直接收 `ThemeSettings` ─────────────────────────
 * 它是「值进来、回调出去」，磁盘那一步由导航宿主接（见 `BeeNavHost` 的
 * SETTINGS 目的地上那句 collect）。两个好处：
 *   1. 本页只剩一个数据依赖（`sources`），预览不用为了一个枚举去构造
 *      `ThemeSettings` —— 预览环境里的 `Context.getSharedPreferences`
 *      不是真实现，硬构造会直接在预览里崩；
 *   2. 选中态的唯一来源是外部传进来的值。页面自己持有一份副本的话，
 *      "别处改了模式、这一页还显示旧的"就会出现。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    sources: ContentSourceRepository,
    settings: PlaybackSettings,
    cache: MediaCache,
    themeMode: ThemeMode,
    onThemeModeChange: (ThemeMode) -> Unit,
) {
    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior(
        state = rememberTopAppBarState(),
    )
    val status by sources.status.collectAsState()

    /*
     * 占用与 `applying` 交给持有者（见 `SettingsState` 的说明）：
     * 后者以前用裸 `remember`，主题切换会重建 Activity —— 按钮会在装载途中复活。
     */
    val viewModel: SettingsViewModel = viewModel(
        factory = viewModelFactory { initializer { SettingsViewModel(sources, cache) } },
    )
    val state = viewModel.state
    val applying by state.applying.collectAsStateWithLifecycle()
    val cacheUsed by state.usageBytes.collectAsStateWithLifecycle()

    /*
     * 输入框的内容以**用户敲进去的**为准，只在配置地址本身变了（比如启动时
     * 从本地恢复）时才跟着更新 —— 反过来让输入框无条件跟随 status 的话，
     * 装载失败把 configUrl 改成用户输入的值倒还好，但一旦将来 status 里
     * 做任何规范化（去空格、补协议头），用户就会被"边打字边被改写"。
     *
     * `rememberSaveable`（而不是裸 `remember`）：主题切换会重建 Activity，
     * 用户正敲到一半的地址不该被清掉；key 仍然是 `status.configUrl`，
     * 所以上面那条"跟随恢复值"的规则不变。
     */
    var input by rememberSaveable(status.configUrl) { mutableStateOf(status.configUrl) }

    // ── 播放缓存 ─────────────────────────────────────────────────────────
    // 设置对象**从外面注入**，不在这里 new —— 播放页读的是同一个实例。
    // 两份实例会让"设置页改了配额、播放页还在用旧值"变成必然（见 PlaybackSettings）。
    var cacheEnabled by remember { mutableStateOf(settings.cacheEnabled) }
    var cacheQuota by remember { mutableStateOf(settings.cacheQuotaBytes) }
    var confirmClear by remember { mutableStateOf(false) }

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
            SettingsSection(title = stringResource(R.string.settings_section_source)) {
                Text(
                    text = stringResource(R.string.settings_source_body),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(BeeDimens.gapMedium))

                OutlinedTextField(
                    value = input,
                    onValueChange = { input = it },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text(stringResource(R.string.settings_source_label)) },
                    placeholder = { Text(stringResource(R.string.settings_source_placeholder)) },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                )

                Spacer(Modifier.height(BeeDimens.gapTiny))

                Row(
                    horizontalArrangement = Arrangement.spacedBy(BeeDimens.gapTiny),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Button(
                        // 失败原因由 status.message 给出，这里不处理返回值 ——
                        // 页面上本来就有一处显示它的地方，返回值和它会是同一句话
                        onClick = { state.applyConfig(input) },
                        enabled = input.isNotBlank() && !applying,
                    ) {
                        Text(
                            text = stringResource(R.string.settings_source_load),
                            style = MaterialTheme.typography.labelLargeEmphasized,
                        )
                    }
                    TextButton(
                        onClick = {
                            state.clearSource()
                            // 输入框也清掉：不清的话地址还显示着，用户会以为没生效
                            input = ""
                        },
                        enabled = input.isNotEmpty() || status.phase != SourcePhase.EMPTY,
                    ) {
                        Text(
                            text = stringResource(R.string.settings_source_clear),
                            style = MaterialTheme.typography.labelLarge,
                        )
                    }
                }

                SourceStatusLine(
                    phase = status.phase,
                    message = status.message,
                    applying = applying,
                )

                if (status.sources.isNotEmpty()) {
                    Spacer(Modifier.height(BeeDimens.gapMedium))
                    Text(
                        text = stringResource(R.string.settings_source_active),
                        style = MaterialTheme.typography.titleSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.height(BeeDimens.gapTiny))
                    val activeIndex = status.sources
                        .indexOfFirst { it.id == status.activeSourceId }
                        .coerceAtLeast(0)
                    BeeChipRow(
                        items = status.sources,
                        selectedIndex = activeIndex,
                        onSelect = { sources.selectSource(status.sources[it].id) },
                    ) { source ->
                        Text(source.name, style = MaterialTheme.typography.labelLarge)
                    }
                }
            }

            Spacer(Modifier.height(BeeDimens.gapSmall))

            /*
             * 外观排在「内容源」之后、「播放缓存」之前。
             *
             * 顺序是有讲究的：内容源是必须做的事（填错就用不了），缓存是**会吃掉
             * 用户存储、还带一个破坏性按钮**的区域，把纯偏好夹在这两者之间，
             * 用户找主题开关时不用先滚过一整块"清空缓存"。
             */
            SettingsSection(title = stringResource(R.string.settings_section_appearance)) {
                Text(
                    text = stringResource(R.string.settings_appearance_body),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(BeeDimens.gapMedium))

                BeeChipRow(
                    items = ThemeMode.entries,
                    selectedIndex = ThemeMode.entries.indexOf(themeMode).coerceAtLeast(0),
                    // 交给上层落盘。这一页不等结果、也不做乐观更新 ——
                    // 值的来源是外面那条流，下一次重组就会把新值送回来
                    onSelect = { index -> onThemeModeChange(ThemeMode.entries[index]) },
                ) { item ->
                    Text(
                        text = stringResource(item.labelRes),
                        style = MaterialTheme.typography.labelLarge,
                    )
                }
            }

            Spacer(Modifier.height(BeeDimens.gapSmall))

            SettingsSection(title = stringResource(R.string.settings_section_cache)) {
                Text(
                    text = stringResource(R.string.settings_cache_body),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(BeeDimens.gapMedium))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = stringResource(R.string.settings_cache_switch),
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier.weight(1f),
                    )
                    Switch(
                        checked = cacheEnabled,
                        // 立刻落盘：这一页随时可能因为返回被销毁，攒着不写就等于没改
                        onCheckedChange = { on ->
                            cacheEnabled = on
                            settings.cacheEnabled = on
                        },
                    )
                }

                if (cacheEnabled) {
                    Spacer(Modifier.height(BeeDimens.gapSmall))
                    Text(
                        text = stringResource(R.string.settings_cache_quota),
                        style = MaterialTheme.typography.titleSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.height(BeeDimens.gapTiny))
                    BeeChipRow(
                        items = PlaybackSettings.QUOTA_CHOICES,
                        selectedIndex = PlaybackSettings.QUOTA_CHOICES
                            .indexOf(cacheQuota)
                            .coerceAtLeast(0),
                        onSelect = { index ->
                            cacheQuota = PlaybackSettings.QUOTA_CHOICES[index]
                            settings.cacheQuotaBytes = cacheQuota
                        },
                    ) { bytes ->
                        Text(
                            text = stringResource(
                                R.string.settings_cache_quota_gb,
                                bytes / PlaybackSettings.GB,
                            ),
                            style = MaterialTheme.typography.labelLarge,
                        )
                    }
                }

                Spacer(Modifier.height(BeeDimens.gapSmall))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = if (cacheEnabled) {
                            stringResource(R.string.settings_cache_used, formatBytes(cacheUsed))
                        } else {
                            // 关掉缓存**不删**已经下好的内容：那是个破坏性动作，
                            // 不该藏在开关后面。要说清楚「关了但东西还在」，
                            // 否则用户会以为关掉就自动腾出空间了
                            stringResource(
                                R.string.settings_cache_used_off,
                                formatBytes(cacheUsed),
                            )
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.weight(1f),
                    )
                    TextButton(
                        onClick = { confirmClear = true },
                        enabled = cacheUsed > 0L,
                    ) {
                        Text(
                            text = stringResource(R.string.settings_cache_clear),
                            style = MaterialTheme.typography.labelLarge,
                        )
                    }
                }
            }

            Spacer(Modifier.height(BeeDimens.gapSmall))

            SettingsSection(title = stringResource(R.string.settings_section_about)) {
                Text(
                    text = stringResource(R.string.settings_about_body),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            Spacer(Modifier.height(BeeDimens.gapHuge))
        }
    }

    /*
     * 清缓存要确认：它删掉的是用户（可能按流量计费）已经花过钱下载的东西，
     * 而且一次删掉几个 G。这种动作不该是单击就生效的。
     */
    if (confirmClear) {
        AlertDialog(
            onDismissRequest = { confirmClear = false },
            title = { Text(stringResource(R.string.settings_cache_clear_title)) },
            text = { Text(stringResource(R.string.settings_cache_clear_body)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        confirmClear = false
                        // 读回真占用，而不是乐观地填 0：删不干净（文件被占）时
                        // 用户需要看见，而不是被我们骗过去。这条在持有者里
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

/**
 * 人类可读的容量。只给一位小数 —— 设置页要的是「占了多少」的量级感，
 * 不是精确到字节的数字。
 */
private fun formatBytes(bytes: Long): String = when {
    bytes >= PlaybackSettings.GB ->
        "%.1f GB".format(bytes.toDouble() / PlaybackSettings.GB)

    bytes >= 1024 * 1024 -> "%.0f MB".format(bytes.toDouble() / (1024 * 1024))
    bytes >= 1024 -> "%.0f KB".format(bytes.toDouble() / 1024)
    else -> "$bytes B"
}

/**
 * 装载状态行。
 *
 * 三态各有各的颜色，而不是一律灰字：
 *   - 进行中：onSurfaceVariant + 转圈（它是过程，不是结果）
 *   - 失败：error（**这条必须显眼**，它是用户唯一能得到的失败信号）
 *   - 就绪：onSurfaceVariant（正常状态不该抢注意力）
 *
 * `status.message` 在 READY 时是"共 N 个来源"，在 FAILED 时是失败原因 ——
 * 都是给用户看的句子，直接显示。
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun SourceStatusLine(
    phase: SourcePhase,
    message: String,
    applying: Boolean,
) {
    if (applying || phase == SourcePhase.LOADING) {
        Spacer(Modifier.height(BeeDimens.gapSmall))
        Row(verticalAlignment = Alignment.CenterVertically) {
            LoadingIndicator(
                modifier = Modifier.size(BeeDimens.gapMedium),
                color = MaterialTheme.colorScheme.primary,
            )
            Spacer(Modifier.size(BeeDimens.gapTiny))
            Text(
                text = stringResource(R.string.settings_source_loading),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        return
    }

    if (message.isEmpty()) return

    Spacer(Modifier.height(BeeDimens.gapSmall))
    Text(
        text = message,
        style = MaterialTheme.typography.bodySmall,
        color = if (phase == SourcePhase.FAILED) {
            MaterialTheme.colorScheme.error
        } else {
            MaterialTheme.colorScheme.onSurfaceVariant
        },
    )
}

@Composable
private fun SettingsSection(
    title: String,
    content: @Composable () -> Unit,
) {
    Surface(
        shape = MaterialTheme.shapes.extraLarge,
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(modifier = Modifier.padding(BeeDimens.gapMedium)) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleSmallEmphasized,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Spacer(Modifier.height(BeeDimens.gapTiny))
            content()
        }
    }
}

/**
 * 三态模式的界面文案。
 *
 * 映射刻意放在界面层，而不是给 `ThemeMode` 挂一个 `labelRes` 字段：
 * 那个枚举属于数据层，把中文资源 id 写死进去，等于让"这个值是什么"
 * 和"它怎么显示"绑在一起 —— 而本项目的数据层里一个中文界面串都没有
 * （「推荐」那一位也是同样的处理，见 `VodContentRepository.categories`）。
 */
@get:StringRes
private val ThemeMode.labelRes: Int
    get() = when (this) {
        ThemeMode.SYSTEM -> R.string.settings_theme_system
        ThemeMode.LIGHT -> R.string.settings_theme_light
        ThemeMode.DARK -> R.string.settings_theme_dark
    }

// ------------------------------------------------------------------ 预览

@Preview(
    name = "设置 · 手机",
    group = "页面",
    showBackground = true,
    backgroundColor = 0xFF0B0A08,
    widthDp = 411,
    heightDp = 891,
)
@Composable
private fun SettingsScreenPreview() {
    BeeVideoTheme(darkTheme = true) {
        PreviewViewModelStoreOwner {
            SettingsScreen(
                sources = FakeSourceRepository.threeSourcesReady(),
                settings = FakePlaybackSettings(),
                // 给一个非零占用，才看得见"已用 1.2 GB"那一行的排版
                cache = FakeMediaCache(usage = 1_288_490_188L),
                themeMode = ThemeMode.SYSTEM,
                onThemeModeChange = {},
            )
        }
    }
}

@Preview(
    name = "设置 · 手机 · 浅色",
    group = "页面",
    showBackground = true,
    backgroundColor = 0xFFFFF9EF,
    widthDp = 411,
    heightDp = 891,
)
@Composable
private fun SettingsScreenLightPreview() {
    BeeVideoTheme(darkTheme = false) {
        PreviewViewModelStoreOwner {
            SettingsScreen(
                sources = FakeSourceRepository.threeSourcesReady(),
                settings = FakePlaybackSettings(),
                cache = FakeMediaCache(usage = 1_288_490_188L),
                themeMode = ThemeMode.LIGHT,
                onThemeModeChange = {},
            )
        }
    }
}
