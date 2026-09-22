package com.scanx.app.convert

import kotlin.math.max
import kotlin.math.min

/**
 * Gộp kết quả của nhiều bộ OCR chạy trên cùng 1 trang (Kotlin thuần, kiểm thử được trên JVM).
 *
 * ML Kit v2 có bộ đọc riêng cho từng hệ chữ: Latin (Việt, Anh, Đức, Pháp…), Hàn, Nhật, Trung — bộ
 * Hàn/Nhật/Trung cũng đọc được chữ Latin nhưng kém dấu tiếng Việt hơn bộ Latin. Cách gộp:
 *  1. Dòng của bộ Hàn/Nhật/Trung chỉ được nhận khi thực sự chứa hệ chữ đó (≥ 30% ký tự) — đúng
 *     loại: Hangul từ bộ Hàn, Kana từ bộ Nhật, Hán tự từ bộ Trung (hoặc bộ Nhật nếu trang có Kana).
 *  2. Ứng viên xếp theo (độ tin cậy × số ký tự đúng hệ chữ); nhận tham lam, không chồng lấn.
 *  3. Dòng Latin giữ lại nếu không bị dòng CJK nào đã nhận che quá 50%.
 * → Tài liệu song ngữ Hàn–Việt: dòng Hàn lấy từ bộ Hàn, dòng Việt giữ đúng dấu từ bộ Latin.
 */
object ScriptMerge {

    /** [byRecognizer]: "ko" / "ja" / "zh" → các dòng bộ đó đọc được. Dòng trả về đã gán [OcrLine.lang]. */
    fun merge(latin: List<OcrLine>, byRecognizer: Map<String, List<OcrLine>>): List<OcrLine> {
        val pageHasKana = byRecognizer["ja"].orEmpty().any { Lang.count(it.text).kana > 0 }

        class Cand(val line: OcrLine, val score: Float)
        val cands = ArrayList<Cand>()
        for ((rec, lines) in byRecognizer) {
            for (l in lines) {
                val c = Lang.count(l.text)
                if (c.letters == 0) continue
                val own = when (rec) {
                    "ko" -> c.hangul
                    "ja" -> c.kana + (if (pageHasKana) c.han else 0)
                    "zh" -> if (pageHasKana) 0 else c.han
                    else -> 0
                }
                if (own == 0 || own < c.letters * 0.3f) continue
                val lang = if (rec == "zh" || (rec == "ja" && c.kana == 0 && !pageHasKana)) "zh" else rec
                val conf = if (l.confidence > 0f) l.confidence else 0.6f
                cands.add(Cand(l.withLang(lang), conf * own))
            }
        }
        cands.sortByDescending { it.score }
        val accepted = ArrayList<OcrLine>()
        for (c in cands) {
            if (accepted.none { overlapRatio(it.box, c.line.box) > 0.5f }) accepted.add(c.line)
        }
        val out = ArrayList<OcrLine>(accepted)
        for (l in latin) {
            if (accepted.none { overlapRatio(it.box, l.box) > 0.5f }) {
                out.add(if (l.lang.isEmpty()) l.withLang(Lang.detect(l.text)) else l)
            }
        }
        return out.sortedWith(compareBy({ it.box.top }, { it.box.left }))
    }

    /** Diện tích giao / diện tích hộp nhỏ hơn. */
    fun overlapRatio(a: Box, b: Box): Float {
        val w = min(a.right, b.right) - max(a.left, b.left)
        val h = min(a.bottom, b.bottom) - max(a.top, b.top)
        if (w <= 0f || h <= 0f) return 0f
        val small = min(a.width * a.height, b.width * b.height)
        return if (small <= 0f) 0f else w * h / small
    }

    private fun OcrLine.withLang(lang: String) = copy(lang = lang, words = words.map { it.copy(lang = lang) })
}
