package com.scanx.app

import android.content.Intent
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
                                    onBack = { screen = Screen.Home },
                                    onShare = { sharePdf(document) },
                                    onDelete = {
                                        viewModel.moveToTrash(document.id)
                                        screen = Screen.Home
                                    }
                                )
                            }
                        }
                    }
                }
            }
        }
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
