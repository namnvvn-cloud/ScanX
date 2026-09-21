package com.scanx.app

import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.google.mlkit.vision.documentscanner.GmsDocumentScannerOptions
import com.google.mlkit.vision.documentscanner.GmsDocumentScanning
import com.google.mlkit.vision.documentscanner.GmsDocumentScanningResult
import com.scanx.app.data.DocumentMeta
import com.scanx.app.ui.ScanViewModel
import com.scanx.app.ui.screens.DocumentDetailScreen
import com.scanx.app.ui.screens.HomeScreen
import com.scanx.app.ui.theme.ScanXTheme

class MainActivity : ComponentActivity() {

    private val viewModel: ScanViewModel by viewModels()

    private val scannerOptions = GmsDocumentScannerOptions.Builder()
        .setGalleryImportAllowed(true)
        .setPageLimit(20)
        .setResultFormats(
            GmsDocumentScannerOptions.RESULT_FORMAT_PDF,
        )
        .setScannerMode(GmsDocumentScannerOptions.SCANNER_MODE_FULL)
        .build()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val scannerLauncher = registerForActivityResult(
            ActivityResultContracts.StartIntentSenderForResult()
        ) { activityResult ->
            if (activityResult.resultCode == Activity.RESULT_OK) {
                val result = GmsDocumentScanningResult.fromActivityResultIntent(activityResult.data)
                val pdf = result?.pdf
                if (pdf != null) {
                    val pageUris = result.pages?.map { it.imageUri } ?: emptyList()
                    viewModel.handleScanResult(pdf.uri, pageUris)
                } else {
                    Toast.makeText(this, "Không lấy được kết quả quét", Toast.LENGTH_SHORT).show()
                }
            }
        }

        val cameraPermissionLauncher = registerForActivityResult(
            ActivityResultContracts.RequestPermission()
        ) { granted ->
            if (granted) {
                startScan(scannerLauncher)
            } else {
                Toast.makeText(this, getString(R.string.camera_permission_denied), Toast.LENGTH_LONG).show()
            }
        }

        setContent {
            ScanXTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    var selectedDocument by rememberSaveable { mutableStateOf<String?>(null) }
                    val documents by viewModel.documents.collectAsStateWithLifecycle()
                    val isProcessing by viewModel.isProcessing.collectAsStateWithLifecycle()
                    val errorMessage by viewModel.errorMessage.collectAsStateWithLifecycle()
                    val context = LocalContext.current

                    LaunchedEffect(errorMessage) {
                        errorMessage?.let {
                            Toast.makeText(context, it, Toast.LENGTH_LONG).show()
                            viewModel.clearError()
                        }
                    }

                    val current = documents.find { it.id == selectedDocument }
                    if (current == null) {
                        HomeScreen(
                            documents = documents,
                            isProcessing = isProcessing,
                            onScanClick = {
                                requestCameraThenScan(cameraPermissionLauncher, scannerLauncher)
                            },
                            onDocumentClick = { doc -> selectedDocument = doc.id }
                        )
                    } else {
                        DocumentDetailScreen(
                            document = current,
                            onBack = { selectedDocument = null },
                            onShare = { sharePdf(current) },
                            onDelete = {
                                viewModel.deleteDocument(current.id)
                                selectedDocument = null
                            }
                        )
                    }
                }
            }
        }
    }

    private fun requestCameraThenScan(
        permissionLauncher: androidx.activity.result.ActivityResultLauncher<String>,
        scannerLauncher: androidx.activity.result.ActivityResultLauncher<IntentSenderRequest>,
    ) {
        val granted = ContextCompat.checkSelfPermission(
            this, android.Manifest.permission.CAMERA
        ) == PackageManager.PERMISSION_GRANTED

        if (granted) {
            startScan(scannerLauncher)
        } else {
            permissionLauncher.launch(android.Manifest.permission.CAMERA)
        }
    }

    private fun startScan(
        scannerLauncher: androidx.activity.result.ActivityResultLauncher<IntentSenderRequest>,
    ) {
        val scanner = GmsDocumentScanning.getClient(scannerOptions)
        scanner.getStartScanIntent(this)
            .addOnSuccessListener { intentSender ->
                scannerLauncher.launch(IntentSenderRequest.Builder(intentSender).build())
            }
            .addOnFailureListener { e ->
                Toast.makeText(this, "Không mở được máy quét: ${e.message}", Toast.LENGTH_LONG).show()
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
}
