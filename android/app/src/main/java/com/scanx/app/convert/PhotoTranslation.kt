package com.scanx.app.convert

import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

/**
 * "Chụp để dịch" / "Dịch trực tiếp" — phần thuần Kotlin (test được trên JVM): gom các dòng OCR thành KHỐI
 * chữ (đoạn văn / ô / nhãn) để dịch theo ngữ cảnh cả khối và vẽ bản dịch đè đúng vùng khối đó, giống
 * Google Dịch.
 *
 * Bản 1.1 — hình học XOAY: trang chụp thường nghiêng vài độ, khung chữ nhật thẳng của một dòng dài bị
 * phình cao (dòng 900 px nghiêng 2° cao thêm ~30 px) → cỡ chữ bản dịch to hơn chữ gốc, các khối chồng lên
 * nhau, nhãn "Jeff:"/"Alyssa:" bị nuốt vào đoạn bên cạnh. Nay đo góc nghiêng trang từ 4 góc thật của dòng
 * (ML Kit cornerPoints), xoay mọi dòng về thẳng ("khung thẳng"), gom khối trong khung thẳng, rồi vẽ lại
 * theo đúng góc nghiêng ([TextBlock.angle]).
 */
object PhotoTranslation {

    /**
     * [box] nằm trong KHUNG THẲNG: ảnh đã xoay đi −[angle] độ quanh gốc toạ độ (0,0). Điểm (x,y) của khung
     * thẳng ứng với điểm [toImage] (x,y) trên ảnh gốc. [angle] = 0 → khung thẳng trùng ảnh.
     */
    class TextBlock(
        val box: Box,
        val lines: List<OcrLine>,
        val text: String,
        val lang: String,
        /** Chiều cao dòng chữ tiêu biểu (trung vị, đo theo 4 góc thật) — dùng chọn cỡ chữ khi vẽ bản dịch. */
        val lineHeight: Float,
        /** Góc nghiêng (độ, chiều kim đồng hồ trên màn hình) của chữ trên ảnh. */
        val angle: Float = 0f,
        /** Độ tin cậy OCR trung bình của khối (0 = ML Kit không báo). */
        val confidence: Float = 0f,
    ) {
        fun toImage(x: Float, y: Float): Pair<Float, Float> = rotate(x, y, angle)
    }

    /** Xoay điểm (x,y) quanh gốc toạ độ [deg] độ (toạ độ ảnh, trục y hướng xuống — giống Canvas.rotate). */
    fun rotate(x: Float, y: Float, deg: Float): Pair<Float, Float> {
        if (deg == 0f) return x to y
        val r = Math.toRadians(deg.toDouble())
        val c = cos(r).toFloat()
        val s = sin(r).toFloat()
        return (x * c - y * s) to (x * s + y * c)
    }

    /** Góc nghiêng (độ) của 1 dòng theo 4 góc thật: trung bình cạnh trên và cạnh dưới. */
    fun lineAngle(l: OcrLine): Float? {
        val q = l.quad ?: return null
        val a1 = atan2((q[3] - q[1]).toDouble(), (q[2] - q[0]).toDouble())
        val a2 = atan2((q[5] - q[7]).toDouble(), (q[4] - q[6]).toDouble())
        return Math.toDegrees((a1 + a2) / 2.0).toFloat()
    }

    /**
     * Góc nghiêng chung của chữ trên ảnh: trung vị có trọng số (theo bề dài dòng) góc các dòng đủ dài
     * (dài ≥ 3 lần cao). Bỏ qua khi |góc| < 0,3° (coi như thẳng) hoặc > 45° (chữ dọc — giữ khung thẳng).
     */
    fun pageAngle(lines: List<OcrLine>): Float {
        val samples = lines.mapNotNull { l ->
            val a = lineAngle(l) ?: return@mapNotNull null
            val (w, h) = trueSize(l) ?: return@mapNotNull null
            if (w < 3f * h) null else a to w
        }.sortedBy { it.first }
        if (samples.isEmpty()) return 0f
        val total = samples.sumOf { it.second.toDouble() }
        var acc = 0.0
        var median = samples.last().first
        for ((a, w) in samples) {
            acc += w
            if (acc >= total / 2) { median = a; break }
        }
        return if (abs(median) < 0.3f || abs(median) > 45f) 0f else median
    }

    /** Bề dài và chiều cao thật của dòng (theo 4 góc), null nếu không có 4 góc. */
    fun trueSize(l: OcrLine): Pair<Float, Float>? {
        val q = l.quad ?: return null
        val w = (hypot(q[2] - q[0], q[3] - q[1]) + hypot(q[4] - q[6], q[5] - q[7])) / 2f
        val h = (hypot(q[6] - q[0], q[7] - q[1]) + hypot(q[4] - q[2], q[5] - q[3])) / 2f
        return if (w > 0f && h > 0f) w to h else null
    }

