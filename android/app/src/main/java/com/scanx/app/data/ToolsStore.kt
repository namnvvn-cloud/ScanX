package com.scanx.app.data

import android.content.ContentResolver
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.provider.DocumentsContract
import android.provider.OpenableColumns
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID

/** Thư mục đám mây đã kết nối (Storage Access Framework): Google Drive / OneDrive / Dropbox / thẻ nhớ… */
data class CloudFolder(val uri: String, val name: String)

/** Mẫu email khi gửi tài liệu. Hỗ trợ biến: {ten} {ngay} {gio} {so_trang}. */
data class EmailTemplate(val to: String, val subject: String, val body: String) {
    companion object {
        val DEFAULT = EmailTemplate(
            to = "",
            subject = "Gửi tài liệu: {ten}",
            body = "Chào anh/chị,\n\nGửi kèm tài liệu «{ten}» ({so_trang} trang), quét ngày {ngay}.\n\nTrân trọng.",
        )
    }
}

/**
 * Quy trình tự động: chuỗi bước chạy trên 1 tài liệu (bấm "Chạy quy trình" hoặc tự chạy sau mỗi lần quét).
 * [renamePattern] trống = giữ tên. [folderId] null = không chuyển thư mục.
 */
data class Workflow(
    val id: String,
    val name: String,
    val renamePattern: String = "",
    val folderId: String? = null,
    val saveToCloudFolder: Boolean = false,
    val backupScanX: Boolean = false,
    val sendEmail: Boolean = false,
    val runAfterScan: Boolean = false,
) {
    fun toJson(): JSONObject = JSONObject()
        .put("id", id).put("name", name).put("renamePattern", renamePattern)
        .put("folderId", folderId ?: JSONObject.NULL).put("saveToCloudFolder", saveToCloudFolder)
        .put("backupScanX", backupScanX).put("sendEmail", sendEmail).put("runAfterScan", runAfterScan)

    val stepCount: Int
        get() = listOf(renamePattern.isNotBlank(), folderId != null, saveToCloudFolder, backupScanX, sendEmail).count { it }

    companion object {
        fun new(name: String) = Workflow(id = UUID.randomUUID().toString(), name = name)
        fun fromJson(o: JSONObject) = Workflow(
            id = o.optString("id").ifBlank { UUID.randomUUID().toString() },
            name = o.optString("name"),
            renamePattern = o.optString("renamePattern"),
            folderId = if (o.isNull("folderId")) null else o.optString("folderId").ifBlank { null },
            saveToCloudFolder = o.optBoolean("saveToCloudFolder"),
            backupScanX = o.optBoolean("backupScanX"),
            sendEmail = o.optBoolean("sendEmail"),
            runAfterScan = o.optBoolean("runAfterScan"),
        )
    }
}

/**
 * Lưu cấu hình các công cụ (Cài đặt → Thư mục đám mây / Tự động tải lên / Mẫu email / Quy trình /
 * Chữ ký / Biểu tượng ứng dụng, Lịch sử QR). SharedPreferences + file PNG chữ ký trong bộ nhớ riêng của app.
 */
class ToolsStore(context: Context) {
    private val appContext = context.applicationContext
    private val prefs = appContext.getSharedPreferences("scanx_tools", Context.MODE_PRIVATE)

    // ---------------- Thư mục đám mây ----------------
    fun cloudFolders(): List<CloudFolder> = readArray(KEY_FOLDERS).map { CloudFolder(it.optString("uri"), it.optString("name")) }

    fun addCloudFolder(treeUri: Uri) {
        runCatching {
            appContext.contentResolver.takePersistableUriPermission(
                treeUri,
                Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION,
            )
        }
        val name = folderDisplayName(appContext.contentResolver, treeUri)
        val list = cloudFolders().filterNot { it.uri == treeUri.toString() } + CloudFolder(treeUri.toString(), name)
        writeArray(KEY_FOLDERS, list.map { JSONObject().put("uri", it.uri).put("name", it.name) })
    }

