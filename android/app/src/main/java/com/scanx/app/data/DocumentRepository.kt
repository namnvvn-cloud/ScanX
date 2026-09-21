package com.scanx.app.data

import android.content.Context
import android.net.Uri
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID

/**
 * Lưu trữ tài liệu scan cục bộ trên máy, chưa cần backend (đúng theo Phase 1 MVP offline-first).
 * Cấu trúc thư mục: filesDir/ScanX/documents/<id>/document.pdf + meta.json
 *
 * Dùng org.json (có sẵn trong Android SDK, không cần thêm dependency) để tránh rủi ro version
 * mismatch khi build lần đầu không có mạng để Gradle đồng bộ thử.
 */
class DocumentRepository(private val context: Context) {

    private val rootDir: File
        get() = File(context.filesDir, "ScanX/documents").apply { mkdirs() }

    fun listDocuments(): List<DocumentMeta> {
        val dir = rootDir
        val docs = dir.listFiles { f -> f.isDirectory } ?: emptyArray()
        return docs.mapNotNull { folder -> readMeta(folder) }
            .sortedByDescending { it.createdAtEpochMillis }
    }

    fun getPdfFile(id: String): File = File(rootDir, "$id/document.pdf")

    fun deleteDocument(id: String) {
        File(rootDir, id).deleteRecursively()
    }

    /**
     * Sao chép PDF từ Uri tạm do ML Kit Document Scanner trả về vào bộ nhớ trong của app,
     * đồng thời ghi metadata (bao gồm text OCR đã nhận dạng trước đó).
     */
    fun saveDocument(pdfUri: Uri, pageCount: Int, ocrText: String): DocumentMeta {
        val id = UUID.randomUUID().toString()
        val folder = File(rootDir, id).apply { mkdirs() }
        val pdfOut = File(folder, "document.pdf")

        context.contentResolver.openInputStream(pdfUri)?.use { input ->
            pdfOut.outputStream().use { output -> input.copyTo(output) }
        } ?: throw IllegalStateException("Không đọc được file PDF vừa scan")

        val createdAt = System.currentTimeMillis()
        val title = defaultTitleFor(createdAt)
        val meta = DocumentMeta(
            id = id,
            title = title,
            createdAtEpochMillis = createdAt,
            pageCount = pageCount,
            ocrText = ocrText
        )
        writeMeta(folder, meta)
        return meta
    }

    fun renameDocument(id: String, newTitle: String) {
        val folder = File(rootDir, id)
        val current = readMeta(folder) ?: return
        writeMeta(folder, current.copy(title = newTitle))
    }

    private fun defaultTitleFor(epochMillis: Long): String {
        val fmt = SimpleDateFormat("'ScanX' dd-MM-yyyy HH:mm", Locale("vi", "VN"))
        return fmt.format(Date(epochMillis))
    }

    private fun writeMeta(folder: File, meta: DocumentMeta) {
        val json = JSONObject().apply {
            put("id", meta.id)
            put("title", meta.title)
            put("createdAtEpochMillis", meta.createdAtEpochMillis)
            put("pageCount", meta.pageCount)
            put("ocrText", meta.ocrText)
        }
        File(folder, "meta.json").writeText(json.toString())
    }

    private fun readMeta(folder: File): DocumentMeta? {
        val metaFile = File(folder, "meta.json")
        if (!metaFile.exists()) return null
        return try {
            val json = JSONObject(metaFile.readText())
            DocumentMeta(
                id = json.getString("id"),
                title = json.getString("title"),
                createdAtEpochMillis = json.getLong("createdAtEpochMillis"),
                pageCount = json.optInt("pageCount", 1),
                ocrText = json.optString("ocrText", "")
            )
        } catch (e: Exception) {
            null
        }
    }
}
