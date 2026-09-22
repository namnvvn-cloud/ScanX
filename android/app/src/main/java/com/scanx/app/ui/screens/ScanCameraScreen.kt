package com.scanx.app.ui.screens

import android.graphics.Bitmap
import android.util.Size as AndroidSize
import android.widget.Toast
import androidx.camera.core.Camera
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageCapture
import androidx.camera.core.resolutionselector.AspectRatioStrategy
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.core.resolutionselector.ResolutionStrategy
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.FlashOff
import androidx.compose.material.icons.filled.FlashOn
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.geometry.Size as ComposeSize
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.scanx.app.data.CaptureMode
import com.scanx.app.scan.DetectedQuad
import com.scanx.app.ui.ScanCameraViewModel
import kotlinx.coroutines.delay
import java.util.concurrent.Executors

/**
 * Màn hình camera tự viết (CameraX + OpenCV), thay cho UI camera có sẵn của Google ML Kit Document
 * Scanner: tự phát hiện khung tài liệu real-time, tự động chụp khi ổn định (chế độ Auto) hoặc
 * chụp thủ công, hiển thị khung xanh theo dõi biên tài liệu, chụp trang tiếp theo ngay khi người
 * dùng lật sang trang mới mà không cần bấm gì thêm.
 */
@Composable
fun ScanCameraScreen(
    viewModel: ScanCameraViewModel,
    onClose: () -> Unit,
    onDone: () -> Unit,
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current

    val captureMode by viewModel.captureMode.collectAsStateWithLifecycle()
    val isFlashOn by viewModel.isFlashOn.collectAsStateWithLifecycle()
    val detectedQuad by viewModel.detectedQuad.collectAsStateWithLifecycle()
    val pages by viewModel.pages.collectAsStateWithLifecycle()
    val captureEvent by viewModel.captureEvent.collectAsStateWithLifecycle()
    val errorMessage by viewModel.errorMessage.collectAsStateWithLifecycle()
    val autoProgress by viewModel.autoProgress.collectAsStateWithLifecycle()
    val waitingForNewPage by viewModel.waitingForNewPage.collectAsStateWithLifecycle()
    val isAiReady by viewModel.isAiReady.collectAsStateWithLifecycle()
    val processingCount by viewModel.processingCount.collectAsStateWithLifecycle()

    var camera by remember { mutableStateOf<Camera?>(null) }
    var showReview by remember { mutableStateOf(false) }
    var showDiscardConfirm by remember { mutableStateOf(false) }
    var flashFrame by remember { mutableStateOf(false) }

    val previewView = remember {
        PreviewView(context).apply {
            implementationMode = PreviewView.ImplementationMode.COMPATIBLE
            scaleType = PreviewView.ScaleType.FILL_CENTER
        }
    }
    val analysisExecutor = remember { Executors.newSingleThreadExecutor() }

    LaunchedEffect(errorMessage) {
        errorMessage?.let {
            Toast.makeText(context, it, Toast.LENGTH_SHORT).show()
            viewModel.clearError()
        }
    }

    LaunchedEffect(captureEvent) {
        if (captureEvent > 0) {
            flashFrame = true
            delay(120)
            flashFrame = false
        }
    }

    DisposableEffect(lifecycleOwner) {
        val providerFuture = ProcessCameraProvider.getInstance(context)
        providerFuture.addListener({
            val provider = providerFuture.get()
            // Preview, phân tích và chụp đều cùng tỉ lệ 4:3 (toàn cảm biến) → cùng trường nhìn,
            // toạ độ 4 góc AI tìm được trên khung phân tích khớp tuyệt đối với ảnh xem trước và ảnh chụp.
            val ratio43 = AspectRatioStrategy.RATIO_4_3_FALLBACK_AUTO_STRATEGY
            val preview = Preview.Builder()
                .setResolutionSelector(ResolutionSelector.Builder().setAspectRatioStrategy(ratio43).build())
                .build()
                .also { it.setSurfaceProvider(previewView.surfaceProvider) }
            val analysis = ImageAnalysis.Builder()
                .setResolutionSelector(
                    ResolutionSelector.Builder()
                        .setAspectRatioStrategy(ratio43)
                        .setResolutionStrategy(
                            ResolutionStrategy(
                                AndroidSize(640, 480),
                                ResolutionStrategy.FALLBACK_RULE_CLOSEST_HIGHER_THEN_LOWER,
                            ),
                        )
                        .build(),
                )
                .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_RGBA_8888)
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .build()
                .also { it.setAnalyzer(analysisExecutor) { proxy -> viewModel.onFrameAnalyzed(proxy) } }
            val imageCapture = ImageCapture.Builder()
                // Zero Shutter Lag: CameraX giữ sẵn bộ đệm khung hình, lấy đúng khung tại thời điểm bấm
                // → gần như không trễ. Máy không hỗ trợ thì CameraX tự lùi về MINIMIZE_LATENCY.
                .setCaptureMode(ImageCapture.CAPTURE_MODE_ZERO_SHUTTER_LAG)
                .setResolutionSelector(
                    ResolutionSelector.Builder()
                        .setAspectRatioStrategy(ratio43)
                        .setResolutionStrategy(
                            ResolutionStrategy(
                                AndroidSize(2560, 1920),
                                ResolutionStrategy.FALLBACK_RULE_CLOSEST_HIGHER_THEN_LOWER,
                            ),
                        )
                        .build(),
                )
                .build()
            viewModel.attachImageCapture(imageCapture)

            try {
                provider.unbindAll()
                camera = provider.bindToLifecycle(
                    lifecycleOwner,
                    CameraSelector.DEFAULT_BACK_CAMERA,
                    preview,
                    analysis,
                    imageCapture,
                )
            } catch (e: Exception) {
                Toast.makeText(context, "Không mở được camera: ${e.message}", Toast.LENGTH_LONG).show()
            }
        }, ContextCompat.getMainExecutor(context))

        onDispose {
            viewModel.attachImageCapture(null)
            runCatching { ProcessCameraProvider.getInstance(context).get().unbindAll() }
            analysisExecutor.shutdown()
        }
    }

    LaunchedEffect(isFlashOn, camera) {
        camera?.cameraControl?.enableTorch(isFlashOn)
    }

    Box(modifier = Modifier.fillMaxSize().background(Color.Black)) {
        AndroidView(factory = { previewView }, modifier = Modifier.fillMaxSize())

        // Khung theo dõi biên tài liệu real-time. Map toạ độ đúng cách PreviewView FILL_CENTER hiển
        // thị (phóng to phủ kín màn hình rồi cắt 2 bên) — bản cũ nhân thẳng với kích thước màn hình
        // nên khung bị bóp hẹp/lệch khỏi mép giấy.
        Canvas(modifier = Modifier.fillMaxSize()) {
            val quad = detectedQuad
            if (quad != null && quad.points.size == 4) {
                val pts = mapToView(quad, size)
                val path = Path().apply {
                    moveTo(pts[0].x, pts[0].y)
                    lineTo(pts[1].x, pts[1].y)
                    lineTo(pts[2].x, pts[2].y)
                    lineTo(pts[3].x, pts[3].y)
                    close()
                }
                val ready = captureMode == CaptureMode.AUTO && autoProgress > 0f
                val color = when {
                    waitingForNewPage -> Color(0xFFFFFFFF)
                    ready -> Color(0xFF34D058)
                    else -> Color(0xFF4FC3F7)
                }
                drawPath(path, color = color.copy(alpha = 0.18f))
                drawPath(path, color = color, style = Stroke(width = 4.dp.toPx(), cap = StrokeCap.Round))
                pts.forEach { drawCircle(color = color, radius = 6.dp.toPx(), center = it) }
            }
        }

        // Dòng trạng thái hướng dẫn người dùng.
        val hint = when {
            !isAiReady -> "Đang khởi động AI nhận diện…"
            captureMode == CaptureMode.MANUAL -> if (detectedQuad != null) "Đã bắt được tài liệu — bấm nút để chụp" else "Đưa tài liệu vào khung hình"
            waitingForNewPage -> "Đã chụp ✓  Lật sang trang tiếp theo"
            detectedQuad == null -> "Đưa tài liệu vào khung hình"
            autoProgress > 0f -> "Giữ yên…"
            else -> "Đang căn chỉnh…"
        }
        Text(
            text = if (processingCount > 0) "$hint  •  đang xử lý $processingCount trang" else hint,
            color = Color.White,
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier
                .align(Alignment.TopCenter)
                .padding(top = 80.dp)
                .background(Color.Black.copy(alpha = 0.5f), RoundedCornerShape(16.dp))
                .padding(horizontal = 14.dp, vertical = 6.dp),
        )

        AnimatedVisibility(visible = flashFrame, modifier = Modifier.fillMaxSize()) {
            Box(modifier = Modifier.fillMaxSize().background(Color.White.copy(alpha = 0.5f)))
        }

        // Thanh trên: đóng + đèn flash.
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp)
                .align(Alignment.TopStart),
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            IconButton(
                onClick = { if (pages.isEmpty()) onClose() else showDiscardConfirm = true },
                modifier = Modifier.background(Color.Black.copy(alpha = 0.4f), CircleShape),
            ) {
                Icon(Icons.Filled.Close, contentDescription = "Đóng", tint = Color.White)
            }
            IconButton(
                onClick = { viewModel.toggleFlash() },
                modifier = Modifier.background(Color.Black.copy(alpha = 0.4f), CircleShape),
            ) {
                Icon(
                    if (isFlashOn) Icons.Filled.FlashOn else Icons.Filled.FlashOff,
                    contentDescription = "Đèn flash",
                    tint = Color.White,
                )
            }
        }

        // Thanh dưới: chuyển Auto/Manual, nút chụp, xem lại + hoàn tất.
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .align(Alignment.BottomCenter)
                .padding(bottom = 24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            CaptureModeSwitch(
                mode = captureMode,
                onModeChange = { viewModel.setCaptureMode(it) },
            )

            Spacer(modifier = Modifier.height(20.dp))

            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 24.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                PageThumbnailBadge(
                    pages = pages,
                    onClick = { if (pages.isNotEmpty()) showReview = true },
                )

                ShutterButton(
                    progress = if (captureMode == CaptureMode.AUTO) autoProgress else 0f,
                    onClick = { viewModel.captureManually() },
                )

                TextButton(
                    onClick = onDone,
                    enabled = pages.isNotEmpty(),
                ) {
                    Icon(Icons.Filled.Check, contentDescription = null, tint = Color.White)
                    Spacer(modifier = Modifier.width(4.dp))
                    Text("Xong (${pages.size})", color = Color.White)
                }
            }
        }
    }

    if (showReview) {
        PageReviewDialog(
            pages = pages,
            onDismiss = { showReview = false },
            onRemovePage = { index -> viewModel.removePage(index) },
        )
    }

    if (showDiscardConfirm) {
        AlertDialog(
            onDismissRequest = { showDiscardConfirm = false },
            title = { Text("Huỷ phiên quét?") },
            text = { Text("${pages.size} trang đã chụp sẽ bị xoá, không thể khôi phục.") },
            confirmButton = {
                TextButton(onClick = {
                    showDiscardConfirm = false
                    viewModel.discardSession()
                    onClose()
                }) { Text("Huỷ trang đã chụp") }
            },
            dismissButton = {
                TextButton(onClick = { showDiscardConfirm = false }) { Text("Tiếp tục quét") }
            },
        )
    }
}

