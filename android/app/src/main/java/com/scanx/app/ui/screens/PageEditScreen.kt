package com.scanx.app.ui.screens

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.PointF
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AutoFixHigh
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Crop
import androidx.compose.material.icons.filled.Redo
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material.icons.filled.Undo
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import com.scanx.app.data.PageFilter
import com.scanx.app.scan.PageCleanup
import com.scanx.app.scan.PageCropRotate
import com.scanx.app.scan.ScanFilters
import com.scanx.app.ui.ScanViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.abs
import kotlin.math.hypot

/**
 * Màn "Chỉnh sửa trang" (bản 0.8) — tham khảo Scanner Pro: 3 công cụ Bộ lọc / Cắt và xoay / Làm sạch,
 * mỗi công cụ có Chấp nhận/Huỷ riêng. Dùng chung cho cả 2 luồng: review ngay sau khi quét (trước khi
 * lưu tài liệu) và mở lại từ tài liệu đã lưu (DocumentDetailScreen) — người gọi truyền ảnh master +
 * bộ lọc hiện tại, nhận lại kết quả CUỐI CÙNG 1 lần duy nhất khi bấm "Xong" (đỡ phải ghi đĩa/dựng lại
 * PDF sau MỖI thao tác nhỏ).
 *
 * [initialMaster] KHÔNG bị màn hình này recycle — người gọi tự quản lý theo đúng vòng đời của mình.
 */
@Composable
fun PageEditScreen(
    initialMaster: Bitmap,
    initialFilter: PageFilter?,
    pageLabel: String,
    onCancel: () -> Unit,
    onDone: (finalMaster: Bitmap, finalFilter: PageFilter?, masterChanged: Boolean) -> Unit,
) {
    var master by remember { mutableStateOf(initialMaster) }
    var masterChanged by remember { mutableStateOf(false) }
    var filter by remember { mutableStateOf(initialFilter) }
    var activeTool by remember { mutableStateOf<PageEditTool?>(null) }
    var showDiscardConfirm by remember { mutableStateOf(false) }

    // Ảnh hiển thị ở khung xem chính (theo bộ lọc đang chọn, null = xem ảnh gốc chưa lọc).
    var displayBitmap by remember { mutableStateOf(master) }
    LaunchedEffect(master, filter) {
        displayBitmap = withContext(Dispatchers.Default) {
            val f = filter
            if (f != null) runCatching { ScanFilters.renderPageFilter(master, f, 1400) }.getOrDefault(master) else master
        }
    }

    fun hasChanges() = masterChanged || filter != initialFilter

    fun requestClose() {
        if (hasChanges()) showDiscardConfirm = true else onCancel()
    }

    // Giải phóng bitmap trung gian màn hình này tự tạo ra (không phải initialMaster) khi rời màn.
    DisposableEffect(Unit) {
        onDispose {
            if (masterChanged && master !== initialMaster && !master.isRecycled) master.recycle()
        }
    }

    Box(modifier = Modifier.fillMaxSize().background(Color.Black)) {
        Column(modifier = Modifier.fillMaxSize()) {
            // Thanh trên: đóng + tên trang + Xong.
            Row(
                modifier = Modifier.fillMaxWidth().padding(8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                IconButton(onClick = { requestClose() }) {
                    Icon(Icons.Filled.Close, contentDescription = "Đóng", tint = Color.White)
                }
                Text(pageLabel, color = Color.White, style = MaterialTheme.typography.titleMedium)
                TextButton(onClick = { onDone(master, filter, masterChanged) }) {
                    Text("Xong", color = Color(0xFF34D058), style = MaterialTheme.typography.titleMedium)
                }
            }

            // Khung xem chính.
            Box(modifier = Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                val bmp = displayBitmap
                Image(
                    bitmap = bmp.asImageBitmap(),
                    contentDescription = pageLabel,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(12.dp)
                        .aspectRatio(bmp.width.toFloat() / bmp.height.toFloat()),
                )
            }

            // Thanh dưới: 3 công cụ, hoặc bảng điều khiển của công cụ đang mở.
            Surface(color = Color(0xFF1C1C1E)) {
                when (activeTool) {
                    null -> PageEditToolBar(onSelect = { activeTool = it })
                    PageEditTool.FILTER -> FilterToolPanel(
                        master = master,
                        current = filter,
                        onCancel = { activeTool = null },
                        onAccept = { chosen -> filter = chosen; activeTool = null },
                    )
                    PageEditTool.CROP_ROTATE -> CropRotateToolPanel(
                        master = master,
                        onCancel = { activeTool = null },
                        onAccept = { newBitmap ->
                            val old = master
                            master = newBitmap
                            masterChanged = true
                            activeTool = null
                            if (old !== initialMaster && !old.isRecycled) old.recycle()
                        },
                    )
                    PageEditTool.CLEANUP -> CleanupToolPanel(
                        master = master,
                        onCancel = { activeTool = null },
                        onAccept = { newBitmap ->
                            val old = master
                            master = newBitmap
                            masterChanged = true
                            activeTool = null
                            if (old !== initialMaster && !old.isRecycled) old.recycle()
                        },
                    )
                }
            }
        }
    }

    if (showDiscardConfirm) {
        AlertDialog(
            onDismissRequest = { showDiscardConfirm = false },
            title = { Text("Bỏ các chỉnh sửa?") },
            text = { Text("Thay đổi trên trang này chưa lưu sẽ mất.") },
            confirmButton = {
                TextButton(onClick = { showDiscardConfirm = false; onCancel() }) { Text("Bỏ thay đổi") }
            },
            dismissButton = {
                TextButton(onClick = { showDiscardConfirm = false }) { Text("Tiếp tục chỉnh sửa") }
            },
        )
    }
}

private enum class PageEditTool { FILTER, CROP_ROTATE, CLEANUP }

@Composable
private fun PageEditToolBar(onSelect: (PageEditTool) -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 14.dp),
        horizontalArrangement = Arrangement.SpaceEvenly,
    ) {
        ToolBarButton("Bộ lọc", Icons.Filled.Tune) { onSelect(PageEditTool.FILTER) }
        ToolBarButton("Cắt và xoay", Icons.Filled.Crop) { onSelect(PageEditTool.CROP_ROTATE) }
        ToolBarButton("Làm sạch", Icons.Filled.AutoFixHigh) { onSelect(PageEditTool.CLEANUP) }
    }
}

