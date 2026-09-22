package com.scanx.app.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Brush
import androidx.compose.material.icons.filled.Cloud
import androidx.compose.material.icons.filled.CloudUpload
import androidx.compose.material.icons.filled.DocumentScanner
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Email
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.TextFields
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material.icons.filled.AccountTree
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.scanx.app.BuildConfig
import com.scanx.app.R

/**
 * Màn Cài đặt tham khảo bố cục Scanner Pro: mục Dịch vụ đám mây + Cài đặt ứng dụng. Phase 1 chưa
 * có backend nên các mục cần máy chủ/thanh toán hiển thị đủ giao diện nhưng khoá "Sắp ra mắt"
 * (quyết định đã chốt với người dùng) — chỉ Nhận dạng văn bản, Quét tài liệu, Cài đặt nâng cao,
 * Giới thiệu ứng dụng là hoạt động thật ở bản này.
 */
@Composable
fun SettingsScreen(
    autoOcrEnabled: Boolean,
    onAutoOcrChange: (Boolean) -> Unit,
    onBack: () -> Unit,
    onScanningClick: () -> Unit,
    onAdvancedClick: () -> Unit,
    onRecommendApp: () -> Unit,
    onComingSoon: () -> Unit,
) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.settings_title)) },
                navigationIcon = {
                    IconButton(onClick = onBack) { Icon(Icons.Filled.ArrowBack, contentDescription = null) }
                }
            )
        }
    ) { padding ->
        LazyColumn(contentPadding = PaddingValues(bottom = 24.dp), modifier = Modifier.padding(padding)) {
            item { SectionHeader(stringResource(R.string.settings_section_cloud)) }
            item {
                SettingsRow(
                    icon = Icons.Filled.Cloud,
                    title = stringResource(R.string.settings_add_service),
                    subtitle = stringResource(R.string.settings_add_service_desc),
                    locked = true,
                    onClick = onComingSoon,
                )
            }
            item {
                SettingsSwitchRow(
                    icon = Icons.Filled.CloudUpload,
                    title = stringResource(R.string.settings_auto_upload),
                    subtitle = stringResource(R.string.settings_auto_upload_desc),
                    checked = false,
                    locked = true,
                    onCheckedChange = { onComingSoon() },
                )
            }

            item { HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp)) }
            item { SectionHeader(stringResource(R.string.settings_section_app)) }
            item {
                SettingsSwitchRow(
                    icon = Icons.Filled.TextFields,
                    title = stringResource(R.string.settings_text_recognition),
                    subtitle = stringResource(R.string.settings_text_recognition_desc),
                    checked = autoOcrEnabled,
                    locked = false,
                    onCheckedChange = onAutoOcrChange,
                )
            }
            item {
                SettingsRow(
                    icon = Icons.Filled.DocumentScanner,
                    title = stringResource(R.string.settings_scanning),
                    locked = false,
                    onClick = onScanningClick,
                )
            }
            item {
                SettingsRow(
                    icon = Icons.Filled.Tune,
                    title = stringResource(R.string.settings_advanced),
                    locked = false,
                    onClick = onAdvancedClick,
                )
            }
            item {
                SettingsRow(
                    icon = Icons.Filled.Email,
                    title = stringResource(R.string.settings_email_template),
                    locked = true,
                    onClick = onComingSoon,
                )
            }
            item {
                SettingsRow(
                    icon = Icons.Filled.AccountTree,
                    title = stringResource(R.string.settings_workflows),
                    locked = true,
                    onClick = onComingSoon,
                )
            }
            item {
                SettingsRow(
                    icon = Icons.Filled.Edit,
                    title = stringResource(R.string.settings_signatures),
                    locked = true,
                    onClick = onComingSoon,
                )
            }
            item {
                SettingsRow(
                    icon = Icons.Filled.Brush,
                    title = stringResource(R.string.settings_app_icon),
                    locked = true,
                    onClick = onComingSoon,
                )
            }

            item { HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp)) }
            item {
                SettingsRow(
                    icon = Icons.Filled.Star,
                    title = stringResource(R.string.settings_upgrade_plus),
                    subtitle = stringResource(R.string.settings_upgrade_plus_desc),
                    locked = true,
                    onClick = onComingSoon,
                )
            }
            item {
                SettingsRow(
                    icon = Icons.Filled.Share,
                    title = stringResource(R.string.settings_recommend_app),
                    locked = false,
                    onClick = onRecommendApp,
                )
            }
            item {
                Text(
                    text = stringResource(R.string.settings_version, BuildConfig.VERSION_NAME),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 20.dp, vertical = 16.dp),
                )
            }
        }
    }
}

@Composable
private fun SectionHeader(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp),
    )
}

@Composable
private fun SettingsRow(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    title: String,
    subtitle: String? = null,
    locked: Boolean,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 20.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
        Column(modifier = Modifier.padding(start = 16.dp).weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            subtitle?.let {
                Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        if (locked) {
            Icon(
                Icons.Filled.Lock,
                contentDescription = stringResource(R.string.coming_soon_badge),
                modifier = Modifier.size(18.dp),
                tint = MaterialTheme.colorScheme.outline,
            )
        }
    }
}

@Composable
private fun SettingsSwitchRow(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    title: String,
    subtitle: String? = null,
    checked: Boolean,
    locked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 20.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.weight(1f)) {
            Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
            Column(modifier = Modifier.padding(start = 16.dp)) {
                Text(title, style = MaterialTheme.typography.bodyLarge)
                subtitle?.let {
                    Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
        if (locked) {
            Icon(
                Icons.Filled.Lock,
                contentDescription = stringResource(R.string.coming_soon_badge),
                modifier = Modifier.size(18.dp).padding(end = 8.dp),
                tint = MaterialTheme.colorScheme.outline,
            )
        }
        Switch(checked = checked, onCheckedChange = onCheckedChange)
    }
}
