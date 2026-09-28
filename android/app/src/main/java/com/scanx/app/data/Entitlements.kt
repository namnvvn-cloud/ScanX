package com.scanx.app.data

import android.content.Context
import org.json.JSONObject
import java.time.Instant

/**
 * Tính năng Business. Danh sách "thật" do backend quyết định (backend/src/users/entitlements.ts, trả qua
 * GET /users/me); nhãn + lượt dùng thử ở đây chỉ là giá trị dự phòng khi chưa từng tải được từ server.
 */
enum class BusinessFeature(val key: String, val fallbackLabel: String, val fallbackTrials: Int) {
    OFFICE_EXPORT("office_export", "Chuyển sang Word / Excel / PowerPoint giữ bố cục", 3),
    DOC_TRANSLATE("doc_translate", "Dịch cả tài liệu sang tiếng Việt (bản dịch / song ngữ)", 3),
    AI_HANDWRITING("ai_handwriting", "AI Cloud đọc chữ viết tay, bản chụp khó", 0),
    CLOUD_BACKUP("cloud_backup", "Sao lưu tài liệu lên đám mây", 0),
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
) {
    /** Tính lại theo giờ máy để gói hết hạn khi đang offline vẫn bị khoá đúng hạn. */
    val businessActive: Boolean
        get() = serverBusinessActive && (businessExpiresAt == null || businessExpiresAt > System.currentTimeMillis())

    fun hasFeature(f: BusinessFeature): Boolean = businessActive && (features[f.key] ?: true)

    fun label(f: BusinessFeature): String = labels[f.key] ?: f.fallbackLabel

    fun trialLimit(f: BusinessFeature): Int = freeTrials[f.key] ?: f.fallbackTrials

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
    }
}