    fun removeCloudFolder(uri: String) {
        runCatching {
            appContext.contentResolver.releasePersistableUriPermission(
                Uri.parse(uri),
                Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION,
            )
        }
        writeArray(KEY_FOLDERS, cloudFolders().filterNot { it.uri == uri }.map { JSONObject().put("uri", it.uri).put("name", it.name) })
        if (defaultCloudFolder == uri) defaultCloudFolder = null
    }

    /** Thư mục dùng cho Tự động tải lên / Quy trình (null = thư mục đầu tiên). */
    var defaultCloudFolder: String?
        get() = prefs.getString(KEY_DEFAULT_FOLDER, null)?.takeIf { u -> cloudFolders().any { it.uri == u } }
            ?: cloudFolders().firstOrNull()?.uri
        set(value) = prefs.edit().putString(KEY_DEFAULT_FOLDER, value).apply()

    // ---------------- Tự động tải lên ----------------
    var autoUploadEnabled: Boolean
        get() = prefs.getBoolean(KEY_AUTO_UPLOAD, false)
        set(value) = prefs.edit().putBoolean(KEY_AUTO_UPLOAD, value).apply()

    /** Đích: thư mục đám mây đã kết nối. */
    var autoUploadToFolder: Boolean
        get() = prefs.getBoolean(KEY_AUTO_FOLDER, true)
        set(value) = prefs.edit().putBoolean(KEY_AUTO_FOLDER, value).apply()

    /** Đích: ScanX Cloud (sao lưu, cần đăng nhập + Business). */
    var autoUploadToScanX: Boolean
        get() = prefs.getBoolean(KEY_AUTO_SCANX, false)
        set(value) = prefs.edit().putBoolean(KEY_AUTO_SCANX, value).apply()

    // ---------------- Mẫu email ----------------
    var emailTemplate: EmailTemplate
        get() = prefs.getString(KEY_EMAIL, null)?.let {
            runCatching { JSONObject(it) }.getOrNull()?.let { o ->
                EmailTemplate(o.optString("to"), o.optString("subject"), o.optString("body"))
            }
        } ?: EmailTemplate.DEFAULT
        set(value) = prefs.edit().putString(
            KEY_EMAIL,
            JSONObject().put("to", value.to).put("subject", value.subject).put("body", value.body).toString(),
        ).apply()

    // ---------------- Quy trình ----------------
    fun workflows(): List<Workflow> = readArray(KEY_WORKFLOWS).map { Workflow.fromJson(it) }

    fun saveWorkflow(w: Workflow) {
        var list = workflows().filterNot { it.id == w.id } + w
        // Chỉ 1 quy trình được tự chạy sau khi quét.
        if (w.runAfterScan) list = list.map { if (it.id != w.id) it.copy(runAfterScan = false) else it }
        writeArray(KEY_WORKFLOWS, list.map { it.toJson() })
    }

    fun deleteWorkflow(id: String) = writeArray(KEY_WORKFLOWS, workflows().filterNot { it.id == id }.map { it.toJson() })

    fun autoWorkflow(): Workflow? = workflows().firstOrNull { it.runAfterScan }

    // ---------------- Chữ ký ----------------
    private val signaturesDir: File get() = File(appContext.filesDir, "signatures").apply { mkdirs() }

    fun signatures(): List<File> = signaturesDir.listFiles { f -> f.extension == "png" }?.sortedBy { it.lastModified() }.orEmpty()

    fun saveSignature(bitmap: Bitmap): File {
        val f = File(signaturesDir, "sig_${System.currentTimeMillis()}.png")
        f.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        return f
    }

    fun deleteSignature(file: File) {
        file.delete()
    }

    fun loadSignature(file: File): Bitmap? = BitmapFactory.decodeFile(file.absolutePath)

    // ---------------- Lịch sử QR ----------------
    fun qrHistory(): List<String> = readArray(KEY_QR).map { it.optString("v") }

    fun addQr(value: String) {
        val list = (listOf(value) + qrHistory().filterNot { it == value }).take(50)
        writeArray(KEY_QR, list.map { JSONObject().put("v", it) })
    }

