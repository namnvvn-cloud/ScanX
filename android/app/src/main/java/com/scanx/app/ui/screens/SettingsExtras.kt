package com.scanx.app.ui.screens

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Paint
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Cloud
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
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
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import com.scanx.app.data.AppIconManager
import com.scanx.app.data.CloudFolder
import com.scanx.app.data.EmailTemplate
import com.scanx.app.data.FolderMeta
import com.scanx.app.data.ToolsStore
import com.scanx.app.data.Workflow
import java.io.File

@Composable
private fun ExtraTopBar(title: String, onBack: () -> Unit) {
    TopAppBar(
        title = { Text(title) },
        navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.Filled.ArrowBack, contentDescription = "Quay lại") } },
    )
}

@Composable
private fun Hint(text: String) {
    Text(text, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
}

// ============================ Thư mục đám mây + Tự động tải lên ============================

@Composable
fun CloudFoldersScreen(
    folders: List<CloudFolder>,
    defaultUri: String?,
    autoUpload: Boolean,
    autoToFolder: Boolean,
    autoToScanX: Boolean,
    autoUploadAllowed: Boolean,
    scanXReady: Boolean,
    onAdd: () -> Unit,
    onRemove: (String) -> Unit,
    onSetDefault: (String) -> Unit,
    onAutoUpload: (Boolean) -> Unit,
    onAutoToFolder: (Boolean) -> Unit,
    onAutoToScanX: (Boolean) -> Unit,
    onBack: () -> Unit,
) {
    var confirmRemove by remember { mutableStateOf<CloudFolder?>(null) }
    Scaffold(topBar = { ExtraTopBar("Dịch vụ lưu trữ", onBack) }) { padding ->
        Column(
            Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text("Thư mục đám mây", style = MaterialTheme.typography.titleMedium)
            Hint(
                "Chọn 1 thư mục trong Google Drive, OneDrive, Dropbox… (cần cài app của dịch vụ đó và đăng nhập) hoặc bộ nhớ máy. " +
                    "ScanX lưu PDF vào đó khi bạn bấm «Lưu vào thư mục đám mây», khi Tự động tải lên, hoặc trong Quy trình.",
            )
            folders.forEach { f ->
                Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)) {
                    Row(Modifier.fillMaxWidth().padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                        RadioButton(selected = f.uri == defaultUri, onClick = { onSetDefault(f.uri) })
                        Column(Modifier.weight(1f)) {
                            Text(f.name, fontWeight = FontWeight.SemiBold)
                            Hint(ToolsStore.providerName(f.uri) + if (f.uri == defaultUri) " · mặc định" else "")
                        }
                        IconButton(onClick = { confirmRemove = f }) { Icon(Icons.Filled.Delete, contentDescription = "Gỡ thư mục") }
                    }
                }
            }
            OutlinedButton(onClick = onAdd, modifier = Modifier.fillMaxWidth()) {
                Icon(Icons.Filled.Add, contentDescription = null)
                Text("  Kết nối thư mục")
            }
            Hint("Không thấy Google Drive trong danh sách? Một số bản Google Drive không cho chọn thư mục — dùng Chia sẻ → Drive, hoặc OneDrive / Dropbox.")

            HorizontalDivider(Modifier.padding(vertical = 8.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Tự động tải lên", style = MaterialTheme.typography.titleMedium)
                    Hint("Mỗi tài liệu mới quét xong được tải lên đích bên dưới.")
                }
                if (!autoUploadAllowed) Icon(Icons.Filled.Lock, contentDescription = "Tính năng Business", modifier = Modifier.padding(end = 8.dp))
                Switch(checked = autoUpload && autoUploadAllowed, onCheckedChange = onAutoUpload)
            }
            if (!autoUploadAllowed) Hint("Tự động tải lên là tính năng Business.")
            Row(verticalAlignment = Alignment.CenterVertically) {
                Checkbox(checked = autoToFolder, onCheckedChange = onAutoToFolder, enabled = folders.isNotEmpty())
                Text("Thư mục đám mây mặc định" + if (folders.isEmpty()) " (chưa kết nối)" else "")
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Checkbox(checked = autoToScanX, onCheckedChange = onAutoToScanX)
                Text("ScanX Cloud (sao lưu tài khoản)" + if (!scanXReady) " — cần đăng nhập Business" else "")
            }
        }
    }
    confirmRemove?.let { f ->
        AlertDialog(
            onDismissRequest = { confirmRemove = null },
            title = { Text("Gỡ thư mục?") },
            text = { Text("ScanX sẽ không lưu vào «${f.name}» nữa. File đã lưu trong thư mục vẫn giữ nguyên.") },
            confirmButton = { TextButton(onClick = { onRemove(f.uri); confirmRemove = null }) { Text("Gỡ") } },
            dismissButton = { TextButton(onClick = { confirmRemove = null }) { Text("Huỷ") } },
        )
    }
}

