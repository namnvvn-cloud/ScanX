package com.scanx.app.scan

import android.graphics.PointF
import kotlin.math.abs
import kotlin.math.hypot

/**
 * Chọn 4 góc tài liệu từ 4 heatmap của model DocAligner (mỗi heatmap [w]×[h], thường 128×128) —
 * thuần Kotlin (bản 0.9) để test được trên JVM với heatmap thật xuất từ model.
 *
 * Khác bản cũ (luôn lấy đỉnh cao nhất toàn ảnh): khi đang theo dõi một tài liệu ([priors] = 4 góc
 * hiện tại), mỗi heatmap ưu tiên đỉnh CỤC BỘ gần góc đang theo dõi. Đo trên cảnh thật (giấy trên bàn
 * gỗ, có dây cáp/giấy khác sát góc): heatmap thường có 2 đỉnh gần nhau về độ cao, lấy đỉnh toàn cục
 * làm góc nhảy qua lại ~10% khung giữa các khung liền nhau → khung xanh "nháy", bộ đếm giữ yên bị
 * reset liên tục. Lấy đỉnh cục bộ loại bỏ hoàn toàn các cú nhảy này.
 */
object HeatmapPeaks {

    class Peak(val x: Float, val y: Float, val value: Float)

    /** 4 góc đã xếp theo hình học TL/TR/BR/BL, [confidences] cùng thứ tự với [points]. */
    class Corners(val points: List<PointF>, val confidences: FloatArray) {
        val minConfidence: Float get() = confidences.minOrNull() ?: 0f
    }

    /** Đỉnh toàn cục + trọng tâm dưới-pixel. */
    fun globalPeak(hm: FloatArray, w: Int, h: Int): Peak? {
        var best = 0
        var bestV = Float.NEGATIVE_INFINITY
        for (i in hm.indices) {
            val v = hm[i]
            if (v > bestV) { bestV = v; best = i }
        }
        if (bestV <= 0f) return null
        return centroid(hm, w, h, best % w, best / w, bestV)
    }

    /** Đỉnh cục bộ trong cửa sổ bán kính [radius] (tỉ lệ bề rộng heatmap) quanh ([cx], [cy]) chuẩn hoá. */
    fun localPeak(hm: FloatArray, w: Int, h: Int, cx: Float, cy: Float, radius: Float): Peak? {
        val px = (cx * w).toInt()
        val py = (cy * h).toInt()
        val r = maxOf(2, (radius * w).toInt())
        val x0 = (px - r).coerceAtLeast(0)
        val x1 = (px + r).coerceAtMost(w - 1)
        val y0 = (py - r).coerceAtLeast(0)
        val y1 = (py + r).coerceAtMost(h - 1)
        if (x1 < x0 || y1 < y0) return null
        var bx = -1
        var by = -1
        var bestV = Float.NEGATIVE_INFINITY
        for (y in y0..y1) {
            val row = y * w
            for (x in x0..x1) {
                val v = hm[row + x]
                if (v > bestV) { bestV = v; bx = x; by = y }
            }
        }
        if (bx < 0 || bestV <= 0f) return null
        return centroid(hm, w, h, bx, by, bestV)
    }

    /** Trọng tâm có trọng số trong cửa sổ 7×7 quanh đỉnh (chỉ lấy điểm ≥ 50% đỉnh) — giống bản 0.4+. */
    private fun centroid(hm: FloatArray, w: Int, h: Int, px: Int, py: Int, maxVal: Float): Peak? {
        val r = 3
        val x0 = (px - r).coerceAtLeast(0)
        val x1 = (px + r).coerceAtMost(w - 1)
        val y0 = (py - r).coerceAtLeast(0)
        val y1 = (py + r).coerceAtMost(h - 1)
        val cutoff = maxVal * 0.5f
        var sw = 0.0
        var sx = 0.0
        var sy = 0.0
        for (y in y0..y1) {
            val row = y * w
            for (x in x0..x1) {
                val v = hm[row + x]
                if (v >= cutoff) {
                    sw += v
                    sx += v.toDouble() * x
                    sy += v.toDouble() * y
                }
            }
        }
        if (sw <= 0.0) return null
        val nx = ((sx / sw + 0.5) / w).toFloat().coerceIn(0f, 1f)
        val ny = ((sy / sw + 0.5) / h).toFloat().coerceIn(0f, 1f)
        return Peak(nx, ny, maxVal)
    }

