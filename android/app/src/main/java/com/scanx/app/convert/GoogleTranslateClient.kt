package com.scanx.app.convert

import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/**
 * Google Cloud Translation — Basic (v2), chính là máy dịch của Google Dịch (bản 1.0). Dùng API key của
 * người dùng (tạo trong Google Cloud Console, bật "Cloud Translation API" + tài khoản thanh toán).
 * Miễn phí 500.000 ký tự/tháng (Google trừ vào $10 tín dụng mỗi tháng), vượt thì $20/1 triệu ký tự.
 *
 *   POST https://translation.googleapis.com/language/translate/v2?key=<API key>
 *   body {"q": ["…", "…"], "target": "vi", "format": "text"[, "source": "ko"]}
 *   → data.translations[i].translatedText / detectedSourceLanguage (1 phần tử cho mỗi chuỗi q).
 * Tối đa 128 chuỗi mỗi lần gọi (theo tài liệu Google); ScanX gửi ≤ [MAX_STRINGS] chuỗi và
 * ≤ [MAX_CHARS] ký tự mỗi lần cho nhanh và an toàn.
 */
class GoogleTranslateClient(private val apiKey: String) {

    class Result(val text: String, val detectedSource: String?)

    class GoogleTranslateException(message: String) : Exception(message)

    /** Dịch [texts] sang [target]; kết quả cùng thứ tự với [texts]. Gọi trên luồng nền. */
    fun translate(texts: List<String>, target: String, source: String? = null): List<Result> {
        if (texts.isEmpty()) return emptyList()
        val out = ArrayList<Result>(texts.size)
        for (chunk in chunkIndices(texts)) out += call(chunk.map { texts[it] }, target, source)
        return out
    }

    private fun call(texts: List<String>, target: String, source: String?): List<Result> {
        val body = JSONObject()
            .put("q", JSONArray(texts))
            .put("target", target)
            .put("format", "text")
        if (!source.isNullOrBlank()) body.put("source", source)
        val url = URL("$ENDPOINT?key=" + URLEncoder.encode(apiKey, "UTF-8"))
        val conn = (url.openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            connectTimeout = 15_000
            readTimeout = 60_000
            doOutput = true
            setRequestProperty("content-type", "application/json; charset=utf-8")
        }
        try {
            conn.outputStream.use { it.write(body.toString().toByteArray(Charsets.UTF_8)) }
            val code = conn.responseCode
            val text = (if (code in 200..299) conn.inputStream else conn.errorStream)
                ?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }.orEmpty()
            if (code !in 200..299) {
                val msg = runCatching { JSONObject(text).getJSONObject("error").getString("message") }
                    .getOrDefault(text.take(200))
                throw GoogleTranslateException(
                    when (code) {
                        400 -> if (msg.contains("key", ignoreCase = true)) "API key Google Dịch không hợp lệ ($msg)" else "Google Dịch từ chối yêu cầu ($msg)"
                        401, 403 -> "Google Dịch chưa sẵn sàng: kiểm tra đã bật Cloud Translation API và tài khoản thanh toán cho dự án chưa ($msg)"
                        429 -> "Vượt hạn mức Google Dịch, thử lại sau ít phút ($msg)"
                        else -> "Lỗi Google Dịch $code: $msg"
                    },
                )
            }
            val arr = JSONObject(text).getJSONObject("data").getJSONArray("translations")
            return List(arr.length()) { i ->
                val o = arr.getJSONObject(i)
                Result(o.optString("translatedText"), o.optString("detectedSourceLanguage").ifBlank { null })
            }
        } finally {
            conn.disconnect()
        }
    }

    companion object {
        private const val ENDPOINT = "https://translation.googleapis.com/language/translate/v2"
        const val MAX_STRINGS = 100
        const val MAX_CHARS = 4500

        /**
         * Chia chuỗi thành các lô ≤ [maxStrings] chuỗi và ≤ [maxChars] ký tự (1 chuỗi dài hơn [maxChars]
         * vẫn đi riêng 1 lô) — trả về chỉ số chuỗi của từng lô, đúng thứ tự. Thuần Kotlin, test được JVM.
         */
        fun chunkIndices(texts: List<String>, maxStrings: Int = MAX_STRINGS, maxChars: Int = MAX_CHARS): List<List<Int>> {
            val out = ArrayList<List<Int>>()
            var cur = ArrayList<Int>()
            var chars = 0
            for (i in texts.indices) {
                val len = texts[i].length
                if (cur.isNotEmpty() && (cur.size >= maxStrings || chars + len > maxChars)) {
                    out.add(cur); cur = ArrayList(); chars = 0
                }
                cur.add(i); chars += len
            }
            if (cur.isNotEmpty()) out.add(cur)
            return out
        }
    }
}

/**
 * Dịch tài liệu (giữ bố cục) bằng Google Cloud Translation — nhanh nhất trong các máy dịch, trả phí
 * theo ký tự sau hạn mức miễn phí (xem [GoogleTranslateClient]). Mỗi đoạn dịch độc lập theo ngôn ngữ
 * nguồn Google tự nhận diện.
 */
class GoogleCloudTranslator(apiKey: String) : TranslationEngine {
    private val client = GoogleTranslateClient(apiKey)
    override val label = "Google Dịch (Cloud)"

    override suspend fun translate(items: List<Translation.Item>, context: String, onProgress: (Int, Int) -> Unit): Map<String, String> {
        val out = HashMap<String, String>()
        val chunks = GoogleTranslateClient.chunkIndices(items.map { it.text })
        chunks.forEachIndexed { ci, idx ->
            onProgress(ci + 1, chunks.size)
            val res = client.translate(idx.map { items[it].text }, Translation.TARGET)
            idx.forEachIndexed { k, i ->
                res.getOrNull(k)?.text?.takeIf { it.isNotBlank() }?.let { out[items[i].id] = it }
            }
        }
        return out
    }
}
