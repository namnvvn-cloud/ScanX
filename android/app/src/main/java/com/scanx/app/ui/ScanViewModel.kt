package com.scanx.app.ui

import android.app.Application
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import com.scanx.app.convert.ExportFormat
import com.scanx.app.convert.ExportManager
import com.scanx.app.data.AppPreferences
import com.scanx.app.data.DocumentMeta
import com.scanx.app.data.DocumentRepository
import com.scanx.app.data.FolderMeta
import com.scanx.app.data.SortOrder
import com.scanx.app.data.TextLayerLine
import com.scanx.app.data.ViewMode
import com.scanx.app.util.awaitTask
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.Locale

class ScanViewModel(application: Application) : AndroidViewModel(application) {

    private val repository = DocumentRepository(application)
    private val prefs = AppPreferences(application)
    private val recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)

    private val _allDocuments = MutableStateFlow<List<DocumentMeta>>(emptyList())
    private val _folders = MutableStateFlow<List<FolderMeta>>(emptyList())
    val folders: StateFlow<List<FolderMeta>> = _folders.asStateFlow()

    private val _searchQuery = MutableStateFlow("")
    val searchQuery: StateFlow<String> = _searchQuery.asStateFlow()

    private val _sortOrder = MutableStateFlow(prefs.sortOrder)
    val sortOrder: StateFlow<SortOrder> = _sortOrder.asStateFlow()

    private val _viewMode = MutableStateFlow(prefs.viewMode)
    val viewMode: StateFlow<ViewMode> = _viewMode.asStateFlow()

    /** null = đang xem toàn bộ tài liệu (chưa vào 1 folder cụ thể nào). */
    private val _currentFolderId = MutableStateFlow<String?>(null)
    val currentFolderId: StateFlow<String?> = _currentFolderId.asStateFlow()

    val documents: StateFlow<List<DocumentMeta>> =
        combine(_allDocuments, _searchQuery, _sortOrder, _currentFolderId) { docs, query, sort, folderId ->
            filterAndSort(docs, query, sort, folderId)
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    private val _trashedDocuments = MutableStateFlow<List<DocumentMeta>>(emptyList())
    val trashedDocuments: StateFlow<List<DocumentMeta>> = _trashedDocuments.asStateFlow()

    private val _isProcessing = MutableStateFlow(false)
    val isProcessing: StateFlow<Boolean> = _isProcessing.asStateFlow()

    private val _errorMessage = MutableStateFlow<String?>(null)
    val errorMessage: StateFlow<String?> = _errorMessage.asStateFlow()

    private val exporter = ExportManager(application)

    /** Trạng thái đang xuất/chuyển đổi file (null = rảnh) — UI hiển thị lớp phủ tiến độ. */
    private val _exportStatus = MutableStateFlow<String?>(null)
    val exportStatus: StateFlow<String?> = _exportStatus.asStateFlow()

    /** Xuất tài liệu đã scan sang [format]; [onDone] chạy trên main thread với các file kết quả. */
    fun exportDocument(id: String, format: ExportFormat, onDone: (List<File>) -> Unit) {
        val doc = repository.getDocument(id) ?: return
        if (_exportStatus.value != null) return
        viewModelScope.launch {
            _exportStatus.value = "Đang xuất ${format.label}…"
            try {
                val files = exporter.exportPdf(repository.getPdfFile(id), doc.title, doc.ocrText, format) { i, n ->
                    _exportStatus.value = if (format == ExportFormat.JPG) "Đang xuất ảnh trang $i/$n…" else "AI đang phân tích bố cục trang $i/$n…"
                }
                onDone(files)
            } catch (e: Throwable) {
                _errorMessage.value = "Xuất file thất bại: ${e.message}"
            } finally {
                _exportStatus.value = null
            }
        }
    }

    /** Công cụ chuyển đổi: PDF/ảnh import → Word/Excel/PowerPoint giữ bố cục. */
    fun convertFiles(uris: List<Uri>, format: ExportFormat, onDone: (File) -> Unit) {
        if (uris.isEmpty() || _exportStatus.value != null) return
        viewModelScope.launch {
            _exportStatus.value = "Đang chuyển đổi sang ${format.label}…"
            try {
                val title = "ScanX chuyển đổi " + java.text.SimpleDateFormat("dd-MM-yyyy HHmm", Locale("vi", "VN")).format(java.util.Date())
                val file = exporter.convertImported(uris, title, format) { i, n -> _exportStatus.value = "AI đang phân tích bố cục trang $i/$n…" }
                onDone(file)
            } catch (e: Throwable) {
                _errorMessage.value = "Chuyển đổi thất bại: ${e.message}"
            } finally {
                _exportStatus.value = null
            }
        }
    }

    init {
        viewModelScope.launch(Dispatchers.IO) {
            repository.purgeExpiredTrash()
        }
        refresh()
    }

    fun refresh() {
        _allDocuments.value = repository.listDocuments()
        _folders.value = repository.listFolders()
    }

    fun refreshTrash() {
        _trashedDocuments.value = repository.listDocuments(includeTrashed = true).filter { it.isTrashed }
    }

    fun clearError() {
        _errorMessage.value = null
    }

    fun setSearchQuery(query: String) {
        _searchQuery.value = query
    }

    fun setSortOrder(order: SortOrder) {
        _sortOrder.value = order
        prefs.sortOrder = order
    }

    fun setViewMode(mode: ViewMode) {
        _viewMode.value = mode
        prefs.viewMode = mode
    }

    fun openFolder(folderId: String?) {
        _currentFolderId.value = folderId
    }

    fun createFolder(name: String) {
        if (name.isBlank()) return
        repository.createFolder(name)
        refresh()
    }

    fun renameFolder(id: String, newName: String) {
        repository.renameFolder(id, newName)
        refresh()
    }

    fun deleteFolder(id: String) {
        repository.deleteFolder(id)
        if (_currentFolderId.value == id) _currentFolderId.value = null
        refresh()
    }

    fun moveToFolder(documentId: String, folderId: String?) {
        repository.moveToFolder(documentId, folderId)
        refresh()
    }

    /** Lưu các trang vừa chụp (đã làm phẳng + tăng cường bởi camera tự viết) thành 1 tài liệu mới. */
    fun saveScannedPages(pages: List<Bitmap>) {
        if (pages.isEmpty()) return
        viewModelScope.launch {
            _isProcessing.value = true
            try {
                val ocr = if (prefs.autoOcrEnabled) {
                    withContext(Dispatchers.Default) { recognizeTextFromBitmaps(pages) }
                } else {
                    OcrResult("", emptyList())
                }
                withContext(Dispatchers.IO) {
                    repository.saveDocument(pages = pages, ocrText = ocr.text, folderId = _currentFolderId.value, textLayers = ocr.layers)
                }
                pages.forEach { if (!it.isRecycled) it.recycle() }
                refresh()
            } catch (e: Exception) {
                _errorMessage.value = "Không lưu được tài liệu: ${e.message}"
            } finally {
                _isProcessing.value = false
            }
        }
    }

    /** Nhập ảnh có sẵn trong máy (nút mở ảnh / Import Files) thành 1 tài liệu mới, không qua camera. */
    fun importImages(uris: List<Uri>) {
        if (uris.isEmpty()) return
        viewModelScope.launch {
            _isProcessing.value = true
            try {
                val context = getApplication<Application>()
                val bitmaps = withContext(Dispatchers.IO) {
                    uris.mapNotNull { uri ->
                        runCatching {
                            context.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it) }
                        }.getOrNull()
                    }
                }
                if (bitmaps.isEmpty()) {
                    _errorMessage.value = "Không đọc được ảnh đã chọn"
                    return@launch
                }
                val ocr = if (prefs.autoOcrEnabled) {
                    withContext(Dispatchers.Default) { recognizeTextFromBitmaps(bitmaps) }
                } else {
                    OcrResult("", emptyList())
                }
                withContext(Dispatchers.IO) {
                    repository.saveDocument(pages = bitmaps, ocrText = ocr.text, folderId = _currentFolderId.value, textLayers = ocr.layers)
                }
                bitmaps.forEach { it.recycle() }
                refresh()
            } catch (e: Exception) {
                _errorMessage.value = "Không nhập được ảnh: ${e.message}"
            } finally {
                _isProcessing.value = false
            }
        }
    }

    private class OcrResult(val text: String, val layers: List<List<TextLayerLine>>)

    /** OCR từng trang: vừa lấy văn bản (tìm kiếm, xem nhanh) vừa lấy toạ độ từng dòng (lớp chữ ẩn trong PDF). */
    private suspend fun recognizeTextFromBitmaps(pages: List<Bitmap>): OcrResult {
        val builder = StringBuilder()
        val layers = ArrayList<List<TextLayerLine>>()
        for ((index, bitmap) in pages.withIndex()) {
            val pageLines = ArrayList<TextLayerLine>()
            try {
                val image = InputImage.fromBitmap(bitmap, 0)
                val result = recognizer.process(image).awaitTask()
                if (result.text.isNotBlank()) {
                    if (pages.size > 1) {
                        builder.append("--- Trang ${index + 1} ---\n")
                    }
                    builder.append(result.text).append("\n\n")
                }
                for (block in result.textBlocks) for (line in block.lines) {
                    val r = line.boundingBox ?: continue
                    pageLines.add(TextLayerLine(line.text, r.left.toFloat(), r.top.toFloat(), r.right.toFloat(), r.bottom.toFloat()))
                }
            } catch (e: Exception) {
                // Bỏ qua lỗi OCR của 1 trang, không chặn việc lưu cả tài liệu.
            }
            layers.add(pageLines)
        }
        return OcrResult(builder.toString().trim(), layers)
    }

    fun moveToTrash(id: String) {
        repository.moveToTrash(id)
        refresh()
    }

    fun restoreFromTrash(id: String) {
        repository.restoreFromTrash(id)
        refresh()
        refreshTrash()
    }

    fun deleteDocumentPermanently(id: String) {
        repository.deleteDocumentPermanently(id)
        refresh()
        refreshTrash()
    }

    fun emptyTrash() {
        repository.emptyTrash()
        refreshTrash()
    }

    fun renameDocument(id: String, newTitle: String) {
        if (newTitle.isBlank()) return
        repository.renameDocument(id, newTitle)
        refresh()
    }

    fun getPdfFile(id: String) = repository.getPdfFile(id)

    fun getThumbnailFile(id: String) = repository.getThumbnailFile(id)

    private fun filterAndSort(
        docs: List<DocumentMeta>,
        query: String,
        sort: SortOrder,
        folderId: String?,
    ): List<DocumentMeta> {
        val locale = Locale("vi", "VN")
        var filtered = if (folderId != null) docs.filter { it.folderId == folderId } else docs
        if (query.isNotBlank()) {
            val q = query.trim().lowercase(locale)
            filtered = filtered.filter {
                it.title.lowercase(locale).contains(q) || it.ocrText.lowercase(locale).contains(q)
            }
        }
        return when (sort) {
            SortOrder.DATE_MODIFIED -> filtered.sortedByDescending { it.modifiedAtEpochMillis }
            SortOrder.DATE_CREATED -> filtered.sortedByDescending { it.createdAtEpochMillis }
            SortOrder.NAME -> filtered.sortedBy { it.title.lowercase(locale) }
        }
    }

    override fun onCleared() {
        super.onCleared()
        recognizer.close()
    }
}