    /**
     * 4 heatmap → 4 góc đã xếp TL/TR/BR/BL. [priors] (nếu có) = 4 góc đang theo dõi: với mỗi heatmap,
     * tìm góc theo dõi gần đỉnh toàn cục nhất rồi lấy đỉnh cục bộ quanh góc đó, miễn là đỉnh cục bộ đủ
     * mạnh (≥ [keepConfidence] và ≥ 50% đỉnh toàn cục). Không có [priors] → dùng đỉnh toàn cục.
     */
    fun extract(
        heatmaps: List<FloatArray>,
        w: Int,
        h: Int,
        priors: List<PointF>?,
        localRadius: Float = 0.09f,
        keepConfidence: Float = 0.25f,
    ): Corners? {
        if (heatmaps.size < 4) return null
        val xs = FloatArray(4)
        val ys = FloatArray(4)
        val cs = FloatArray(4)
        for (c in 0 until 4) {
            val g = globalPeak(heatmaps[c], w, h) ?: return null
            var px = g.x
            var py = g.y
            var pv = g.value
            if (priors != null && priors.size == 4) {
                var nearest = 0
                var nd = Float.MAX_VALUE
                for (i in 0 until 4) {
                    val d = hypot(g.x - priors[i].x, g.y - priors[i].y)
                    if (d < nd) { nd = d; nearest = i }
                }
                val loc = localPeak(heatmaps[c], w, h, priors[nearest].x, priors[nearest].y, localRadius)
                if (loc != null && loc.value >= maxOf(keepConfidence, 0.5f * g.value)) {
                    px = loc.x; py = loc.y; pv = loc.value
                }
            }
            xs[c] = px; ys[c] = py; cs[c] = pv
        }
        val idx = orderIndices(xs, ys)
        return Corners(
            points = idx.map { PointF(xs[it], ys[it]) },
            confidences = FloatArray(4) { cs[idx[it]] },
        )
    }

    /** Thứ tự TL/TR/BR/BL theo hình học (không phụ thuộc thứ tự kênh của model khi xoay máy). */
    fun orderIndices(xs: FloatArray, ys: FloatArray): IntArray {
        var tl = 0; var br = 0
        for (i in 1 until 4) {
            if (xs[i] + ys[i] < xs[tl] + ys[tl]) tl = i
            if (xs[i] + ys[i] > xs[br] + ys[br]) br = i
        }
        val rest = (0 until 4).filter { it != tl && it != br }
        if (tl == br || rest.size != 2) {
            var tr = 0; var bl = 0
            for (i in 1 until 4) {
                if (ys[i] - xs[i] < ys[tr] - xs[tr]) tr = i
                if (ys[i] - xs[i] > ys[bl] - xs[bl]) bl = i
            }
            return intArrayOf(tl, tr, br, bl)
        }
        val a = rest[0]; val b = rest[1]
        val tr = if (ys[a] - xs[a] <= ys[b] - xs[b]) a else b
        val bl = if (tr == a) b else a
        return intArrayOf(tl, tr, br, bl)
    }

    /** Diện tích tứ giác (tỉ lệ khung) theo công thức shoelace. */
    fun area(p: List<PointF>): Float {
        var s = 0f
        for (i in p.indices) {
            val a = p[i]
            val b = p[(i + 1) % p.size]
            s += a.x * b.y - b.x * a.y
        }
        return abs(s) / 2f
    }

    /** Tứ giác lồi (mọi tích có hướng của 2 cạnh liên tiếp cùng dấu, khác 0). */
    fun isConvex(p: List<PointF>): Boolean {
        if (p.size != 4) return false
        var sign = 0
        for (i in 0 until 4) {
            val a = p[i]; val b = p[(i + 1) % 4]; val c = p[(i + 2) % 4]
            val cross = (b.x - a.x) * (c.y - b.y) - (b.y - a.y) * (c.x - b.x)
            if (abs(cross) < 1e-9f) return false
            val s = if (cross > 0) 1 else -1
            if (sign == 0) sign = s else if (s != sign) return false
        }
        return true
    }
}
