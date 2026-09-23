package com.scanx.app.convert

import android.graphics.Bitmap
import android.util.Base64
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.net.HttpURLConnection
import java.net.URL

/**
 * Gọi Claude (Anthropic Messages API, có thị giác) để đọc lại chữ của 1 trang theo bố cục máy đã dựng
 * ([CloudTranscription]). Chỉ chạy khi người dùng đã nhập API key của chính họ trong Cài đặt VÀ bật
 * "AI Cloud" cho lần xuất đó — ảnh trang được gửi trực tiếp từ máy tới api.anthropic.com qua HTTPS,
 * không qua máy chủ trung gian nào.
 */
class CloudAiClient(private val apiKey: String, private val model: String) {

    class CloudAiException(message: String) : Exception(message)

    /** Trả về trang đã thay chữ bằng kết quả AI. Ném [CloudAiException] khi lỗi mạng/khoá/hạn mức. */
    fun transcribe(page: DocPage, pageImage: Bitmap): DocPage = transcribeBatch(listOf(page), listOf(encode(pageImage)))[0]

    /**
     * Đọc chữ NHIỀU trang trong 1 lần gọi Claude (gộp trang, bản 0.7): [jpegs] là ảnh từng trang đã
     * mã hoá JPEG sẵn (xem [encode]) — gọi trước khi Bitmap bị recycle, chỉ giữ byte JPEG nhỏ gọn
     * trong lúc gom lô, không giữ Bitmap gốc. Trả về danh sách trang đã thay chữ, đúng thứ tự.
     * Ném [CloudAiException] khi lỗi mạng/khoá/hạn mức — áp dụng cho cả lô, không đọc được trang nào.
     */
    fun transcribeBatch(pages: List<DocPage>, jpegs: List<ByteArray>): List<DocPage> {
        require(pages.size == jpegs.size) { "Số trang và số ảnh JPEG phải bằng nhau" }
        if (pages.isEmpty()) return emptyList()
        if (pages.size == 1) {
            val body = JSONObject()
                .put("model", model)
                .put("max_tokens", 8192)
                .put("messages", JSONArray().put(JSONObject().put("role", "user").put("content", imageContent(jpegs[0], CloudTranscription.prompt(pages[0])))))
            return listOf(applyAnswer(pages[0], call(body)))
        }
        val content = JSONArray()
        for (jpeg in jpegs) {
            content.put(
                JSONObject().put("type", "image").put(
                    "source",
                    JSONObject().put("type", "base64").put("media_type", "image/jpeg")
                        .put("data", Base64.encodeToString(jpeg, Base64.NO_WRAP)),
                ),
            )
        }
        content.put(JSONObject().put("type", "text").put("text", CloudTranscription.batchPrompt(pages)))
        val body = JSONObject()
            .put("model", model)
            .put("max_tokens", (6000 * pages.size).coerceAtMost(24000))
            .put("messages", JSONArray().put(JSONObject().put("role", "user").put("content", content)))
        val raw = call(body)
        val start = raw.indexOf('{')
        val end = raw.lastIndexOf('}')
        if (start < 0 || end <= start) throw CloudAiException("AI trả về dữ liệu không đúng định dạng")
        val json = JSONObject(raw.substring(start, end + 1))
        val pagesJson = json.optJSONArray("pages") ?: throw CloudAiException("AI trả về dữ liệu không đúng định dạng (thiếu \"pages\")")
        return pages.mapIndexed { i, page ->
            val pj = pagesJson.optJSONObject(i) ?: return@mapIndexed page
            val (answers, extras) = parseSlotsAndExtras(pj)
            CloudTranscription.apply(page, answers, extras)
        }
    }

    private fun imageContent(jpeg: ByteArray, prompt: String): JSONArray =
        JSONArray()
            .put(
                JSONObject().put("type", "image").put(
                    "source",
                    JSONObject().put("type", "base64").put("media_type", "image/jpeg")
                        .put("data", Base64.encodeToString(jpeg, Base64.NO_WRAP)),
                ),
            )
            .put(JSONObject().put("type", "text").put("text", prompt))