// ============================ Mẫu email ============================

@Composable
fun EmailTemplateScreen(template: EmailTemplate, onSave: (EmailTemplate) -> Unit, onBack: () -> Unit) {
    var to by remember { mutableStateOf(template.to) }
    var subject by remember { mutableStateOf(template.subject) }
    var body by remember { mutableStateOf(template.body) }
    var saved by remember { mutableStateOf(false) }
    Scaffold(topBar = { ExtraTopBar("Mẫu email", onBack) }) { padding ->
        Column(
            Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Hint("Dùng khi bấm «Gửi email» ở màn tài liệu và trong Quy trình. Biến tự thay: {ten} tên tài liệu, {ngay} ngày quét, {gio} giờ, {so_trang} số trang.")
            OutlinedTextField(value = to, onValueChange = { to = it; saved = false }, label = { Text("Gửi tới (nhiều email cách nhau dấu phẩy, để trống = tự chọn)") }, modifier = Modifier.fillMaxWidth())
            OutlinedTextField(value = subject, onValueChange = { subject = it; saved = false }, label = { Text("Tiêu đề") }, singleLine = true, modifier = Modifier.fillMaxWidth())
            OutlinedTextField(value = body, onValueChange = { body = it; saved = false }, label = { Text("Nội dung") }, minLines = 6, modifier = Modifier.fillMaxWidth())
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                Button(onClick = { onSave(EmailTemplate(to.trim(), subject, body)); saved = true }) { Text("Lưu mẫu") }
                OutlinedButton(onClick = {
                    to = EmailTemplate.DEFAULT.to; subject = EmailTemplate.DEFAULT.subject; body = EmailTemplate.DEFAULT.body; saved = false
                }) { Text("Mẫu mặc định") }
                if (saved) Text("✓ Đã lưu", color = MaterialTheme.colorScheme.primary)
            }
        }
    }
}

// ============================ Quy trình tự động ============================

@Composable
fun WorkflowsScreen(
    workflows: List<Workflow>,
    folders: List<FolderMeta>,
    hasCloudFolder: Boolean,
    allowed: Boolean,
    onSave: (Workflow) -> Unit,
    onDelete: (String) -> Unit,
    onBack: () -> Unit,
) {
    var editing by remember { mutableStateOf<Workflow?>(null) }
    Scaffold(topBar = { ExtraTopBar("Quy trình tự động", onBack) }) { padding ->
        Column(
            Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Hint(
                "Gộp nhiều thao tác thành 1 lần bấm: đặt tên theo mẫu, chuyển thư mục, lưu vào thư mục đám mây, sao lưu ScanX Cloud, gửi email theo mẫu. " +
                    "Chạy ở màn tài liệu (menu ⋮ → Chạy quy trình) hoặc tự chạy sau mỗi lần quét.",
            )
            if (!allowed) Hint("Quy trình tự động là tính năng Business — bạn vẫn tạo được, khi chạy sẽ dùng lượt thử (nếu có).")
            workflows.forEach { w ->
                Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant), modifier = Modifier.clickable { editing = w }) {
                    Row(Modifier.fillMaxWidth().padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Filled.PlayArrow, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                        Column(Modifier.weight(1f).padding(start = 12.dp)) {
                            Text(w.name, fontWeight = FontWeight.SemiBold)
                            Hint("${w.stepCount} bước" + if (w.runAfterScan) " · tự chạy sau khi quét" else "")
                        }
                        IconButton(onClick = { editing = w }) { Icon(Icons.Filled.Edit, contentDescription = "Sửa") }
                        IconButton(onClick = { onDelete(w.id) }) { Icon(Icons.Filled.Delete, contentDescription = "Xoá") }
                    }
                }
            }
            OutlinedButton(onClick = { editing = Workflow.new("Quy trình ${workflows.size + 1}") }, modifier = Modifier.fillMaxWidth()) {
                Icon(Icons.Filled.Add, contentDescription = null)
                Text("  Tạo quy trình")
            }
        }
    }
    editing?.let { w ->
        WorkflowEditor(w, folders, hasCloudFolder, onDismiss = { editing = null }, onSave = { onSave(it); editing = null })
    }
}

