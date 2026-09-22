package com.scanx.app.scan

import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 * Tinh chỉnh 4 góc AI tìm được xuống mức dưới-pixel trên ảnh độ phân giải cao (Kotlin thuần, chạy
 * trên mảng xám → kiểm thử được trên JVM).
 *
 * Heatmap DocAligner có độ phân giải 128×128 nên góc lệch 0,3–1% khung (tới ~20 px trên ảnh 2400 px)
 * → mép giấy còn viền nền hoặc bị cắt lẹm. Cách làm: dọc mỗi cạnh lấy 24 mặt cắt vuông góc trong dải
 * ±1,5% đường chéo, ở mỗi mặt cắt chọn đỉnh gradient (theo pháp tuyến cạnh) gần đường dự đoán nhất
 * trong các đỉnh ≥ 60% đỉnh mạnh nhất (tránh bắt nhầm dòng kẻ/chữ gần mép), rồi khớp đường thẳng
 * bền vững (IRLS, trọng số Huber). Cạnh chỉ được nhận khi lệch góc ≤ 2° so với dự đoán và ≥ 60%
 * điểm nằm sát đường; giao 2 cạnh kề → góc mới, chỉ chấp nhận nếu dịch ≤ 1,5 × độ rộng dải.
 * Kiểm thử trên ảnh chụp thật: mép giấy khớp sát, các cạnh không chắc chắn giữ nguyên kết quả AI.
 */
object CornerRefiner {

    private const val SAMPLES = 24

    /** [gray] ảnh xám (đã làm mờ nhẹ) [w]×[h]; [corners] pixel TL, TR, BR, BL. Trả về góc đã tinh chỉnh. */
    fun refine(gray: ByteArray, w: Int, h: Int, corners: List<DoubleArray>): List<DoubleArray> {
        val band = max(6.0, hypot(w.toDouble(), h.toDouble()) * 0.015)
        fun px(x: Int, y: Int): Int = gray[y.coerceIn(0, h - 1) * w + x.coerceIn(0, w - 1)].toInt() and 0xFF

        val lines = arrayOfNulls<DoubleArray>(4) // (x0, y0, vx, vy)
        for (i in 0 until 4) {
            val a = corners[i]
            val b = corners[(i + 1) % 4]
            val dx = b[0] - a[0]
            val dy = b[1] - a[1]
            val len = hypot(dx, dy)
            if (len < 20) continue
            val tx = dx / len
            val ty = dy / len
            // Pháp tuyến hướng vào trong tứ giác → phân biệt "giấy sáng hơn nền" hay ngược lại.
            val mxC = corners.sumOf { it[0] } / 4 - (a[0] + b[0]) / 2
            val myC = corners.sumOf { it[1] } / 4 - (a[1] + b[1]) / 2
            val inward = if (mxC * (-ty) + myC * tx >= 0) 1.0 else -1.0
            val nx = -ty * inward
            val ny = tx * inward
            val steps = (2 * band).toInt() + 1
            val byPolarity = arrayOf(ArrayList<DoubleArray>(), ArrayList<DoubleArray>())
            val gp = DoubleArray(steps)
            for (s in 0 until SAMPLES) {
                val f = 0.1 + 0.8 * s / (SAMPLES - 1)
                val cx = a[0] + dx * f
                val cy = a[1] + dy * f
                var mx = 0.0
                for (k in 0 until steps) {
                    val off = -band + k
                    val x = cx + nx * off
                    val y = cy + ny * off
                    // Gradient có dấu theo pháp tuyến (trong − ngoài), 2 phía cách 1,5 px.
                    val g1 = px((x + nx * 1.5).roundToInt(), (y + ny * 1.5).roundToInt())
                    val g0 = px((x - nx * 1.5).roundToInt(), (y - ny * 1.5).roundToInt())
                    gp[k] = (g1 - g0).toDouble()
                    if (abs(gp[k]) > mx) mx = abs(gp[k])
                }
                if (mx < 20) continue
                for (pol in 0..1) {
                    val sign = if (pol == 0) 1.0 else -1.0
                    var bestK = -1
                    var bestOff = Double.MAX_VALUE
                    for (k in 1 until steps - 1) {
                        val v = gp[k] * sign
                        if (v >= 0.6 * mx && v >= gp[k - 1] * sign && v >= gp[k + 1] * sign) {
                            val off = abs(-band + k)
                            if (off < bestOff) { bestOff = off; bestK = k }
                        }
                    }
                    if (bestK >= 0) {
                        val off = -band + bestK
                        byPolarity[pol].add(doubleArrayOf(cx + nx * off, cy + ny * off))
                    }
                }
            }
            // Mép giấy có cùng 1 kiểu tương phản suốt chiều dài cạnh → chọn chiều chiếm đa số.
            val pts = if (byPolarity[0].size >= byPolarity[1].size) byPolarity[0] else byPolarity[1]
            if (pts.size < 8) continue
            val line = fitLineRobust(pts) ?: continue
            val angle = Math.toDegrees(abs(atan2(line[2] * ty - line[3] * tx, line[2] * tx + line[3] * ty)))
            val ang = min(angle, 180 - angle)
            val inliers = pts.count { abs((it[0] - line[0]) * line[3] - (it[1] - line[1]) * line[2]) < 3.0 }
            if (ang > 2.0 || inliers < pts.size * 0.6) continue
            lines[i] = line
        }

        return (0 until 4).map { i ->
            val l1 = lines[(i + 3) % 4]
            val l2 = lines[i]
            val orig = corners[i]
            if (l1 == null || l2 == null) return@map orig
            val c = intersect(l1, l2) ?: return@map orig
            if (hypot(c[0] - orig[0], c[1] - orig[1]) < band * 1.5) c else orig
        }
    }