@Composable
private fun CaptureModeSwitch(mode: CaptureMode, onModeChange: (CaptureMode) -> Unit) {
    Row(
        modifier = Modifier
            .background(Color.Black.copy(alpha = 0.4f), RoundedCornerShape(20.dp))
            .padding(4.dp),
    ) {
        listOf(CaptureMode.AUTO to "Tự động", CaptureMode.MANUAL to "Thủ công").forEach { (m, label) ->
            val selected = mode == m
            Surface(
                color = if (selected) Color.White else Color.Transparent,
                shape = RoundedCornerShape(16.dp),
                modifier = Modifier
                    .clickable { onModeChange(m) }
                    .padding(horizontal = 4.dp),
            ) {
                Text(
                    text = label,
                    color = if (selected) Color.Black else Color.White,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                    style = MaterialTheme.typography.labelLarge,
                )
            }
        }
    }
}

@Composable
private fun ShutterButton(progress: Float, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .size(76.dp)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            val stroke = 5.dp.toPx()
            drawCircle(color = Color.White, radius = size.minDimension / 2 - stroke / 2, style = Stroke(width = stroke))
            if (progress > 0f) {
                drawArc(
                    color = Color(0xFF34D058),
                    startAngle = -90f,
                    sweepAngle = 360f * progress,
                    useCenter = false,
                    topLeft = Offset(stroke / 2, stroke / 2),
                    size = ComposeSize(size.width - stroke, size.height - stroke),
                    style = Stroke(width = stroke, cap = StrokeCap.Round),
                )
            }
        }
        Box(
            modifier = Modifier
                .size(58.dp)
                .background(Color(0xFFFF7A1A), CircleShape),
        )
    }
}

