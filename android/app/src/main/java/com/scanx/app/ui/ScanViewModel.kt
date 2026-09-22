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
import com.scanx.app.convert.CloudConfig
import com.scanx.app.convert.ExportFormat
import com.scanx.app.convert.ExportManager
import com.scanx.app.data.AppPreferences
import com.scanx.app.data.DocumentMeta
import com.scanx.app.data.DocumentRepository
import com.scanx.app.data.FolderMeta
import com.scanx.app.data.SortOrder
import com.scanx.app.data.PdfExportMode
import com.scanx.app.data.PdfTextLine
import com.scanx.app.scan.CapturedPage
import com.scanx.app.scan.ScanFilters
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
    /** Đã nhập API key cho AI Cloud chưa. */
    val isCloudConfigured: Boolean get() = prefs.cloudApiKey.isNotBlank()

    fun cloudApiKey(): String = prefs.cloudApiKey
    fun cloudModel(): String = prefs.cloudModel

    fun saveCloudSettings(apiKey: String, model: String) {
        prefs.cloudApiKey = apiKey
        prefs.cloudModel = model
    }

    private fun cloudConfig(useCloud: Boolean): CloudConfig? =
        if (useCloud && prefs.cloudApiKey.isNotBlank()) CloudConfig(prefs.cloudApiKey, prefs.cloudModel) else null

    fun exportDocument(
        id: String,
        format: ExportFormat,
        pdfMode: PdfExportMode = PdfExportMode.DEFAULT,
        useCloud: Boolean = false,
        onDone: (List<File>) -> Unit,
    ) {
        val doc = repository.getDocument(id) ?: return
        if (_exportStatus.value != null) return
        viewModelScope.launch {
            _exportStatus.value = if (format == ExportFormat.PDF) "Đang tạo PDF ${pdfMode.label}…" else "Đang xuất ${format.label}…"
            try {
                val cloud = cloudConfig(useCloud)
                val files = exporter.exportDocument(repository, id, doc.title, doc.ocrText, format, pdfMode, cloud) { i, n ->
                    _exportStatus.value = when {
                        format == ExportFormat.JPG -> "Đang xuất ảnh trang $i/$n…"
                        cloud != null -> "AI Cloud đang đọc chữ trang $i/$n…"
                        else -> "AI đang phân tích bố cục trang $i/$n…"
                    }
                }
                onDone(files)
                exporter.lastNotice?.let { _errorMessage.value = it }
            } catch (e: Throwable) {
                _errorMessage.value = "Xuất file thất bại: ${e.message}"
            } finally {
                _exportStatus.value = null
            }
        }
    }

    /** Công cụ chuyển đổi: PDF/ảnh import → Word/Excel/PowerPoint giữ bố cục. */
    fun convertFiles(uris: List<Uri>, format: ExportFormat, useCloud: Boolean = false, onDone: (File) -> Unit) {
        if (uris.isEmpty() || _exportStatus.value != null) return
        viewModelScope.launch {
            _exportStatus.value = "Đang chuyển đổi sang ${format.label}…"
            try {
                val title = "ScanX chuyển đổi " + java.text.SimpleDateFormat("dd-MM-yyyy HHmm", Locale("vi", "VN")).format(java.util.Date())
                val cloud = cloudConfig(useCloud)
                val file = exporter.convertImported(uris, title, format, cloud) { i, n ->
                    _exportStatus.value = if (cloud != null) "AI Cloud đang đọc chữ trang $i/$n…" else "AI đang phân tích bố cục trang $i/$n…"
                }
                onDone(file)
                exporter.lastNotice?.let { _errorMessage.value = it }
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

    /** Lưu các trang vừa chụp (ảnh master đã làm phẳng trên đĩa) thành 1 tài liệu mới, PDF mặc định Đen trắng – Chất lượng cao. */
    fun saveScannedPages(pages: List<CapturedPage>) {
        if (pages.isEmpty()) return
        viewModelScope.launch {
            _isProcessing.value = true
            try {
                val masters = pages.map { it.masterFile }
                val ocr = if (prefs.autoOcrEnabled) {
                    withContext(Dispatchers.Default) { recognizeMasters(masters) }
                } else {
                    OcrResult("", emptyList())
                }
                withContext(Dispatchers.IO) {
                    repository.saveDocument(masters = masters, ocrText = ocr.text, folderId = _currentFolderId.value, textLayers = ocr.layers)
                }
                pages.forEach { if (!it.preview.isRecycled) it.preview.recycle() }
                refresh()
            } catch (e: Exception) {
                _errorMessage.value = "Không lưu được tài liệu: ${e.message}"
            } finally {
                _isProcessing.value = false
            }
        }
    }

    /** Nhập ảnh có sẵn trong máy (nút mở ảnh / Import Files) thành 1 tài liệu mới (giữ màu), không qua camera. */
    fun importImages(uris: List<Uri>) {
        if (uris.isEmpty()) return
        viewModelScope.launch {
            _isProcessing.value = true
            try {
                val context = getApplication<Application>()
                val dir = File(context.cacheDir, "import_session").apply { mkdirs() }
                val masters = withContext(Dispatchers.IO) {
                    uris.mapIndexedNotNull { i, uri ->
                        runCatching {
                            val bmp = decodeSampled(uri, 3000) ?: return@runCatching null
                            val f = File(dir, "import_${System.currentTimeMillis()}_$i.jpg")
                            f.outputStream().use { bmp.compress(Bitmap.CompressFormat.JPEG, 92, it) }
                            bmp.recycle()
                            f
                        }.getOrNull()
                    }
                }
                if (masters.isEmpty()) {
                    _errorMessage.value = "Không đọc được ảnh đã chọn"
                    return@launch
                }
                val ocr = if (prefs.autoOcrEnabled) {
                    withContext(Dispatchers.Default) { recognizeMasters(masters) }
                } else {
                    OcrResult("", emptyList())
                }
                withContext(Dispatchers.IO) {
                    repository.saveDocument(
                        masters = masters, ocrText = ocr.text, folderId = _currentFolderId.value,
                        textLayers = ocr.layers, pdfMode = PdfExportMode.COLOR_HQ,
                    )
                }
                refresh()
            } catch (e: Exception) {
                _errorMessage.value = "Không nhập được ảnh: ${e.message}"
            } finally {
                _isProcessing.value = false
            }
        }
    }

    private fun decodeSampled(uri: Uri, maxSide: Int): Bitmap? {
        val resolver = getApplication<Application>().contentResolver
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        resolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
        var sample = 1
        while (maxOf(bounds.outWidth, bounds.outHeight) / (sample * 2) >= maxSide) sample *= 2
        return resolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, BitmapFactory.Options().apply { inSampleSize = sample }) }
    }

    private class OcrResult(val text: String, val layers: List<List<PdfTextLine>>)

    /**
     * OCR từng trang master (trên bản đen trắng đã chuẩn hoá ánh sáng → ít lỗi do bóng/nền ố): lấy văn
     * bản (tìm kiếm, xem nhanh) + toạ độ từng dòng chuẩn hoá 0..1 (lớp chữ ẩn của PDF ở mọi chế độ).
     */
    private suspend fun recognizeMasters(masters: List<File>): OcrResult {
        val builder = StringBuilder()
        val layers = ArrayList<List<PdfTextLine>>()
        for ((index, file) in masters.withIndex()) {
            val pageLines = ArrayList<PdfTextLine>()
            try {
                val master = BitmapFactory.decodeFile(file.absolutePath) ?: error("ảnh trang lỗi")
                val bw = ScanFilters.renderBitmap(master, PdfExportMode.BW_HQ, 3000)
                master.recycle()
                val result = recognizer.process(InputImage.fromBitmap(bw, 0)).awaitTask()
                val w = bw.width.toFloat()
                val h = bw.height.toFloat()
                bw.recycle()
                if (result.text.isNotBlank()) {
                    if (masters.size > 1) builder.append("--- Trang ${index + 1} ---\n")
                    builder.append(result.text).append("\n\n")
                }
                for (block in result.textBlocks) for (line in block.lines) {
                    val r = line.boundingBox ?: continue
                    pageLines.add(PdfTextLine(line.text, r.left / w, r.top / h, r.right / w, r.bottom / h))
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
