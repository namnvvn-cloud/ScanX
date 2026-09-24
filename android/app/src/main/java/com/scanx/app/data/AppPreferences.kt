package com.scanx.app.data

import android.content.Context

enum class CaptureMode { AUTO, MANUAL }

/** Bộ quét dùng cho "Scan tự động/thủ công": bản 1.0 mặc định camera ScanX (quét LIÊN TỤC nhiều trang);
 *  Google ML Kit Document Scanner (dừng xem/sửa sau mỗi trang) là tuỳ chọn trong Cài đặt quét. */
enum class ScanEngine { GOOGLE, SCANX }
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

    /** Bộ quét (bản 1.1 mặc định [ScanEngine.GOOGLE] theo quyết định của anh Nam — dùng bộ quét Google, không
     *  dùng thuật toán bắt khung tự viết). Máy không hỗ trợ bộ quét Google thì app tự lùi về camera ScanX.
     *  Khoá mới để máy đã cài 1.0 (mặc định ScanX) cũng chuyển về Google. */
    var scanEngine: ScanEngine
        get() = runCatching {
            ScanEngine.valueOf(prefs.getString(KEY_SCAN_ENGINE, ScanEngine.GOOGLE.name)!!)
        }.getOrDefault(ScanEngine.GOOGLE)
        set(value) = prefs.edit().putString(KEY_SCAN_ENGINE, value.name).apply()

    var autoOcrEnabled: Boolean
        get() = prefs.getBoolean(KEY_AUTO_OCR, true)
        set(value) = prefs.edit().putBoolean(KEY_AUTO_OCR, value).apply()

    var flashEnabled: Boolean
        get() = prefs.getBoolean(KEY_FLASH, false)
        set(value) = prefs.edit().putBoolean(KEY_FLASH, value).apply()

    /**
     * API key Anthropic của người dùng cho "AI Cloud" — mã hoá bằng Android Keystore trước khi lưu
     * (xem [KeystoreCrypto]), chỉ tồn tại trong bộ nhớ riêng của app trên máy. Key đã lưu dạng chữ
     * thường (bản cũ, trước khi có mã hoá) tự động được đọc đúng và ghi đè lại thành bản mã hoá ngay
     * lần đọc đầu tiên — không mất key đã nhập trước đó.
     */
    var cloudApiKey: String
        get() = readSecret(KEY_CLOUD_KEY)
        set(value) = writeSecret(KEY_CLOUD_KEY, value.trim())

    var cloudModel: String
        get() = prefs.getString(KEY_CLOUD_MODEL, "claude-sonnet-5").orEmpty().ifBlank { "claude-sonnet-5" }
        set(value) = prefs.edit().putString(KEY_CLOUD_MODEL, value).apply()

    /** API key Gemini của người dùng (miễn phí, aistudio.google.com/apikey) — chỉ dùng để dịch chữ; mã hoá như [cloudApiKey]. */
    var geminiApiKey: String
        get() = readSecret(KEY_GEMINI_KEY)
        set(value) = writeSecret(KEY_GEMINI_KEY, value.trim())

    /** Bản 1.0: API key Google Cloud Translation (Google Dịch) — mã hoá như [cloudApiKey]. */
    var googleTranslateKey: String
        get() = readSecret(KEY_GOOGLE_TRANSLATE_KEY)
        set(value) = writeSecret(KEY_GOOGLE_TRANSLATE_KEY, value.trim())

    var geminiModel: String
        get() = prefs.getString(KEY_GEMINI_MODEL, "gemini-2.5-flash").orEmpty().ifBlank { "gemini-2.5-flash" }
        set(value) = prefs.edit().putString(KEY_GEMINI_MODEL, value).apply()

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

    /** Ghi giá trị đã mã hoá, có tiền tố [ENC_PREFIX] để phân biệt với key cũ lưu chữ thường. */
    private fun writeSecret(key: String, value: String) {
        val stored = if (value.isEmpty()) "" else ENC_PREFIX + KeystoreCrypto.encrypt(value)
        prefs.edit().putString(key, stored).apply()
    }

    /**
     * Đọc key: nếu có tiền tố [ENC_PREFIX] thì giải mã bình thường; nếu không (key nhập từ bản cũ,
     * trước khi có mã hoá) thì dùng nguyên văn VÀ ghi đè lại thành bản mã hoá luôn — chỉ 1 lần.
     */
    private fun readSecret(key: String): String {
        val raw = prefs.getString(key, "").orEmpty()
        if (raw.isEmpty()) return ""
        if (raw.startsWith(ENC_PREFIX)) return KeystoreCrypto.decrypt(raw.removePrefix(ENC_PREFIX))
        writeSecret(key, raw) // key cũ chưa mã hoá → nâng cấp ngầm, không cần người dùng nhập lại
        return raw
    }

    companion object {
        private const val ENC_PREFIX = "enc1:"
        private const val KEY_CAPTURE_MODE = "capture_mode"
        private const val KEY_SCAN_ENGINE = "scan_engine_v11"
        private const val KEY_STABLE_FRAMES = "auto_capture_stable_frames"
        private const val KEY_AUTO_OCR = "auto_ocr_enabled"
        private const val KEY_FLASH = "flash_enabled"
        private const val KEY_SORT = "sort_order"
        private const val KEY_VIEW_MODE = "view_mode"
        private const val KEY_CLOUD_KEY = "cloud_api_key"
        private const val KEY_CLOUD_MODEL = "cloud_model"
        private const val KEY_GEMINI_KEY = "gemini_api_key"
        private const val KEY_GOOGLE_TRANSLATE_KEY = "google_translate_api_key"
        private const val KEY_GEMINI_MODEL = "gemini_model"
        const val DEFAULT_STABLE_FRAMES = 5
    }
}
