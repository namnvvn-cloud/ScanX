package com.scanx.app.convert

import android.graphics.Bitmap
import android.graphics.Rect
import com.google.mlkit.nl.languageid.LanguageIdentification
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.Text
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.TextRecognizer
import com.google.mlkit.vision.text.chinese.ChineseTextRecognizerOptions
import com.google.mlkit.vision.text.japanese.JapaneseTextRecognizerOptions
import com.google.mlkit.vision.text.korean.KoreanTextRecognizerOptions
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import com.scanx.app.util.awaitTask
import java.io.Closeable

/**
 * OCR đa ngôn ngữ offline: ML Kit Text Recognition v2 bộ Latin (Việt, Anh, Đức, Pháp, Tây Ban Nha…)
 * + bộ Hàn / Nhật / Trung (model nhúng sẵn trong APK), gộp theo hệ chữ từng dòng ([ScriptMerge]),
 * rồi gán ngôn ngữ từng dòng bằng ML Kit Language ID + quy tắc hệ chữ ([Lang.detect]).
 *
 * Tiết kiệm thời gian: nếu mọi dòng Latin đều đọc chắc chắn (độ tin cậy ≥ 0,6) → tài liệu chữ Latin,
 * bỏ qua bộ CJK. Ngược lại chạy lần lượt Hàn → (không có Hangul) Nhật → (không có Kana mà có Hán tự) Trung.
 */
class MultiScriptOcr : Closeable {

    private val latin: TextRecognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
    private val korean: TextRecognizer by lazy { TextRecognition.getClient(KoreanTextRecognizerOptions.Builder().build()) }
    private val japanese: TextRecognizer by lazy { TextRecognition.getClient(JapaneseTextRecognizerOptions.Builder().build()) }
    private val chinese: TextRecognizer by lazy { TextRecognition.getClient(ChineseTextRecognizerOptions.Builder().build()) }
    private val langId by lazy { LanguageIdentification.getClient() }
    private var usedCjk = false

    /** Bộ Latin (dùng cho việc chỉ cần chữ Latin, vd đoán chiều trang). */
    val latinRecognizer: TextRecognizer get() = latin

    suspend fun recognize(bmp: Bitmap): List<OcrLine> {
        val image = InputImage.fromBitmap(bmp, 0)
        val latinLines = toLines(latin.process(image).awaitTask())
        val needCjk = latinLines.isNotEmpty() && latinLines.any { it.confidence in 0.001f..0.6f }
        val merged = if (!needCjk) {
            latinLines
        } else {
            usedCjk = true
            val byRec = LinkedHashMap<String, List<OcrLine>>()
            val ko = toLines(korean.process(image).awaitTask())
            byRec["ko"] = ko
            if (ko.none { Lang.count(it.text).hangul > 0 }) {
                val ja = toLines(japanese.process(image).awaitTask())
                byRec["ja"] = ja
                val hasKana = ja.any { Lang.count(it.text).kana > 0 }
                if (!hasKana && ja.any { Lang.count(it.text).han > 0 }) {
                    byRec["zh"] = toLines(chinese.process(image).awaitTask())
                }
            }
            ScriptMerge.merge(latinLines, byRec)
        }
        return merged.map { l -> identify(l) }
    }

    /** Gán ngôn ngữ cho dòng chữ Latin bằng Language ID (dòng CJK đã có ngôn ngữ theo hệ chữ). */
    private suspend fun identify(l: OcrLine): OcrLine {
        if (Lang.isEastAsian(l.lang)) return l
        val hint = if (l.text.length >= 12) {
            runCatching { langId.identifyLanguage(l.text).awaitTask() }.getOrNull()
        } else {
            null
        }
        val lang = Lang.detect(l.text, hint)
        return l.copy(lang = lang, words = l.words.map { it.copy(lang = lang) })
    }

    private fun Rect.toBox() = Box(left.toFloat(), top.toFloat(), right.toFloat(), bottom.toFloat())

    private fun toLines(text: Text): List<OcrLine> {
        val out = ArrayList<OcrLine>()
        for (block in text.textBlocks) for (line in block.lines) {
            val rect = line.boundingBox ?: continue
            val words = line.elements.mapNotNull { el -> el.boundingBox?.let { OcrWord(el.text, it.toBox(), 0, el.confidence) } }
            out.add(OcrLine(line.text, rect.toBox(), words, 0f, 0, line.confidence))
        }
        return out
    }

    override fun close() {
        latin.close()
        if (usedCjk) {
            runCatching { korean.close() }
            runCatching { japanese.close() }
            runCatching { chinese.close() }
        }
        runCatching { langId.close() }
    }
}
