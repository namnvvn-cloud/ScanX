package com.scanx.app

import android.app.Activity
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import androidx.activity.result.contract.ActivityResultContract
import androidx.activity.result.IntentSenderRequest
import com.google.mlkit.vision.documentscanner.GmsDocumentScanner
import com.google.mlkit.vision.documentscanner.GmsDocumentScannerOptions
import com.google.mlkit.vision.documentscanner.GmsDocumentScanning
import com.google.mlkit.vision.documentscanner.GmsDocumentScanningResult
import com.scanx.app.data.ScanEngine
import com.scanx.app.data.CaptureMode
import com.scanx.app.ui.screens.PageEditTool
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Row
import androidx.compose.material3.Checkbox
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.ui.Alignment
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.scanx.app.convert.ExportFormat
import java.io.File
import android.content.pm.PackageManager
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.scanx.app.data.DocumentMeta
import com.scanx.app.ui.AuthViewModel
import com.scanx.app.ui.ScanCameraViewModel
import com.scanx.app.ui.ScanViewModel
import com.scanx.app.ui.screens.AccountScreen
import com.scanx.app.ui.screens.AdvancedSettingsScreen
import com.scanx.app.convert.TranslationChoice
import com.scanx.app.ui.screens.CloudSettingsDialog
import com.scanx.app.ui.screens.GeminiSettingsDialog
import com.scanx.app.ui.screens.GoogleTranslateSettingsDialog
import com.scanx.app.ui.screens.TranslateDialog
import com.scanx.app.ui.screens.DocumentDetailScreen
import com.scanx.app.ui.screens.HomeScreen
import com.scanx.app.ui.screens.PageEditOverlayForDocument
import com.scanx.app.ui.screens.ScanCameraScreen
import com.scanx.app.ui.screens.ScanningSettingsScreen
import com.scanx.app.ui.screens.SettingsScreen
import com.scanx.app.ui.screens.TrashScreen
import com.scanx.app.ui.theme.ScanXTheme

/** Các màn hình điều hướng trong app — dùng state Compose đơn giản, không thêm thư viện Navigation. */
private sealed class Screen {
    data object Home : Screen()
    data object Camera : Screen()
    data object Settings : Screen()
    data object Account : Screen()
    data object ScanningSettings : Screen()
    data object AdvancedSettings : Screen()
    data object Trash : Screen()
    data class Detail(val documentId: String) : Screen()
    /** Bản 1.0: "Chụp để dịch" (kiểu Google Dịch). */
    data object Translate : Screen()
}

/** Hộp thoại "Lưu vào máy" (Storage Access Framework) với MIME + tên file chọn lúc chạy. */
private class CreateDocumentContract : ActivityResultContract<Pair<String, String>, Uri?>() {
    override fun createIntent(context: Context, input: Pair<String, String>): Intent =
        Intent(Intent.ACTION_CREATE_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType(input.second).putExtra(Intent.EXTRA_TITLE, input.first)

    override fun parseResult(resultCode: Int, intent: Intent?): Uri? = if (resultCode == Activity.RESULT_OK) intent?.data else null
}

class MainActivity : ComponentActivity() {