@Composable
private fun WorkflowEditor(initial: Workflow, folders: List<FolderMeta>, hasCloudFolder: Boolean, onDismiss: () -> Unit, onSave: (Workflow) -> Unit) {
    var w by remember { mutableStateOf(initial) }
    var folderMenu by remember { mutableStateOf(false) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Quy trình") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(value = w.name, onValueChange = { w = w.copy(name = it) }, label = { Text("Tên quy trình") }, singleLine = true)
                OutlinedTextField(
                    value = w.renamePattern, onValueChange = { w = w.copy(renamePattern = it) },
                    label = { Text("Đặt tên theo mẫu (trống = giữ tên)") }, singleLine = true,
                    placeholder = { Text("VD: Hoá đơn {ngay} {gio}") },
                )
                ExposedDropdownMenuBox(expanded = folderMenu, onExpandedChange = { folderMenu = it }) {
                    OutlinedTextField(
                        value = folders.firstOrNull { it.id == w.folderId }?.name ?: "Không chuyển thư mục",
                        onValueChange = {},
                        readOnly = true,
                        label = { Text("Chuyển vào thư mục") },
                        trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = folderMenu) },
                        modifier = Modifier.menuAnchor(),
                    )
                    ExposedDropdownMenu(expanded = folderMenu, onDismissRequest = { folderMenu = false }) {
                        DropdownMenuItem(text = { Text("Không chuyển thư mục") }, onClick = { w = w.copy(folderId = null); folderMenu = false })
                        folders.forEach { f ->
                            DropdownMenuItem(text = { Text(f.name) }, onClick = { w = w.copy(folderId = f.id); folderMenu = false })
                        }
                    }
                }
                CheckRow("Lưu PDF vào thư mục đám mây" + if (!hasCloudFolder) " (chưa kết nối)" else "", w.saveToCloudFolder) { w = w.copy(saveToCloudFolder = it) }
                CheckRow("Sao lưu lên ScanX Cloud (Business)", w.backupScanX) { w = w.copy(backupScanX = it) }
                CheckRow("Gửi email theo Mẫu email", w.sendEmail) { w = w.copy(sendEmail = it) }
                HorizontalDivider()
                CheckRow("Tự chạy sau mỗi lần quét", w.runAfterScan) { w = w.copy(runAfterScan = it) }
            }
        },
        confirmButton = { TextButton(onClick = { onSave(w.copy(name = w.name.ifBlank { "Quy trình" })) }, enabled = w.stepCount > 0) { Text("Lưu") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Huỷ") } },
    )
}

@Composable
private fun CheckRow(label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().clickable { onChange(!checked) }, verticalAlignment = Alignment.CenterVertically) {
        Checkbox(checked = checked, onCheckedChange = onChange)
        Text(label, style = MaterialTheme.typography.bodyMedium)
    }
}

// ============================ Chữ ký ============================

@Composable
fun SignaturesScreen(signatures: List<File>, onAdd: (Bitmap) -> Unit, onDelete: (File) -> Unit, onBack: () -> Unit) {
    var drawing by remember { mutableStateOf(false) }
    Scaffold(topBar = { ExtraTopBar("Chữ ký", onBack) }) { padding ->
        Column(
            Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Hint("Vẽ chữ ký 1 lần, sau đó chèn vào tài liệu ở màn tài liệu (thanh dưới → Ký). Chữ ký chỉ lưu trên máy này.")
            signatures.forEach { f ->
                val bmp = remember(f.path) { BitmapFactory.decodeFile(f.absolutePath) }
                Card(colors = CardDefaults.cardColors(containerColor = Color.White)) {
                    Row(Modifier.fillMaxWidth().padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                        if (bmp != null) {
                            Image(bmp.asImageBitmap(), contentDescription = "Chữ ký", modifier = Modifier.height(64.dp).weight(1f))
                        } else {
                            Spacer(Modifier.weight(1f))
                        }
                        IconButton(onClick = { onDelete(f) }) { Icon(Icons.Filled.Delete, contentDescription = "Xoá chữ ký", tint = Color.DarkGray) }
                    }
                }
            }
            OutlinedButton(onClick = { drawing = true }, modifier = Modifier.fillMaxWidth()) {
                Icon(Icons.Filled.Add, contentDescription = null)
                Text("  Vẽ chữ ký mới")
            }
        }
    }
    if (drawing) {
        SignaturePadDialog(onDismiss = { drawing = false }, onDone = { onAdd(it); drawing = false })
    }
}

