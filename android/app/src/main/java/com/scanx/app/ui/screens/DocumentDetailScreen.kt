package com.scanx.app.ui.screens

import android.graphics.Bitmap
import android.graphics.Color as AndroidColor
import android.graphics.pdf.PdfRenderer
import android.os.ParcelFileDescriptor
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.FileDownload
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.PictureAsPdf
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Slideshow
import androidx.compose.material.icons.filled.TableChart
import androidx.compose.material.icons.filled.TextSnippet
import androidx.compose.material.icons.filled.Translate
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.scanx.app.R
import com.scanx.app.convert.ExportFormat
import com.scanx.app.convert.TranslationChoice
import com.scanx.app.data.DocumentMeta
import com.scanx.app.data.PdfExportMode
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Switch
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Màn chi tiết tài liệu: mặc định hiển thị ĐÚNG bản PDF (từng trang như bản scan), tab phụ xem văn
 * bản OCR (chọn/copy được). Nút Xuất → chọn định dạng PDF/Word/Excel/PowerPoint/JPG/TXT rồi Chia sẻ
 * hoặc Lưu vào máy.
 */
@Composable
fun DocumentDetailScreen(
    document: DocumentMeta,
    pdfFile: File,
    onBack: () -> Unit,
    onExport: (ExportFormat, PdfExportMode, useCloud: Boolean, share: Boolean) -> Unit,
    onDelete: () -> Unit,
    cloudConfigured: Boolean,
    geminiConfigured: Boolean,
    onOpenCloudSettings: () -> Unit,
    onOpenGeminiSettings: () -> Unit,
    onTranslate: (engine: TranslationChoice, cloudOcr: Boolean, output: ExportFormat, share: Boolean) -> Unit,
) {
    var showTranslate by remember { mutableStateOf(false) }
    val docMode = PdfExportMode.fromCode(document.pdfMode)
    var showDeleteDialog by remember { mutableStateOf(false) }
    var showExportSheet by remember { mutableStateOf(false) }
    var tab by remember { mutableIntStateOf(0) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(document.title, maxLines = 1) },
                navigationIcon = {
                    IconButton(onClick = onBack) { Icon(Icons.Filled.ArrowBack, contentDescription = null) }
                },
                actions = {
                    IconButton(onClick = { onExport(ExportFormat.PDF, docMode, false, true) }) {
                        Icon(Icons.Filled.Share, contentDescription = stringResource(R.string.action_share))
                    }
                    IconButton(onClick = { showTranslate = true }) {
                        Icon(Icons.Filled.Translate, contentDescription = stringResource(R.string.cd_translate))
                    }
                    IconButton(onClick = { showExportSheet = true }) {
                        Icon(Icons.Filled.FileDownload, contentDescription = stringResource(R.string.export_title))
                    }
                    IconButton(onClick = { showDeleteDialog = true }) {
                        Icon(Icons.Filled.Delete, contentDescription = stringResource(R.string.action_delete))
                    }
                }
            )
        }
    ) { padding ->
        Column(modifier = Modifier.fillMaxSize().padding(padding)) {
            TabRow(selectedTabIndex = tab) {
                Tab(selected = tab == 0, onClick = { tab = 0 }, text = { Text(stringResource(R.string.detail_tab_pdf)) })
                Tab(selected = tab == 1, onClick = { tab = 1 }, text = { Text(stringResource(R.string.detail_tab_text)) })
            }
            if (tab == 0) {
                PdfPagesView(pdfFile, Modifier.fillMaxSize())
            } else {
                SelectionContainer {
                    Text(
                        text = document.ocrText.ifBlank { stringResource(R.string.detail_no_text) },
                        modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
                        style = MaterialTheme.typography.bodyLarge,
                    )
                }
            }
        }
    }

    if (showExportSheet) {
        ExportSheet(
            initialMode = docMode,
            cloudConfigured = cloudConfigured,
            onDismiss = { showExportSheet = false },
            onExport = { format, mode, cloud, share -> showExportSheet = false; onExport(format, mode, cloud, share) },
            onOpenCloudSettings = onOpenCloudSettings,
        )
    }

    if (showTranslate) {
        TranslateDialog(
            cloudConfigured = cloudConfigured,
            geminiConfigured = geminiConfigured,
            showShare = true,
            onDismiss = { showTranslate = false },
            onOpenCloudSettings = { showTranslate = false; onOpenCloudSettings() },
            onOpenGeminiSettings = { showTranslate = false; onOpenGeminiSettings() },
            onConfirm = { engine, cloudOcr, output, share -> showTranslate = false; onTranslate(engine, cloudOcr, output, share) },
        )
    }

    if (showDeleteDialog) {
        AlertDialog(
            onDismissRequest = { showDeleteDialog = false },
            title = { Text(stringResource(R.string.action_delete_confirm_title)) },
            text = { Text(stringResource(R.string.action_delete_confirm_body)) },
            confirmButton = {
                TextButton(onClick = { showDeleteDialog = false; onDelete() }) { Text(stringResource(R.string.action_delete)) }
            },
            dismissButton = {
                TextButton(onClick = { showDeleteDialog = false }) { Text(stringResource(R.string.action_cancel)) }
            }
        )
    }
}

