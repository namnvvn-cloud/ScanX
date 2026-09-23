package com.scanx.app.convert

import com.google.mlkit.common.model.DownloadConditions
import com.google.mlkit.nl.translate.TranslateLanguage
import com.google.mlkit.nl.translate.Translation as MlTranslation
import com.google.mlkit.nl.translate.TranslatorOptions
import com.scanx.app.util.awaitTask
import org.json.JSONObject

/** Máy dịch sang tiếng Việt: nhận các đoạn [Translation.Item], trả về bản dịch theo mã đoạn. */
interface TranslationEngine {
    val label: String
    suspend fun translate(items: List<Translation.Item>, context: String, onProgress: (Int, Int) -> Unit): Map<String, String>
}

/** 3 máy dịch người dùng có thể chọn ở hộp thoại "Dịch sang tiếng Việt". */
enum class TranslationChoice { CLAUDE, GEMINI, MLKIT }

/**
 * Dịch bằng Claude (mô hình ngôn ngữ lớn): hiểu ngữ cảnh cả tài liệu, thuật ngữ chuyên ngành, văn phong
 * hành chính/kỹ thuật tiếng Việt — chất lượng cao nhất hiện nay cho Hàn/Nhật/Trung/Anh/Đức/Pháp → Việt.
 * Gửi theo lô (~6000 ký tự) để giữ ngữ cảnh mà vẫn ổn định; dùng API key AI Cloud của người dùng.
 */
class ClaudeTranslator(apiKey: String, model: String) : TranslationEngine {
    private val client = CloudAiClient(apiKey, model)
    override val label = "Claude"

    override suspend fun translate(items: List<Translation.Item>, context: String, onProgress: (Int, Int) -> Unit): Map<String, String> {
        val out = HashMap<String, String>()
        val batches = Translation.batches(items)
        batches.forEachIndexed { i, batch ->
            onProgress(i + 1, batches.size)
            val raw = client.complete(Translation.llmPrompt(batch, context), maxTokens = 16000)
            val start = raw.indexOf('{')
            val end = raw.lastIndexOf('}')
            if (start < 0 || end <= start) throw CloudAiClient.CloudAiException("AI trả về bản dịch không đúng định dạng")
            val json = JSONObject(raw.substring(start, end + 1))
            for (it in batch) json.optString(it.id, "").takeIf { s -> s.isNotBlank() }?.let { s -> out[it.id] = s }
        }
        return out
    }
}

/**
 * Dịch bằng Gemini (Google AI Studio) — mô hình ngôn ngữ lớn, MIỄN PHÍ ở hạn mức cá nhân (lấy API key
 * tại aistudio.google.com/apikey, không cần thẻ). Chất lượng thấp hơn Claude một chút với văn bản
 * chuyên ngành phức tạp nhưng đủ tốt cho phần lớn tài liệu, và không tốn phí — ưu tiên mặc định khi
 * người dùng đã cấu hình. Dùng chung [Translation.batches]/[Translation.llmPrompt] như Claude.
 */
class GeminiTranslator(apiKey: String, model: String) : TranslationEngine {
    private val client = GeminiAiClient(apiKey, model)
    override val label = "Gemini (miễn phí)"

    override suspend fun translate(items: List<Translation.Item>, context: String, onProgress: (Int, Int) -> Unit): Map<String, String> {
        val out = HashMap<String, String>()
        val batches = Translation.batches(items)
        batches.forEachIndexed { i, batch ->
            onProgress(i + 1, batches.size)
            val raw = client.complete(Translation.llmPrompt(batch, context), maxTokens = 16000)
            val start = raw.indexOf('{')
            val end = raw.lastIndexOf('}')
            if (start < 0 || end <= start) throw GeminiAiClient.GeminiException("Gemini trả về bản dịch không đúng định dạng")
            val json = JSONObject(raw.substring(start, end + 1))
            for (it in batch) json.optString(it.id, "").takeIf { s -> s.isNotBlank() }?.let { s -> out[it.id] = s }
        }
        return out
    }
}

/**
 * Dịch offline bằng Google ML Kit Translation (mô hình dịch máy chạy trên máy, miễn phí; tải model
 * ~30 MB/ngôn ngữ ở lần đầu). Chất lượng khá — phù hợp khi không có mạng/không muốn gửi tài liệu ra ngoài.
 * Mỗi đoạn dịch theo ngôn ngữ nguồn của chính nó (tài liệu song ngữ Hàn–Việt: chỉ dịch đoạn tiếng Hàn).
 */
class MlKitTranslator : TranslationEngine {
    override val label = "ML Kit (offline)"

    override suspend fun translate(items: List<Translation.Item>, context: String, onProgress: (Int, Int) -> Unit): Map<String, String> {
        val out = HashMap<String, String>()
        val bySource = items.groupBy { src(it.lang) }
        var done = 0
        for ((source, group) in bySource) {
            if (source == null || source == TranslateLanguage.VIETNAMESE) { done += group.size; continue }
            val translator = MlTranslation.getClient(
                TranslatorOptions.Builder().setSourceLanguage(source).setTargetLanguage(TranslateLanguage.VIETNAMESE).build(),
            )
            try {
                translator.downloadModelIfNeeded(DownloadConditions.Builder().build()).awaitTask()
                for (it in group) {
                    done++
                    onProgress(done, items.size)
                    runCatching { translator.translate(it.text).awaitTask() }.getOrNull()
                        ?.takeIf { s -> s.isNotBlank() }?.let { s -> out[it.id] = s }
                }
            } finally {
                translator.close()
            }
        }
        return out
    }

    private fun src(lang: String): String? = when (lang) {
        "", "en" -> TranslateLanguage.ENGLISH
        "zh" -> TranslateLanguage.CHINESE
        else -> TranslateLanguage.fromLanguageTag(lang)
    }
}