@Composable
private fun ToolBarButton(label: String, icon: ImageVector, onClick: () -> Unit) {
    Column(
        modifier = Modifier.clickable(onClick = onClick).padding(8.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Icon(icon, contentDescription = label, tint = Color.White)
        Spacer(modifier = Modifier.height(4.dp))
        Text(label, color = Color.White, style = MaterialTheme.typography.labelMedium)
    }
}

@Composable
private fun AcceptCancelRow(onCancel: () -> Unit, onAccept: () -> Unit, acceptEnabled: Boolean = true) {
    Row(modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp), horizontalArrangement = Arrangement.SpaceBetween) {
        TextButton(onClick = onCancel) { Text("Huỷ", color = Color.White) }
        Button(onClick = onAccept, enabled = acceptEnabled) { Text("Chấp nhận") }
    }
}

// ------------------------------------------------------------------------------- Bộ lọc

@Composable
private fun FilterToolPanel(
    master: Bitmap,
    current: PageFilter?,
    onCancel: () -> Unit,
    onAccept: (PageFilter) -> Unit,
) {
    var selected by remember { mutableStateOf(current ?: PageFilter.AUTO) }
    var thumbnails by remember { mutableStateOf<Map<PageFilter, Bitmap>?>(null) }

    LaunchedEffect(master) {
        thumbnails = withContext(Dispatchers.Default) {
            PageFilter.values().associateWith { f ->
                runCatching { ScanFilters.renderPageFilter(master, f, 160) }.getOrElse { master }
            }
        }
    }

    Column(modifier = Modifier.padding(vertical = 12.dp)) {
        val thumbs = thumbnails
        if (thumbs == null) {
            Box(modifier = Modifier.fillMaxWidth().height(96.dp), contentAlignment = Alignment.Center) {
                CircularProgressIndicator(color = Color.White)
            }
        } else {
            LazyRow(
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                contentPadding = PaddingValues(horizontal = 16.dp),
            ) {
                items(PageFilter.values().toList()) { f ->
                    val isSelected = f == selected
                    Column(
                        modifier = Modifier.clickable { selected = f },
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        val thumb = thumbs[f]
                        Box(
                            modifier = Modifier
                                .size(64.dp)
                                .background(if (isSelected) Color(0xFF34D058).copy(alpha = 0.25f) else Color.DarkGray, RoundedCornerShape(8.dp)),
                            contentAlignment = Alignment.Center,
                        ) {
                            if (thumb != null) {
                                Image(
                                    bitmap = thumb.asImageBitmap(),
                                    contentDescription = f.label,
                                    modifier = Modifier.fillMaxSize().padding(3.dp),
                                )
                            }
                        }
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            f.label,
                            color = if (isSelected) Color(0xFF34D058) else Color.White,
                            style = MaterialTheme.typography.labelSmall,
                        )
                    }
                }
            }
        }
        Spacer(modifier = Modifier.height(12.dp))
        AcceptCancelRow(onCancel = onCancel, onAccept = { onAccept(selected) })
    }
}

