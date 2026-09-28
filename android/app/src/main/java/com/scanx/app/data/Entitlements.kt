package com.scanx.app.data

import android.content.Context
import org.json.JSONObject
import java.time.Instant

/**
 * Tính năng có thể khoá theo gói. Quyền THẬT do admin tích chọn trên Web Admin → "Tính năng"
 * (backend/src/users/entitlements.ts, trả qua GET /users/me và GET /app/config); nhãn / quyền / lượt thử
 * ở đây chỉ là dự phòng khi chưa từng tải được từ server. Quét + OCR/PDF là tính năng lõi, luôn miễn phí.
 */
enum class BusinessFeature(val key: String, val fallbackLabel: String, val fallbackAccess: String, val fallbackTrials: Int) {
    EXPORT_IMAGE_TEXT("export_image_text", "Xuất ảnh JPG, văn bản TXT", "free", 0),
    CAMERA_TRANSLATE("camera_translate", "Chụp để dịch, dịch trực tiếp khi soi camera", "free", 0),
    OFFICE_EXPORT("office_export", "Chuyển sang Word / Excel / PowerPoint giữ bố cục", "business", 3),
    DOC_TRANSLATE("doc_translate", "Dịch cả tài liệu sang tiếng Việt (bản dịch / song ngữ)", "business", 3),
    AI_HANDWRITING("ai_handwriting", "AI Cloud đọc chữ viết tay, bản chụp khó", "business", 0),
    CLOUD_BACKUP("cloud_backup", "Sao lưu tài liệu lên đám mây", "business", 0),
}

/** Trạng thái gói của tài khoản đang đăng nhập (hoặc Free khi chưa đăng nhập). */
data class Entitlements(
    val email: String?,
    /** Server báo Business còn hạn tại thời điểm tải. */
    val serverBusinessActive: Boolean,
    /** Epoch ms; null = không thời hạn / chưa mua. */
    val businessExpiresAt: Long?,
    val features: Map<String, Boolean>,
    val labels: Map<String, String>,
    val freeTrials: Map<String, Int>,
    val fetchedAt: Long,
    /** "free" | "business" | "off" theo chính sách admin (null = server cũ chưa gửi). */
    val featureAccess: Map<String, String> = emptyMap(),
) {
    /** Tính lại theo giờ máy để gói hết hạn khi đang offline vẫn bị khoá đúng hạn. */
    val businessActive: Boolean
        get() = serverBusinessActive && (businessExpiresAt == null || businessExpiresAt > System.currentTimeMillis())

    fun access(f: BusinessFeature): String = featureAccess[f.key] ?: f.fallbackAccess

    fun hasFeature(f: BusinessFeature): Boolean = when (access(f)) {
        "free" -> true
        "off" -> false
        else -> businessActive
    }

    fun label(f: BusinessFeature): String = labels[f.key] ?: f.fallbackLabel

    fun trialLimit(f: BusinessFeature): Int = if (access(f) == "business") freeTrials[f.key] ?: f.fallbackTrials else 0

    /** Giữ chính sách (quyền/nhãn/lượt thử) nhưng bỏ gói — dùng khi đăng xuất. */
    fun asAnonymous(): Entitlements = copy(email = null, serverBusinessActive = false, businessExpiresAt = null)

    companion object {
        fun free(email: String?) = Entitlements(email, false, null, emptyMap(), emptyMap(), emptyMap(), 0L)

        /** Đọc phản hồi GET /users/me hoặc POST /auth/login (`user`). */
        fun fromUserJson(email: String?, user: JSONObject, now: Long = System.currentTimeMillis()): Entitlements {
            val ent = user.optJSONObject("entitlements")
            fun <T> map(obj: JSONObject?, read: (JSONObject, String) -> T): Map<String, T> {
                val o = obj ?: return emptyMap()
                return o.keys().asSequence().associateWith { read(o, it) }
            }
            val expiresRaw = ent?.optString("businessExpiresAt")?.takeIf { it.isNotBlank() && it != "null" }
                ?: user.optString("business_expires_at").takeIf { it.isNotBlank() && it != "null" }
            val expires = expiresRaw?.let { runCatching { Instant.parse(it).toEpochMilli() }.getOrNull() }
            val active = ent?.optBoolean("businessActive") ?: user.optBoolean("business_active", false)
            return Entitlements(
                email = email,
                serverBusinessActive = active,
                businessExpiresAt = expires,
                features = map(ent?.optJSONObject("features")) { o, k -> o.optBoolean(k) },
                labels = map(ent?.optJSONObject("featureLabels")) { o, k -> o.optString(k) },
                freeTrials = map(ent?.optJSONObject("freeTrials")) { o, k -> o.optInt(k) },
                fetchedAt = now,
                featureAccess = map(ent?.optJSONObject("featureAccess")) { o, k -> o.optString(k) },
            )
        }
    }
}

/**
 * Lưu trạng thái gói đã tải gần nhất (để dùng offline) + đếm lượt dùng thử THEO MÁY (không reset khi
 * đăng xuất/đổi tài khoản — tránh lách bằng cách tạo tài khoản mới).
 */
class EntitlementsStore(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences("scanx_entitlements", Context.MODE_PRIVATE)

    fun cached(email: String?): Entitlements? {
        if (email == null || prefs.getString(KEY_EMAIL, null) != email) return null
        val json = prefs.getString(KEY_USER_JSON, null) ?: return null
        return runCatching {
            Entitlements.fromUserJson(email, JSONObject(json), prefs.getLong(KEY_FETCHED_AT, 0L))
        }.getOrNull()
    }

    fun save(email: String, user: JSONObject) {
        prefs.edit()
            .putString(KEY_EMAIL, email)
            .putString(KEY_USER_JSON, user.toString())
            .putLong(KEY_FETCHED_AT, System.currentTimeMillis())
            .apply()
    }

    /** Chính sách cho người CHƯA đăng nhập (từ GET /app/config → entitlements). */
    fun saveAnonymous(entitlements: JSONObject) {
        prefs.edit().putString(KEY_ANON_JSON, JSONObject().put("entitlements", entitlements).toString()).apply()
    }

    fun cachedAnonymous(): Entitlements =
        prefs.getString(KEY_ANON_JSON, null)
            ?.let { runCatching { Entitlements.fromUserJson(null, JSONObject(it)) }.getOrNull() }
            ?: Entitlements.free(null)

    fun clearAccount() {
        prefs.edit().remove(KEY_EMAIL).remove(KEY_USER_JSON).remove(KEY_FETCHED_AT).apply()
    }

    fun trialsUsed(f: BusinessFeature): Int = prefs.getInt(KEY_TRIAL_PREFIX + f.key, 0)

    fun consumeTrial(f: BusinessFeature) {
        prefs.edit().putInt(KEY_TRIAL_PREFIX + f.key, trialsUsed(f) + 1).apply()
    }

    private companion object {
        const val KEY_EMAIL = "email"
        const val KEY_USER_JSON = "user_json"
        const val KEY_FETCHED_AT = "fetched_at"
        const val KEY_TRIAL_PREFIX = "trial_used_"
        const val KEY_ANON_JSON = "anon_json"
    }
}