    private val viewModel: ScanViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        setContent {
            ScanXTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    val context = LocalContext.current
                    // Screen không phải Parcelable/Serializable nên dùng remember thường (không rememberSaveable)
                    // để tránh crash khi hệ thống cố lưu instance state lúc app vào nền.
                    var screen by remember { mutableStateOf<Screen>(Screen.Home) }

                    val documents by viewModel.documents.collectAsStateWithLifecycle()
                    val folders by viewModel.folders.collectAsStateWithLifecycle()
                    val currentFolderId by viewModel.currentFolderId.collectAsStateWithLifecycle()
                    val searchQuery by viewModel.searchQuery.collectAsStateWithLifecycle()
                    val sortOrder by viewModel.sortOrder.collectAsStateWithLifecycle()
                    val viewMode by viewModel.viewMode.collectAsStateWithLifecycle()
                    val trashedDocuments by viewModel.trashedDocuments.collectAsStateWithLifecycle()
                    val isProcessing by viewModel.isProcessing.collectAsStateWithLifecycle()
                    val errorMessage by viewModel.errorMessage.collectAsStateWithLifecycle()
                    val exportStatus by viewModel.exportStatus.collectAsStateWithLifecycle()
                    var pendingSave by remember { mutableStateOf<File?>(null) }
                    var convertedFile by remember { mutableStateOf<Pair<File, ExportFormat>?>(null) }
                    var convertUris by remember { mutableStateOf<List<Uri>>(emptyList()) }
                    var showCloudSettings by remember { mutableStateOf(false) }
                    var cloudConfigured by remember { mutableStateOf(viewModel.isCloudConfigured) }
                    var showGeminiSettings by remember { mutableStateOf(false) }
                    // Bản 1.0: Google Dịch (Cloud Translation) — API key riêng.
                    var showGoogleTranslateSettings by remember { mutableStateOf(false) }
                    var googleTranslateConfigured by remember { mutableStateOf(com.scanx.app.data.AppPreferences(context).googleTranslateKey.isNotBlank()) }
                    var geminiConfigured by remember { mutableStateOf(viewModel.isGeminiConfigured) }
                    var convertWithCloud by remember { mutableStateOf(false) }
                    var translateUris by remember { mutableStateOf<List<Uri>>(emptyList()) }
                    val translatePicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
                        if (!uris.isNullOrEmpty()) translateUris = uris
                    }
                    // Bản 0.8: đang mở màn "Chỉnh sửa trang" cho trang nào của tài liệu đã lưu (Screen.Detail);
                    // bản 0.9: kèm công cụ mở sẵn (null = thanh 3 công cụ).
                    var editingDetailPage by remember { mutableStateOf<Int?>(null) }
                    var editingDetailTool by remember { mutableStateOf<PageEditTool?>(null) }
                    LaunchedEffect(screen) { editingDetailPage = null; editingDetailTool = null }
                    val prefs = remember { com.scanx.app.data.AppPreferences(context) }

                    LaunchedEffect(errorMessage) {
                        errorMessage?.let {
                            Toast.makeText(context, it, Toast.LENGTH_LONG).show()
                            viewModel.clearError()
                        }
                    }

                    LaunchedEffect(screen) {
                        if (screen is Screen.Trash) viewModel.refreshTrash()
                    }

                    fun showComingSoon() {
                        Toast.makeText(context, getString(R.string.coming_soon_toast), Toast.LENGTH_SHORT).show()
                    }

                    val saveLauncher = rememberLauncherForActivityResult(CreateDocumentContract()) { uri ->
                        val file = pendingSave
                        pendingSave = null
                        if (uri != null && file != null) {
                            runCatching {
                                contentResolver.openOutputStream(uri)?.use { out -> file.inputStream().use { it.copyTo(out) } }
                            }.onSuccess {
                                Toast.makeText(context, getString(R.string.export_saved, file.name), Toast.LENGTH_SHORT).show()
                            }.onFailure {
                                Toast.makeText(context, getString(R.string.export_save_failed, it.message ?: ""), Toast.LENGTH_LONG).show()
                            }
                        }
                    }

                    fun deliver(files: List<File>, format: ExportFormat, share: Boolean) {
                        if (files.isEmpty()) return
                        when {
                            share -> shareFiles(files, format.mime)
                            files.size == 1 -> { pendingSave = files[0]; saveLauncher.launch(files[0].name to format.mime) }
                            else -> saveImagesToGallery(files)
                        }
                    }

                    val convertPicker = rememberLauncherForActivityResult(
                        ActivityResultContracts.OpenMultipleDocuments()
                    ) { uris -> if (uris.isNotEmpty()) convertUris = uris }

                    val importLauncher = rememberLauncherForActivityResult(
                        ActivityResultContracts.GetMultipleContents()
                    ) { uris ->
                        if (uris.isNotEmpty()) viewModel.importImages(uris)
                    }

