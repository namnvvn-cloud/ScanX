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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.QrCode2
import androidx.compose.material.icons.filled.Save
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.WorkspacePremium
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.scanx.app.data.ExpenseItem
import com.scanx.app.data.ProductInfo
import java.text.NumberFormat
import java.util.Locale

@Composable
private fun BackTopBar(title: String, onBack: () -> Unit, actions: @Composable () -> Unit = {}) {
    TopAppBar(
        title = { Text(title) },
        navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.Filled.ArrowBack, contentDescription = "Quay lại") } },
        actions = { actions() },
    )
}

// ============================ Quét lấy văn bản ============================

/** Kết quả "Văn bản": chữ OCR sửa được, sao chép / chia sẻ / lưu TXT. */
@Composable
fun TextScanScreen(
    initialText: String,
    onBack: () -> Unit,
    onCopy: (String) -> Unit,
    onShare: (String) -> Unit,
    onSaveTxt: (String) -> Unit,
) {
    var text by remember(initialText) { mutableStateOf(initialText) }
    Scaffold(
        topBar = {
            BackTopBar("Văn bản", onBack) {
                IconButton(onClick = { onCopy(text) }) { Icon(Icons.Filled.ContentCopy, contentDescription = "Sao chép") }
                IconButton(onClick = { onShare(text) }) { Icon(Icons.Filled.Share, contentDescription = "Chia sẻ") }
                IconButton(onClick = { onSaveTxt(text) }) { Icon(Icons.Filled.Save, contentDescription = "Lưu file TXT") }
            }
        },
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding).padding(16.dp)) {
            if (initialText.isBlank()) {
                Text(
                    "Không nhận dạng được chữ nào — thử chụp lại gần hơn, đủ sáng, giữ máy thẳng.",
                    color = MaterialTheme.colorScheme.error,
                )
                Spacer(Modifier.height(12.dp))
            } else {
                Text(
                    "${text.length} ký tự · sửa trực tiếp trước khi sao chép / chia sẻ",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(8.dp))
            }
            OutlinedTextField(
                value = text,
                onValueChange = { text = it },
                modifier = Modifier.fillMaxWidth().weight(1f),
            )
        }
    }
}

// ============================ Mã QR ============================

/** Loại nội dung QR để hiện nút phù hợp. */
private fun qrKind(v: String): String = when {
    v.startsWith("http://", true) || v.startsWith("https://", true) -> "link"
    v.startsWith("WIFI:", true) -> "wifi"
    v.startsWith("tel:", true) -> "phone"
    v.startsWith("mailto:", true) -> "email"
    v.startsWith("BEGIN:VCARD", true) -> "contact"
    else -> "text"
}

private fun wifiField(v: String, key: String): String =
    Regex("""(?:^|;|:)$key:((?:\\;|[^;])*)""").find(v)?.groupValues?.get(1)?.replace("\\;", ";").orEmpty()

@Composable
fun QrResultDialog(
    value: String,
    onOpen: (String) -> Unit,
    onCopy: (String) -> Unit,
    onShare: (String) -> Unit,
    onHistory: () -> Unit,
    onDismiss: () -> Unit,
) {
    val kind = qrKind(value)
    AlertDialog(
        onDismissRequest = onDismiss,
        icon = { Icon(Icons.Filled.QrCode2, contentDescription = null) },
        title = {
            Text(
                when (kind) {
                    "link" -> "Liên kết"
                    "wifi" -> "Mạng Wi-Fi"
                    "phone" -> "Số điện thoại"
                    "email" -> "Email"
                    "contact" -> "Danh bạ"
                    else -> "Nội dung mã"
                },
            )
        },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                if (kind == "wifi") {
                    Text("Tên mạng: ${wifiField(value, "S")}", fontWeight = FontWeight.SemiBold)
                    val pass = wifiField(value, "P")
                    Text(if (pass.isBlank()) "Không có mật khẩu" else "Mật khẩu: $pass")
                    Text("Bảo mật: ${wifiField(value, "T").ifBlank { "—" }}", style = MaterialTheme.typography.bodySmall)
                } else {
                    Text(value)
                }
            }
        },
        confirmButton = {
            when (kind) {
                "link", "phone", "email" -> TextButton(onClick = { onOpen(value) }) { Text("Mở") }
                "wifi" -> TextButton(onClick = { onCopy(wifiField(value, "P")) }) { Text("Chép mật khẩu") }
                else -> TextButton(onClick = { onCopy(value) }) { Text("Sao chép") }
            }
            TextButton(onClick = { onShare(value) }) { Text("Chia sẻ") }
        },
        dismissButton = {
            Row {
                TextButton(onClick = onHistory) { Text("Lịch sử") }
                TextButton(onClick = onDismiss) { Text("Đóng") }
            }
        },
    )
}