    /** Khớp đường thẳng tổng bình phương nhỏ nhất có trọng số Huber (IRLS 5 vòng). (x0, y0, vx, vy). */
    fun fitLineRobust(pts: List<DoubleArray>): DoubleArray? {
        val wts = DoubleArray(pts.size) { 1.0 }
        var result: DoubleArray? = null
        repeat(5) {
            var sw = 0.0; var mx = 0.0; var my = 0.0
            for (j in pts.indices) { sw += wts[j]; mx += wts[j] * pts[j][0]; my += wts[j] * pts[j][1] }
            if (sw <= 0) return result
            mx /= sw; my /= sw
            var sxx = 0.0; var syy = 0.0; var sxy = 0.0
            for (j in pts.indices) {
                val ex = pts[j][0] - mx; val ey = pts[j][1] - my
                sxx += wts[j] * ex * ex; syy += wts[j] * ey * ey; sxy += wts[j] * ex * ey
            }
            // Vector riêng ứng với trị riêng lớn nhất của ma trận hiệp phương sai = hướng đường thẳng.
            val tr = sxx + syy
            val det = sxx * syy - sxy * sxy
            val l1 = tr / 2 + sqrt(max(0.0, tr * tr / 4 - det))
            var vx = sxy
            var vy = l1 - sxx
            if (abs(vx) + abs(vy) < 1e-12) { vx = if (sxx >= syy) 1.0 else 0.0; vy = if (sxx >= syy) 0.0 else 1.0 }
            val n = hypot(vx, vy)
            vx /= n; vy /= n
            result = doubleArrayOf(mx, my, vx, vy)
            val c = 1.5 // ngưỡng Huber (px)
            for (j in pts.indices) {
                val r = abs((pts[j][0] - mx) * vy - (pts[j][1] - my) * vx)
                wts[j] = if (r <= c) 1.0 else c / r
            }
        }
        return result
    }

    private fun intersect(a: DoubleArray, b: DoubleArray): DoubleArray? {
        // a0 + t·va = b0 + s·vb
        val det = a[2] * (-b[3]) - a[3] * (-b[2])
        if (abs(det) < 1e-9) return null
        val rx = b[0] - a[0]
        val ry = b[1] - a[1]
        val t = (rx * (-b[3]) - ry * (-b[2])) / det
        return doubleArrayOf(a[0] + a[2] * t, a[1] + a[3] * t)
    }
}