                    // Bản 1.0: màn cần camera sẽ mở sau khi cấp quyền (camera quét hoặc chụp để dịch).
                    var afterCameraPermission by remember { mutableStateOf<Screen>(Screen.Camera) }
                    // Chế độ chụp áp cho camera ScanX lúc mở (Scan tự động / Scan thủ công).
                    var pendingCaptureMode by remember { mutableStateOf(prefs.captureMode) }
                    val cameraPermissionLauncher = rememberLauncherForActivityResult(
                        ActivityResultContracts.RequestPermission()
                    ) { granted ->
                        if (granted) {
                            screen = afterCameraPermission
                        } else {
                            Toast.makeText(context, getString(R.string.camera_permission_denied), Toast.LENGTH_LONG).show()
                        }
                    }

                    fun requestCameraThenOpen(target: Screen = Screen.Camera) {
                        val granted = ContextCompat.checkSelfPermission(
                            context, android.Manifest.permission.CAMERA
                        ) == PackageManager.PERMISSION_GRANTED
                        afterCameraPermission = target
                        if (granted) screen = target else cameraPermissionLauncher.launch(android.Manifest.permission.CAMERA)
                    }

                    // Bản 1.3 (quyết định anh Nam): BỎ vòng lặp tự mở lại camera của bản 1.2 — chỉ dùng đúng
                    // tính năng GỐC của Google: dấu "+" trên màn xem trước để thêm trang, ngay trong 1 lần mở
                    // camera. Gọi bộ quét Google đúng 1 LẦN mỗi phiên; người dùng tự thêm bao nhiêu trang tuỳ
                    // ý bằng "+" của Google, bấm Lưu/Xong bên Google mới trả hết kết quả về đây. Không cần nút
                    // "Hoàn thành" riêng trong ScanX: quét xong tự lưu và mở thẳng màn Chi tiết tài liệu (đã có
                    // sẵn Bộ lọc/Cắt xoay/Làm sạch + Xuất file). Xem mục 000000 trong tài liệu kiến trúc project
                    // để biết lý do bỏ và cách khôi phục lại vòng lặp bản 1.2 nếu sau này cần.
                    val googleScanLauncher = rememberLauncherForActivityResult(
                        ActivityResultContracts.StartIntentSenderForResult()
                    ) { result ->
                        val scan = if (result.resultCode == Activity.RESULT_OK) {
                            GmsDocumentScanningResult.fromActivityResultIntent(result.data)
                        } else {
                            null
                        }
                        val uris = scan?.pages?.mapNotNull { it.imageUri }.orEmpty()
                        if (uris.isNotEmpty()) {
                            viewModel.saveGoogleScan(uris) { id -> if (screen is Screen.Home) screen = Screen.Detail(id) }
                        }
                    }

                    // Bản 1.1 (quyết định của anh Nam): Scan tự động / Scan thủ công dùng BỘ QUÉT GOOGLE — không
                    // dùng thuật toán bắt khung tự viết. Camera ScanX chỉ còn khi chọn trong Cài đặt quét.
                    fun launchGoogleScanner(mode: CaptureMode) {
                        val options = GmsDocumentScannerOptions.Builder()
                            .setScannerMode(GmsDocumentScannerOptions.SCANNER_MODE_FULL)
                            .setResultFormats(GmsDocumentScannerOptions.RESULT_FORMAT_JPEG)
                            .setGalleryImportAllowed(true)
                            .build()
                        val scanner: GmsDocumentScanner = GmsDocumentScanning.getClient(options)
                        scanner.getStartScanIntent(this@MainActivity)
                            .addOnSuccessListener { sender ->
                                googleScanLauncher.launch(IntentSenderRequest.Builder(sender).build())
                            }
                            .addOnFailureListener { e ->
                                // Máy không có Google Play services / RAM < 1,7 GB / chưa tải được module → camera ScanX.
                                Toast.makeText(
                                    context,
                                    getString(R.string.scan_google_unavailable, e.message ?: ""),
                                    Toast.LENGTH_LONG,
                                ).show()
                                pendingCaptureMode = mode
                                requestCameraThenOpen()
                            }
                    }

