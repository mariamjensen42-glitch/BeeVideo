package com.cycling.beevideo.ui.settings

import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import com.cycling.beevideo.R
import com.cycling.beevideo.domain.model.DecoderInUse
import com.cycling.beevideo.domain.model.DecoderPreference
import com.cycling.beevideo.domain.model.ThemeMode
import com.cycling.beevideo.domain.model.invalidCustomHeaderLineCount
import com.cycling.beevideo.domain.repository.PlaybackSettings
import com.cycling.beevideo.ui.components.BeeChipRow
import com.cycling.beevideo.ui.theme.BeeDimens

/** 【外观】三态模式。 */
@Composable
internal fun AppearanceSection(
    themeMode: ThemeMode,
    onThemeModeChange: (ThemeMode) -> Unit,
) {
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
            // 交给上层落盘：值的来源是外面那条流，下次重组就会把新值送回来
            onSelect = { index -> onThemeModeChange(ThemeMode.entries[index]) },
        ) { item ->
            Text(
                text = stringResource(item.labelRes),
                style = MaterialTheme.typography.labelLarge,
            )
        }
    }
}

/** 【播放】自动连播 / 画中画 / 解码器偏好 / 自定义请求头。全是**立刻落盘**，攒着不写等于没改。 */
@Composable
internal fun PlaybackSection(
    autoPlayNext: Boolean,
    onAutoPlayNextChange: (Boolean) -> Unit,
    pictureInPicture: Boolean,
    onPictureInPictureChange: (Boolean) -> Unit,
    decoderPreference: DecoderPreference,
    onDecoderPreferenceChange: (DecoderPreference) -> Unit,
    decoderInUse: DecoderInUse?,
    headerText: String,
    onHeaderTextChange: (String) -> Unit,
) {
    SettingsSection(title = stringResource(R.string.settings_section_playback)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = stringResource(R.string.settings_auto_next),
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.weight(1f),
            )
            Switch(
                checked = autoPlayNext,
                onCheckedChange = onAutoPlayNextChange,
            )
        }

        Spacer(Modifier.height(BeeDimens.gapMedium))
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = stringResource(R.string.settings_picture_in_picture),
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.weight(1f),
            )
            Switch(
                checked = pictureInPicture,
                onCheckedChange = onPictureInPictureChange,
            )
        }
        Spacer(Modifier.height(BeeDimens.gapTiny))
        // 两档的差别要写清楚：「退出播放页之后声音还在不在」是用户唯一能感知到的结果
        Text(
            text = stringResource(R.string.settings_picture_in_picture_body),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        Spacer(Modifier.height(BeeDimens.gapMedium))
        Text(
            text = stringResource(R.string.settings_decoder),
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(BeeDimens.gapTiny))
        BeeChipRow(
            items = DecoderPreference.entries,
            selectedIndex = DecoderPreference.entries.indexOf(decoderPreference),
            onSelect = { index -> onDecoderPreferenceChange(DecoderPreference.entries[index]) },
        ) { item ->
            Text(
                text = stringResource(item.labelRes),
                style = MaterialTheme.typography.labelLarge,
            )
        }
        Spacer(Modifier.height(BeeDimens.gapTiny))
        Text(
            text = stringResource(R.string.settings_decoder_body),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(BeeDimens.gapTiny))
        // 偏好只是偏好 —— 设备缺软件解码器时内核会静默回落硬解。没有这一行，
        // 「切了软解还是卡」就会被归咎到源站头上
        Text(
            text = if (decoderInUse == null) {
                stringResource(R.string.settings_decoder_not_played)
            } else {
                stringResource(
                    R.string.settings_decoder_in_use,
                    decoderInUse.name,
                    stringResource(if (decoderInUse.software) R.string.decoder_software else R.string.decoder_hardware),
                )
            },
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        Spacer(Modifier.height(BeeDimens.gapMedium))
        Text(
            text = stringResource(R.string.settings_headers),
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(BeeDimens.gapTiny))
        OutlinedTextField(
            value = headerText,
            onValueChange = onHeaderTextChange,
            modifier = Modifier.fillMaxWidth(),
            placeholder = { Text(stringResource(R.string.settings_headers_placeholder)) },
            // 单行关掉：一条头就是一行，折行会让「每行一条」的心智失效
            singleLine = false,
            maxLines = 4,
        )
        val invalid = invalidCustomHeaderLineCount(headerText)
        if (invalid > 0) {
            Spacer(Modifier.height(BeeDimens.gapTiny))
            Text(
                text = stringResource(R.string.settings_headers_invalid, invalid),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
            )
        }
    }
}