@Composable
fun QrHistoryScreen(history: List<String>, onBack: () -> Unit, onScan: () -> Unit, onItem: (String) -> Unit, onClear: () -> Unit) {
    Scaffold(
        topBar = {
            BackTopBar("Lịch sử quét mã", onBack) {
                if (history.isNotEmpty()) IconButton(onClick = onClear) { Icon(Icons.Filled.Delete, contentDescription = "Xoá lịch sử") }
            }
        },
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            Button(onClick = onScan, modifier = Modifier.fillMaxWidth().padding(16.dp)) {
                Icon(Icons.Filled.QrCode2, contentDescription = null)
                Text("  Quét mã mới")
            }
            if (history.isEmpty()) {
                Text("Chưa quét mã nào.", modifier = Modifier.padding(16.dp), color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            LazyColumn {
                items(history) { v ->
                    Row(
                        Modifier.fillMaxWidth().clickable { onItem(v) }.padding(horizontal = 16.dp, vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(Icons.Filled.History, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
                        Text(v, maxLines = 2, modifier = Modifier.padding(start = 12.dp))
                    }
                    HorizontalDivider()
                }
            }
        }
    }
}

// ============================ Báo cáo chi phí ============================

private val vnd = NumberFormat.getNumberInstance(Locale("vi", "VN"))

/**
 * Báo cáo chi phí: mỗi hoá đơn/biên lai 1 dòng (cửa hàng, ngày, số tiền, nhóm, ghi chú) — tự điền từ
 * chữ OCR, người dùng sửa lại rồi xuất CSV (mở bằng Excel / Google Sheets).
 */
@Composable
fun ExpenseReportScreen(
    initialItems: List<ExpenseItem>,
    titles: Map<String, String>,
    onBack: () -> Unit,
    onExport: (List<ExpenseItem>) -> Unit,
) {
    val rows = remember(initialItems) { mutableStateListOf<ExpenseItem>().apply { addAll(initialItems) } }
    val included = remember(initialItems) { mutableStateListOf<Boolean>().apply { repeat(initialItems.size) { add(true) } } }
    val total = rows.indices.filter { included.getOrElse(it) { false } }.sumOf { rows[it].amount ?: 0L }
    Scaffold(topBar = { BackTopBar("Báo cáo chi phí", onBack) }) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            Card(
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
                modifier = Modifier.fillMaxWidth().padding(16.dp),
            ) {
                Column(Modifier.padding(16.dp)) {
                    Text("Tổng ${included.count { it }} khoản", style = MaterialTheme.typography.bodyMedium)
                    Text("${vnd.format(total)} ₫", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
                    Spacer(Modifier.height(8.dp))
                    Button(
                        onClick = { onExport(rows.filterIndexed { i, _ -> included.getOrElse(i) { false } }) },
                        enabled = included.any { it },
                    ) { Text("Xuất CSV (Excel)") }
                }
            }
            if (rows.isEmpty()) {
                Text(
                    "Chưa có tài liệu nào giống hoá đơn / biên lai. Quét hoá đơn (bật OCR trong Cài đặt) rồi mở lại màn này.",
                    modifier = Modifier.padding(16.dp),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            LazyColumn(Modifier.fillMaxSize()) {
                items(rows.size) { i ->
                    val it0 = rows[i]
                    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Checkbox(checked = included.getOrElse(i) { false }, onCheckedChange = { c -> included[i] = c })
                            Text(titles[it0.documentId] ?: "", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        OutlinedTextField(
                            value = it0.merchant, onValueChange = { v -> rows[i] = it0.copy(merchant = v) },
                            label = { Text("Nơi mua / nhà cung cấp") }, singleLine = true, modifier = Modifier.fillMaxWidth(),
                        )
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            OutlinedTextField(
                                value = it0.date, onValueChange = { v -> rows[i] = it0.copy(date = v) },
                                label = { Text("Ngày") }, singleLine = true, modifier = Modifier.weight(1f),
                            )
                            OutlinedTextField(
                                value = it0.amount?.toString().orEmpty(),
                                onValueChange = { v -> rows[i] = it0.copy(amount = v.filter { c -> c.isDigit() }.take(13).toLongOrNull()) },
                                label = { Text("Số tiền (₫)") }, singleLine = true, modifier = Modifier.weight(1f),
                                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                            )
                        }
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            OutlinedTextField(
                                value = it0.category, onValueChange = { v -> rows[i] = it0.copy(category = v) },
                                label = { Text("Nhóm (ăn uống, đi lại…)") }, singleLine = true, modifier = Modifier.weight(1f),
                            )
                            OutlinedTextField(
                                value = it0.note, onValueChange = { v -> rows[i] = it0.copy(note = v) },
                                label = { Text("Ghi chú") }, singleLine = true, modifier = Modifier.weight(1f),
                            )
                        }
                        HorizontalDivider(Modifier.padding(top = 8.dp))
                    }
                }
            }
        }
    }
}

// ============================ Hỗ trợ ============================

@Composable
fun SupportScreen(
    defaultEmail: String?,
    isBusiness: Boolean,
    info: ProductInfo,
    sending: Boolean,
    result: String?,
    onSend: (subject: String, message: String, email: String) -> Unit,
    onEmail: (String) -> Unit,
    onCall: (String) -> Unit,
    onBack: () -> Unit,
) {
    var subject by remember { mutableStateOf("") }
    var message by remember { mutableStateOf("") }
    var email by remember(defaultEmail) { mutableStateOf(defaultEmail.orEmpty()) }
    Scaffold(topBar = { BackTopBar(if (isBusiness) "Hỗ trợ ưu tiên" else "Hỗ trợ", onBack) }) { padding ->
        Column(
            Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)) {
                Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Filled.WorkspacePremium, contentDescription = null, tint = if (isBusiness) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant)
                    Text(
                        if (isBusiness) "Tài khoản Business: yêu cầu của bạn được xử lý ưu tiên."
                        else "Gói Business được hỗ trợ ưu tiên. Bạn vẫn gửi yêu cầu bình thường bên dưới.",
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.padding(start = 12.dp),
                    )
                }
            }
            OutlinedTextField(value = subject, onValueChange = { subject = it.take(150) }, label = { Text("Chủ đề") }, singleLine = true, modifier = Modifier.fillMaxWidth())
            OutlinedTextField(
                value = message, onValueChange = { message = it.take(5000) }, label = { Text("Mô tả vấn đề / góp ý") },
                minLines = 5, modifier = Modifier.fillMaxWidth(),
            )
            OutlinedTextField(
                value = email, onValueChange = { email = it.trim() }, label = { Text("Email để nhận phản hồi") }, singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email), modifier = Modifier.fillMaxWidth(),
            )
            Text(
                "Kèm theo tự động: phiên bản app, dòng máy — để hỗ trợ nhanh hơn.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Button(
                onClick = { onSend(subject, message, email) },
                enabled = !sending && subject.trim().length >= 3 && message.trim().length >= 5,
                modifier = Modifier.fillMaxWidth(),
            ) { Text("Gửi yêu cầu") }
            if (sending) LinearProgressIndicator(Modifier.fillMaxWidth())
            result?.let { Text(it, color = if (it.startsWith("✓")) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error) }
            if (info.email.isNotBlank() || info.phone.isNotBlank()) {
                HorizontalDivider()
                Text("Hoặc liên hệ trực tiếp", style = MaterialTheme.typography.titleSmall)
                if (info.email.isNotBlank()) OutlinedButton(onClick = { onEmail(info.email) }, modifier = Modifier.fillMaxWidth()) { Text("Email: ${info.email}") }
                if (info.phone.isNotBlank()) OutlinedButton(onClick = { onCall(info.phone) }, modifier = Modifier.fillMaxWidth()) { Text("Gọi: ${info.phone}") }
            }
        }
    }
}