                    fun startScan(mode: CaptureMode = prefs.captureMode) {
                        if (prefs.scanEngine == ScanEngine.SCANX) {
                            pendingCaptureMode = mode
                            requestCameraThenOpen(Screen.Camera)
                            return
                        }
                        if (mode == CaptureMode.MANUAL) {
                            Toast.makeText(context, getString(R.string.scan_google_manual_hint), Toast.LENGTH_LONG).show()
                        }
                        launchGoogleScanner(mode)
                    }

                    // Bản 1.0: nút Back của Android quay về màn trước thay vì thoát app (camera quét và
                    // chụp để dịch tự xử lý Back để không mất trang đã chụp/ảnh đã dịch).
                    BackHandler(enabled = screen !is Screen.Home && screen !is Screen.Camera && screen !is Screen.Translate) {
                        when {
                            editingDetailPage != null -> { editingDetailPage = null; editingDetailTool = null }
                            screen is Screen.ScanningSettings || screen is Screen.AdvancedSettings || screen is Screen.Account -> screen = Screen.Settings
                            else -> screen = Screen.Home
                        }
                    }

                    when (val current = screen) {
                        is Screen.Home -> {
                            HomeScreen(
                                documents = documents,
                                folders = folders,
                                currentFolderId = currentFolderId,
                                searchQuery = searchQuery,
                                sortOrder = sortOrder,
                                viewMode = viewMode,
                                isProcessing = isProcessing,
                                getThumbnailFile = { id -> viewModel.getThumbnailFile(id) },
                                onSearchQueryChange = viewModel::setSearchQuery,
                                onOpenFolder = viewModel::openFolder,
                                onCreateFolder = viewModel::createFolder,
                                onDeleteFolder = viewModel::deleteFolder,
                                onSortOrderChange = viewModel::setSortOrder,
                                onViewModeChange = viewModel::setViewMode,
                                onDocumentClick = { doc -> screen = Screen.Detail(doc.id) },
                                onMoveDocumentsToFolder = { ids, folderId ->
                                    ids.forEach { id -> viewModel.moveToFolder(id, folderId) }
                                },
                                onDeleteDocuments = { ids ->
                                    ids.forEach { id -> viewModel.moveToTrash(id) }
                                },
                                onSettingsClick = { screen = Screen.Settings },
                                onTrashClick = { screen = Screen.Trash },
                                onImportFilesClick = { importLauncher.launch("image/*") },
                                onScanAuto = { startScan(CaptureMode.AUTO) },
                                onScanManual = { startScan(CaptureMode.MANUAL) },
                                onCameraTranslate = { requestCameraThenOpen(Screen.Translate) },
                                onComingSoon = { showComingSoon() },
                                onConvertFiles = { convertPicker.launch(arrayOf("application/pdf", "image/*")) },
                                onTranslateFiles = { translatePicker.launch(arrayOf("application/pdf", "image/*")) },
                            )
                        }

                        is Screen.Translate -> {
                            val translateViewModel: com.scanx.app.ui.CameraTranslateViewModel = viewModel()
                            com.scanx.app.ui.screens.CameraTranslateScreen(
                                viewModel = translateViewModel,
                                onClose = { screen = Screen.Home },
                            )
                        }

                        is Screen.Camera -> {
                            val cameraViewModel: ScanCameraViewModel = viewModel()
                            // Áp chế độ Tự động/Thủ công người dùng vừa chọn ở menu camera (ViewModel sống theo
                            // Activity nên không tự đọc lại Cài đặt mỗi lần mở).
                            LaunchedEffect(cameraViewModel, pendingCaptureMode) { cameraViewModel.setCaptureMode(pendingCaptureMode) }
                            ScanCameraScreen(
                                viewModel = cameraViewModel,
                                onClose = { screen = Screen.Home },
                                onDone = {
                                    val pages = cameraViewModel.takeSessionPages()
                                    if (pages.isNotEmpty()) {
                                        // Bản 0.9: lưu xong mở thẳng màn tài liệu (thanh Bộ lọc/Cắt xoay/Làm sạch).
                                        viewModel.saveScannedPages(pages) { id -> if (screen is Screen.Home) screen = Screen.Detail(id) }
                                    }
                                    screen = Screen.Home
                                },
                            )
                        }

                        is Screen.Settings -> {
                            SettingsScreen(
                                autoOcrEnabled = prefs.autoOcrEnabled,
                                onAutoOcrChange = { enabled -> prefs.autoOcrEnabled = enabled },
                                onBack = { screen = Screen.Home },
                                onScanningClick = { screen = Screen.ScanningSettings },
                                onAdvancedClick = { screen = Screen.AdvancedSettings },
                                onAccountClick = { screen = Screen.Account },
                                cloudConfigured = cloudConfigured,
                                onCloudAiClick = { showCloudSettings = true },
                                geminiConfigured = geminiConfigured,
                                onGeminiClick = { showGeminiSettings = true },
                                googleTranslateConfigured = googleTranslateConfigured,
                                onGoogleTranslateClick = { showGoogleTranslateSettings = true },
                                onRecommendApp = { shareApp() },
                                onComingSoon = { showComingSoon() },
                            )
                        }

                        is Screen.Account -> {
                            val authViewModel: AuthViewModel = viewModel()
                            AccountScreen(
                                viewModel = authViewModel,
                                documents = documents,
                                getPdfFile = { id -> viewModel.getPdfFile(id) },
                                onBack = { screen = Screen.Settings },
                            )
                        }

                        is Screen.ScanningSettings -> {
                            var scanEngine by remember { mutableStateOf(prefs.scanEngine) }
                            var captureMode by remember { mutableStateOf(prefs.captureMode) }
                            var stableFrames by remember { mutableStateOf(prefs.autoCaptureStableFrames) }
                            var flashDefault by remember { mutableStateOf(prefs.flashEnabled) }
                            ScanningSettingsScreen(
                                scanEngine = scanEngine,
                                onScanEngineChange = { e -> scanEngine = e; prefs.scanEngine = e },
                                captureMode = captureMode,
                                autoCaptureStableFrames = stableFrames,
                                flashDefault = flashDefault,
                                onBack = { screen = Screen.Settings },
                                onCaptureModeChange = { mode -> captureMode = mode; prefs.captureMode = mode },
                                onStableFramesChange = { value -> stableFrames = value; prefs.autoCaptureStableFrames = value },
                                onFlashDefaultChange = { value -> flashDefault = value; prefs.flashEnabled = value },
                            )
                        }

                        is Screen.AdvancedSettings -> {
                            AdvancedSettingsScreen(
                                viewMode = viewMode,
                                sortOrder = sortOrder,
                                onBack = { screen = Screen.Settings },
                                onViewModeChange = viewModel::setViewMode,
                                onSortOrderChange = viewModel::setSortOrder,
                                onClearCache = {
                                    runCatching { context.cacheDir.deleteRecursively() }
                                    Toast.makeText(context, getString(R.string.advanced_clear_cache), Toast.LENGTH_SHORT).show()
                                },
                            )
                        }

                        is Screen.Trash -> {
                            TrashScreen(
                                trashedDocuments = trashedDocuments,
                                getThumbnailFile = { id -> viewModel.getThumbnailFile(id) },
                                onBack = { screen = Screen.Home },
                                onRestore = { id -> viewModel.restoreFromTrash(id) },
                                onDeleteForever = { id -> viewModel.deleteDocumentPermanently(id) },
                                onEmptyTrash = { viewModel.emptyTrash() },
                            )
                        }

                        is Screen.Detail -> {
                            val document = documents.find { it.id == current.documentId }
                                ?: trashedDocuments.find { it.id == current.documentId }
                                ?: viewModel.documentById(current.documentId)
                            if (document == null) {
                                LaunchedEffect(current.documentId) { screen = Screen.Home }
                            } else {
                                DocumentDetailScreen(
                                    document = document,
                                    pdfFile = viewModel.getPdfFile(document.id),
                                    onBack = { screen = Screen.Home },
                                    onExport = { format, mode, useCloud, share ->
                                        viewModel.exportDocument(document.id, format, mode, useCloud) { files -> deliver(files, format, share) }
                                    },
                                    cloudConfigured = cloudConfigured,
                                    geminiConfigured = geminiConfigured,
                                    onOpenCloudSettings = { showCloudSettings = true },
                                    onOpenGeminiSettings = { showGeminiSettings = true },
                                    onTranslate = { engine, cloudOcr, bilingual, output, share ->
                                        viewModel.translateDocument(document.id, engine, cloudOcr, bilingual, output) { file -> deliver(listOf(file), output, share) }
                                    },
                                    onDelete = {
                                        viewModel.moveToTrash(document.id)
                                        screen = Screen.Home
                                    },
                                    onEditPage = { pageIndex, tool -> editingDetailTool = tool; editingDetailPage = pageIndex },
                                )
                            }
                        }
                    }

