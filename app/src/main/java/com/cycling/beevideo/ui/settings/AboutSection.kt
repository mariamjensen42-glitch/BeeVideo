package com.cycling.beevideo.ui.settings

import android.content.pm.PackageManager
import android.os.Build
import androidx.annotation.StringRes
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.cycling.beevideo.R
import com.cycling.beevideo.ui.theme.BeeDimens

/** 项目仓库。链接不进 strings —— 它不随语言变。 */
private const val REPO_URL = "https://github.com/mariamjensen42-glitch/BeeVideo"

/** 更新日志里最新那一版。⚠️ 与 `settings_about_changelog_body` 正文一起改。 */
private const val LATEST_RELEASE = "v1.2.0"

/** 对话框正文的最大高度。超过就滚动 —— 更新日志与许可清单后面只会更长。 */
private val BODY_MAX_HEIGHT = 420.dp

/** 【关于】里的四个入口。声明顺序即展示顺序。 */
internal enum class AboutPage {
    APP,
    CHANGELOG,
    LICENSE,
    DISCLAIMER,
}

/** 入口标题。映射放界面层而不是给 AboutPage 挂字段 —— 与 [ThemeMode.labelRes] 同一个理由。 */
@get:StringRes
internal val AboutPage.titleRes: Int
    get() = when (this) {
        AboutPage.APP -> R.string.settings_about_row_app
        AboutPage.CHANGELOG -> R.string.settings_about_row_changelog
        AboutPage.LICENSE -> R.string.settings_about_row_license
        AboutPage.DISCLAIMER -> R.string.settings_about_row_disclaimer
    }

/**
 * 【关于】。四个入口各自开一个对话框 —— 这四段都是纯阅读内容，
 * 为它们各开一条路由（还要各配一个顶栏）不划算。
 */
@Composable
internal fun AboutSection() {
    // 存下标而不是枚举：`rememberSaveable` 只保证 Bundle 能装的东西活过重建
    var openIndex by rememberSaveable { mutableIntStateOf(-1) }
    val version = rememberVersionName()

    SettingsSection(title = stringResource(R.string.settings_section_about)) {
        AboutPage.entries.forEachIndexed { index, page ->
            if (index > 0) Spacer(Modifier.height(BeeDimens.gapTight))
            AboutRow(
                title = stringResource(page.titleRes),
                summary = when (page) {
                    AboutPage.APP ->
                        stringResource(R.string.settings_about_summary_app, version)

                    AboutPage.CHANGELOG ->
                        stringResource(R.string.settings_about_summary_changelog, LATEST_RELEASE)

                    AboutPage.LICENSE -> stringResource(R.string.settings_about_summary_license)
                    AboutPage.DISCLAIMER ->
                        stringResource(R.string.settings_about_summary_disclaimer)
                },
                onClick = { openIndex = index },
            )
        }
    }

    AboutPage.entries.getOrNull(openIndex)?.let { page ->
        AboutDialog(page = page, version = version, onDismiss = { openIndex = -1 })
    }
}

/** 一行入口：标题 + 一行摘要 + 右侧箭头。整行可点。 */
@Composable
private fun AboutRow(
    title: String,
    summary: String,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            // 48dp 是 M3 的最小可点目标，写在 clickable 之前浅色波纹才铺满整行
            .heightIn(min = 48.dp)
            .clickable(onClick = onClick)
            .padding(vertical = BeeDimens.gapTiny),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Text(
                text = summary,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Icon(
            imageVector = Icons.AutoMirrored.Filled.KeyboardArrowRight,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun AboutDialog(
    page: AboutPage,
    version: String,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(page.titleRes)) },
        text = {
            Column(
                modifier = Modifier
                    .heightIn(max = BODY_MAX_HEIGHT)
                    .verticalScroll(rememberScrollState()),
            ) {
                when (page) {
                    AboutPage.APP -> AppInfo(version)
                    AboutPage.CHANGELOG -> Body(stringResource(R.string.settings_about_changelog_body))
                    AboutPage.LICENSE -> Body(stringResource(R.string.settings_about_license_body))
                    AboutPage.DISCLAIMER -> Body(stringResource(R.string.settings_about_disclaimer_body))
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.dialog_close))
            }
        },
    )
}

/** 这一节原来那句正文挪进来的 —— 它本来就是这个应用的一句话自我介绍。 */
@Composable
private fun AppInfo(version: String) {
    val context = LocalContext.current
    val uriHandler = LocalUriHandler.current

    Body(stringResource(R.string.settings_about_body))
    Spacer(Modifier.height(BeeDimens.gapMedium))

    InfoRow(stringResource(R.string.settings_about_app_version), version)
    InfoRow(stringResource(R.string.settings_about_app_package), context.packageName)
    InfoRow(
        label = stringResource(R.string.settings_about_app_license),
        value = stringResource(R.string.settings_about_app_license_value),
    )

    Spacer(Modifier.height(BeeDimens.gapTiny))
    // openUri 在没有浏览器的设备上会抛，不能让它带崩对话框
    TextButton(onClick = { runCatching { uriHandler.openUri(REPO_URL) } }) {
        Text(stringResource(R.string.settings_about_app_repo_open))
    }
}

@Composable
private fun InfoRow(label: String, value: String) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = BeeDimens.gapTight),
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.width(72.dp),
        )
        // 版本与包名是用户报问题时唯一会抄的两样东西，得能选中复制
        SelectionContainer {
            Text(
                text = value,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface,
            )
        }
    }
}

@Composable
private fun Body(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

/**
 * 版本名。走 `PackageManager` 而不是 `BuildConfig` —— 只为一串数字打开 `buildConfig` 不划算。
 */
@Composable
@Suppress("DEPRECATION") // minSdk 31 < 33，老重载那条分支必须留着
private fun rememberVersionName(): String {
    val context = LocalContext.current
    return remember(context) {
        val pm = context.packageManager
        val info = runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                pm.getPackageInfo(context.packageName, PackageManager.PackageInfoFlags.of(0))
            } else {
                pm.getPackageInfo(context.packageName, 0)
            }
        }.getOrNull()
        info?.versionName?.takeIf { it.isNotBlank() } ?: "—"
    }
}
