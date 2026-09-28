package com.scanx.app.data

import android.graphics.Bitmap

/**
 * Chế độ "Sách": ảnh chụp 2 trang sách mở (ảnh nằm ngang) → tách thành trang trái + trang phải theo
 * gáy sách (cột tối nhất trong khoảng 40–60 % bề ngang — bóng ở gáy). Ảnh dọc (1 trang) giữ nguyên.
 */
object BookSplitter {
    fun split(src: Bitmap): List<Bitmap> {
        if (src.width < src.height * 1.15f) return listOf(src)
        val sw = 400
        val sh = (sw.toFloat() * src.height / src.width).toInt().coerceAtLeast(1)
        val small = Bitmap.createScaledBitmap(src, sw, sh, true)
        val from = (sw * 0.40f).toInt()
        val to = (sw * 0.60f).toInt()
        val column = IntArray(sh)
        var bestX = sw / 2
        var best = Double.MAX_VALUE
        // Trung bình 3 cột liền nhau để bớt nhiễu chữ.
        val means = DoubleArray(sw)
        for (x in from - 1..to + 1) {
            if (x < 0 || x >= sw) continue
            small.getPixels(column, 0, 1, x, 0, 1, sh)
            var sum = 0.0
            for (p in column) sum += 0.299 * ((p shr 16) and 255) + 0.587 * ((p shr 8) and 255) + 0.114 * (p and 255)
            means[x] = sum / sh
        }
        for (x in from until to) {
            val m = (means[(x - 1).coerceAtLeast(0)] + means[x] + means[(x + 1).coerceAtMost(sw - 1)]) / 3
            if (m < best) {
                best = m
                bestX = x
            }
        }
        if (small !== src) small.recycle()
        val cut = (bestX.toFloat() / sw * src.width).toInt().coerceIn(src.width / 3, src.width * 2 / 3)
        val left = Bitmap.createBitmap(src, 0, 0, cut, src.height)
        val right = Bitmap.createBitmap(src, cut, 0, src.width - cut, src.height)
        return listOf(left, right)
    }
}
