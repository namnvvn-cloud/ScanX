package com.scanx.app.convert

import android.graphics.Bitmap
import android.graphics.Matrix
import android.os.SystemClock
import androidx.camera.core.ImageProxy
import com.google.android.gms.tasks.Tasks
import com.google.mlkit.common.model.DownloadConditions
import com.google.mlkit.nl.languageid.LanguageIdentification
import com.google.mlkit.nl.languageid.LanguageIdentificationOptions
import com.google.mlkit.nl.translate.TranslateLanguage
import com.google.mlkit.nl.translate.Translation as MlTranslation
import com.google.mlkit.nl.translate.Translator
import com.google.mlkit.nl.translate.TranslatorOptions
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.TextRecognizer
import com.google.mlkit.vision.text.chinese.ChineseTextRecognizerOptions
import com.google.mlkit.vision.text.japanese.JapaneseTextRecognizerOptions
import com.google.mlkit.vision.text.korean.KoreanTextRecognizerOptions
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import com.scanx.app.util.awaitTask
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.io.Closeable
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.abs
import kotlin.math.max

/**
 * DỊCH TRỰC TIẾP khi soi camera (bản 1.1) — kiểu chế độ "Tức thì" của Google Dịch: mỗi khung hình phân
 * tích (~4–5 hình/giây) → OCR ML Kit trên máy → gom khối ([PhotoTranslation]) → dịch sang tiếng Việt bằng
 * ML Kit Translation OFFLINE (nhanh, miễn phí, không cần mạng sau lần tải gói ngôn ngữ đầu tiên ~30 MB) →
 * phát [Frame] để lớp phủ vẽ bản dịch đè lên đúng chỗ chữ trên camera.
 *
 * - Bản dịch lưu đệm theo nội dung khối: chữ đứng yên trong khung hình thì chỉ dịch 1 lần, lớp phủ không nháy.
 * - Khối mới được dịch nền (không chặn camera); khối chưa có bản dịch thì để nguyên chữ gốc.
 * - Vị trí khối được làm mượt giữa các khung (cùng nội dung → trượt dần tới vị trí mới) để bớt rung.
 * - Muốn chính xác hơn: bấm chụp → dịch online (Google Cloud / Gemini) trên ảnh độ phân giải cao.
 */
class LiveTranslator : Closeable {

    enum class Script(val label: String) { LATIN("Anh/Latin"), CHINESE("中文"), JAPANESE("日本語"), KOREAN("한국어") }

    class LiveBlock(val block: PhotoTranslation.TextBlock, val translation: String?, val colors: PhotoTranslateRenderer.Colors)

    /** Kết quả 1 khung hình, toạ độ theo ảnh đã xoay đứng [width]×[height] (cùng khung nhìn với preview 4:3). */
    class Frame(val width: Int, val height: Int, val blocks: List<LiveBlock>, val status: String?)

    private val _frame = MutableStateFlow<Frame?>(null)
    val frame: StateFlow<Frame?> = _frame.asStateFlow()

    @Volatile var enabled: Boolean = true
        set(value) {
            field = value
            if (!value) _frame.value = null
        }

