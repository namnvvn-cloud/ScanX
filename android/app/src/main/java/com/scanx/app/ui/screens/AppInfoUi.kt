package com.scanx.app.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Business
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Email
import androidx.compose.material.icons.filled.Language
import androidx.compose.material.icons.filled.LocationOn
import androidx.compose.material.icons.filled.Phone
import androidx.compose.material.icons.filled.Policy
import androidx.compose.material.icons.filled.SystemUpdate
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogProperties
import com.scanx.app.data.ProductInfo
import com.scanx.app.data.UpdateInfo

/**
 * Hộp thoại cập nhật. [UpdateInfo.forceUpdate] = true → KHÔNG đóng được (bấm ngoài / nút Back vô hiệu),
 * chỉ còn nút "Cập nhật ngay" — người dùng phải cài bản mới mới dùng tiếp.
 */
@Composable
fun UpdateDialog(
    info: UpdateInfo,
    currentVersionName: String,
    onUpdate: () -> Unit,
    onLater: () -> Unit,
) {
    val forced = info.forceUpdate
    AlertDialog(
        onDismissRequest = { if (!forced) onLater() },
        properties = DialogProperties(dismissOnBackPress = !forced, dismissOnClickOutside = !forced),
        icon = { Icon(Icons.Filled.SystemUpdate, contentDescription = null) },
        title = { Text(if (forced) "Cần cập nhật ScanX" else "Đã có phiên bản mới") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Bản mới: ${info.latestVersionName.ifBlank { info.latestVersionCode.toString() }} — bạn đang dùng $currentVersionName.")
                if (forced) {
                    Text("Phiên bản hiện tại không còn được hỗ trợ. Vui lòng cập nhật để tiếp tục sử dụng.")
                }
                if (info.releaseNotes.isNotBlank()) {
                    Text(info.releaseNotes, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Text(
                    "Bấm «Cập nhật ngay» để tải file cài đặt, mở file vừa tải và chọn «Cài đặt» (cài đè, không mất dữ liệu).",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
        confirmButton = { TextButton(onClick = onUpdate) { Text("Cập nhật ngay") } },
        dismissButton = {
            if (!forced) TextButton(onClick = onLater) { Text("Để sau") }
        },
    )
}

/** Màn "Thông tin sản phẩm": mô tả, chức năng, phiên bản, nhà phát hành, liên hệ, chính sách. */
@Composable
fun AboutScreen(
    info: ProductInfo,
    versionName: String,
    versionCode: Int,
    checking: Boolean,
    checkResult: String?,
    onCheckUpdate: () -> Unit,
    onOpenUrl: (String) -> Unit,
    onEmail: (String) -> Unit,
    onCall: (String) -> Unit,
    onBack: () -> Unit,
) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Thông tin sản phẩm") },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.Filled.ArrowBack, contentDescription = "Quay lại") } },
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()).padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Column {
                Text(info.appName, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
                if (info.tagline.isNotBlank()) {
                    Text(info.tagline, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }

            Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant), modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text("Phiên bản $versionName (build $versionCode)", style = MaterialTheme.typography.titleSmall)
                    OutlinedButton(onClick = onCheckUpdate, enabled = !checking) { Text("Kiểm tra cập nhật") }
                    if (checking) LinearProgressIndicator(Modifier.fillMaxWidth())
                    checkResult?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
                }
            }

            if (info.description.isNotBlank()) {
                Text(info.description, style = MaterialTheme.typography.bodyMedium)
            }

            if (info.features.isNotEmpty()) {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Chức năng chính", style = MaterialTheme.typography.titleMedium)
                    info.features.forEach { f ->
                        Row(verticalAlignment = Alignment.Top) {
                            Icon(Icons.Filled.CheckCircle, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(18.dp).padding(top = 2.dp))
                            Text(f, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(start = 10.dp))
                        }
                    }
                }
            }

            val contacts = buildList<Triple<ImageVector, String, (() -> Unit)?>> {
                if (info.publisher.isNotBlank()) add(Triple(Icons.Filled.Business, "Nhà phát hành: ${info.publisher}", null))
                if (info.website.isNotBlank()) add(Triple(Icons.Filled.Language, info.website, { onOpenUrl(info.website) }))
                if (info.email.isNotBlank()) add(Triple(Icons.Filled.Email, info.email, { onEmail(info.email) }))
                if (info.phone.isNotBlank()) add(Triple(Icons.Filled.Phone, info.phone, { onCall(info.phone) }))
                if (info.address.isNotBlank()) add(Triple(Icons.Filled.LocationOn, info.address, null))
                if (info.privacyUrl.isNotBlank()) add(Triple(Icons.Filled.Policy, "Chính sách quyền riêng tư", { onOpenUrl(info.privacyUrl) }))
                if (info.termsUrl.isNotBlank()) add(Triple(Icons.Filled.Description, "Điều khoản sử dụng", { onOpenUrl(info.termsUrl) }))
            }
            if (contacts.isNotEmpty()) {
                HorizontalDivider()
                Text("Nhà phát hành & liên hệ", style = MaterialTheme.typography.titleMedium)
                contacts.forEach { (icon, text, action) -> ContactRow(icon, text, action) }
            }
            Spacer(Modifier.height(8.dp))
        }
    }
}

@Composable
private fun ContactRow(icon: ImageVector, text: String, onClick: (() -> Unit)?) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .let { if (onClick != null) it.clickable(onClick = onClick) else it }
            .padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
        Text(
            text,
            style = MaterialTheme.typography.bodyMedium,
            color = if (onClick != null) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.padding(start = 14.dp),
        )
    }
}