/** Bảng vẽ chữ ký (ngón tay / bút) → Bitmap nền trong suốt, đã cắt sát nét vẽ. */
@Composable
fun SignaturePadDialog(onDismiss: () -> Unit, onDone: (Bitmap) -> Unit) {
    val strokes = remember { mutableStateListOf<List<Offset>>() }
    val current = remember { mutableStateListOf<Offset>() }
    var size by remember { mutableStateOf(IntSize.Zero) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Vẽ chữ ký") },
        text = {
            Column {
                Box(
                    Modifier
                        .fillMaxWidth()
                        .height(220.dp)
                        .background(Color.White, RoundedCornerShape(8.dp))
                        .border(1.dp, MaterialTheme.colorScheme.outline, RoundedCornerShape(8.dp))
                        .onSizeChanged { size = it }
                        .pointerInput(Unit) {
                            detectDragGestures(
                                onDragStart = { current.clear(); current.add(it) },
                                onDrag = { change, _ -> current.add(change.position) },
                                onDragEnd = { strokes.add(current.toList()); current.clear() },
                                onDragCancel = { strokes.add(current.toList()); current.clear() },
                            )
                        },
                ) {
                    Canvas(Modifier.fillMaxSize()) {
                        (strokes + listOf(current.toList())).forEach { pts ->
                            if (pts.size > 1) {
                                val path = Path().apply {
                                    moveTo(pts[0].x, pts[0].y)
                                    pts.drop(1).forEach { lineTo(it.x, it.y) }
                                }
                                drawPath(path, Color(0xFF0D2A6B), style = Stroke(width = 5.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round))
                            }
                        }
                    }
                }
                Hint("Ký bằng ngón tay bên trong khung.")
            }
        },
        confirmButton = {
            TextButton(
                enabled = strokes.any { it.size > 1 },
                onClick = { renderSignature(strokes.toList(), size, density = 3f)?.let(onDone) },
            ) { Text("Lưu") }
        },
        dismissButton = {
            Row {
                TextButton(onClick = { strokes.clear(); current.clear() }) { Text("Xoá nét") }
                TextButton(onClick = onDismiss) { Text("Huỷ") }
            }
        },
    )
}

private fun renderSignature(strokes: List<List<Offset>>, size: IntSize, density: Float): Bitmap? {
    val pts = strokes.flatten()
    if (pts.isEmpty() || size.width <= 0) return null
    val stroke = 5f * density
    val pad = stroke * 2
    val minX = (pts.minOf { it.x } - pad).coerceAtLeast(0f)
    val minY = (pts.minOf { it.y } - pad).coerceAtLeast(0f)
    val maxX = (pts.maxOf { it.x } + pad).coerceAtMost(size.width.toFloat())
    val maxY = (pts.maxOf { it.y } + pad).coerceAtMost(size.height.toFloat())
    val w = (maxX - minX).toInt().coerceAtLeast(1)
    val h = (maxY - minY).toInt().coerceAtLeast(1)
    val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
    val canvas = android.graphics.Canvas(bmp)
    val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFF0D2A6B.toInt()
        style = Paint.Style.STROKE
        strokeWidth = stroke
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }
    strokes.filter { it.size > 1 }.forEach { s ->
        val path = android.graphics.Path()
        path.moveTo(s[0].x - minX, s[0].y - minY)
        s.drop(1).forEach { path.lineTo(it.x - minX, it.y - minY) }
        canvas.drawPath(path, paint)
    }
    return bmp
}

// ============================ Biểu tượng ứng dụng ============================