/** 【播放缓存】开关 + 配额 + 占用与清空。开关与配额都是**立刻落盘**，攒着不写等于没改。 */
@Composable
internal fun CacheSection(
    enabled: Boolean,
    quota: Long,
    usedBytes: Long,
    onEnabledChange: (Boolean) -> Unit,
    onQuotaChange: (Int) -> Unit,
    onClearClick: () -> Unit,
) {
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
                checked = enabled,
                onCheckedChange = onEnabledChange,
            )
        }

        if (enabled) {
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
                    .indexOf(quota)
                    .coerceAtLeast(0),
                onSelect = onQuotaChange,
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
                text = if (enabled) {
                    stringResource(R.string.settings_cache_used, formatBytes(usedBytes))
                } else {
                    // 关掉缓存**不删**已下好的内容，得说清楚，否则用户以为关掉就腾出空间了
                    stringResource(
                        R.string.settings_cache_used_off,
                        formatBytes(usedBytes),
                    )
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.weight(1f),
            )
            TextButton(
                onClick = onClearClick,
                enabled = usedBytes > 0L,
            ) {
                Text(
                    text = stringResource(R.string.settings_cache_clear),
                    style = MaterialTheme.typography.labelLarge,
                )
            }
        }
    }
}

/**
 * 【无痕模式】。开关一拨就落盘 —— 它改的是**后续所有写入**，攒着不写等于没改。
 *
 * 说明文字不省略：这个模式是"关掉的时候"才清数据，用户必须在打开前就知道它管什么、
 * 不管什么（站点配置与已下载的播放缓存都不受影响），否则关掉时看到占用变了会归咎于此。
 */
@Composable
internal fun IncognitoSection(
    enabled: Boolean,
    onEnabledChange: (Boolean) -> Unit,
) {
    SettingsSection(title = stringResource(R.string.settings_section_incognito)) {
        Text(
            text = stringResource(R.string.settings_incognito_body),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(BeeDimens.gapMedium))

        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = stringResource(R.string.settings_incognito_switch),
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.weight(1f),
            )
            Switch(
                checked = enabled,
                onCheckedChange = onEnabledChange,
            )
        }

        // 只在开着时摆这一句：关着的时候它是纯噪音
        if (enabled) {
            Spacer(Modifier.height(BeeDimens.gapSmall))
            Text(
                text = stringResource(R.string.settings_incognito_on),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/** 设置页每一节的通用容器。 */
@Composable
internal fun SettingsSection(
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

/** 三态模式的界面文案。映射放界面层而不是给 ThemeMode 挂字段 —— 数据层里一个中文串都没有。 */
@get:StringRes
internal val ThemeMode.labelRes: Int
    get() = when (this) {
        ThemeMode.SYSTEM -> R.string.settings_theme_system
        ThemeMode.LIGHT -> R.string.settings_theme_light
        ThemeMode.DARK -> R.string.settings_theme_dark
    }

/** 解码器偏好的界面文案。同样不挂到枚举上 —— 取值域的知识留在取值的地方。 */
@get:StringRes
internal val DecoderPreference.labelRes: Int
    get() = when (this) {
        DecoderPreference.AUTO -> R.string.settings_decoder_auto
        DecoderPreference.PREFER_SOFTWARE -> R.string.settings_decoder_software
    }

/** 人类可读的容量。只给一位小数 —— 设置页要的是量级感，不是精确到字节。 */
private fun formatBytes(bytes: Long): String = when {
    bytes >= PlaybackSettings.GB ->
        "%.1f GB".format(bytes.toDouble() / PlaybackSettings.GB)

    bytes >= 1024 * 1024 -> "%.0f MB".format(bytes.toDouble() / (1024 * 1024))
    bytes >= 1024 -> "%.0f KB".format(bytes.toDouble() / 1024)
    else -> "$bytes B"
}