                    // Bản 0.8: màn "Chỉnh sửa trang" (Bộ lọc/Cắt xoay/Làm sạch) cho 1 trang của tài liệu đã lưu.
                    val editingDoc = (screen as? Screen.Detail)?.documentId
                    val editingPage = editingDetailPage
                    if (editingDoc != null && editingPage != null) {
                        PageEditOverlayForDocument(
                            viewModel = viewModel,
                            documentId = editingDoc,
                            pageIndex = editingPage,
                            initialTool = editingDetailTool,
                            onClose = { editingDetailPage = null; editingDetailTool = null },
                        )
                    }

                    // Chọn định dạng đích sau khi chọn file cần chuyển đổi.
                    if (convertUris.isNotEmpty()) {
                        AlertDialog(
                            onDismissRequest = { convertUris = emptyList() },
                            title = { Text(getString(R.string.convert_choose_format)) },
                            text = {
                                Column {
                                    listOf(ExportFormat.DOCX, ExportFormat.XLSX, ExportFormat.PPTX).forEach { f ->
                                        TextButton(onClick = {
                                            val uris = convertUris
                                            convertUris = emptyList()
                                            viewModel.convertFiles(uris, f, convertWithCloud && cloudConfigured) { file -> convertedFile = file to f }
                                        }) { Text(f.label) }
                                    }
                                    Row(
                                        verticalAlignment = Alignment.CenterVertically,
                                        modifier = Modifier.clickable { if (cloudConfigured) convertWithCloud = !convertWithCloud else showCloudSettings = true },
                                    ) {
                                        Checkbox(checked = convertWithCloud && cloudConfigured, onCheckedChange = { if (cloudConfigured) convertWithCloud = it else showCloudSettings = true })
                                        Text(getString(R.string.convert_use_cloud))
                                    }
                                }
                            },
                            confirmButton = {},
                            dismissButton = { TextButton(onClick = { convertUris = emptyList() }) { Text(getString(R.string.action_cancel)) } },
                        )
                    }