@Composable
fun AppIconScreen(current: String, onSelect: (String) -> Unit, onBack: () -> Unit) {
    Scaffold(topBar = { ExtraTopBar("Biểu tượng ứng dụng", onBack) }) { padding ->
        Column(Modifier.fillMaxSize().padding(padding).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Hint("Đổi màu biểu tượng ScanX trên màn hình chính. Màn hình chính có thể mất vài giây để cập nhật; một số máy tự đóng app sau khi đổi.")
            AppIconManager.OPTIONS.forEach { o ->
                Row(
                    Modifier.fillMaxWidth().clickable { onSelect(o.alias) }.padding(vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Box(Modifier.size(48.dp).background(Color(o.color), RoundedCornerShape(12.dp)), contentAlignment = Alignment.Center) {
                        Box(Modifier.size(width = 22.dp, height = 28.dp).background(Color.White, RoundedCornerShape(3.dp)))
                    }
                    Text(o.label, modifier = Modifier.weight(1f).padding(start = 16.dp))
                    if (o.alias == current) Icon(Icons.Filled.CheckCircle, contentDescription = "Đang dùng", tint = MaterialTheme.colorScheme.primary)
                }
            }
        }
    }
}

// ============================ Chèn chữ ký vào trang ============================

/**
 * Đặt chữ ký lên trang: kéo để di chuyển, nút +/− đổi cỡ. [onApply] nhận vị trí/cỡ chuẩn hoá 0..1 theo
 * ảnh trang (x, y góc trên trái; w bề rộng chữ ký / bề rộng trang).
 */
@Composable
fun SignPlacementScreen(
    page: Bitmap,
    signature: Bitmap,
    pageLabel: String,
    onApply: (x: Float, y: Float, w: Float) -> Unit,
    onBack: () -> Unit,
) {
    var box by remember { mutableStateOf(IntSize.Zero) }
    var nx by remember { mutableStateOf(0.55f) }
    var ny by remember { mutableStateOf(0.78f) }
    var nw by remember { mutableStateOf(0.32f) }
    val sigAspect = signature.height.toFloat() / signature.width.coerceAtLeast(1)
    val pageAspect = page.height.toFloat() / page.width.coerceAtLeast(1)
    Scaffold(topBar = { ExtraTopBar("Ký — $pageLabel", onBack) }) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            Box(Modifier.weight(1f).fillMaxWidth().background(Color(0xFF303030)), contentAlignment = Alignment.Center) {
                // Ảnh trang giữ tỉ lệ; chữ ký vẽ đè theo toạ độ chuẩn hoá.
                Canvas(
                    Modifier
                        .fillMaxSize()
                        .padding(12.dp)
                        .onSizeChanged { box = it }
                        .pointerInput(pageAspect) {
                            detectDragGestures { change, drag ->
                                change.consume()
                                val (pw, ph) = fitSize(box, pageAspect)
                                if (pw > 0 && ph > 0) {
                                    nx = (nx + drag.x / pw).coerceIn(0f, 1f - nw)
                                    ny = (ny + drag.y / ph).coerceIn(0f, 1f - nw * sigAspect / pageAspect)
                                }
                            }
                        },
                ) {
                    val (pw, ph) = fitSize(box, pageAspect)
                    val left = (size.width - pw) / 2
                    val top = (size.height - ph) / 2
                    drawImage(
                        page.asImageBitmap(),
                        dstOffset = androidx.compose.ui.unit.IntOffset(left.toInt(), top.toInt()),
                        dstSize = IntSize(pw.toInt(), ph.toInt()),
                    )
                    val sw = nw * pw
                    val sh = sw * sigAspect
                    drawImage(
                        signature.asImageBitmap(),
                        dstOffset = androidx.compose.ui.unit.IntOffset((left + nx * pw).toInt(), (top + ny * ph).toInt()),
                        dstSize = IntSize(sw.toInt().coerceAtLeast(1), sh.toInt().coerceAtLeast(1)),
                    )
                    drawRect(
                        Color(0xFF2F6FED),
                        topLeft = Offset(left + nx * pw, top + ny * ph),
                        size = androidx.compose.ui.geometry.Size(sw, sh),
                        style = Stroke(width = 2.dp.toPx()),
                    )
                }
            }
            Row(
                Modifier.fillMaxWidth().padding(12.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                OutlinedButton(onClick = { nw = (nw - 0.05f).coerceAtLeast(0.1f) }) { Text("−") }
                OutlinedButton(onClick = { nw = (nw + 0.05f).coerceAtMost(0.9f); nx = nx.coerceAtMost(1f - nw) }) { Text("+") }
                Text("Kéo chữ ký để đặt vị trí", style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f))
                Button(onClick = { onApply(nx, ny, nw) }) { Text("Chèn") }
            }
        }
    }
}

private fun fitSize(box: IntSize, aspect: Float): Pair<Float, Float> {
    if (box.width <= 0 || box.height <= 0) return 0f to 0f
    val w = box.width.toFloat()
    val h = box.height.toFloat()
    return if (w * aspect <= h) w to w * aspect else h / aspect to h
}

/** Dấu chấm tròn nhỏ (dùng cho chú thích trạng thái). */
@Composable
fun Dot(color: Color) {
    Box(Modifier.size(8.dp).background(color, CircleShape))
}
