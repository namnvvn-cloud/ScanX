package com.scanx.app.convert

import kotlin.math.max
import kotlin.math.min

/**
 * Quy màu mực đo được (trung bình các điểm mực của 1 dòng/từ) về bảng màu chữ chuẩn của văn bản:
 * đen, xanh dương, đỏ, xanh lá, tím. Màu đo trên ảnh chụp luôn bị nhạt/ám (ánh sáng, JPEG) — quy về
 * màu chuẩn cho văn bản Word/Excel sạch, đúng "mực xanh / mực đỏ" như bản gốc.
 */
object InkColor {
    const val BLACK = 0
    const val BLUE = 0x1F3A93
    const val RED = 0xC0392B
    const val GREEN = 0x1E7B34
    const val PURPLE = 0x6A1B9A

    /** [r], [g], [b] 0..255 (trung bình điểm mực). */
    fun classify(r: Int, g: Int, b: Int): Int {
        val mx = max(r, max(g, b))
        val mn = min(r, min(g, b))
        val chroma = mx - mn
        // Mực đen/xám hoặc gần như không màu.
        if (chroma < 28 || mx == 0 || chroma.toFloat() / mx < 0.22f) return BLACK
        val hue = when (mx) {
            r -> ((g - b).toFloat() / chroma).let { if (it < 0) it + 6f else it } * 60f
            g -> ((b - r).toFloat() / chroma + 2f) * 60f
            else -> ((r - g).toFloat() / chroma + 4f) * 60f
        }
        return when {
            hue < 20f || hue >= 330f -> RED
            hue < 70f -> BLACK // vàng/cam: thường là bút dạ quang tô nền, không phải màu chữ
            hue < 170f -> GREEN
            hue < 255f -> BLUE
            else -> PURPLE
        }
    }
}