    @Volatile var script: Script = Script.LATIN
        set(value) {
            if (field != value) {
                field = value
                prevBlocks = emptyList()
                _frame.value = null
            }
        }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val recognizers = HashMap<Script, TextRecognizer>()
    private val langId = LanguageIdentification.getClient(
        LanguageIdentificationOptions.Builder().setConfidenceThreshold(0.4f).build(),
    )
    private val translators = ConcurrentHashMap<String, Translator>()
    private val readyModels = ConcurrentHashMap.newKeySet<String>()
    private val downloading = ConcurrentHashMap.newKeySet<String>()
    private val failedAt = ConcurrentHashMap<String, Long>()
    private val pending = ConcurrentHashMap.newKeySet<String>()
    private val cache = object : LinkedHashMap<String, String>(256, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, String>?) = size > CACHE_SIZE
    }

    @Volatile private var modelStatus: String? = null
    private var lastRun = 0L
    private var latinSource = TranslateLanguage.ENGLISH
    private var sourceCheckedAt = 0L
    private var prevBlocks: List<PhotoTranslation.TextBlock> = emptyList()

    /** Gọi trên luồng phân tích của CameraX (ImageAnalysis, KEEP_ONLY_LATEST, RGBA_8888). Luôn đóng [image]. */
    fun analyze(image: ImageProxy) {
        val bmp = try {
            val now = SystemClock.elapsedRealtime()
            if (!enabled || now - lastRun < MIN_INTERVAL_MS) return
            lastRun = now
            uprightBitmap(image)
        } catch (e: Throwable) {
            null
        } finally {
            image.close()
        }
        if (bmp == null) return
        try {
            process(bmp)
        } catch (_: Throwable) {
            // Khung hình lỗi (máy bận, model chưa sẵn) → bỏ qua, khung sau làm lại.
        } finally {
            bmp.recycle()
        }
    }

    private fun process(bmp: Bitmap) {
        val sc = script
        val text = Tasks.await(recognizer(sc).process(InputImage.fromBitmap(bmp, 0)))
        var lines = MultiScriptOcr.linesOf(text, 0)
        if (!enabled) return
        if (lines.isEmpty()) {
            prevBlocks = emptyList()
            _frame.value = Frame(bmp.width, bmp.height, emptyList(), modelStatus ?: "Hướng camera vào chữ cần dịch")
            return
        }
        val source = sourceFor(sc, lines)
        lines = lines.map { it.copy(lang = source) }
        if (source == TranslateLanguage.VIETNAMESE) {
            prevBlocks = emptyList()
            _frame.value = Frame(bmp.width, bmp.height, emptyList(), "Chữ trên camera đã là tiếng Việt")
            return
        }
        val modelReady = ensureModel(source)
        val blocks = smooth(PhotoTranslation.groupBlocks(lines).filter { PhotoTranslation.needsTranslation(it) })
        prevBlocks = blocks
        val out = ArrayList<LiveBlock>(blocks.size)
        for (b in blocks) {
            val key = "$source|${b.text}"
            val tr = synchronized(cache) { cache[key] }
            if (tr == null && modelReady) enqueue(key, source, b.text)
            out.add(LiveBlock(b, tr, PhotoTranslateRenderer.blockColors(bmp, b)))
        }
        _frame.value = Frame(bmp.width, bmp.height, out, modelStatus)
    }

    /** Ngôn ngữ nguồn: CJK theo bộ nhận dạng đã chọn; Latin đoán bằng Language ID (1,5 s/lần, cả khung hình). */
    private fun sourceFor(sc: Script, lines: List<OcrLine>): String = when (sc) {
        Script.CHINESE -> TranslateLanguage.CHINESE
        Script.JAPANESE -> TranslateLanguage.JAPANESE
        Script.KOREAN -> TranslateLanguage.KOREAN
        Script.LATIN -> {
            val now = SystemClock.elapsedRealtime()
            if (now - sourceCheckedAt > LANG_CHECK_MS) {
                val joined = lines.joinToString(" ") { it.text }.take(600)
                if (joined.count { it.isLetter() } >= 12) {
                    sourceCheckedAt = now
                    val tag = runCatching { Tasks.await(langId.identifyLanguage(joined)) }.getOrNull()
                    val lang = tag?.takeIf { it != "und" }?.let { TranslateLanguage.fromLanguageTag(it) }
                    if (lang != null) latinSource = lang
                }
            }
            latinSource
        }
    }

    /** Cùng nội dung với khối ở khung trước → trượt 60% về vị trí mới (bớt rung); đổi chỗ xa thì nhảy luôn. */
    private fun smooth(blocks: List<PhotoTranslation.TextBlock>): List<PhotoTranslation.TextBlock> {
        if (prevBlocks.isEmpty()) return blocks
        val byText = prevBlocks.associateBy { it.text }
        return blocks.map { b ->
            val p = byText[b.text] ?: return@map b
            val lim = 2f * max(b.lineHeight, 1f)
            if (abs(p.box.cx - b.box.cx) > lim || abs(p.box.cy - b.box.cy) > lim || abs(p.angle - b.angle) > 3f) return@map b
            fun mix(a: Float, n: Float) = a + (n - a) * 0.6f
            PhotoTranslation.TextBlock(
                box = Box(mix(p.box.left, b.box.left), mix(p.box.top, b.box.top), mix(p.box.right, b.box.right), mix(p.box.bottom, b.box.bottom)),
                lines = b.lines, text = b.text, lang = b.lang,
                lineHeight = mix(p.lineHeight, b.lineHeight), angle = mix(p.angle, b.angle), confidence = b.confidence,
            )
        }
    }

    private fun recognizer(sc: Script): TextRecognizer = synchronized(recognizers) {
        recognizers.getOrPut(sc) {
            when (sc) {
                Script.LATIN -> TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
                Script.CHINESE -> TextRecognition.getClient(ChineseTextRecognizerOptions.Builder().build())
                Script.JAPANESE -> TextRecognition.getClient(JapaneseTextRecognizerOptions.Builder().build())
                Script.KOREAN -> TextRecognition.getClient(KoreanTextRecognizerOptions.Builder().build())
            }
        }
    }

    private fun translator(source: String): Translator = translators.getOrPut(source) {
        MlTranslation.getClient(
            TranslatorOptions.Builder().setSourceLanguage(source).setTargetLanguage(TranslateLanguage.VIETNAMESE).build(),
        )
    }

    /** Gói dịch [source]→Việt đã sẵn trên máy chưa; chưa thì tải nền (lần đầu, cần mạng) và báo trạng thái. */
    private fun ensureModel(source: String): Boolean {
        if (source in readyModels) return true
        val failed = failedAt[source]
        if (failed != null && SystemClock.elapsedRealtime() - failed < RETRY_MS) return false
        if (!downloading.add(source)) return false
        val name = languageName(source)
        modelStatus = "Đang tải gói dịch $name → tiếng Việt (~30 MB, chỉ lần đầu)…"
        scope.launch {
            try {
                translator(source).downloadModelIfNeeded(DownloadConditions.Builder().build()).awaitTask()
                readyModels.add(source)
                failedAt.remove(source)
                modelStatus = null
            } catch (e: Throwable) {
                failedAt[source] = SystemClock.elapsedRealtime()
                modelStatus = "Chưa tải được gói dịch $name (cần mạng lần đầu): ${e.message ?: "lỗi"}"
            } finally {
                downloading.remove(source)
            }
        }
        return false
    }

    private fun enqueue(key: String, source: String, text: String) {
        if (pending.size >= MAX_PENDING || !pending.add(key)) return
        scope.launch {
            try {
                val t = translator(source)
                val parts = text.split('\n')
                val out = parts.map { p -> if (p.isBlank()) p else t.translate(p.trim()).awaitTask() }.joinToString("\n")
                if (out.isNotBlank()) synchronized(cache) { cache[key] = out }
            } catch (_: Throwable) {
                // Dịch lỗi → khung sau thử lại.
            } finally {
                pending.remove(key)
            }
        }
    }

    private fun languageName(tag: String): String =
        runCatching { Locale(tag).getDisplayLanguage(Locale("vi")) }.getOrNull()?.takeIf { it.isNotBlank() } ?: tag

    private fun uprightBitmap(image: ImageProxy): Bitmap {
        val src = image.toBitmap()
        val rotation = image.imageInfo.rotationDegrees
        val k = minOf(1f, MAX_SIDE.toFloat() / maxOf(src.width, src.height))
        if (rotation == 0 && k >= 1f) return src
        val m = Matrix().apply {
            postScale(k, k)
            postRotate(rotation.toFloat())
        }
        val out = Bitmap.createBitmap(src, 0, 0, src.width, src.height, m, true)
        if (out !== src) src.recycle()
        return out
    }

    override fun close() {
        enabled = false
        scope.cancel()
        synchronized(recognizers) {
            recognizers.values.forEach { runCatching { it.close() } }
            recognizers.clear()
        }
        runCatching { langId.close() }
        translators.values.forEach { runCatching { it.close() } }
        translators.clear()
    }

    companion object {
        private const val MIN_INTERVAL_MS = 200L
        private const val LANG_CHECK_MS = 1500L
        private const val RETRY_MS = 15_000L
        private const val CACHE_SIZE = 500
        private const val MAX_PENDING = 40
        private const val MAX_SIDE = 1280
    }
}
