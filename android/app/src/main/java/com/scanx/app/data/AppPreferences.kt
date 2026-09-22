package com.scanx.app.data

import android.content.Context

enum class CaptureMode { AUTO, MANUAL }
enum class SortOrder { DATE_MODIFIED, DATE_CREATED, NAME }
enum class ViewMode { GRID, LIST }

/**
 * Cài đặt của app, lưu bằng SharedPreferences có sẵn trong Android SDK (không thêm dependency
 * DataStore để giảm rủi ro version khi build). Dùng cho màn Settings (mục "Scanning", sắp xếp/
 * hiển thị ở màn My Scans) và toàn app đọc lại khi cần.
 */
class AppPreferences(context: Context) {

    private val prefs = context.applicationContext
        .getSharedPreferences("scanx_prefs", Context.MODE_PRIVATE)

    var captureMode: CaptureMode
        get() = runCatching {
            CaptureMode.valueOf(prefs.getString(KEY_CAPTURE_MODE, CaptureMode.AUTO.name)!!)
        }.getOrDefault(CaptureMode.AUTO)
        set(value) = prefs.edit().putString(KEY_CAPTURE_MODE, value.name).apply()

    /** Độ nhạy tự chụp: số nấc × 90 ms tài liệu phải đứng yên trước khi tự chụp (mặc định 5 = 0,45 s). */
    var autoCaptureStableFrames: Int
        get() = prefs.getInt(KEY_STABLE_FRAMES, DEFAULT_STABLE_FRAMES)
        set(value) = prefs.edit().putInt(KEY_STABLE_FRAMES, value.coerceIn(3, 15)).apply()

    var autoOcrEnabled: Boolean
        get() = prefs.getBoolean(KEY_AUTO_OCR, true)
        set(value) = prefs.edit().putBoolean(KEY_AUTO_OCR, value).apply()

    var flashEnabled: Boolean
        get() = prefs.getBoolean(KEY_FLASH, false)
        set(value) = prefs.edit().putBoolean(KEY_FLASH, value).apply()

    /** API key Anthropic của người dùng cho "AI Cloud" (chỉ lưu trong bộ nhớ riêng của app trên máy). */
    var cloudApiKey: String
        get() = prefs.getString(KEY_CLOUD_KEY, "").orEmpty()
        set(value) = prefs.edit().putString(KEY_CLOUD_KEY, value.trim()).apply()

    var cloudModel: String
        get() = prefs.getString(KEY_CLOUD_MODEL, "claude-sonnet-5").orEmpty().ifBlank { "claude-sonnet-5" }
        set(value) = prefs.edit().putString(KEY_CLOUD_MODEL, value).apply()

    var sortOrder: SortOrder
        get() = runCatching {
            SortOrder.valueOf(prefs.getString(KEY_SORT, SortOrder.DATE_MODIFIED.name)!!)
        }.getOrDefault(SortOrder.DATE_MODIFIED)
        set(value) = prefs.edit().putString(KEY_SORT, value.name).apply()

    var viewMode: ViewMode
        get() = runCatching {
            ViewMode.valueOf(prefs.getString(KEY_VIEW_MODE, ViewMode.GRID.name)!!)
        }.getOrDefault(ViewMode.GRID)
        set(value) = prefs.edit().putString(KEY_VIEW_MODE, value.name).apply()

    companion object {
        private const val KEY_CAPTURE_MODE = "capture_mode"
        private const val KEY_STABLE_FRAMES = "auto_capture_stable_frames"
        private const val KEY_AUTO_OCR = "auto_ocr_enabled"
        private const val KEY_FLASH = "flash_enabled"
        private const val KEY_SORT = "sort_order"
        private const val KEY_VIEW_MODE = "view_mode"
        private const val KEY_CLOUD_KEY = "cloud_api_key"
        private const val KEY_CLOUD_MODEL = "cloud_model"
        const val DEFAULT_STABLE_FRAMES = 5
    }
}
