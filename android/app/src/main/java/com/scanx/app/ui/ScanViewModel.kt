package com.scanx.app.ui

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import com.scanx.app.data.DocumentMeta
import com.scanx.app.data.DocumentRepository
import com.scanx.app.util.awaitTask
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class ScanViewModel(application: Application) : AndroidViewModel(application) {

    private val repository = DocumentRepository(application)
    private val recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)

    private val _documents = MutableStateFlow<List<DocumentMeta>>(emptyList())
    val documents: StateFlow<List<DocumentMeta>> = _documents.asStateFlow()

    private val _isProcessing = MutableStateFlow(false)
    val isProcessing: StateFlow<Boolean> = _isProcessing.asStateFlow()

    private val _errorMessage = MutableStateFlow<String?>(null)
    val errorMessage: StateFlow<String?> = _errorMessage.asStateFlow()

    init {
        refresh()
    }

    fun refresh() {
        _documents.value = repository.listDocuments()
    }

    fun clearError() {
        _errorMessage.value = null
    }

    /**
     * Nhận kết quả từ ML Kit Document Scanner: chạy OCR trên từng trang, ghép text,
     * rồi lưu PDF + metadata vào bộ nhớ trong. Chạy trên IO dispatcher để không chặn UI.
     */
    fun handleScanResult(pdfUri: Uri, pageImageUris: List<Uri>) {
        viewModelScope.launch {
            _isProcessing.value = true
            try {
                val ocrText = withContext(Dispatchers.Default) {
                    recognizeTextFromPages(pageImageUris)
                }
                withContext(Dispatchers.IO) {
                    repository.saveDocument(
                        pdfUri = pdfUri,
                        pageCount = pageImageUris.size,
                        ocrText = ocrText
                    )
                }
                refresh()
            } catch (e: Exception) {
                _errorMessage.value = "Không lưu được tài liệu: ${e.message}"
            } finally {
                _isProcessing.value = false
            }
        }
    }

    private suspend fun recognizeTextFromPages(pageImageUris: List<Uri>): String {
        val context = getApplication<Application>()
        val builder = StringBuilder()
        for ((index, uri) in pageImageUris.withIndex()) {
            try {
                val image = InputImage.fromFilePath(context, uri)
                val result = recognizer.process(image).awaitTask()
                if (result.text.isNotBlank()) {
                    if (pageImageUris.size > 1) {
                        builder.append("--- Trang ${index + 1} ---\n")
                    }
                    builder.append(result.text).append("\n\n")
                }
            } catch (e: Exception) {
                // Bỏ qua lỗi OCR của 1 trang, không chặn việc lưu cả tài liệu.
            }
        }
        return builder.toString().trim()
    }

    fun deleteDocument(id: String) {
        repository.deleteDocument(id)
        refresh()
    }

    fun renameDocument(id: String, newTitle: String) {
        if (newTitle.isBlank()) return
        repository.renameDocument(id, newTitle)
        refresh()
    }

    fun getPdfFile(id: String) = repository.getPdfFile(id)

    override fun onCleared() {
        super.onCleared()
        recognizer.close()
    }
}
