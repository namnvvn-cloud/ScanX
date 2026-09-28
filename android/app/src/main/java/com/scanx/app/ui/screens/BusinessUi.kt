package com.scanx.app.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.WorkspacePremium
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.scanx.app.data.BusinessFeature
import com.scanx.app.data.Entitlements
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private fun formatDate(ms: Long): String = SimpleDateFormat("dd/MM/yyyy", Locale("vi", "VN")).format(Date(ms))

/** Danh sách tính năng Business, dấu ✓ (đã mở) hoặc ổ khoá — luôn kèm chữ, không chỉ dựa vào màu. */
@Composable
fun BusinessFeatureList(entitlements: Entitlements, highlight: BusinessFeature? = null, trialsRemaining: (BusinessFeature) -> Int) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        BusinessFeature.entries.forEach { f ->
            val unlocked = entitlements.hasFeature(f)
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    if (unlocked) Icons.Filled.CheckCircle else Icons.Filled.Lock,
                    contentDescription = if (unlocked) "Đã mở" else "Đang khoá",
                    tint = if (unlocked) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(18.dp),
                )
                Column(Modifier.padding(start = 10.dp)) {
                    Text(
                        entitlements.label(f),
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = if (f == highlight) FontWeight.SemiBold else FontWeight.Normal,
                    )
                    if (!unlocked && entitlements.trialLimit(f) > 0) {
                        Text(
                            "Dùng thử: còn ${trialsRemaining(f)}/${entitlements.trialLimit(f)} lượt",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }
    }
}

/**
 * Hộp thoại khi bấm tính năng Business mà tài khoản chưa có gói (và đã hết lượt dùng thử).
 * Nút "Xem gói Business" (mở web) CHỈ hiện khi [onOpenPricing] != null — bản Google Play/App Store tắt.
 */
@Composable
fun BusinessPaywallDialog(
    feature: BusinessFeature,
    entitlements: Entitlements,
    loggedIn: Boolean,
    refreshing: Boolean,
    refreshError: String?,
    trialsRemaining: (BusinessFeature) -> Int,
    onLogin: () -> Unit,
    onRefresh: () -> Unit,
    onOpenPricing: (() -> Unit)?,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        icon = { Icon(Icons.Filled.WorkspacePremium, contentDescription = null) },
        title = { Text("Tính năng ScanX Business") },
        text = {
            Column {
                val trialNote = if (entitlements.trialLimit(feature) > 0) " Bạn đã dùng hết lượt dùng thử miễn phí." else ""
                Text("«${entitlements.label(feature)}» dành cho tài khoản Business.$trialNote")
                Spacer(Modifier.height(14.dp))
                BusinessFeatureList(entitlements, highlight = feature, trialsRemaining = trialsRemaining)
                Spacer(Modifier.height(14.dp))
                Text(
                    if (loggedIn) {
                        "Đã mua gói cho tài khoản ${entitlements.email ?: ""}? Bấm «Làm mới» để cập nhật."
                    } else {
                        "Đã có gói Business? Đăng nhập đúng tài khoản để mở khoá."
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (refreshing) {
                    Spacer(Modifier.height(10.dp))
                    LinearProgressIndicator(Modifier.fillMaxWidth())
                }
                refreshError?.let {
                    Spacer(Modifier.height(8.dp))
                    Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                }
            }
        },
        confirmButton = {
            if (loggedIn) {
                TextButton(onClick = onRefresh, enabled = !refreshing) { Text("Làm mới") }
            } else {
                TextButton(onClick = onLogin) { Text("Đăng nhập") }
            }
            if (onOpenPricing != null) {
                TextButton(onClick = onOpenPricing) { Text("Xem gói Business") }
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Để sau") } },
    )
}

/** Thẻ "Gói hiện tại" ở màn Tài khoản. */
@Composable
fun PlanCard(
    entitlements: Entitlements,
    refreshing: Boolean,
    refreshError: String?,
    trialsRemaining: (BusinessFeature) -> Int,
    onRefresh: () -> Unit,
    onOpenPricing: (() -> Unit)?,
) {
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    if (entitlements.businessActive) Icons.Filled.WorkspacePremium else Icons.Filled.Lock,
                    contentDescription = null,
                    tint = if (entitlements.businessActive) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Column(Modifier.padding(start = 10.dp).weight(1f)) {
                    Text(
                        if (entitlements.businessActive) "Gói Business ✓ đang hoạt động" else "Gói Miễn phí",
                        style = MaterialTheme.typography.titleMedium,
                    )
                    val sub = when {
                        entitlements.businessActive && entitlements.businessExpiresAt != null ->
                            "Hết hạn ${formatDate(entitlements.businessExpiresAt)}"
                        entitlements.businessActive -> "Không thời hạn"
                        entitlements.businessExpiresAt != null -> "Business đã hết hạn ${formatDate(entitlements.businessExpiresAt)}"
                        else -> "Quét, OCR, PDF, chụp để dịch — miễn phí"
                    }
                    Text(sub, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            Spacer(Modifier.height(14.dp))
            BusinessFeatureList(entitlements, trialsRemaining = trialsRemaining)
            if (refreshing) {
                Spacer(Modifier.height(10.dp))
                LinearProgressIndicator(Modifier.fillMaxWidth())
            }
            refreshError?.let {
                Spacer(Modifier.height(8.dp))
                Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
            }
            Spacer(Modifier.height(12.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = onRefresh, enabled = !refreshing) { Text("Làm mới") }
                if (onOpenPricing != null && !entitlements.businessActive) {
                    OutlinedButton(onClick = onOpenPricing) { Text("Xem gói Business") }
                }
            }
        }
    }
}
