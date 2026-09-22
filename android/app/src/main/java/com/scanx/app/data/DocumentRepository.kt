package com.scanx.app.data

import android.content.Context
import android.graphics.Bitmap
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID
import java.util.concurrent.TimeUnit

/**
 * Lưu trữ tài liệu scan cục bộ trên máy, chưa cần backend (đúng theo Phase 1 MVP offline-first).
 * Cấu trúc thư mục: filesDir/ScanX/documents/<id>/document.pdf + meta.json + thumbnail.jpg
 * Danh sách folder (chỉ để gom nhóm, không phải thư mục hệ điều hành) lưu ở filesDir/ScanX/folders.json.
 *
 * Dùng org.json (có sẵn trong Android SDK, không cần thêm dependency) để tránh rủi ro version
 * mismatch khi build lần đầu không có mạng để Gradle đồng bộ thử.
 */
class DocumentRepository(private val context: Context) {

    private val rootDir: File
        get() = File(context.filesDir, "ScanX/documents").apply { mkdirs() }

    private val foldersFile: File
        get() = File(context.filesDir, "ScanX/folders.json").apply { parentFile?.mkdirs() }

    // ----------------------------------------------------------------- Documents

    fun listDocuments(includeTrashed: Boolean = false): List<DocumentMeta> {
        val docs = rootDir.listFiles { f -> f.isDirectory } ?: emptyArray()
        return docs.mapNotNull { folder -> readMeta(folder) }
            .filter { includeTrashed || !it.isTrashed }
    }

    fun getDocument(id: String): DocumentMeta? = readMeta(File(rootDir, id))

    fun getPdfFile(id: String): File = File(rootDir, "$id/document.pdf")

    fun getThumbnailFile(id: String): File = File(rootDir, "$id/thumbnail.jpg")

    /**
     * Ghép các trang đã chụp (đã làm phẳng/tăng nét) thành 1 tài liệu mới: tạo PDF, lưu thumbnail,
     * ghi metadata (đã có sẵn text OCR nhận dạng trước đó).
     */
    fun saveDocument(
        pages: List<Bitmap>,
        ocrText: String,
        folderId: String? = null,
        title: String? = null,
        textLayers: List<List<TextLayerLine>> = emptyList(),
    ): DocumentMeta {
        require(pages.isNotEmpty()) { "Không có trang nào để lưu" }
        val id = UUID.randomUUID().toString()
        val folder = File(rootDir, id).apply { mkdirs() }

        PdfBuilder.buildPdf(pages, File(folder, "document.pdf"), textLayers)
        PdfBuilder.saveThumbnail(pages.first(), getThumbnailFile(id))

        val createdAt = System.currentTimeMillis()
        val meta = DocumentMeta(
            id = id,
            title = title ?: defaultTitleFor(createdAt),
            createdAtEpochMillis = createdAt,
            modifiedAtEpochMillis = createdAt,
            pageCount = pages.size,
            ocrText = ocrText,
            folderId = folderId,
        )
        writeMeta(folder, meta)
        return meta
    }

    fun deleteDocumentPermanently(id: String) {
        File(rootDir, id).deleteRecursively()
    }

    /** Xoá mềm: chuyển vào Trash Bin, tự xoá vĩnh viễn sau 30 ngày (gọi purgeExpiredTrash() lúc mở app). */
    fun moveToTrash(id: String) {
        updateMeta(id) { it.copy(isTrashed = true, trashedAtEpochMillis = System.currentTimeMillis()) }
    }

    fun restoreFromTrash(id: String) {
        updateMeta(id) { it.copy(isTrashed = false, trashedAtEpochMillis = null) }
    }

    fun emptyTrash() {
        listDocuments(includeTrashed = true).filter { it.isTrashed }.forEach { deleteDocumentPermanently(it.id) }
    }

    fun purgeExpiredTrash(maxAgeDays: Long = 30) {
        val cutoff = System.currentTimeMillis() - TimeUnit.DAYS.toMillis(maxAgeDays)
        listDocuments(includeTrashed = true)
            .filter { it.isTrashed && (it.trashedAtEpochMillis ?: 0L) < cutoff }
            .forEach { deleteDocumentPermanently(it.id) }
    }

