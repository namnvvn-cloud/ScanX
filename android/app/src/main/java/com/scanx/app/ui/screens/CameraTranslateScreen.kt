package com.scanx.app.ui.screens

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.util.Size as AndroidSize
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.core.resolutionselector.AspectRatioStrategy
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.core.resolutionselector.ResolutionStrategy
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.PhotoLibrary
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Subject
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.scanx.app.convert.LiveTranslator
import com.scanx.app.convert.PhotoTranslateRenderer
import com.scanx.app.scan.ImageProxyUtils
import com.scanx.app.ui.CameraTranslateViewModel
import java.io.File
import java.util.concurrent.Executors

/**
 * Màn "Dịch" — bản 1.1: soi camera là bản dịch hiện đè TRỰC TIẾP (ML Kit offline, [LiveTranslator]); bấm
 * chụp để dịch online chính xác hơn. Bản 1.0: "Chụp để dịch" — như chế độ Quét của Google Dịch: chụp (hoặc chọn ảnh) → bản dịch
 * tiếng Việt vẽ đè lên đúng chỗ chữ gốc; bấm "Bản gốc" để so, "Văn bản" xem từng đoạn gốc–dịch,
 * sao chép bản dịch hoặc chia sẻ ảnh đã dịch.
 */
@Composable
fun CameraTranslateScreen(viewModel: CameraTranslateViewModel, onClose: () -> Unit) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    BackHandler {
        if (state is CameraTranslateViewModel.State.Camera) onClose() else viewModel.reset()
    }
    when (val s = state) {
        is CameraTranslateViewModel.State.Camera -> TranslateCamera(viewModel, onClose)
        is CameraTranslateViewModel.State.Working -> Box(
            modifier = Modifier.fillMaxSize().background(Color.Black),
            contentAlignment = Alignment.Center,
        ) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                CircularProgressIndicator(color = Color.White)
                Spacer(Modifier.height(16.dp))
                Text(s.message, color = Color.White)
            }
        }
        is CameraTranslateViewModel.State.Result -> TranslateResult(s, onRetake = { viewModel.reset() }, onClose = { viewModel.reset(); onClose() })
        is CameraTranslateViewModel.State.Error -> Box(
            modifier = Modifier.fillMaxSize().background(Color.Black).padding(24.dp),
            contentAlignment = Alignment.Center,
        ) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(s.message, color = Color.White)
                Spacer(Modifier.height(16.dp))
                TextButton(onClick = { viewModel.reset() }) { Text("Chụp lại", color = Color(0xFF4FC3F7)) }
            }
        }
    }
}

