package com.scanx.app.data

import android.content.Context
import android.os.Build
import com.scanx.app.BuildConfig
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.util.Locale
import java.util.UUID

/**
 * Báo thiết bị về backend (POST /devices/ping) mỗi lần mở app — để Web Admin thấy cả người đã cài nhưng
 * CHƯA đăng ký. Chỉ gửi: mã cài đặt ngẫu nhiên (UUID tạo lần đầu, gỡ app là mất), phiên bản app, dòng máy,
 * phiên bản Android, ngôn ngữ. Đã đăng nhập thì kèm token để gắn thiết bị với tài khoản.
 */
object DeviceRegistry {
    private const val PREFS = "scanx_device"
    private const val KEY_INSTALL_ID = "install_id"

    fun installId(context: Context): String {
        val prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        prefs.getString(KEY_INSTALL_ID, null)?.let { return it }
        val id = UUID.randomUUID().toString()
        prefs.edit().putString(KEY_INSTALL_ID, id).apply()
        return id
    }

    /** Chạy trên luồng nền; lỗi mạng bỏ qua (không ảnh hưởng người dùng). */
    fun ping(context: Context, idToken: String?) {
        runCatching {
            val body = JSONObject()
                .put("installId", installId(context))
                .put("platform", "android")
                .put("versionCode", BuildConfig.VERSION_CODE)
                .put("versionName", BuildConfig.VERSION_NAME)
                .put("model", "${Build.MANUFACTURER} ${Build.MODEL}".trim().take(80))
                .put("osVersion", Build.VERSION.RELEASE.take(40))
                .put("locale", Locale.getDefault().toLanguageTag().take(20))
            val conn = (URL("${BackendApi.BASE_URL}/devices/ping").openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                doOutput = true
                connectTimeout = 60_000
                readTimeout = 60_000
                setRequestProperty("Content-Type", "application/json")
                if (idToken != null) setRequestProperty("Authorization", "Bearer $idToken")
            }
            try {
                conn.outputStream.use { it.write(body.toString().toByteArray(Charsets.UTF_8)) }
                conn.responseCode
            } finally {
                conn.disconnect()
            }
        }
    }
}