    /** Dòng trong khung thẳng: tâm xoay đi −[angle], kích thước = kích thước thật (không phình). */
    fun deskew(l: OcrLine, angle: Float): OcrLine {
        val size = trueSize(l)
        if (size == null) {
            if (angle == 0f) return l
            val pts = listOf(
                rotate(l.box.left, l.box.top, -angle), rotate(l.box.right, l.box.top, -angle),
                rotate(l.box.right, l.box.bottom, -angle), rotate(l.box.left, l.box.bottom, -angle),
            )
            return l.copy(box = Box(pts.minOf { it.first }, pts.minOf { it.second }, pts.maxOf { it.first }, pts.maxOf { it.second }))
        }
        val q = l.quad!!
        val cx = (q[0] + q[2] + q[4] + q[6]) / 4f
        val cy = (q[1] + q[3] + q[5] + q[7]) / 4f
        val (x, y) = rotate(cx, cy, -angle)
        val (w, h) = size
        return l.copy(box = Box(x - w / 2f, y - h / 2f, x + w / 2f, y + h / 2f))
    }

    /**
     * Gom dòng thành khối (trong khung thẳng). Một dòng nối vào khối đang mở nếu: cùng khối ML Kit
     * ([OcrLine.blockId], khi có), cùng nhóm hệ chữ (CJK / không CJK), cao gần bằng nhau (tỉ lệ 0,7–1,4),
     * khoảng trống dọc ≤ 0,8 × chiều cao dòng, và chồng lấn ngang đủ (≥ 30% bề rộng dòng hẹp hơn) hoặc thẳng
     * lề trái. Dòng không nối được khối nào thì mở khối mới — 2 cột chữ cạnh nhau tách thành 2 khối. Trong
     * khối, chỗ xuống đoạn/mục liệt kê được giữ bằng "\n" ([joinLines]).
     */
    fun groupBlocks(lines: List<OcrLine>): List<TextBlock> {
        val valid = lines.filter { it.text.isNotBlank() && it.box.height > 0f && it.box.width > 0f }
        val angle = pageAngle(valid)
        val sorted = valid.map { deskew(it, angle) }
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
            val conf = g.map { it.confidence }.filter { it > 0f }
            TextBlock(
                box = box,
                lines = g,
                text = joinLines(g),
                lang = dominantLang(g),
                lineHeight = heights[heights.size / 2],
                angle = angle,
                confidence = if (conf.isEmpty()) 0f else conf.average().toFloat(),
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
            // Dòng dạng "Nhãn: giá trị" liên tiếp (biểu mẫu, hội thoại) → mỗi mục 1 dòng.
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

    /** Dòng chỉ là nhãn tên người nói/tên riêng: "Jeff:", "Alyssa:", "A:", "B:" → giữ nguyên, không dịch. */
    private val SPEAKER = Regex("^[\\p{Lu}][\\p{L}'.-]{0,14}\\s?[:：]$")

    /**
     * Khối cần dịch sang [target]: bỏ khối đã đúng ngôn ngữ đích, khối chỉ có số/ký hiệu, khối toàn nhãn
     * người nói ("Jeff:"), và khối OCR đọc kém (độ tin cậy < 0,35 — thường là chữ viết tay/nhoè: dịch ra
     * chữ vô nghĩa đè lên còn tệ hơn giữ nguyên).
     */
    fun needsTranslation(block: TextBlock, target: String = Translation.TARGET): Boolean {
        if (block.lang == target) return false
        if (block.text.none { it.isLetter() }) return false
        if (block.lines.all { SPEAKER.matches(it.text.trim()) }) return false
        if (block.confidence in 0.001f..0.35f) return false
        return true
    }

    /**
     * Tách văn bản các khối thành từng đoạn theo "\n" để dịch (máy dịch hay gộp mất chỗ xuống dòng → danh
     * sách/hội thoại bị dồn thành 1 đoạn). Trả danh sách đoạn phẳng và cách ghép lại bằng [joinSegments].
     */
    fun splitSegments(texts: List<String>): Pair<List<String>, List<Int>> {
        val flat = ArrayList<String>()
        val counts = ArrayList<Int>()
        for (t in texts) {
            val parts = t.split('\n').map { it.trim() }.filter { it.isNotEmpty() }
            val use = parts.ifEmpty { listOf(t) }
            flat.addAll(use)
            counts.add(use.size)
        }
        return flat to counts
    }

    /** Ghép bản dịch từng đoạn ([splitSegments]) về theo khối; khối thiếu bản dịch mọi đoạn → null. */
    fun joinSegments(translated: List<String?>, counts: List<Int>, originals: List<String>? = null): List<String?> {
        val out = ArrayList<String?>()
        var k = 0
        for ((bi, n) in counts.withIndex()) {
            val part = (0 until n).map { translated.getOrNull(k + it) }
            k += n
            if (part.all { it.isNullOrBlank() }) { out.add(null); continue }
            val origParts = originals?.getOrNull(bi)?.split('\n')?.map { it.trim() }?.filter { it.isNotEmpty() }
            out.add(part.mapIndexed { i, s -> s?.takeIf { it.isNotBlank() } ?: origParts?.getOrNull(i).orEmpty() }.joinToString("\n"))
        }
        return out
    }
}
