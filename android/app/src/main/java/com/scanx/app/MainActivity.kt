package com.scanx.app

import android.app.Activity
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import androidx.activity.result.contract.ActivityResultContract
import androidx.compose.foundation.background
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
import com.scanx.app.ui.ScanCameraViewModel
import com.scanx.app.ui.ScanViewModel
import com.scanx.app.ui.screens.AdvancedSettingsScreen
import com.scanx.app.ui.screens.DocumentDetailScreen
import com.scanx.app.ui.screens.HomeScreen
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
    data object ScanningSettings : Screen()
    data object AdvancedSettings : Screen()
    data object Trash : Screen()
    data class Detail(val documentId: String) : Screen()
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

                    val cameraPermissionLauncher = rememberLauncherForActivityResult(
                        ActivityResultContracts.RequestPermission()
                    ) { granted ->
                        if (granted) {
                            screen = Screen.Camera
                        } else {
                            Toast.makeText(context, getString(R.string.camera_permission_denied), Toast.LENGTH_LONG).show()
                        }
                    }

                    fun requestCameraThenOpen() {
                        val granted = ContextCompat.checkSelfPermission(
                            context, android.Manifest.permission.CAMERA
                        ) == PackageManager.PERMISSION_GRANTED
                        if (granted) screen = Screen.Camera else cameraPermissionLauncher.launch(android.Manifest.permission.CAMERA)
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
                                onCameraClick = { requestCameraThenOpen() },
                                onComingSoon = { showComingSoon() },
                                onConvertFiles = { convertPicker.launch(arrayOf("application/pdf", "image/*")) },
                            )
                        }

                        is Screen.Camera -> {
                            val cameraViewModel: ScanCameraViewModel = viewModel()
                            ScanCameraScreen(
                                viewModel = cameraViewModel,
                                onClose = { screen = Screen.Home },
                                onDone = {
                                    val pages = cameraViewModel.takeSessionPages()
                                    if (pages.isNotEmpty()) {
                                        viewModel.saveScannedPages(pages)
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
                                onRecommendApp = { shareApp() },
                                onComingSoon = { showComingSoon() },
                            )
                        }

                        is Screen.ScanningSettings -> {
                            var captureMode by remember { mutableStateOf(prefs.captureMode) }
                            var stableFrames by remember { mutableStateOf(prefs.autoCaptureStableFrames) }
                            var flashDefault by remember { mutableStateOf(prefs.flashEnabled) }
                            ScanningSettingsScreen(
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
                            if (document == null) {
                                LaunchedEffect(current.documentId) { screen = Screen.Home }
                            } else {
                                DocumentDetailScreen(
                                    document = document,
                                    pdfFile = viewModel.getPdfFile(document.id),
                                    onBack = { screen = Screen.Home },
                                    onExport = { format, share ->
                                        viewModel.exportDocument(document.id, format) { files -> deliver(files, format, share) }
                                    },
                                    onComingSoon = { showComingSoon() },
                                    onDelete = {
                                        viewModel.moveToTrash(document.id)
                                        screen = Screen.Home
                                    }
                                )
                            }
                        }
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
                                            viewModel.convertFiles(uris, f) { file -> convertedFile = file to f }
                                        }) { Text(f.label) }
                                    }
                                }
                            },
                            confirmButton = {},
                            dismissButton = { TextButton(onClick = { convertUris = emptyList() }) { Text(getString(R.string.action_cancel)) } },
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
