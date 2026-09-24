package com.scanx.app.scan

import kotlin.math.sqrt

/**
 * Phép toán thuần Kotlin trên "chữ ký" nội dung trang (ảnh xám 48×64 đã lọc thông cao, xem
 * [AiDocumentDetector.pageSignature]). Tách riêng (bản 0.9) để máy trạng thái tự chụp test được
 * trên JVM mà không cần OpenCV.
 */
object PageSignature {
    const val SIG_W = 48
    const val SIG_H = 64
    const val BLANK_TEXTURE = 0.012f

    /** Độ "có nội dung" của trang (độ lệch chuẩn chữ ký): ≈ 0 với giấy trắng, ≥ 0,03 khi có chữ.
     *  Với chữ ký chống răng cưa ([AiDocumentDetector.pageSignatureSharp]) còn dùng làm thước đo độ nét. */
    fun texture(sig: FloatArray): Float {
        var mean = 0.0
        for (v in sig) mean += v
        mean /= sig.size
        var s = 0.0
        for (v in sig) s += (v - mean) * (v - mean)
        return sqrt(s / sig.size).toFloat()
    }

    /**
     * Độ giống nhau của 2 trang, -1..1. Trang trắng/ít nội dung được xử lý riêng: 2 trang cùng trắng
     * → 1 (coi như giống, chỉ phân biệt được nhờ rút giấy ra/đổi vị trí); 1 trắng 1 có chữ → 0.
     */
    fun similarity(a: FloatArray, b: FloatArray): Float {
        if (a.size != b.size || a.isEmpty()) return 0f
        val sa = texture(a)
        val sb = texture(b)
        if (sa < BLANK_TEXTURE && sb < BLANK_TEXTURE) return 1f
        if (minOf(sa, sb) < BLANK_TEXTURE && maxOf(sa, sb) > BLANK_TEXTURE * 1.7f) return 0f
        var ma = 0.0
        var mb = 0.0
        for (i in a.indices) { ma += a[i]; mb += b[i] }
        ma /= a.size; mb /= b.size
        var cov = 0.0
        for (i in a.indices) cov += (a[i] - ma) * (b[i] - mb)
        cov /= a.size
        return (cov / (sa.toDouble() * sb + 1e-6)).toFloat()
    }
}