    fun renameDocument(id: String, newTitle: String) {
        if (newTitle.isBlank()) return
        updateMeta(id) { it.copy(title = newTitle, modifiedAtEpochMillis = System.currentTimeMillis()) }
    }

    fun moveToFolder(id: String, folderId: String?) {
        updateMeta(id) { it.copy(folderId = folderId, modifiedAtEpochMillis = System.currentTimeMillis()) }
    }

    private fun updateMeta(id: String, transform: (DocumentMeta) -> DocumentMeta) {
        val folder = File(rootDir, id)
        val current = readMeta(folder) ?: return
        writeMeta(folder, transform(current))
    }

    private fun defaultTitleFor(epochMillis: Long): String {
        val fmt = SimpleDateFormat("'Scan' dd-MM-yyyy HH:mm", Locale("vi", "VN"))
        return fmt.format(Date(epochMillis))
    }

    private fun writeMeta(folder: File, meta: DocumentMeta) {
        val json = JSONObject().apply {
            put("id", meta.id)
            put("title", meta.title)
            put("createdAtEpochMillis", meta.createdAtEpochMillis)
            put("modifiedAtEpochMillis", meta.modifiedAtEpochMillis)
            put("pageCount", meta.pageCount)
            put("ocrText", meta.ocrText)
            put("folderId", meta.folderId ?: JSONObject.NULL)
            put("isTrashed", meta.isTrashed)
            put("trashedAtEpochMillis", meta.trashedAtEpochMillis ?: JSONObject.NULL)
        }
        File(folder, "meta.json").writeText(json.toString())
    }

    private fun readMeta(folder: File): DocumentMeta? {
        val metaFile = File(folder, "meta.json")
        if (!metaFile.exists()) return null
        return try {
            val json = JSONObject(metaFile.readText())
            val createdAt = json.getLong("createdAtEpochMillis")
            DocumentMeta(
                id = json.getString("id"),
                title = json.getString("title"),
                createdAtEpochMillis = createdAt,
                modifiedAtEpochMillis = json.optLong("modifiedAtEpochMillis", createdAt),
                pageCount = json.optInt("pageCount", 1),
                ocrText = json.optString("ocrText", ""),
                folderId = if (json.isNull("folderId")) null else json.optString("folderId", null),
                isTrashed = json.optBoolean("isTrashed", false),
                trashedAtEpochMillis = if (json.isNull("trashedAtEpochMillis")) null
                    else json.optLong("trashedAtEpochMillis").takeIf { it != 0L },
            )
        } catch (e: Exception) {
            null
        }
    }

    // ----------------------------------------------------------------- Folders

    fun listFolders(): List<FolderMeta> {
        if (!foldersFile.exists()) return emptyList()
        return try {
            val arr = JSONArray(foldersFile.readText())
            (0 until arr.length()).map { i ->
                val o = arr.getJSONObject(i)
                FolderMeta(
                    id = o.getString("id"),
                    name = o.getString("name"),
                    createdAtEpochMillis = o.getLong("createdAtEpochMillis"),
                )
            }.sortedBy { it.name.lowercase(Locale("vi", "VN")) }
        } catch (e: Exception) {
            emptyList()
        }
    }

    fun createFolder(name: String): FolderMeta {
        val folder = FolderMeta(id = UUID.randomUUID().toString(), name = name.trim(), createdAtEpochMillis = System.currentTimeMillis())
        writeFolders(listFolders() + folder)
        return folder
    }

    fun renameFolder(id: String, newName: String) {
        if (newName.isBlank()) return
        writeFolders(listFolders().map { if (it.id == id) it.copy(name = newName.trim()) else it })
    }

    /** Xoá 1 folder: các tài liệu bên trong không bị xoá, chỉ chuyển về "Không phân loại". */
    fun deleteFolder(id: String) {
        listDocuments(includeTrashed = true).filter { it.folderId == id }.forEach { moveToFolder(it.id, null) }
        writeFolders(listFolders().filterNot { it.id == id })
    }

    private fun writeFolders(folders: List<FolderMeta>) {
        val arr = JSONArray()
        folders.forEach { f ->
            arr.put(JSONObject().apply {
                put("id", f.id)
                put("name", f.name)
                put("createdAtEpochMillis", f.createdAtEpochMillis)
            })
        }
        foldersFile.writeText(arr.toString())
    }
}