                    // Dịch file import: chọn máy dịch / định dạng sau khi chọn file.
                    if (translateUris.isNotEmpty()) {
                        TranslateDialog(
                            cloudConfigured = cloudConfigured,
                            geminiConfigured = geminiConfigured,
                            showShare = false,
                            onDismiss = { translateUris = emptyList() },
                            onOpenCloudSettings = { showCloudSettings = true },
                            onOpenGeminiSettings = { showGeminiSettings = true },
                            onConfirm = { engine, cloudOcr, bilingual, output, _ ->
                                val uris = translateUris
                                translateUris = emptyList()
                                viewModel.translateFiles(uris, engine, cloudOcr, bilingual, output) { file -> convertedFile = file to output }
                            },
                        )
                    }

                    if (showCloudSettings) {
                        CloudSettingsDialog(
                            initialKey = viewModel.cloudApiKey(),
                            initialModel = viewModel.cloudModel(),
                            onDismiss = { showCloudSettings = false },
                            onSave = { key, model ->
                                viewModel.saveCloudSettings(key, model)
                                cloudConfigured = key.isNotBlank()
                                showCloudSettings = false
                            },
                        )
                    }

                    if (showGoogleTranslateSettings) {
                        GoogleTranslateSettingsDialog(
                            initialKey = prefs.googleTranslateKey,
                            onDismiss = { showGoogleTranslateSettings = false },
                            onSave = { key ->
                                prefs.googleTranslateKey = key
                                googleTranslateConfigured = key.isNotBlank()
                                showGoogleTranslateSettings = false
                            },
                        )
                    }