// ------------------------------------------------------------------------------- Cắt và xoay

private enum class CropStage { ROTATE, CROP }

@Composable
private fun CropRotateToolPanel(
    master: Bitmap,
    onCancel: () -> Unit,
    onAccept: (Bitmap) -> Unit,
) {
    val scope = rememberCoroutineScope()
    var stage by remember { mutableStateOf(CropStage.ROTATE) }
    var working by remember { mutableStateOf(master) }
    var workingChanged by remember { mutableStateOf(false) }
    var tiltDeg by remember { mutableStateOf(0f) }
    var quad by remember { mutableStateOf(PageCropRotate.fullFrameQuad()) }
    var isBusy by remember { mutableStateOf(false) }

    fun recycleWorkingIfOwned() {
        if (workingChanged && working !== master && !working.isRecycled) working.recycle()
    }

    fun bakeRotation(newBitmap: Bitmap) {
        recycleWorkingIfOwned()
        working = newBitmap
        workingChanged = true
        tiltDeg = 0f
    }

    // Thử dò khung tài liệu ngay khi vào bước Cắt, để người dùng thường chỉ cần tinh chỉnh nhẹ.
    LaunchedEffect(stage, working) {
        if (stage == CropStage.CROP) {
            isBusy = true
            val detected = withContext(Dispatchers.Default) { PageCropRotate.autoDetectQuad(working) }
            quad = detected ?: PageCropRotate.fullFrameQuad()
            isBusy = false
        }
    }

    Column(modifier = Modifier.padding(vertical = 12.dp)) {
        when (stage) {
            CropStage.ROTATE -> {
                Box(modifier = Modifier.fillMaxWidth().padding(horizontal = 24.dp), contentAlignment = Alignment.Center) {
                    Image(
                        bitmap = working.asImageBitmap(),
                        contentDescription = "Xoay trang",
                        modifier = Modifier
                            .fillMaxWidth()
                            .aspectRatio(working.width.toFloat() / working.height.toFloat()),
                    )
                }
                Row(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 8.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    TextButton(onClick = { bakeRotation(PageCropRotate.rotate90(working, -1)) }) {
                        Text("⟲ Trái 90°", color = Color.White)
                    }
                    TextButton(onClick = { bakeRotation(PageCropRotate.rotate90(working, 1)) }) {
                        Text("⟳ Phải 90°", color = Color.White)
                    }
                }
                Row(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 24.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text("Nghiêng", color = Color.White, style = MaterialTheme.typography.labelMedium)
                    Slider(
                        value = tiltDeg,
                        onValueChange = { tiltDeg = it },
                        onValueChangeFinished = {
                            if (abs(tiltDeg) >= 0.05f) bakeRotation(PageCropRotate.rotateFree(working, tiltDeg))
                        },
                        valueRange = -15f..15f,
                        modifier = Modifier.weight(1f).padding(horizontal = 8.dp),
                    )
                    Text("${"%.1f".format(tiltDeg)}°", color = Color.White, modifier = Modifier.width(48.dp))
                }
                Spacer(modifier = Modifier.height(8.dp))
                Row(modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp), horizontalArrangement = Arrangement.SpaceBetween) {
                    TextButton(onClick = { recycleWorkingIfOwned(); onCancel() }) { Text("Huỷ", color = Color.White) }
                    Button(onClick = { stage = CropStage.CROP }) { Text("Tiếp theo — Cắt") }
                }
            }

            CropStage.CROP -> {
                Box(modifier = Modifier.fillMaxWidth().padding(horizontal = 24.dp), contentAlignment = Alignment.Center) {
                    if (isBusy) {
                        Box(modifier = Modifier.fillMaxWidth().aspectRatio(0.75f), contentAlignment = Alignment.Center) {
                            CircularProgressIndicator(color = Color.White)
                        }
                    } else {
                        QuadCropCanvas(bitmap = working, quad = quad, onQuadChange = { quad = it })
                    }
                }
                Row(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 8.dp),
                    horizontalArrangement = Arrangement.Center,
                ) {
                    TextButton(onClick = {
                        scope.launch {
                            isBusy = true
                            val detected = withContext(Dispatchers.Default) { PageCropRotate.autoDetectQuad(working) }
                            quad = detected ?: PageCropRotate.fullFrameQuad()
                            isBusy = false
                        }
                    }) { Text("Tự động dò lại khung", color = Color.White) }
                }
                Row(modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp), horizontalArrangement = Arrangement.SpaceBetween) {
                    TextButton(onClick = { stage = CropStage.ROTATE }) { Text("← Quay lại", color = Color.White) }
                    Row {
                        TextButton(onClick = { recycleWorkingIfOwned(); onCancel() }) { Text("Huỷ", color = Color.White) }
                        Spacer(modifier = Modifier.width(8.dp))
                        Button(
                            enabled = !isBusy,
                            onClick = {
                                scope.launch {
                                    isBusy = true
                                    val result = withContext(Dispatchers.Default) { PageCropRotate.cropQuad(working, quad) }
                                    isBusy = false
                                    recycleWorkingIfOwned()
                                    onAccept(result)
                                }
                            },
                        ) { Text("Chấp nhận") }
                    }
                }
            }
        }
    }
}