@Composable
private fun TranslateCamera(viewModel: CameraTranslateViewModel, onClose: () -> Unit) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val live = viewModel.live
    var imageCapture by remember { mutableStateOf<ImageCapture?>(null) }
    var busy by remember { mutableStateOf(false) }
    var liveOn by remember { mutableStateOf(true) }
    var script by remember { mutableStateOf(live.script) }
    val frame by live.frame.collectAsStateWithLifecycle()
    val executor = remember { Executors.newSingleThreadExecutor() }
    val analysisExecutor = remember { Executors.newSingleThreadExecutor() }
    val previewView = remember {
        PreviewView(context).apply {
            implementationMode = PreviewView.ImplementationMode.COMPATIBLE
            scaleType = PreviewView.ScaleType.FIT_CENTER
        }
    }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri != null) viewModel.translateUri(uri)
    }

    DisposableEffect(lifecycleOwner) {
        val future = ProcessCameraProvider.getInstance(context)
        var analysis: ImageAnalysis? = null
        live.enabled = liveOn
        future.addListener({
            val provider = future.get()
            val ratio = AspectRatioStrategy.RATIO_4_3_FALLBACK_AUTO_STRATEGY
            val preview = Preview.Builder()
                .setResolutionSelector(ResolutionSelector.Builder().setAspectRatioStrategy(ratio).build())
                .build().also { it.setSurfaceProvider(previewView.surfaceProvider) }
            val capture = ImageCapture.Builder()
                .setCaptureMode(ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY)
                .setResolutionSelector(
                    ResolutionSelector.Builder()
                        .setAspectRatioStrategy(ratio)
                        .setResolutionStrategy(ResolutionStrategy(AndroidSize(3264, 2448), ResolutionStrategy.FALLBACK_RULE_CLOSEST_HIGHER_THEN_LOWER))
                        .build(),
                )
                .build()
            // Bản 1.1: khung phân tích 4:3 (cùng khung nhìn với preview) cho dịch trực tiếp.
            val an = ImageAnalysis.Builder()
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_RGBA_8888)
                .setResolutionSelector(
                    ResolutionSelector.Builder()
                        .setAspectRatioStrategy(ratio)
                        .setResolutionStrategy(ResolutionStrategy(AndroidSize(1280, 960), ResolutionStrategy.FALLBACK_RULE_CLOSEST_HIGHER_THEN_LOWER))
                        .build(),
                )
                .build()
            an.setAnalyzer(analysisExecutor) { image -> live.analyze(image) }
            try {
                provider.unbindAll()
                try {
                    provider.bindToLifecycle(lifecycleOwner, CameraSelector.DEFAULT_BACK_CAMERA, preview, capture, an)
                    analysis = an
                } catch (e: Exception) {
                    // Máy không chạy được 3 luồng camera cùng lúc → bỏ dịch trực tiếp, vẫn chụp để dịch được.
                    provider.unbindAll()
                    provider.bindToLifecycle(lifecycleOwner, CameraSelector.DEFAULT_BACK_CAMERA, preview, capture)
                    Toast.makeText(context, "Máy không hỗ trợ dịch trực tiếp — bấm chụp để dịch", Toast.LENGTH_LONG).show()
                }
                imageCapture = capture
            } catch (e: Exception) {
                Toast.makeText(context, "Không mở được camera: ${e.message}", Toast.LENGTH_LONG).show()
            }
        }, ContextCompat.getMainExecutor(context))
        onDispose {
            imageCapture = null
            live.enabled = false
            analysis?.clearAnalyzer()
            runCatching { ProcessCameraProvider.getInstance(context).get().unbindAll() }
            executor.shutdown()
            analysisExecutor.shutdown()
        }
    }

    Box(modifier = Modifier.fillMaxSize().background(Color.Black)) {
        AndroidView(factory = { previewView }, modifier = Modifier.fillMaxSize())
        // Lớp phủ bản dịch trực tiếp — cùng phép co giãn FIT_CENTER với PreviewView.
        val f = frame
        if (liveOn && f != null && f.blocks.any { it.translation != null }) {
            Canvas(modifier = Modifier.fillMaxSize()) {
                val scale = minOf(size.width / f.width, size.height / f.height)
                val ox = (size.width - f.width * scale) / 2f
                val oy = (size.height - f.height * scale) / 2f
                drawIntoCanvas { c ->
                    val nc = c.nativeCanvas
                    nc.save()
                    nc.translate(ox, oy)
                    nc.scale(scale, scale)
                    for (b in f.blocks) {
                        val t = b.translation ?: continue
                        PhotoTranslateRenderer.drawBlock(nc, b.block, t, b.colors)
                    }
                    nc.restore()
                }
            }
        }

        Column(
            modifier = Modifier.align(Alignment.TopCenter).fillMaxWidth().padding(top = 12.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IconButton(
                    onClick = onClose,
                    modifier = Modifier.background(Color.Black.copy(alpha = 0.4f), CircleShape),
                ) { Icon(Icons.Filled.Close, contentDescription = "Đóng", tint = Color.White) }
                Spacer(Modifier.weight(1f))
                FilterChip(
                    selected = liveOn,
                    onClick = {
                        liveOn = !liveOn
                        live.enabled = liveOn
                    },
                    label = { Text(if (liveOn) "Dịch trực tiếp: Bật" else "Dịch trực tiếp: Tắt", color = Color.White) },
                )
            }
            Row(
                modifier = Modifier.padding(top = 6.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                for (sc in LiveTranslator.Script.entries) {
                    FilterChip(
                        selected = script == sc,
                        onClick = {
                            script = sc
                            live.script = sc
                        },
                        label = { Text(sc.label, color = Color.White) },
                    )
                }
            }
            val status = when {
                !liveOn -> "Hướng camera vào chữ rồi bấm chụp để dịch"
                f?.status != null -> f.status
                f == null -> "Đang mở dịch trực tiếp…"
                f.blocks.isNotEmpty() && f.blocks.none { it.translation != null } -> "Đang dịch…"
                else -> null
            }
            if (status != null) {
                Text(
                    status,
                    color = Color.White,
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier
                        .padding(top = 8.dp, start = 16.dp, end = 16.dp)
                        .background(Color.Black.copy(alpha = 0.55f), RoundedCornerShape(16.dp))
                        .padding(horizontal = 14.dp, vertical = 6.dp),
                )
            }
        }

        Column(
            modifier = Modifier.align(Alignment.BottomCenter).fillMaxWidth().padding(bottom = 24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(
                "Bấm chụp để dịch online chính xác hơn",
                color = Color.White,
                style = MaterialTheme.typography.labelMedium,
                modifier = Modifier
                    .background(Color.Black.copy(alpha = 0.45f), RoundedCornerShape(12.dp))
                    .padding(horizontal = 10.dp, vertical = 4.dp),
            )
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 32.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                IconButton(
                    onClick = { picker.launch("image/*") },
                    modifier = Modifier.background(Color.Black.copy(alpha = 0.4f), CircleShape),
                ) { Icon(Icons.Filled.PhotoLibrary, contentDescription = "Chọn ảnh", tint = Color.White) }
                Box(
                    modifier = Modifier
                        .size(76.dp)
                        .background(if (busy) Color.Gray else Color(0xFF1E88E5), CircleShape)
                        .clickable(enabled = !busy && imageCapture != null) {
                            val cap = imageCapture ?: return@clickable
                            busy = true
                            live.enabled = false
                            cap.takePicture(executor, object : ImageCapture.OnImageCapturedCallback() {
                                override fun onCaptureSuccess(image: ImageProxy) {
                                    val bmp = try {
                                        ImageProxyUtils.capturedToUprightBitmap(image, CameraTranslateViewModel.MAX_SIDE)
                                    } catch (e: Throwable) {
                                        null
                                    } finally {
                                        image.close()
                                    }
                                    ContextCompat.getMainExecutor(context).execute {
                                        busy = false
                                        if (bmp != null) {
                                            viewModel.translatePhoto(bmp)
                                        } else {
                                            live.enabled = liveOn
                                            Toast.makeText(context, "Không đọc được ảnh chụp", Toast.LENGTH_SHORT).show()
                                        }
                                    }
                                }

                                override fun onError(exception: ImageCaptureException) {
                                    ContextCompat.getMainExecutor(context).execute {
                                        busy = false
                                        live.enabled = liveOn
                                        Toast.makeText(context, "Chụp thất bại: ${exception.message}", Toast.LENGTH_SHORT).show()
                                    }
                                }
                            })
                        },
                    contentAlignment = Alignment.Center,
                ) { Text("Chụp", color = Color.White, style = MaterialTheme.typography.titleMedium) }
                Spacer(Modifier.size(48.dp))
            }
        }
    }
}

