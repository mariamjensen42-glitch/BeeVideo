package com.cycling.beevideo.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.LoadingIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import com.cycling.beevideo.R
import com.cycling.beevideo.domain.model.SourcePhase
import com.cycling.beevideo.domain.model.SourceStatus
import com.cycling.beevideo.ui.components.BeeChipGrid
import com.cycling.beevideo.ui.theme.BeeDimens

/**
 * 「内容源」节 —— 本页唯一的输入。预置 + 自持两条路：预置体检过的地址点一下就切换，自持地址仍保留。
 * 成功前**不要**把旧输入清掉：改一个字符重试是常态。
 */
@Composable
internal fun SourceSection(
    status: SourceStatus,
    input: String,
    onInputChange: (String) -> Unit,
    applying: Boolean,
    selectedConfig: Int,
    onSelectConfig: (Int) -> Unit,
    onApply: () -> Unit,
    onClear: () -> Unit,
    onOpenPicker: () -> Unit,
) {
    SettingsSection(title = stringResource(R.string.settings_section_source)) {
        Text(
            text = stringResource(R.string.settings_source_body),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(BeeDimens.gapMedium))

        // 预置放输入框**上面**：让"不用手输"成为第一眼看到的东西。
        // 用 BeeChipGrid 而不是 ChipRow —— 后者是 LazyRow，滑过去的就看不见了，这里要一眼看全
        Text(
            text = stringResource(R.string.settings_config_preset),
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(BeeDimens.gapTiny))
        BeeChipGrid(
            items = RECOMMENDED_CONFIGS,
            selectedIndex = selectedConfig,
            onSelect = onSelectConfig,
        ) { config ->
            Text(
                text = stringResource(config.nameRes),
                style = MaterialTheme.typography.labelLarge,
            )
        }
        if (selectedConfig >= 0) {
            Spacer(Modifier.height(BeeDimens.gapTiny))
            Text(
                text = stringResource(RECOMMENDED_CONFIGS[selectedConfig].noteRes),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        Spacer(Modifier.height(BeeDimens.gapMedium))

        OutlinedTextField(
            value = input,
            onValueChange = onInputChange,
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
                // 失败原因由 status.message 给出，页面上本来就有一处显示它
                onClick = onApply,
                enabled = input.isNotBlank() && !applying,
            ) {
                Text(
                    text = stringResource(R.string.settings_source_load),
                    style = MaterialTheme.typography.labelLargeEmphasized,
                )
            }
            TextButton(
                onClick = onClear,
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
            // 只留一行，清单进 SourcePickerSheet —— 实测一份配置 86 个站点，就地铺开是 43 行
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = status.sources
                        .firstOrNull { it.id == status.activeSourceId }
                        ?.name
                        .orEmpty(),
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                TextButton(onClick = onOpenPicker) {
                    Text(
                        text = stringResource(R.string.settings_source_change),
                        style = MaterialTheme.typography.labelLarge,
                    )
                }
            }
        }
    }
}

/** 装载状态行。失败态给 error 色 —— 它是用户唯一能得到的失败信号。 */
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
