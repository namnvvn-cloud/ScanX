package com.scanx.app.convert

import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/**
 * Gọi Gemini API (Google) qua API key MIỄN PHÍ của chính người dùng (lấy tại aistudio.google.com/apikey,
 * không cần thẻ/tài khoản thanh toán). Dùng cho tính năng "Dịch sang tiếng Việt" như một lựa chọn thay
 * cho Claude khi người dùng không muốn trả phí — chỉ gửi VĂN BẢN (không gửi ảnh trang).
 *
 * Dùng API "Interactions" (thay cho "generateContent" cũ, Google đã đổi API kể từ giữa 2026):
 *   POST https://generativelanguage.googleapis.com/v1beta/interactions
 *   header x-goog-api-key: <key>
 *   body {"model": "...", "input": "...", "generation_config": {"temperature":..., "max_output_tokens":...}}
 * Chữ trả lời nằm ở steps[i].content[j].text với steps[i].type == "model_output".
 *
 * Hạn mức miễn phí dễ va phải khi dịch tài liệu nhiều lô liên tiếp → lỗi 429. Tự thử lại tối đa
 * [RETRY_DELAYS_MS].size lần với thời gian chờ tăng dần trước khi báo lỗi hẳn cho người dùng; lỗi
 * khoá sai (401/403) hoặc lỗi khác không thử lại (thử lại không giải quyết được gì).
 */
class GeminiAiClient(private val apiKey: String, private val model: String) {

    open class GeminiException(message: String) : Exception(message)

    /** Gửi 1 yêu cầu chỉ có chữ (vd dịch), trả về phần văn bản trả lời của mô hình. */
    fun complete(prompt: String, maxTokens: Int = 8192): String {
        val body = JSONObject()
            .put("model", model)
            .put("input", prompt)
            .put(
                "generation_config",
                JSONObject().put("temperature", 0.2).put("max_output_tokens", maxTokens),
            )
        var lastError: GeminiException? = null
        for (attempt in 0..RETRY_DELAYS_MS.size) {
            try {
                return call(body)
            } catch (e: RateLimitException) {
                lastError = e
                if (attempt < RETRY_DELAYS_MS.size) Thread.sleep(RETRY_DELAYS_MS[attempt])
            }
        }
        throw lastError!!
    }

    /** Lỗi 429 (rate_limit_exceeded/quota_exceeded) — tách riêng để [complete] biết khi nào nên thử lại. */
    private class RateLimitException(message: String) : GeminiException(message)

    private fun call(body: JSONObject): String {
        val conn = (URL(ENDPOINT).openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            connectTimeout = 20_000
            readTimeout = 180_000
            doOutput = true
            setRequestProperty("content-type", "application/json")
            setRequestProperty("x-goog-api-key", apiKey)
        }
        try {
            conn.outputStream.use { it.write(body.toString().toByteArray(Charsets.UTF_8)) }
            val code = conn.responseCode
            val text = (if (code in 200..299) conn.inputStream else conn.errorStream)?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }.orEmpty()
            if (code !in 200..299) {
                val msg = runCatching { JSONObject(text).getJSONObject("error").getString("message") }.getOrDefault(text.take(200))
                if (code == 429) throw RateLimitException("Vượt hạn mức miễn phí Gemini, đã tự thử lại nhưng vẫn còn giới hạn ($msg)")
                throw GeminiException(
                    when (code) {
                        401, 403 -> "API key Gemini không hợp lệ ($msg)"
                        else -> "Lỗi Gemini $code: $msg"
                    },
                )
            }
            val steps = JSONObject(text).optJSONArray("steps") ?: return ""
            val sb = StringBuilder()
            for (i in 0 until steps.length()) {
                val step = steps.optJSONObject(i) ?: continue
                if (step.optString("type") != "model_output") continue
                val content = step.optJSONArray("content") ?: continue
                for (j in 0 until content.length()) {
                    val part = content.optJSONObject(j) ?: continue
                    if (part.optString("type") == "text") sb.append(part.optString("text"))
                }
            }
            return sb.toString()
        } finally {
            conn.disconnect()
        }
    }

    companion object {
        private const val ENDPOINT = "https://generativelanguage.googleapis.com/v1beta/interactions"
        /** Thời gian chờ (ms) trước mỗi lần thử lại khi gặp lỗi 429 — 3 lần thử lại, tổng chờ tối đa ~27 s. */
        private val RETRY_DELAYS_MS = longArrayOf(3_000L, 8_000L, 16_000L)
        const val DEFAULT_MODEL = "gemini-2.5-flash"
        /** Gợi ý sẵn — Google đổi tên model khá thường xuyên, ô nhập vẫn cho gõ tự do. */
        val SUGGESTED_MODELS = listOf(
            "gemini-2.5-flash" to "gemini-2.5-flash (ổn định, khuyên dùng)",
            "gemini-3.8-flash" to "gemini-3.8-flash (mới nhất, có thể hạn mức khác)",
        )
    }
}
