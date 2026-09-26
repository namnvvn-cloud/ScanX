package com.scanx.app.data

import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

class BackendApiException(message: String) : Exception(message)

/**
 * Client gọi backend ScanX (NestJS, xem backend/README.md) bằng HttpURLConnection thuần — cùng cách
 * [com.scanx.app.convert.CloudAiClient]/[com.scanx.app.convert.GoogleTranslateClient] đang dùng,
 * không thêm Retrofit/OkHttp cho gọn.
 *
 * BASE_URL trỏ thẳng bản production trên Render. Free tier tự ngủ sau 15 phút không có request —
 * lần gọi đầu tiên sau khi ngủ có thể mất 50 giây trở lên mới có phản hồi, nên timeout đặt dài
 * (60s) thay vì mặc định ngắn, để không báo lỗi oan khi server chỉ đang "thức dậy".
 */
class BackendApi(private val idToken: String) {

    companion object {
        const val BASE_URL = "https://scanx-450n.onrender.com"
        private const val CONNECT_TIMEOUT_MS = 60_000
        private const val READ_TIMEOUT_MS = 60_000
    }

    /** POST /auth/login — xác thực token, backend tự tạo hồ sơ user trong DB nếu là lần đầu. */
    fun login(): JSONObject = JSONObject(call("POST", "/auth/login", null))

    /** GET /documents — danh sách tài liệu đã sao lưu của user trên cloud. */
    fun listDocuments(): List<JSONObject> {
        val arr = JSONArray(call("GET", "/documents", null))
        return (0 until arr.length()).map { arr.getJSONObject(it) }
    }

    /** POST /documents — tạo metadata + trả uploadUrl (presigned) để PUT file PDF thẳng lên storage. */
    fun createDocument(title: String, pageCount: Int): JSONObject {
        val body = JSONObject().put("title", title).put("pageCount", pageCount).put("mimeType", "application/pdf")
        return JSONObject(call("POST", "/documents", body))
    }

    /** PUT thẳng file PDF lên [uploadUrl] (presigned URL trả về từ [createDocument]) — không qua backend. */
    fun uploadFile(uploadUrl: String, file: File) {
        val conn = (URL(uploadUrl).openConnection() as HttpURLConnection).apply {
            requestMethod = "PUT"
            doOutput = true
            connectTimeout = CONNECT_TIMEOUT_MS
            readTimeout = READ_TIMEOUT_MS
            setRequestProperty("Content-Type", "application/pdf")
        }
        try {
            conn.outputStream.use { out -> file.inputStream().use { it.copyTo(out) } }
            val code = conn.responseCode
            if (code !in 200..299) {
                throw BackendApiException("Tải file lên storage thất bại (HTTP $code)")
            }
        } finally {
            conn.disconnect()
        }
    }

    private fun call(method: String, path: String, body: JSONObject?): String {
        val conn = (URL(BASE_URL + path).openConnection() as HttpURLConnection).apply {
            requestMethod = method
            connectTimeout = CONNECT_TIMEOUT_MS
            readTimeout = READ_TIMEOUT_MS
            setRequestProperty("Authorization", "Bearer $idToken")
            setRequestProperty("Content-Type", "application/json")
            if (body != null) doOutput = true
        }
        try {
            if (body != null) {
                conn.outputStream.use { it.write(body.toString().toByteArray(Charsets.UTF_8)) }
            }
            val code = conn.responseCode
            val stream = if (code in 200..299) conn.inputStream else conn.errorStream
            val text = stream?.bufferedReader()?.use { it.readText() }.orEmpty()
            if (code !in 200..299) {
                val msg = runCatching { JSONObject(text).optString("message", "Lỗi HTTP $code") }
                    .getOrDefault("Lỗi HTTP $code")
                throw BackendApiException(msg)
            }
            return text
        } finally {
            conn.disconnect()
        }
    }
}
