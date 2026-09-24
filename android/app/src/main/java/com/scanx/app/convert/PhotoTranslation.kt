package com.scanx.app.convert

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * "Chụp để dịch" (bản 1.0) — phần thuần Kotlin (test được trên JVM): gom các dòng OCR thành KHỐI chữ
 * (đoạn văn / ô / nhãn) để dịch theo ngữ cảnh cả khối và vẽ bản dịch đè đúng vùng khối đó, giống chế
 * độ "Quét" của Google Dịch. Dịch từng dòng rời rạc sẽ sai nghĩa (câu bị cắt giữa chừng) và chữ dịch
 * dài hơn không vừa dòng gốc.
 */
object PhotoTranslation {

    class TextBlock(
        val box: Box,
        val lines: List<OcrLine>,
        val text: String,
        val lang: String,
        /** Chiều cao dòng chữ tiêu biểu (trung vị) — dùng chọn cỡ chữ khi vẽ bản dịch. */
        val lineHeight: Float,
    )

    /**
     * Gom dòng thành khối. Một dòng nối vào khối đang mở nếu: cùng khối ML Kit ([OcrLine.blockId], khi
     * có), cùng nhóm hệ chữ (CJK / không CJK), cao gần bằng nhau (tỉ lệ 0,7–1,4), khoảng trống dọc ≤ 0,8 ×
     * chiều cao dòng, và chồng lấn ngang đủ (≥ 30% bề rộng dòng hẹp hơn) hoặc thẳng lề trái (lệch ≤ 1,5 ×
     * chiều cao dòng). Dòng không nối được khối nào thì mở khối mới — 2 cột chữ cạnh nhau tách thành 2 khối.
     * Trong khối, chỗ xuống đoạn/mục liệt kê được giữ bằng "\n" ([joinLines]) để bản dịch giữ đúng dòng.
     */
    fun groupBlocks(lines: List<OcrLine>): List<TextBlock> {
        val sorted = lines.filter { it.text.isNotBlank() && it.box.height > 0f && it.box.width > 0f }
            .sortedWith(compareBy({ it.box.top }, { it.box.left }))
        val groups = ArrayList<MutableList<OcrLine>>()
        for (line in sorted) {
            var best: MutableList<OcrLine>? = null
            var bestGap = Float.MAX_VALUE
            for (g in groups) {
                val last = g.last()
                if (!joinable(last, line)) continue
                val gap = line.box.top - last.box.bottom
                if (gap < bestGap) { bestGap = gap; best = g }
            }
            if (best != null) best.add(line) else groups.add(mutableListOf(line))
        }
        return groups.map { g ->
            val box = g.drop(1).fold(g.first().box) { acc, l -> acc.union(l.box) }
            val heights = g.map { it.box.height }.sorted()
            TextBlock(
                box = box,
                lines = g,
                text = joinLines(g),
                lang = dominantLang(g),
                lineHeight = heights[heights.size / 2],
            )
        }.sortedWith(compareBy({ it.box.top }, { it.box.left }))
    }

    private fun joinable(a: OcrLine, b: OcrLine): Boolean {
        if (a.blockId >= 0 && b.blockId >= 0 && a.blockId != b.blockId) return false
        if (Lang.isEastAsian(a.lang) != Lang.isEastAsian(b.lang)) return false
        val ha = a.box.height
        val hb = b.box.height
        val ratio = hb / ha
        if (ratio < 0.7f || ratio > 1.4f) return false
        val h = max(ha, hb)
        val gap = b.box.top - a.box.bottom
        if (gap > 0.8f * h || gap < -0.5f * h) return false
        val overlap = min(a.box.right, b.box.right) - max(a.box.left, b.box.left)
        val narrow = min(a.box.width, b.box.width)
        val aligned = abs(a.box.left - b.box.left) <= 1.5f * h
        return overlap >= 0.3f * narrow || (aligned && overlap > 0f)
    }

    /**
     * Nối các dòng của 1 khối: dòng bị ngắt giữa câu nối bằng dấu cách (bỏ gạch nối cuối dòng), chữ CJK
     * nối liền; hết đoạn/mục thì giữ xuống dòng "\n" — dòng trước ngắn hẳn (< 75% dòng rộng nhất khối),
     * hoặc kết thúc bằng : . ; ! ? mà dòng sau bắt đầu bằng chữ hoa/số/gạch đầu dòng.
     */
    fun joinLines(lines: List<OcrLine>): String {
        val widest = lines.maxOfOrNull { it.box.width } ?: 0f
        val sb = StringBuilder()
        for ((i, l) in lines.withIndex()) {
            val t = l.text.trim()
            if (i == 0) { sb.append(t); continue }
            val prev = lines[i - 1]
            val prevText = prev.text.trim()
            val prevCjk = Lang.isEastAsian(prev.lang)
            val first = t.firstOrNull()
            val startsItem = first != null && (first.isUpperCase() || first.isDigit() || first in "-•*–+·")
            val endsSentence = prevText.lastOrNull()?.let { it in ":.;!?。：；！？" } == true
            val shortPrev = prev.box.width < 0.75f * widest
            // Dòng dạng "Nhãn: giá trị" liên tiếp (biểu mẫu, danh sách) → mỗi mục 1 dòng.
            val labelPair = isLabelLine(prevText) && isLabelLine(t)
            val endsWithHyphen = sb.endsWith("-") && sb.length >= 2 && sb[sb.length - 2].isLetter()
            when {
                endsWithHyphen && first?.isLowerCase() == true -> { sb.setLength(sb.length - 1); sb.append(t) }
                labelPair || (shortPrev && (startsItem || prevCjk)) || (endsSentence && startsItem) -> sb.append('\n').append(t)
                prevCjk && Lang.isEastAsian(l.lang) -> sb.append(t)
                else -> sb.append(' ').append(t)
            }
        }
        return sb.toString()
    }

    private fun isLabelLine(t: String): Boolean {
        val i = t.indexOfFirst { it == ':' || it == '：' }
        return i in 1..(t.length * 6 / 10)
    }

    private fun dominantLang(lines: List<OcrLine>): String =
        lines.groupingBy { it.lang }.eachCount().maxByOrNull { it.value }?.key.orEmpty()

    /** Khối cần dịch sang [target] (bỏ khối đã đúng ngôn ngữ đích, khối chỉ có số/ký hiệu). */
    fun needsTranslation(block: TextBlock, target: String = Translation.TARGET): Boolean {
        if (block.lang == target) return false
        return block.text.any { it.isLetter() }
    }
}