    /** Gửi 1 yêu cầu chỉ có chữ (vd dịch), trả về phần văn bản trả lời của mô hình. */
    fun complete(prompt: String, maxTokens: Int = 8192): String {
        val body = JSONObject()
            .put("model", model)
            .put("max_tokens", maxTokens)
            .put("messages", JSONArray().put(JSONObject().put("role", "user").put("content", prompt)))
        return call(body)
    }

    private fun call(body: JSONObject): String {
        val conn = (URL(ENDPOINT).openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            connectTimeout = 20_000
            readTimeout = 180_000
            doOutput = true
            setRequestProperty("content-type", "application/json")
            setRequestProperty("x-api-key", apiKey)
            setRequestProperty("anthropic-version", "2023-06-01")
        }
        try {
            conn.outputStream.use { it.write(body.toString().toByteArray(Charsets.UTF_8)) }
            val code = conn.responseCode
            val text = (if (code in 200..299) conn.inputStream else conn.errorStream)?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }.orEmpty()
            if (code !in 200..299) {
                val msg = runCatching { JSONObject(text).getJSONObject("error").getString("message") }.getOrDefault(text.take(200))
                throw CloudAiException(
                    when (code) {
                        401 -> "API key không hợp lệ ($msg)"
                        429 -> "Vượt hạn mức gọi AI, thử lại sau ($msg)"
                        else -> "Lỗi AI Cloud $code: $msg"
                    },
                )
            }
            val content = JSONObject(text).getJSONArray("content")
            val sb = StringBuilder()
            for (i in 0 until content.length()) {
                val part = content.getJSONObject(i)
                if (part.optString("type") == "text") sb.append(part.optString("text"))
            }
            return sb.toString()
        } finally {
            conn.disconnect()
        }
    }

    private fun applyAnswer(page: DocPage, raw: String): DocPage {
        val start = raw.indexOf('{')
        val end = raw.lastIndexOf('}')
        if (start < 0 || end <= start) throw CloudAiException("AI trả về dữ liệu không đúng định dạng")
        val (answers, extras) = parseSlotsAndExtras(JSONObject(raw.substring(start, end + 1)))
        return CloudTranscription.apply(page, answers, extras)
    }

    private fun parseSlotsAndExtras(json: JSONObject): Pair<Map<String, String>, List<CloudTranscription.ExtraText>> {
        val answers = HashMap<String, String>()
        json.optJSONObject("slots")?.let { slots ->
            val keys = slots.keys()
            while (keys.hasNext()) {
                val k = keys.next()
                answers[k] = slots.optString(k, "")
            }
        }
        val extras = ArrayList<CloudTranscription.ExtraText>()
        json.optJSONArray("extra")?.let { arr ->
            for (i in 0 until arr.length()) {
                val o = arr.optJSONObject(i) ?: continue
                val b = o.optJSONArray("box") ?: continue
                if (b.length() < 4) continue
                extras.add(
                    CloudTranscription.ExtraText(
                        o.optString("text", ""),
                        Box(b.optDouble(0).toFloat(), b.optDouble(1).toFloat(), b.optDouble(2).toFloat(), b.optDouble(3).toFloat()),
                    ),
                )
            }
        }
        return answers to extras
    }

    /** JPEG cạnh dài ≤ 2000 px, q85: đủ nét cho chữ viết tay, dưới giới hạn ảnh của API. Gọi trước khi
     *  Bitmap gốc bị recycle để gom nhiều trang lại (chỉ giữ byte JPEG nhỏ gọn trong lúc gom lô). */
    fun encode(bmp: Bitmap): ByteArray {
        val k = 2000f / maxOf(bmp.width, bmp.height)
        val scaled = if (k < 1f) Bitmap.createScaledBitmap(bmp, (bmp.width * k).toInt(), (bmp.height * k).toInt(), true) else bmp
        val bos = ByteArrayOutputStream()
        scaled.compress(Bitmap.CompressFormat.JPEG, 85, bos)
        if (scaled !== bmp) scaled.recycle()
        return bos.toByteArray()
    }

    companion object {
        private const val ENDPOINT = "https://api.anthropic.com/v1/messages"
        const val DEFAULT_MODEL = "claude-sonnet-5"
        val MODELS = listOf(
            "claude-sonnet-5" to "Claude Sonnet 5 (khuyên dùng: nhanh, chi phí thấp)",
            "claude-opus-5" to "Claude Opus 5 (chính xác nhất, chi phí cao hơn)",
        )
    }
}
