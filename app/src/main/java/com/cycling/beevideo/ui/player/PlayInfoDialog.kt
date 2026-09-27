package com.cycling.beevideo.ui.player

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.cycling.beevideo.R
import com.cycling.beevideo.ui.theme.BeeDimens

/** 播放信息对话框。M3 给 Compact 宽度指定的就是全屏形态 —— 窄屏放基础对话框会把长地址挤成好几行。 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun PlayInfoDialog(
    title: String,
    lineName: String,
    episodeName: String,
    url: String,
    state: String,
    onDismiss: () -> Unit,
) {
    val body: @Composable () -> Unit = {
        Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
            InfoRow(stringResource(R.string.player_info_line), lineName)
            Spacer(Modifier.height(BeeDimens.gapTiny))
            InfoRow(stringResource(R.string.player_info_episode), episodeName)
            Spacer(Modifier.height(BeeDimens.gapTiny))
            InfoRow(stringResource(R.string.player_info_state), state)
            Spacer(Modifier.height(BeeDimens.gapTiny))
            InfoRow(stringResource(R.string.player_info_url), url)
        }
    }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Surface(
            modifier = Modifier.fillMaxSize(),
            color = MaterialTheme.colorScheme.surfaceContainerHigh,
        ) {
            Column {
                // M3 规定头部高 56dp、左右留白 24dp；48dp 的 IconButton 内图标 24dp 居中
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(56.dp)
                        .padding(start = BeeDimens.gapSmall, end = BeeDimens.gapLarge),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    IconButton(onClick = onDismiss) {
                        Icon(
                            imageVector = Icons.Filled.Close,
                            contentDescription = stringResource(R.string.cd_close),
                        )
                    }
                    Spacer(Modifier.width(BeeDimens.gapTiny))
                    Text(
                        text = title,
                        style = MaterialTheme.typography.titleLarge,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                }
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                Column(
                    modifier = Modifier
                        .weight(1f)
                        .padding(BeeDimens.gapLarge),
                ) {
                    body()
                }
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                // 底部操作栏高 56dp（8 + 40 + 8），留白与头部对齐
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(
                            horizontal = BeeDimens.gapLarge,
                            vertical = BeeDimens.gapTiny,
                        ),
                    horizontalArrangement = Arrangement.End,
                ) {
                    TextButton(onClick = onDismiss) {
                        Text(
                            text = stringResource(R.string.dialog_close),
                            style = MaterialTheme.typography.labelLarge,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun InfoRow(label: String, value: String) {
    Row {
        Text(
            text = label,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            style = MaterialTheme.typography.labelMedium,
            modifier = Modifier.width(56.dp),
        )
        Text(
            text = value,
            color = MaterialTheme.colorScheme.onSurface,
            style = MaterialTheme.typography.bodyMedium,
        )
    }
}
