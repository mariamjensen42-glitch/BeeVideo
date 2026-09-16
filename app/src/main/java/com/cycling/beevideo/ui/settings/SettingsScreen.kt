package com.cycling.beevideo.ui.settings

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
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.LoadingIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.MediumFlexibleTopAppBar
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.rememberTopAppBarState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.tooling.preview.Preview
import com.cycling.beevideo.R
import com.cycling.beevideo.domain.model.ContentSource
import com.cycling.beevideo.domain.model.SourcePhase
import com.cycling.beevideo.domain.model.SourceStatus
import com.cycling.beevideo.domain.repository.ContentSourceRepository
import com.cycling.beevideo.ui.components.BeeChipRow
import com.cycling.beevideo.ui.components.beeTopAppBarColors
import com.cycling.beevideo.ui.theme.BeeDimens
import com.cycling.beevideo.ui.theme.BeeVideoTheme
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch

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
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(sources: ContentSourceRepository) {
    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior(
        state = rememberTopAppBarState(),
    )
    val status by sources.status.collectAsState()
    val scope = rememberCoroutineScope()

    /*
     * 输入框的内容以**用户敲进去的**为准，只在配置地址本身变了（比如启动时
     * 从本地恢复）时才跟着更新 —— 反过来让输入框无条件跟随 status 的话，
     * 装载失败把 configUrl 改成用户输入的值倒还好，但一旦将来 status 里
     * 做任何规范化（去空格、补协议头），用户就会被"边打字边被改写"。
     */
    var input by remember(status.configUrl) { mutableStateOf(status.configUrl) }
    var applying by remember { mutableStateOf(false) }

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
                        onClick = {
                            scope.launch {
                                applying = true
                                // 失败原因由 status.message 给出，这里不处理返回值 ——
                                // 页面上本来就有一处显示它的地方，返回值和它会是同一句话
                                sources.applyConfig(input)
                                applying = false
                            }
                        },
                        enabled = input.isNotBlank() && !applying,
                    ) {
                        Text(
                            text = stringResource(R.string.settings_source_load),
                            style = MaterialTheme.typography.labelLargeEmphasized,
                        )
                    }
                    TextButton(
                        onClick = {
                            scope.launch {
                                sources.clear()
                                input = ""
                            }
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

// ------------------------------------------------------------------ 预览

/** 预览用的假来源：不联网，直接给一个"已装好三个来源"的状态。 */
private class PreviewSourceRepository : ContentSourceRepository {
    override val status = MutableStateFlow(
        SourceStatus(
            phase = SourcePhase.READY,
            configUrl = "http://127.0.0.1:18080/config.json",
            sources = listOf(
                ContentSource("mock_json", "Mock JSON 源"),
                ContentSource("mock_xml", "Mock XML 源"),
                ContentSource("mock_spider", "Mock Jar 源"),
            ),
            activeSourceId = "mock_json",
            message = "共 3 个来源",
        )
    )

    override suspend fun restore() = Unit
    override suspend fun applyConfig(url: String): String? = null
    override fun selectSource(sourceId: String) = Unit
    override suspend fun clear() = Unit
}

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
        SettingsScreen(sources = PreviewSourceRepository())
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
        SettingsScreen(sources = PreviewSourceRepository())
    }
}