/** Map 4 góc chuẩn hoá (theo khung phân tích đứng) sang toạ độ màn hình của PreviewView FILL_CENTER. */
private fun mapToView(quad: DetectedQuad, view: ComposeSize): List<Offset> {
    val fw = quad.frameWidth.toFloat().coerceAtLeast(1f)
    val fh = quad.frameHeight.toFloat().coerceAtLeast(1f)
    val scale = maxOf(view.width / fw, view.height / fh)
    val dx = (view.width - fw * scale) / 2f
    val dy = (view.height - fh * scale) / 2f
    return quad.points.map { Offset(it.x * fw * scale + dx, it.y * fh * scale + dy) }
}

@Composable
private fun PageThumbnailBadge(pages: List<Bitmap>, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .size(48.dp)
            .background(Color.Black.copy(alpha = 0.4f), RoundedCornerShape(8.dp))
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        val last = pages.lastOrNull()
        if (last != null) {
            Image(
                bitmap = last.asImageBitmap(),
                contentDescription = "Trang vừa chụp",
                modifier = Modifier.fillMaxSize().padding(2.dp),
            )
        }
        if (pages.isNotEmpty()) {
            Text(
                text = "${pages.size}",
                color = Color.White,
                style = MaterialTheme.typography.labelSmall,
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .background(Color(0xFFFF7A1A), RoundedCornerShape(topStart = 6.dp))
                    .padding(horizontal = 4.dp),
            )
        }
    }
}

@Composable
private fun PageReviewDialog(
    pages: List<Bitmap>,
    onDismiss: () -> Unit,
    onRemovePage: (Int) -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("${pages.size} trang đã chụp") },
        text = {
            LazyRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                contentPadding = PaddingValues(vertical = 8.dp),
            ) {
                items(pages.size) { index ->
                    Box {
                        Image(
                            bitmap = pages[index].asImageBitmap(),
                            contentDescription = "Trang ${index + 1}",
                            modifier = Modifier
                                .size(100.dp)
                                .background(Color.LightGray, RoundedCornerShape(8.dp)),
                        )
                        IconButton(
                            onClick = { onRemovePage(index) },
                            modifier = Modifier
                                .align(Alignment.TopEnd)
                                .size(28.dp)
                                .background(Color.Black.copy(alpha = 0.6f), CircleShape),
                        ) {
                            Icon(
                                Icons.Filled.Close,
                                contentDescription = "Xoá trang ${index + 1}",
                                tint = Color.White,
                                modifier = Modifier.size(16.dp),
                            )
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text("Đóng") }
        },
    )
}
