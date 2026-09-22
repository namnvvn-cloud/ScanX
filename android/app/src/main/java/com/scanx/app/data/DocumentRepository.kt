package com.scanx.app.data

import android.content.Context
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
 * + pages/page_NNN.jpg (ảnh master màu từng trang) + text_layers.json (lớp chữ OCR).
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

    fun getPagesDir(id: String): File = File(rootDir, "$id/pages")

    /** Ảnh master màu từng trang (chỉ có với tài liệu tạo từ bản 0.3 trở đi; bản cũ trả về rỗng). */
    fun getPageFiles(id: String): List<File> =
        getPagesDir(id).listFiles { f -> f.isFile && f.name.endsWith(".jpg") }?.sortedBy { it.name } ?: emptyList()

    /** Lớp chữ OCR đã lưu (toạ độ chuẩn hoá) để dựng lại PDF ở chế độ khác vẫn tìm kiếm được. */
    fun getTextLayers(id: String): List<List<PdfTextLine>> {
        val f = File(rootDir, "$id/text_layers.json")
        if (!f.exists()) return emptyList()
        return try {
            val arr = JSONArray(f.readText())
            (0 until arr.length()).map { p ->
                val lines = arr.getJSONArray(p)
                (0 until lines.length()).map { i ->
                    val o = lines.getJSONArray(i)
                    PdfTextLine(o.getString(0), o.getDouble(1).toFloat(), o.getDouble(2).toFloat(), o.getDouble(3).toFloat(), o.getDouble(4).toFloat())
                }
            }
        } catch (e: Exception) {
            emptyList()
        }
    }

    /**
     * Tạo tài liệu mới từ các ảnh master (màu, đã làm phẳng): chuyển ảnh vào thư mục tài liệu, dựng PDF
     * theo [pdfMode] (mặc định Đen trắng – Chất lượng cao), lưu thumbnail, lớp chữ OCR và metadata.
     */
    fun saveDocument(
        masters: List<File>,
        ocrText: String,
        folderId: String? = null,
        title: String? = null,
        textLayers: List<List<PdfTextLine>> = emptyList(),
        pdfMode: PdfExportMode = PdfExportMode.DEFAULT,
    ): DocumentMeta {
        require(masters.isNotEmpty()) { "Không có trang nào để lưu" }
        val id = UUID.randomUUID().toString()
        val folder = File(rootDir, id).apply { mkdirs() }
        val pagesDir = getPagesDir(id).apply { mkdirs() }
        val stored = masters.mapIndexed { i, src ->
            val dst = File(pagesDir, String.format(Locale.US, "page_%03d.jpg", i + 1))
            if (!src.renameTo(dst)) {
                src.copyTo(dst, overwrite = true)
                src.delete()
            }
            dst
        }
        writeTextLayers(folder, textLayers)

        val createdAt = System.currentTimeMillis()
        val finalTitle = title ?: defaultTitleFor(createdAt)
        PdfBuilder.buildPdf(stored, File(folder, "document.pdf"), pdfMode, finalTitle, textLayers)
        PdfBuilder.saveThumbnail(stored.first(), pdfMode, getThumbnailFile(id))

        val meta = DocumentMeta(
            id = id,
            title = finalTitle,
            createdAtEpochMillis = createdAt,
            modifiedAtEpochMillis = createdAt,
            pageCount = stored.size,
            ocrText = ocrText,
            folderId = folderId,
            pdfMode = pdfMode.code,
        )
        writeMeta(folder, meta)
        return meta
    }

    /** Dựng PDF của tài liệu [id] ở chế độ [mode] ra [outFile] (dùng khi xuất file). */
    fun buildPdf(id: String, mode: PdfExportMode, outFile: File, title: String): Boolean {
        val pages = getPageFiles(id)
        if (pages.isEmpty()) return false
        PdfBuilder.buildPdf(pages, outFile, mode, title, getTextLayers(id))
        return true
    }

    private fun writeTextLayers(folder: File, layers: List<List<PdfTextLine>>) {
        val arr = JSONArray()
        for (page in layers) {
            val pa = JSONArray()
            for (l in page) pa.put(JSONArray().put(l.text).put(l.left.toDouble()).put(l.top.toDouble()).put(l.right.toDouble()).put(l.bottom.toDouble()))
            arr.put(pa)
        }
        File(folder, "text_layers.json").writeText(arr.toString())
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
            put("pdfMode", meta.pdfMode ?: JSONObject.NULL)
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
                pdfMode = if (json.isNull("pdfMode")) null else json.optString("pdfMode", null),
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