@Composable
private fun TranslateResult(result: CameraTranslateViewModel.State.Result, onRetake: () -> Unit, onClose: () -> Unit) {
    val context = LocalContext.current
    var showOriginal by remember { mutableStateOf(false) }
    var showText by remember { mutableStateOf(false) }
    val bmp = if (showOriginal) result.original else result.translated

    Column(modifier = Modifier.fillMaxSize().background(Color.Black)) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = onClose) { Icon(Icons.Filled.Close, contentDescription = "Đóng", tint = Color.White) }
            Text(
                if (result.engineLabel.isNotBlank()) "Dịch bởi ${result.engineLabel}" else "Chụp để dịch",
                color = Color.White,
                style = MaterialTheme.typography.titleSmall,
                modifier = Modifier.weight(1f),
            )
            FilterChip(selected = !showOriginal, onClick = { showOriginal = false }, label = { Text("Bản dịch") })
            Spacer(Modifier.size(6.dp))
            FilterChip(selected = showOriginal, onClick = { showOriginal = true }, label = { Text("Bản gốc") })
        }
        result.notice?.let {
            Text(
                it,
                color = Color(0xFFFFD54F),
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
            )
        }
        Box(modifier = Modifier.weight(1f).fillMaxWidth().padding(8.dp), contentAlignment = Alignment.Center) {
            Image(
                bitmap = bmp.asImageBitmap(),
                contentDescription = if (showOriginal) "Ảnh gốc" else "Ảnh đã dịch",
                modifier = Modifier.aspectRatio(bmp.width.toFloat() / bmp.height.toFloat()),
            )
        }
        Row(
            modifier = Modifier.fillMaxWidth().padding(vertical = 10.dp),
            horizontalArrangement = Arrangement.SpaceEvenly,
        ) {
            ActionButton("Chụp lại", Icons.Filled.Refresh, onRetake)
            ActionButton("Văn bản", Icons.Filled.Subject) { showText = true }
            ActionButton("Sao chép", Icons.Filled.ContentCopy) {
                val text = result.pairs.joinToString("\n\n") { it.second }
                val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                cm.setPrimaryClip(ClipData.newPlainText("Bản dịch ScanX", text))
                Toast.makeText(context, "Đã sao chép bản dịch", Toast.LENGTH_SHORT).show()
            }
            ActionButton("Chia sẻ", Icons.Filled.Share) { shareBitmap(context, result.translated) }
        }
    }

    if (showText) {
        ModalBottomSheet(onDismissRequest = { showText = false }) {
            LazyColumn(modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp).padding(bottom = 24.dp)) {
                if (result.pairs.isEmpty()) item { Text(result.notice ?: "Không có đoạn nào cần dịch") }
                items(result.pairs) { (src, dst) ->
                    Text(src, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text(dst, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.padding(top = 2.dp, bottom = 10.dp))
                    HorizontalDivider()
                    Spacer(Modifier.height(10.dp))
                }
            }
        }
    }
}

@Composable
private fun ActionButton(label: String, icon: androidx.compose.ui.graphics.vector.ImageVector, onClick: () -> Unit) {
    Column(
        modifier = Modifier.clickable(onClick = onClick).padding(8.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Icon(icon, contentDescription = label, tint = Color.White)
        Text(label, color = Color.White, style = MaterialTheme.typography.labelMedium)
    }
}

private fun shareBitmap(context: Context, bmp: Bitmap) {
    runCatching {
        val dir = File(context.cacheDir, "exports").apply { mkdirs() }
        val file = File(dir, "ScanX_dich_${System.currentTimeMillis()}.jpg")
        file.outputStream().use { bmp.compress(Bitmap.CompressFormat.JPEG, 90, it) }
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "image/jpeg"
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        context.startActivity(Intent.createChooser(intent, "Chia sẻ ảnh đã dịch"))
    }.onFailure { Toast.makeText(context, "Không chia sẻ được: ${it.message}", Toast.LENGTH_SHORT).show() }
}