/** Vẽ ảnh + tứ giác cắt có thể kéo 4 góc. Toạ độ [quad]: chuẩn hoá [0,1] TL/TR/BR/BL theo [bitmap]. */
@Composable
private fun QuadCropCanvas(bitmap: Bitmap, quad: List<PointF>, onQuadChange: (List<PointF>) -> Unit) {
    var boxSize by remember { mutableStateOf(IntSize.Zero) }
    var draggedIndex by remember { mutableStateOf<Int?>(null) }
    val density = androidx.compose.ui.platform.LocalDensity.current
    val handleTouchRadiusPx = with(density) { 28.dp.toPx() }
    // Đọc quad/boxSize MỚI NHẤT bên trong pointerInput mà KHÔNG cài lại trình phát hiện kéo mỗi khi
    // quad đổi (mỗi lần kéo là 1 lần đổi quad) — nếu không, thao tác kéo bị ngắt giữa chừng vì
    // detectDragGestures khởi động lại và phải chờ nhấc/nhấn tay lại mới nhận tiếp được.
    val currentQuad = rememberUpdatedState(quad)
    val currentBoxSize = rememberUpdatedState(boxSize)

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .aspectRatio(bitmap.width.toFloat() / bitmap.height.toFloat())
            .onSizeChanged { boxSize = it },
    ) {
        Image(bitmap = bitmap.asImageBitmap(), contentDescription = "Chọn khung cắt", modifier = Modifier.fillMaxSize())
        Canvas(
            modifier = Modifier
                .fillMaxSize()
                .pointerInput(Unit) {
                    detectDragGestures(
                        onDragStart = { offset ->
                            val w = currentBoxSize.value.width.toFloat(); val h = currentBoxSize.value.height.toFloat()
                            if (w <= 0f || h <= 0f) return@detectDragGestures
                            var best = -1
                            var bestDist = Float.MAX_VALUE
                            currentQuad.value.forEachIndexed { i, p ->
                                val d = hypot((p.x * w - offset.x).toDouble(), (p.y * h - offset.y).toDouble()).toFloat()
                                if (d < bestDist) { bestDist = d; best = i }
                            }
                            draggedIndex = if (bestDist <= handleTouchRadiusPx) best else null
                        },
                        onDrag = { change, _ ->
                            val idx = draggedIndex ?: return@detectDragGestures
                            change.consume()
                            val w = currentBoxSize.value.width.toFloat(); val h = currentBoxSize.value.height.toFloat()
                            if (w <= 0f || h <= 0f) return@detectDragGestures
                            val nx = (change.position.x / w).coerceIn(0f, 1f)
                            val ny = (change.position.y / h).coerceIn(0f, 1f)
                            val newQuad = currentQuad.value.toMutableList()
                            newQuad[idx] = PointF(nx, ny)
                            onQuadChange(newQuad)
                        },
                        onDragEnd = { draggedIndex = null },
                        onDragCancel = { draggedIndex = null },
                    )
                },
        ) {
            if (quad.size == 4) {
                val pts = quad.map { Offset(it.x * size.width, it.y * size.height) }
                val path = Path().apply {
                    moveTo(pts[0].x, pts[0].y)
                    lineTo(pts[1].x, pts[1].y)
                    lineTo(pts[2].x, pts[2].y)
                    lineTo(pts[3].x, pts[3].y)
                    close()
                }
                drawPath(path, color = Color(0xFF34D058).copy(alpha = 0.15f))
                drawPath(path, color = Color(0xFF34D058), style = Stroke(width = 3.dp.toPx()))
                pts.forEach { p ->
                    drawCircle(color = Color.White, radius = 12.dp.toPx(), center = p)
                    drawCircle(color = Color(0xFF34D058), radius = 12.dp.toPx(), center = p, style = Stroke(width = 3.dp.toPx()))
                }
            }
        }
    }
}

