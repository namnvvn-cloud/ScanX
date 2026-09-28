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
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Brush
import androidx.compose.material.icons.filled.Cloud
import androidx.compose.material.icons.filled.CloudUpload
import androidx.compose.material.icons.filled.DocumentScanner
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Email
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.TextFields
import androidx.compose.material.icons.filled.Translate
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material.icons.filled.AccountCircle
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
 * Màn Cài đặt tham khảo bố cục Scanner Pro: Dịch vụ lưu trữ (thư mục đám mây, tự động tải lên) + Cài
 * đặt ứng dụng (mẫu email, quy trình, chữ ký, biểu tượng) + Nâng cấp Business. Quyền từng tính năng
 * theo chính sách admin (Web Admin → Tính năng), kiểm tra lúc dùng.
 */
@Composable
fun SettingsScreen(
    autoOcrEnabled: Boolean,
    onAutoOcrChange: (Boolean) -> Unit,
    onBack: () -> Unit,
    onScanningClick: () -> Unit,
    onAdvancedClick: () -> Unit,
    onAccountClick: () -> Unit,
    cloudConfigured: Boolean,
    onCloudAiClick: () -> Unit,
    geminiConfigured: Boolean,
    onGeminiClick: () -> Unit,
    onRecommendApp: () -> Unit,
    /** Bản 1.0: Google Dịch (Cloud Translation). */
    googleTranslateConfigured: Boolean = false,
    onGoogleTranslateClick: () -> Unit = {},
    onComingSoon: () -> Unit,
    /** Màn "Thông tin sản phẩm" (mô tả, chức năng, nhà phát hành, liên hệ, kiểm tra cập nhật). */
    onAboutClick: () -> Unit = {},
    /** Dịch vụ lưu trữ: thư mục đám mây (Drive/OneDrive/Dropbox…) + Tự động tải lên. */
    cloudFolderCount: Int = 0,
    autoUploadOn: Boolean = false,
    onCloudFoldersClick: () -> Unit = {},
    onEmailTemplateClick: () -> Unit = {},
    workflowCount: Int = 0,
    onWorkflowsClick: () -> Unit = {},
    signatureCount: Int = 0,
    onSignaturesClick: () -> Unit = {},
    onAppIconClick: () -> Unit = {},
    isBusiness: Boolean = false,
    onUpgradeClick: () -> Unit = {},
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
            item { SectionHeader("Tài khoản") }
            item {
                SettingsRow(
                    icon = Icons.Filled.AccountCircle,
                    title = "Tài khoản & Sao lưu đám mây",
                    subtitle = "Đăng nhập (tuỳ chọn) để sao lưu tài liệu lên cloud",
                    locked = false,
                    onClick = onAccountClick,
                )
            }
            item { HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp)) }
            item { SectionHeader(stringResource(R.string.settings_section_cloud)) }
            item {
                SettingsRow(
                    icon = Icons.Filled.Cloud,
                    title = stringResource(R.string.settings_add_service),
                    subtitle = if (cloudFolderCount > 0) "Đã kết nối $cloudFolderCount thư mục" else stringResource(R.string.settings_add_service_desc),
                    locked = false,
                    onClick = onCloudFoldersClick,
                )
            }
            item {
                SettingsRow(
                    icon = Icons.Filled.CloudUpload,
                    title = stringResource(R.string.settings_auto_upload),
                    subtitle = if (autoUploadOn) "Đang bật" else stringResource(R.string.settings_auto_upload_desc),
                    locked = false,
                    onClick = onCloudFoldersClick,
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
                    icon = Icons.Filled.AutoAwesome,
                    title = stringResource(R.string.settings_cloud_ai),
                    subtitle = stringResource(if (cloudConfigured) R.string.settings_cloud_ai_on else R.string.settings_cloud_ai_off),
                    locked = false,
                    onClick = onCloudAiClick,
                )
            }
            item {
                SettingsRow(
                    icon = Icons.Filled.Translate,
                    title = stringResource(R.string.settings_gemini),
                    subtitle = stringResource(if (geminiConfigured) R.string.settings_gemini_on else R.string.settings_gemini_off),
                    locked = false,
                    onClick = onGeminiClick,
                )
            }
            item {
                SettingsRow(
                    icon = Icons.Filled.Translate,
                    title = stringResource(R.string.settings_google_translate),
                    subtitle = stringResource(if (googleTranslateConfigured) R.string.settings_google_translate_on else R.string.settings_google_translate_off),
                    locked = false,
                    onClick = onGoogleTranslateClick,
                )
            }
            item {
                SettingsRow(
                    icon = Icons.Filled.Email,
                    title = stringResource(R.string.settings_email_template),
                    subtitle = "Tiêu đề, nội dung, người nhận khi gửi tài liệu",
                    locked = false,
                    onClick = onEmailTemplateClick,
                )
            }
            item {
                SettingsRow(
                    icon = Icons.Filled.AccountTree,
                    title = stringResource(R.string.settings_workflows),
                    subtitle = if (workflowCount > 0) "$workflowCount quy trình" else "Đặt tên, chuyển thư mục, tải lên, gửi email bằng 1 lần bấm",
                    locked = false,
                    onClick = onWorkflowsClick,
                )
            }
            item {
                SettingsRow(
                    icon = Icons.Filled.Edit,
                    title = stringResource(R.string.settings_signatures),
                    subtitle = if (signatureCount > 0) "$signatureCount chữ ký đã lưu" else "Vẽ chữ ký để chèn vào tài liệu",
                    locked = false,
                    onClick = onSignaturesClick,
                )
            }
            item {
                SettingsRow(
                    icon = Icons.Filled.Brush,
                    title = stringResource(R.string.settings_app_icon),
                    subtitle = "Đổi màu biểu tượng trên màn hình chính",
                    locked = false,
                    onClick = onAppIconClick,
                )
            }

            item { HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp)) }
            item {
                SettingsRow(
                    icon = Icons.Filled.Star,
                    title = if (isBusiness) "ScanX Business đang hoạt động" else stringResource(R.string.settings_upgrade_plus),
                    subtitle = if (isBusiness) "Xem hạn gói, tính năng đã mở khoá" else stringResource(R.string.settings_upgrade_plus_desc),
                    locked = false,
                    onClick = onUpgradeClick,
                )
            }
            item {
                SettingsRow(
                    icon = Icons.Filled.Info,
                    title = "Thông tin sản phẩm",
                    subtitle = "Chức năng, phiên bản, nhà phát hành, liên hệ",
                    locked = false,
                    onClick = onAboutClick,
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
