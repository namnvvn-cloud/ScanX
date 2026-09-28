package com.scanx.app.data

import android.content.Context
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/** Kết quả kiểm tra phiên bản từ backend (GET /app/config). */
data class UpdateInfo(
    val latestVersionCode: Int,
    val latestVersionName: String,
    val requiredVersionCode: Int,
    val updateAvailable: Boolean,
    val forceUpdate: Boolean,
    val downloadUrl: String,
    val releaseNotes: String,
) {
    companion object {
        fun fromJson(o: JSONObject) = UpdateInfo(
            latestVersionCode = o.optInt("latestVersionCode"),
            latestVersionName = o.optString("latestVersionName"),
            requiredVersionCode = o.optInt("requiredVersionCode"),
            updateAvailable = o.optBoolean("updateAvailable"),
            forceUpdate = o.optBoolean("forceUpdate"),
            downloadUrl = o.optString("downloadUrl"),
            releaseNotes = o.optString("releaseNotes"),
        )
    }
}

/** Nội dung màn "Thông tin sản phẩm" — anh Nam sửa trên Web Admin, app tải về (có bản lưu offline). */
data class ProductInfo(
    val appName: String,
    val tagline: String,
    val description: String,
    val features: List<String>,
    val publisher: String,
    val website: String,
    val email: String,
    val phone: String,
    val address: String,
    val privacyUrl: String,
    val termsUrl: String,
) {
    companion object {
        /** Mặc định khi chưa tải được từ server lần nào. */
        val DEFAULT = ProductInfo(
            appName = "ScanX",
            tagline = "Quét tài liệu thông minh — OCR tiếng Việt, chuyển Word/Excel/PowerPoint, dịch tài liệu",
            description = "ScanX biến điện thoại thành máy quét tài liệu: tự nhận mép giấy, làm phẳng, lọc màu, " +
                "nhận dạng chữ tiếng Việt và xuất PDF tìm kiếm được.",
            features = listOf(
                "Quét tài liệu: tự nhận mép giấy, làm phẳng, lọc màu / đen trắng, xoá bóng",
                "OCR tiếng Việt, PDF có lớp chữ tìm kiếm được (4 chế độ)",
                "Chụp để dịch, dịch trực tiếp khi soi camera",
                "Business: chuyển Word / Excel / PowerPoint, dịch tài liệu, AI Cloud, sao lưu đám mây",
            ),
            publisher = "", website = "", email = "", phone = "", address = "", privacyUrl = "", termsUrl = "",
        )

        fun fromJson(o: JSONObject): ProductInfo {
            val arr = o.optJSONArray("features")
            return ProductInfo(
                appName = o.optString("appName").ifBlank { "ScanX" },
                tagline = o.optString("tagline"),
                description = o.optString("description"),
                features = if (arr == null) emptyList() else (0 until arr.length()).map { arr.optString(it) }.filter { it.isNotBlank() },
                publisher = o.optString("publisher"),
                website = o.optString("website"),
                email = o.optString("email"),
                phone = o.optString("phone"),
                address = o.optString("address"),
                privacyUrl = o.optString("privacyUrl"),
                termsUrl = o.optString("termsUrl"),
            )
        }
    }
}

/** Gọi GET /app/config (công khai, không cần đăng nhập) + lưu thông tin sản phẩm để xem offline. */
class AppConfigRepository(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences("scanx_app_config", Context.MODE_PRIVATE)

    fun cachedProductInfo(): ProductInfo =
        prefs.getString(KEY_PRODUCT, null)
            ?.let { runCatching { ProductInfo.fromJson(JSONObject(it)) }.getOrNull() }
            ?: ProductInfo.DEFAULT

    /** Chạy trên luồng nền. Trả (update, productInfo); ném lỗi nếu mạng/server lỗi. */
    fun fetch(versionCode: Int): Pair<UpdateInfo, ProductInfo> {
        val url = URL("${BackendApi.BASE_URL}/app/config?platform=android&versionCode=$versionCode")
        val conn = (url.openConnection() as HttpURLConnection).apply {
            connectTimeout = 60_000
            readTimeout = 60_000
        }
        try {
            val code = conn.responseCode
            if (code !in 200..299) throw BackendApiException("Lỗi HTTP $code")
            val json = JSONObject(conn.inputStream.bufferedReader().use { it.readText() })
            json.optJSONObject("productInfo")?.let { prefs.edit().putString(KEY_PRODUCT, it.toString()).apply() }
            val product = json.optJSONObject("productInfo")?.let { ProductInfo.fromJson(it) } ?: cachedProductInfo()
            val update = UpdateInfo.fromJson(json.optJSONObject("update") ?: JSONObject())
            return update to product
        } finally {
            conn.disconnect()
        }
    }

    /** Bản đã bấm "Để sau" (chỉ áp dụng khi KHÔNG bắt buộc) — không nhắc lại bản đó trong 24 giờ. */
    fun snoozedUntil(versionCode: Int): Long =
        if (prefs.getInt(KEY_SNOOZE_VERSION, -1) == versionCode) prefs.getLong(KEY_SNOOZE_UNTIL, 0L) else 0L

    fun snooze(versionCode: Int) {
        prefs.edit()
            .putInt(KEY_SNOOZE_VERSION, versionCode)
            .putLong(KEY_SNOOZE_UNTIL, System.currentTimeMillis() + 24 * 3_600_000L)
            .apply()
    }

    private companion object {
        const val KEY_PRODUCT = "product_info"
        const val KEY_SNOOZE_VERSION = "snooze_version"
        const val KEY_SNOOZE_UNTIL = "snooze_until"
    }
}