                    if (showGeminiSettings) {
                        GeminiSettingsDialog(
                            initialKey = viewModel.geminiApiKey(),
                            initialModel = viewModel.geminiModel(),
                            onDismiss = { showGeminiSettings = false },
                            onSave = { key, model ->
                                viewModel.saveGeminiSettings(key, model)
                                geminiConfigured = key.isNotBlank()
                                showGeminiSettings = false
                            },
                        )
                    }

                    convertedFile?.let { (file, format) ->
                        AlertDialog(
                            onDismissRequest = { convertedFile = null },
                            title = { Text(getString(R.string.convert_done_title)) },
                            text = { Text(file.name) },
                            confirmButton = {
                                TextButton(onClick = { convertedFile = null; deliver(listOf(file), format, share = false) }) { Text(getString(R.string.export_save)) }
                            },
                            dismissButton = {
                                TextButton(onClick = { convertedFile = null; deliver(listOf(file), format, share = true) }) { Text(getString(R.string.action_share)) }
                            },
                        )
                    }

                    exportStatus?.let { status ->
                        Box(
                            modifier = Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.55f)),
                            contentAlignment = Alignment.Center,
                        ) {
                            Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(16.dp)) {
                                CircularProgressIndicator(color = Color.White)
                                Text(status, color = Color.White, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.padding(horizontal = 32.dp))
                            }
                        }
                    }
                }
            }
        }
    }

    private fun shareFiles(files: List<File>, mime: String) {
        val uris = ArrayList(files.map { FileProvider.getUriForFile(this, "$packageName.fileprovider", it) })
        val intent = if (uris.size == 1) {
            Intent(Intent.ACTION_SEND).apply { type = mime; putExtra(Intent.EXTRA_STREAM, uris[0]) }
        } else {
            Intent(Intent.ACTION_SEND_MULTIPLE).apply { type = mime; putParcelableArrayListExtra(Intent.EXTRA_STREAM, uris) }
        }
        intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        startActivity(Intent.createChooser(intent, getString(R.string.action_share)))
    }

    /** Nhiều ảnh JPG → lưu thẳng vào thư viện ảnh (Pictures/ScanX); Android < 10 thì chuyển sang chia sẻ. */
    private fun saveImagesToGallery(files: List<File>) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
            shareFiles(files, "image/jpeg")
            return
        }
        var saved = 0
        for (f in files) {
            val values = ContentValues().apply {
                put(MediaStore.Images.Media.DISPLAY_NAME, f.name)
                put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
                put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/ScanX")
            }
            val uri = contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values) ?: continue
            runCatching { contentResolver.openOutputStream(uri)?.use { out -> f.inputStream().use { it.copyTo(out) } } }
                .onSuccess { saved++ }
        }
        Toast.makeText(this, getString(R.string.export_saved_gallery, saved), Toast.LENGTH_LONG).show()
    }

    private fun sharePdf(document: DocumentMeta) {
        val file = viewModel.getPdfFile(document.id)
        if (!file.exists()) {
            Toast.makeText(this, "Không tìm thấy file PDF", Toast.LENGTH_SHORT).show()
            return
        }
        val uri = FileProvider.getUriForFile(this, "$packageName.fileprovider", file)
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "application/pdf"
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        startActivity(Intent.createChooser(intent, getString(R.string.action_share)))
    }

    private fun shareApp() {
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_TEXT, "Mình đang dùng ScanX để quét tài liệu, bạn thử xem nhé!")
        }
        startActivity(Intent.createChooser(intent, getString(R.string.settings_recommend_app)))
    }
}