// ------------------------------------------------------------------------------- Làm sạch

@Composable
private fun CleanupToolPanel(
    master: Bitmap,
    onCancel: () -> Unit,
    onAccept: (Bitmap) -> Unit,
) {
    val scope = rememberCoroutineScope()
    // Xem trước trên bản thu nhỏ cho mượt tay — vá thật ở ảnh gốc chỉ lúc Chấp nhận.
    val previewBase = remember(master) {
        val k = 1100.0 / maxOf(master.width, master.height)
        if (k < 1.0) Bitmap.createScaledBitmap(master, (master.width * k).toInt(), (master.height * k).toInt(), true) else master
    }
    var healedPreview by remember { mutableStateOf(previewBase) }
    val strokes = remember { mutableStateListOf<PageCleanup.Stroke>() }
    val redoStack = remember { mutableStateListOf<PageCleanup.Stroke>() }
    var brushRadius by remember { mutableStateOf(0.02f) }
    var currentPoints by remember { mutableStateOf<List<PointF>>(emptyList()) }
    var isBusy by remember { mutableStateOf(false) }
    var boxSize by remember { mutableStateOf(IntSize.Zero) }

    DisposableEffect(previewBase) {
        onDispose {
            if (previewBase !== master && !previewBase.isRecycled) previewBase.recycle()
            if (healedPreview !== previewBase && healedPreview !== master && !healedPreview.isRecycled) healedPreview.recycle()
        }
    }

    fun rebake() {
        scope.launch {
            isBusy = true
            val result = withContext(Dispatchers.Default) { PageCleanup.heal(previewBase, strokes) }
            val old = healedPreview
            healedPreview = result
            if (old !== previewBase && !old.isRecycled) old.recycle()
            isBusy = false
        }
    }

    Column(modifier = Modifier.padding(vertical = 12.dp)) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 24.dp)
                .aspectRatio(previewBase.width.toFloat() / previewBase.height.toFloat())
                .onSizeChanged { boxSize = it }
                .pointerInput(Unit) {
                    detectDragGestures(
                        onDragStart = { offset ->
                            val w = boxSize.width.toFloat(); val h = boxSize.height.toFloat()
                            if (w <= 0f || h <= 0f) return@detectDragGestures
                            currentPoints = listOf(PointF(offset.x / w, offset.y / h))
                        },
                        onDrag = { change, _ ->
                            change.consume()
                            val w = boxSize.width.toFloat(); val h = boxSize.height.toFloat()
                            if (w <= 0f || h <= 0f) return@detectDragGestures
                            currentPoints = currentPoints + PointF(change.position.x / w, change.position.y / h)
                        },
                        onDragEnd = {
                            if (currentPoints.isNotEmpty()) {
                                strokes.add(PageCleanup.Stroke(currentPoints, brushRadius))
                                redoStack.clear()
                                currentPoints = emptyList()
                                rebake()
                            }
                        },
                        onDragCancel = { currentPoints = emptyList() },
                    )
                },
        ) {
            Image(bitmap = healedPreview.asImageBitmap(), contentDescription = "Làm sạch vết bẩn", modifier = Modifier.fillMaxSize())
            if (currentPoints.isNotEmpty()) {
                Canvas(modifier = Modifier.fillMaxSize()) {
                    val r = brushRadius * maxOf(size.width, size.height)
                    val pts = currentPoints.map { Offset(it.x * size.width, it.y * size.height) }
                    val path = Path().apply {
                        moveTo(pts.first().x, pts.first().y)
                        pts.drop(1).forEach { lineTo(it.x, it.y) }
                    }
                    drawPath(path, color = Color(0xFFFF7A1A).copy(alpha = 0.55f), style = Stroke(width = r * 2, cap = StrokeCap.Round))
                }
            }
            if (isBusy) {
                Box(modifier = Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.25f)), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator(color = Color.White)
                }
            }
        }

        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("Cỡ bút", color = Color.White, style = MaterialTheme.typography.labelMedium)
            Slider(
                value = brushRadius,
                onValueChange = { brushRadius = it },
                valueRange = 0.008f..0.05f,
                modifier = Modifier.weight(1f).padding(horizontal = 8.dp),
            )
        }

        Row(modifier = Modifier.fillMaxWidth().padding(horizontal = 24.dp), horizontalArrangement = Arrangement.Center) {
            IconButton(onClick = {
                if (strokes.isNotEmpty()) {
                    redoStack.add(strokes.removeAt(strokes.size - 1))
                    rebake()
                }
            }, enabled = strokes.isNotEmpty()) {
                Icon(Icons.Filled.Undo, contentDescription = "Hoàn tác", tint = if (strokes.isNotEmpty()) Color.White else Color.Gray)
            }
            Spacer(modifier = Modifier.width(24.dp))
            IconButton(onClick = {
                if (redoStack.isNotEmpty()) {
                    strokes.add(redoStack.removeAt(redoStack.size - 1))
                    rebake()
                }
            }, enabled = redoStack.isNotEmpty()) {
                Icon(Icons.Filled.Redo, contentDescription = "Làm lại", tint = if (redoStack.isNotEmpty()) Color.White else Color.Gray)
            }
        }

        Spacer(modifier = Modifier.height(8.dp))
        AcceptCancelRow(
            onCancel = onCancel,
            acceptEnabled = !isBusy,
            onAccept = {
                scope.launch {
                    isBusy = true
                    val result = withContext(Dispatchers.Default) { PageCleanup.heal(master, strokes) }
                    isBusy = false
                    onAccept(result)
                }
            },
        )
    }
}