    fun clearQr() = prefs.edit().remove(KEY_QR).apply()

    // ---------------- helpers ----------------
    private fun readArray(key: String): List<JSONObject> {
        val raw = prefs.getString(key, null) ?: return emptyList()
        return runCatching {
            val arr = JSONArray(raw)
            (0 until arr.length()).mapNotNull { arr.optJSONObject(it) }
        }.getOrDefault(emptyList())
    }

    private fun writeArray(key: String, items: List<JSONObject>) {
        val arr = JSONArray()
        items.forEach { arr.put(it) }
        prefs.edit().putString(key, arr.toString()).apply()
    }

    companion object {
        private const val KEY_FOLDERS = "cloud_folders"
        private const val KEY_DEFAULT_FOLDER = "default_cloud_folder"
        private const val KEY_AUTO_UPLOAD = "auto_upload"
        private const val KEY_AUTO_FOLDER = "auto_upload_folder"
        private const val KEY_AUTO_SCANX = "auto_upload_scanx"
        private const val KEY_EMAIL = "email_template"
        private const val KEY_WORKFLOWS = "workflows"
        private const val KEY_QR = "qr_history"

        fun folderDisplayName(resolver: ContentResolver, treeUri: Uri): String {
            return runCatching {
                val docUri = DocumentsContract.buildDocumentUriUsingTree(treeUri, DocumentsContract.getTreeDocumentId(treeUri))
                resolver.query(docUri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
                    if (c.moveToFirst()) c.getString(0) else null
                }
            }.getOrNull() ?: (treeUri.lastPathSegment ?: "Thư mục")
        }

        /** Tên nhà cung cấp dễ đọc từ authority của URI (Google Drive, OneDrive…). */
        fun providerName(uri: String): String {
            val a = Uri.parse(uri).authority.orEmpty()
            return when {
                a.contains("google.android.apps.docs") -> "Google Drive"
                a.contains("microsoft.skydrive") || a.contains("onedrive") -> "OneDrive"
                a.contains("dropbox") -> "Dropbox"
                a.contains("externalstorage") -> "Bộ nhớ máy"
                a.contains("mega") -> "MEGA"
                a.contains("box.android") -> "Box"
                else -> a.substringAfterLast('.').replaceFirstChar { it.uppercase() }.ifBlank { "Thư mục" }
            }
        }

        /** Thay biến {ten} {ngay} {gio} {so_trang} {stt} trong mẫu. */
        fun render(template: String, doc: DocumentMeta, index: Int = 1): String {
            val now = Date(doc.createdAtEpochMillis)
            return template
                .replace("{ten}", doc.title)
                .replace("{ngay}", SimpleDateFormat("dd/MM/yyyy", Locale("vi", "VN")).format(now))
                .replace("{gio}", SimpleDateFormat("HH:mm", Locale("vi", "VN")).format(now))
                .replace("{so_trang}", doc.pageCount.toString())
                .replace("{stt}", index.toString())
        }

        /** Tên file an toàn (bỏ ký tự cấm trên các hệ lưu trữ). */
        fun safeFileName(name: String): String = name.replace(Regex("[\\\\/:*?\"<>|]"), "_").trim().ifBlank { "ScanX" }.take(120)

        /**
         * Ghi [file] vào thư mục đã kết nối [treeUri] với tên [displayName]. Trả về true nếu thành công.
         * Chạy trên luồng nền.
         */
        fun exportToFolder(context: Context, treeUri: String, file: File, displayName: String, mime: String): Boolean {
            return runCatching {
                val tree = Uri.parse(treeUri)
                val parent = DocumentsContract.buildDocumentUriUsingTree(tree, DocumentsContract.getTreeDocumentId(tree))
                val created = DocumentsContract.createDocument(context.contentResolver, parent, mime, displayName) ?: return false
                context.contentResolver.openOutputStream(created)?.use { out -> file.inputStream().use { it.copyTo(out) } } != null
            }.getOrDefault(false)
        }
    }
}
