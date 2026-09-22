package com.scanx.app.scan

import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 * Tính kích thước ảnh đầu ra "đúng như thật" cho trang giấy chụp nghiêng (Kotlin thuần, kiểm thử
 * được trên JVM).
 *
 * Cách cũ lấy cạnh dài nhất của tứ giác → trang chụp chéo bị bóp/giãn (A4 ra 1:1,3 hoặc 1:1,6).
 * Cách mới: khôi phục tỉ lệ thật của hình chữ nhật từ phép chiếu phối cảnh theo Zhang & He,
 * "Whiteboard scanning and image enhancement" (Digital Signal Processing, 2007): ước lượng tiêu cự
 * từ 2 điểm tụ của tứ giác, rồi suy tỉ lệ rộng/cao thật (khi chỉ nghiêng 1 trục thì dùng tiêu cự
 * điển hình). Kiểm thử mô phỏng: sai số tỉ lệ < 1% ở góc nghiêng tới ~30°, trong khi cách cũ lệch
 * 6–9%. Tỉ lệ gần khổ giấy chuẩn (A4/A5, Letter, Legal) trong ±2% được chốt đúng khổ chuẩn.
 */
object PageGeometry {

    private val STANDARD_RATIOS = doubleArrayOf(
        sqrt(2.0),          // A-series (A3/A4/A5)
        11.0 / 8.5,         // Letter
        14.0 / 8.5,         // Legal
    )

    /**
     * [corners] toạ độ pixel theo thứ tự TL, TR, BR, BL trên ảnh kích thước [imageW]×[imageH].
     * Trả về (rộng, cao) pixel của ảnh làm phẳng, cạnh dài không vượt [maxSide].
     */
    fun outputSize(corners: List<DoubleArray>, imageW: Int, imageH: Int, maxSide: Int): Pair<Int, Int> {
        val tl = corners[0]; val tr = corners[1]; val br = corners[2]; val bl = corners[3]
        val wAvg = (dist(tl, tr) + dist(bl, br)) / 2
        val hAvg = (dist(tl, bl) + dist(tr, br)) / 2
        val wMax = max(dist(tl, tr), dist(bl, br))
        val hMax = max(dist(tl, bl), dist(tr, br))
        var ratio = trueAspectRatio(corners, imageW / 2.0, imageH / 2.0) ?: (wAvg / hAvg)
        ratio = snapRatio(ratio)
        // Chiều dài theo độ phân giải thực có trên ảnh (cạnh dài nhất), không phóng to vô ích.
        var outH: Double
        var outW: Double
        if (ratio >= 1.0) {
            outW = max(wMax, hMax * ratio)
            outH = outW / ratio
        } else {
            outH = max(hMax, wMax / ratio)
            outW = outH * ratio
        }
        val k = maxSide / max(outW, outH)
        if (k < 1.0) { outW *= k; outH *= k }
        return outW.roundToInt().coerceAtLeast(1) to outH.roundToInt().coerceAtLeast(1)
    }

    /** Tỉ lệ rộng/cao thật, hoặc null khi tứ giác suy biến. (u0, v0) = tâm ảnh. */
    fun trueAspectRatio(corners: List<DoubleArray>, u0: Double, v0: Double): Double? {
        // Theo ký hiệu bài báo: m1 = TL, m2 = TR, m3 = BL, m4 = BR (toạ độ đồng nhất, gốc tại tâm ảnh).
        fun h(p: DoubleArray) = doubleArrayOf(p[0] - u0, p[1] - v0, 1.0)
        val m1 = h(corners[0]); val m2 = h(corners[1]); val m4 = h(corners[2]); val m3 = h(corners[3])
        val d2 = dot(cross(m2, m4), m3)
        val d3 = dot(cross(m3, m4), m2)
        if (abs(d2) < 1e-9 || abs(d3) < 1e-9) return null
        val k2 = dot(cross(m1, m4), m3) / d2
        val k3 = dot(cross(m1, m4), m2) / d3
        val n2 = DoubleArray(3) { k2 * m2[it] - m1[it] }
        val n3 = DoubleArray(3) { k3 * m3[it] - m1[it] }
        val scale = max(u0, v0) * 2
        // Tiêu cự ước lượng từ 2 điểm tụ; khi 1 cặp cạnh gần song song (chỉ nghiêng theo 1 trục)
        // công thức suy biến → dùng tiêu cự điển hình camera chính điện thoại (~0,85 × cạnh dài ảnh,
        // tương đương ống kính 26–28 mm). Sai số tiêu cự ±20% chỉ làm tỉ lệ lệch < 1% ở góc nghiêng thường gặp.
        val prior = (0.85 * scale) * (0.85 * scale)
        val f2Est = if (abs(n2[2] * n3[2]) < 1e-12) Double.NaN else -(n2[0] * n3[0] + n2[1] * n3[1]) / (n2[2] * n3[2])
        val f2 = if (f2Est.isNaN() || f2Est < 0.25 * scale * scale || f2Est > 9.0 * scale * scale) prior else f2Est
        val num = n2[0] * n2[0] + n2[1] * n2[1] + f2 * n2[2] * n2[2]
        val den = n3[0] * n3[0] + n3[1] * n3[1] + f2 * n3[2] * n3[2]
        if (num <= 0 || den <= 0) return null
        val r = sqrt(num / den)
        return if (r.isFinite() && r in 0.2..5.0) r else null
    }

    private fun snapRatio(r: Double): Double {
        val long = if (r >= 1) r else 1 / r
        val best = STANDARD_RATIOS.minByOrNull { abs(it - long) / it }!!
        if (abs(best - long) / best > 0.02) return r
        return if (r >= 1) best else 1 / best
    }

    private fun dist(a: DoubleArray, b: DoubleArray) = hypot(a[0] - b[0], a[1] - b[1])
    private fun dot(a: DoubleArray, b: DoubleArray) = a[0] * b[0] + a[1] * b[1] + a[2] * b[2]
    private fun cross(a: DoubleArray, b: DoubleArray) = doubleArrayOf(
        a[1] * b[2] - a[2] * b[1], a[2] * b[0] - a[0] * b[2], a[0] * b[1] - a[1] * b[0],
    )
}