// ------------------------------------------------------------------------------- Tài liệu đã lưu

/**
 * Bản 0.8: tải ảnh master 1 trang của tài liệu ĐÃ LƯU rồi mở [PageEditScreen] toàn màn hình — dùng từ
 * DocumentDetailScreen (MainActivity giữ [pageIndex] đang chỉnh sửa). Đóng sẽ tự giải phóng ảnh tạm
 * nếu không dùng tới (huỷ, hoặc [ScanViewModel.commitPageEdit] đã nhận quyền sở hữu ảnh mới).
 */
@Composable
fun PageEditOverlayForDocument(
    viewModel: ScanViewModel,
    documentId: String,
    pageIndex: Int,
    onClose: () -> Unit,
) {
    var master by remember(documentId, pageIndex) { mutableStateOf<Bitmap?>(null) }
    var filter by remember(documentId, pageIndex) { mutableStateOf<PageFilter?>(null) }
    LaunchedEffect(documentId, pageIndex) {
        filter = viewModel.getPageFilters(documentId).getOrNull(pageIndex)
        master = withContext(Dispatchers.IO) {
            viewModel.getPageFiles(documentId).getOrNull(pageIndex)?.let { BitmapFactory.decodeFile(it.absolutePath) }
        }
    }
    val m = master
    if (m == null) {
        Box(modifier = Modifier.fillMaxSize().background(Color.Black), contentAlignment = Alignment.Center) {
            CircularProgressIndicator(color = Color.White)
        }
    } else {
        PageEditScreen(
            initialMaster = m,
            initialFilter = filter,
            pageLabel = "Trang ${pageIndex + 1}",
            onCancel = { if (!m.isRecycled) m.recycle(); onClose() },
            onDone = { finalMaster, finalFilter, masterChanged ->
                viewModel.commitPageEdit(documentId, pageIndex, if (masterChanged) finalMaster else null, finalFilter)
                if (!masterChanged && !finalMaster.isRecycled) finalMaster.recycle()
                onClose()
            },
        )
    }
}