/** PdfRenderer không an toàn đa luồng → dựng tuần tự từng trang. */
private val pdfRenderLock = Mutex()

private suspend fun pdfPageCount(file: File): Int = withContext(Dispatchers.IO) {
    pdfRenderLock.withLock {
        runCatching {
            ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY).use { pfd -> PdfRenderer(pfd).use { it.pageCount } }
        }.getOrDefault(0)
    }
}

private suspend fun renderPdfPage(file: File, index: Int, targetWidth: Int): Bitmap? = withContext(Dispatchers.IO) {
    pdfRenderLock.withLock {
        runCatching {
            ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY).use { pfd ->
                PdfRenderer(pfd).use { renderer ->
                    renderer.openPage(index).use { page ->
                        val h = (targetWidth.toLong() * page.height / page.width).toInt().coerceAtLeast(1)
                        val bmp = Bitmap.createBitmap(targetWidth, h, Bitmap.Config.ARGB_8888)
                        bmp.eraseColor(AndroidColor.WHITE)
                        page.render(bmp, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                        bmp
                    }
                }
            }
        }.getOrNull()
    }
}

@Composable
private fun PdfPagesView(file: File, modifier: Modifier) {
    val pageCount by produceState(initialValue = -1, file) { value = pdfPageCount(file) }
    when {
        pageCount < 0 -> Box(modifier, contentAlignment = Alignment.Center) { CircularProgressIndicator() }
        pageCount == 0 -> Box(modifier, contentAlignment = Alignment.Center) { Text(stringResource(R.string.detail_pdf_error)) }
        else -> LazyColumn(
            modifier = modifier.background(Color(0xFFE9ECEF)),
            contentPadding = PaddingValues(12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            items(pageCount) { index -> PdfPage(file, index) }
        }
    }
}

@Composable
private fun PdfPage(file: File, index: Int) {
    val bitmap by produceState<Bitmap?>(initialValue = null, file, index) { value = renderPdfPage(file, index, 1240) }
    Surface(shadowElevation = 2.dp, modifier = Modifier.fillMaxWidth()) {
        val bmp = bitmap
        if (bmp != null) {
            Image(
                bitmap = bmp.asImageBitmap(),
                contentDescription = "Trang ${index + 1}",
                modifier = Modifier.fillMaxWidth().aspectRatio(bmp.width.toFloat() / bmp.height),
            )
        } else {
            Box(Modifier.fillMaxWidth().aspectRatio(0.707f).background(Color.White), contentAlignment = Alignment.Center) {
                CircularProgressIndicator(modifier = Modifier.size(28.dp))
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ExportSheet(
    initialMode: PdfExportMode,
    cloudConfigured: Boolean,
    onDismiss: () -> Unit,
    onExport: (ExportFormat, PdfExportMode, Boolean, Boolean) -> Unit,
    onOpenCloudSettings: () -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    var selected by remember { mutableStateOf(ExportFormat.PDF) }
    var mode by remember { mutableStateOf(initialMode) }
    var useCloud by remember { mutableStateOf(false) }
    val options: List<Triple<ExportFormat, ImageVector, Int>> = listOf(
        Triple(ExportFormat.PDF, Icons.Filled.PictureAsPdf, R.string.export_desc_pdf),
        Triple(ExportFormat.DOCX, Icons.Filled.Description, R.string.export_desc_docx),
        Triple(ExportFormat.XLSX, Icons.Filled.TableChart, R.string.export_desc_xlsx),
        Triple(ExportFormat.PPTX, Icons.Filled.Slideshow, R.string.export_desc_pptx),
        Triple(ExportFormat.JPG, Icons.Filled.Image, R.string.export_desc_jpg),
        Triple(ExportFormat.TXT, Icons.Filled.TextSnippet, R.string.export_desc_txt),
    )
    val isOffice = selected == ExportFormat.DOCX || selected == ExportFormat.XLSX || selected == ExportFormat.PPTX
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState) {
        Column(modifier = Modifier.padding(bottom = 24.dp).verticalScroll(rememberScrollState())) {
            Text(
                stringResource(R.string.export_title),
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp),
            )
            for ((format, icon, desc) in options) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { selected = format }
                        .padding(horizontal = 20.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                    Column(modifier = Modifier.weight(1f).padding(start = 16.dp)) {
                        Text(format.label, style = MaterialTheme.typography.bodyLarge)
                        Text(stringResource(desc), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    if (selected == format) Icon(Icons.Filled.CheckCircle, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                }
            }
            HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))
            if (selected == ExportFormat.PDF || selected == ExportFormat.JPG) {
                // 2 kiểu scan × 2 mức chất lượng.
                Text(
                    stringResource(R.string.export_mode_title),
                    style = MaterialTheme.typography.labelLarge,
                    modifier = Modifier.padding(horizontal = 20.dp, vertical = 4.dp),
                )
                FlowRow(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    for (m in PdfExportMode.values()) {
                        FilterChip(
                            selected = mode == m,
                            onClick = { mode = m },
                            label = { Text("${m.code} · ${m.label}") },
                        )
                    }
                }
                Text(
                    mode.description,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 20.dp, vertical = 4.dp),
                )
            }
            if (isOffice) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { if (cloudConfigured) useCloud = !useCloud else onOpenCloudSettings() }
                        .padding(horizontal = 20.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(Icons.Filled.AutoAwesome, contentDescription = null, tint = MaterialTheme.colorScheme.secondary)
                    Column(modifier = Modifier.weight(1f).padding(start = 16.dp)) {
                        Text(stringResource(R.string.export_cloud_ai), style = MaterialTheme.typography.bodyLarge)
                        Text(
                            stringResource(if (cloudConfigured) R.string.export_cloud_ai_desc else R.string.export_cloud_ai_setup),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    if (cloudConfigured) {
                        Switch(checked = useCloud, onCheckedChange = { useCloud = it })
                    } else {
                        Icon(Icons.Filled.Lock, contentDescription = null, tint = MaterialTheme.colorScheme.outline, modifier = Modifier.size(18.dp))
                    }
                }
            }
            Row(modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 12.dp)) {
                OutlinedButton(onClick = { onExport(selected, mode, useCloud && isOffice, true) }, modifier = Modifier.weight(1f)) {
                    Icon(Icons.Filled.Share, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp))
                    Text(stringResource(R.string.action_share))
                }
                Spacer(Modifier.width(12.dp))
                Button(onClick = { onExport(selected, mode, useCloud && isOffice, false) }, modifier = Modifier.weight(1f)) {
                    Icon(Icons.Filled.FileDownload, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp))
                    Text(stringResource(R.string.export_save))
                }
            }
        }
    }
}
