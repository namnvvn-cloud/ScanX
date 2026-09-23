package com.scanx.app.scan

import kotlin.math.abs
import kotlin.math.atan
import kotlin.math.max
import kotlin.math.min

/**
 * Hình học dòng chữ dùng để nắn trang cho "thẳng hàng, cân đối" (Kotlin thuần → kiểm thử trên JVM).
 *
 * Làm phẳng bằng 4 góc (perspective) chỉ đúng khi tờ giấy phẳng tuyệt đối. Giấy đặt trên bàn luôn
 * hơi vênh/cong ở mép và gáy, nên các dòng chữ sau khi nắn vẫn lượn sóng và không song song nhau —
 * đây chính là khác biệt so với các app scan thương mại, vốn nắn thêm một lần nữa theo DÒNG CHỮ
 * ("text-line based dewarping", hướng của Ulges 2005 / Stamatopoulos 2011).
 *
 * Cách làm: tìm các dải chữ (đã nối ngang), lấy đường chân chữ (baseline) từng dải bằng cách lấy
 * điểm mực thấp nhất theo từng cột, khớp đa thức bậc 2 bền vững (loại điểm lệch 2 vòng), rồi dựng
 * trường dịch dọc: mỗi baseline được kéo về đúng đường ngang của nó, các hàng ở giữa nội suy tuyến
 * tính giữa 2 baseline kề nhau.
 *
 * Đo trên bản scan thật: độ lệch góc giữa các dòng 0,84° → 0,04°; độ cong trung bình 1,3 px → 0,1 px
 * (Scanner Pro cùng trang: 0,23°).
 */
object TextLineGeometry {

    /** Đường chân chữ: y = a·x² + b·x + c, chỉ đo được trong [x0, x1]. */
    class Baseline(val a: Double, val b: Double, val c: Double, val x0: Double, val x1: Double) {
        fun yAt(x: Double): Double {
            val xc = x.coerceIn(x0, x1)
            return a * xc * xc + b * xc + c
        }
        val centerX: Double get() = (x0 + x1) / 2
        val centerY: Double get() = yAt(centerX)
        /** Độ dốc tại giữa dòng (độ). */
        val slopeDegrees: Double get() = Math.toDegrees(atan(2 * a * centerX + b))
    }

    /**
     * [mask] ảnh nhị phân (≠0 = mực) kích thước [w]×[h], ĐÃ nối ngang các chữ thành dải (morphology
     * close với phần tử ngang). Chỉ nhận dải rộng ≥ [minWidthFrac] chiều rộng trang và cao ≤
     * [maxHeightFrac] chiều cao trang (loại ảnh, bảng đặc, vệt bẩn).
     */
    fun detect(
        mask: ByteArray,
        w: Int,
        h: Int,
        minWidthFrac: Float = 0.3f,
        maxHeightFrac: Float = 0.05f,
    ): List<Baseline> {
        val labels = IntArray(w * h) { -1 }
        val parent = ArrayList<Int>()
        fun find(i: Int): Int {
            var x = i
            while (parent[x] != x) { parent[x] = parent[parent[x]]; x = parent[x] }
            return x
        }
        fun union(a: Int, b: Int) {
            val ra = find(a); val rb = find(b)
            if (ra != rb) parent[ra] = rb
        }
        // Gán nhãn 8 liên thông (2 lượt).
        for (y in 0 until h) {
            for (x in 0 until w) {
                val i = y * w + x
                if (mask[i].toInt() == 0) continue
                var best = -1
                for (dy in -1..0) for (dx in -1..1) {
                    if (dy == 0 && dx >= 0) continue
                    val nx = x + dx; val ny = y + dy
                    if (nx < 0 || ny < 0 || nx >= w) continue
                    val l = labels[ny * w + nx]
                    if (l < 0) continue
                    best = if (best < 0) l else { union(best, l); min(find(best), find(l)) }
                }
                if (best < 0) {
                    best = parent.size
                    parent.add(best)
                }
                labels[i] = best
            }
        }
        // Gom điểm mực thấp nhất mỗi cột cho từng thành phần.
        class Comp {
            var minX = Int.MAX_VALUE; var maxX = Int.MIN_VALUE
            var minY = Int.MAX_VALUE; var maxY = Int.MIN_VALUE
            val bottom = HashMap<Int, Int>()
        }
        val comps = HashMap<Int, Comp>()
        for (y in 0 until h) for (x in 0 until w) {
            val l = labels[y * w + x]
            if (l < 0) continue
            val r = find(l)
            val c = comps.getOrPut(r) { Comp() }
            if (x < c.minX) c.minX = x
            if (x > c.maxX) c.maxX = x
            if (y < c.minY) c.minY = y
            if (y > c.maxY) c.maxY = y
            val cur = c.bottom[x]
            if (cur == null || y > cur) c.bottom[x] = y
        }
        val out = ArrayList<Baseline>()
        for (c in comps.values) {
            val cw = c.maxX - c.minX + 1
            val ch = c.maxY - c.minY + 1
            if (cw < w * minWidthFrac || ch > h * maxHeightFrac || ch < 5) continue
            var xs = DoubleArray(c.bottom.size)
            var ys = DoubleArray(c.bottom.size)
            var n = 0
            for ((x, y) in c.bottom) { xs[n] = x.toDouble(); ys[n] = y.toDouble(); n++ }
            if (n < 10) continue
            xs = xs.copyOf(n); ys = ys.copyOf(n)
            var coef = fitQuadratic(xs, ys) ?: continue
            repeat(2) {
                val res = DoubleArray(xs.size) { ys[it] - (coef[0] * xs[it] * xs[it] + coef[1] * xs[it] + coef[2]) }
                val sd = stdDev(res) + 1e-6
                val keepX = ArrayList<Double>(xs.size)
                val keepY = ArrayList<Double>(xs.size)
                for (i in xs.indices) if (abs(res[i]) < 2.0 * sd) { keepX.add(xs[i]); keepY.add(ys[i]) }
                if (keepX.size >= 10) {
                    xs = keepX.toDoubleArray(); ys = keepY.toDoubleArray()
                    coef = fitQuadratic(xs, ys) ?: coef
                }
            }
            out.add(Baseline(coef[0], coef[1], coef[2], xs.min(), xs.max()))
        }
        return out.sortedBy { it.centerY }
    }

    /** Góc nghiêng chung của trang (độ, dương = xoay theo chiều kim đồng hồ để nắn thẳng). */
    fun medianSkewDegrees(lines: List<Baseline>): Double {
        if (lines.isEmpty()) return 0.0
        val s = lines.map { it.slopeDegrees }.sorted()
        return s[s.size / 2]
    }

    /**
     * Độ dịch dọc cần áp cho hàng [y]: `nguồn_y = y + out[x]`. Trả về false nếu không đủ dòng chữ
     * để nắn (giữ nguyên ảnh). [maxShift] giới hạn an toàn (px).
     */
    fun rowOffsets(lines: List<Baseline>, w: Int, y: Int, out: FloatArray, maxShift: Float): Boolean {
        if (lines.size < 6 || out.size < w) return false
        var i = 0
        while (i < lines.size && lines[i].centerY < y) i++
        val lo = if (i == 0) null else lines[i - 1]
        val hi = if (i >= lines.size) null else lines[i]
        for (x in 0 until w) {
            val xd = x.toDouble()
            val o = when {
                lo == null && hi != null -> hi.yAt(xd) - hi.centerY
                hi == null && lo != null -> lo.yAt(xd) - lo.centerY
                lo != null && hi != null -> {
                    val span = (hi.centerY - lo.centerY).coerceAtLeast(1e-6)
                    val t = ((y - lo.centerY) / span).coerceIn(0.0, 1.0)
                    (lo.yAt(xd) - lo.centerY) * (1 - t) + (hi.yAt(xd) - hi.centerY) * t
                }
                else -> 0.0
            }
            out[x] = o.coerceIn(-maxShift.toDouble(), maxShift.toDouble()).toFloat()
        }
        return true
    }

    /** Khớp y = a·x² + b·x + c bằng bình phương tối thiểu (giải hệ 3×3 bằng khử Gauss). */
    fun fitQuadratic(xs: DoubleArray, ys: DoubleArray): DoubleArray? {
        var s0 = 0.0; var s1 = 0.0; var s2 = 0.0; var s3 = 0.0; var s4 = 0.0
        var t0 = 0.0; var t1 = 0.0; var t2 = 0.0
        for (i in xs.indices) {
            val x = xs[i]; val y = ys[i]
            val x2 = x * x
            s0 += 1.0; s1 += x; s2 += x2; s3 += x2 * x; s4 += x2 * x2
            t0 += y; t1 += x * y; t2 += x2 * y
        }
        val m = arrayOf(
            doubleArrayOf(s4, s3, s2, t2),
            doubleArrayOf(s3, s2, s1, t1),
            doubleArrayOf(s2, s1, s0, t0),
        )
        for (col in 0 until 3) {
            var piv = col
            for (r in col + 1 until 3) if (abs(m[r][col]) > abs(m[piv][col])) piv = r
            if (abs(m[piv][col]) < 1e-9) return null
            val tmp = m[col]; m[col] = m[piv]; m[piv] = tmp
            for (r in 0 until 3) {
                if (r == col) continue
                val f = m[r][col] / m[col][col]
                for (c in col until 4) m[r][c] -= f * m[col][c]
            }
        }
        return doubleArrayOf(m[0][3] / m[0][0], m[1][3] / m[1][1], m[2][3] / m[2][2])
    }

    private fun stdDev(v: DoubleArray): Double {
        if (v.isEmpty()) return 0.0
        val mean = v.average()
        var s = 0.0
        for (x in v) s += (x - mean) * (x - mean)
        return Math.sqrt(s / v.size)
    }

    /**
     * Bù méo NGANG do trang sách cong theo mặt trụ (gáy sách, bản 0.7 — mở rộng nắn dòng chữ từ 2D
     * sang "3D"): mặt giấy càng gần gáy càng nghiêng xa ống kính → chữ càng gần gáy càng bị nén lại
     * theo CẢ 2 chiều, không chỉ lượn sóng theo chiều dọc. Đo độ nén qua khoảng cách dọc giữa 2
     * baseline kề nhau tại từng cột (cột nén thì khoảng cách dòng cũng hẹp theo cùng tỉ lệ, vì đây là
     * phép co cùng hệ số ở 1 điểm ảnh) — lấy vùng có khoảng cách dòng LỚN NHẤT làm chuẩn "phẳng", các
     * cột khác kéo giãn lại đúng tỉ lệ nén so với chuẩn đó bằng phép đổi biến tích luỹ (giữ đơn điệu,
     * không chồng chéo điểm ảnh). Trả về mảng cột đích → cột nguồn kích thước [w]; identity (x → x)
     * nếu không đủ dữ liệu hoặc trang đã phẳng đều (không có nén > ~6%).
     */
    fun horizontalDewarpMap(lines: List<Baseline>, w: Int): FloatArray {
        val identity = FloatArray(w) { it.toFloat() }
        if (lines.size < 3 || w < 20) return identity
        val sorted = lines.sortedBy { it.centerY }
        val spacingSum = DoubleArray(w)
        val spacingCount = IntArray(w)
        for (i in 0 until sorted.size - 1) {
            val lo = sorted[i]; val hi = sorted[i + 1]
            val x0 = max(lo.x0, hi.x0).toInt().coerceIn(0, w - 1)
            val x1 = min(lo.x1, hi.x1).toInt().coerceIn(0, w - 1)
            if (x1 <= x0) continue
            for (x in x0..x1) {
                val sp = hi.yAt(x.toDouble()) - lo.yAt(x.toDouble())
                if (sp > 0.5) { spacingSum[x] += sp; spacingCount[x]++ }
            }
        }
        val profile = DoubleArray(w) { if (spacingCount[it] > 0) spacingSum[it] / spacingCount[it] else Double.NaN }
        fillNaN(profile)
        val valid = profile.filter { !it.isNaN() }
        if (valid.size < w / 2) return identity
        val ref = valid.max()
        if (ref <= 0.0 || valid.min() / ref > 0.94) return identity
        // Tích luỹ nghịch đảo hệ số nén → toạ độ "chiều rộng thật" (đơn điệu tăng), quy về đúng [0, w).
        val cum = DoubleArray(w)
        var acc = 0.0
        for (x in 0 until w) {
            val scale = (profile[x] / ref).coerceIn(0.55, 1.0)
            acc += 1.0 / scale
            cum[x] = acc
        }
        val norm = (w - 1) / cum[w - 1]
        for (x in 0 until w) cum[x] *= norm
        // Đảo hàm tích luỹ: mỗi cột ĐÍCH → cột NGUỒN tương ứng (cum đơn điệu tăng → dò tuần tự O(w)).
        val out = FloatArray(w)
        var j = 0
        for (destX in 0 until w) {
            while (j < w - 1 && cum[j] < destX) j++
            val x0i = max(0, j - 1)
            val c0 = cum[x0i]; val c1 = cum[j]
            val t = if (c1 > c0) ((destX - c0) / (c1 - c0)).coerceIn(0.0, 1.0) else 0.0
            out[destX] = (x0i + t * (j - x0i)).toFloat().coerceIn(0f, (w - 1).toFloat())
        }
        return out
    }

    private fun fillNaN(v: DoubleArray) {
        var last = Double.NaN
        for (i in v.indices) { if (!v[i].isNaN()) last = v[i] else if (!last.isNaN()) v[i] = last }
        last = Double.NaN
        for (i in v.indices.reversed()) { if (!v[i].isNaN()) last = v[i] else if (!last.isNaN()) v[i] = last }
    }

    /** Biên tối còn sót sau khi làm phẳng (bóng gáy sách, mép bàn): số px cần cắt mỗi cạnh. */
    fun darkBorderTrim(rowMeans: FloatArray, colMeans: FloatArray, maxFrac: Float = 0.04f, darkLevel: Float = 120f): IntArray {
        fun scan(v: FloatArray, fromStart: Boolean): Int {
            val limit = (v.size * maxFrac).toInt().coerceAtLeast(1)
            var cut = 0
            var i = if (fromStart) 0 else v.size - 1
            while (cut < limit) {
                if (v[i] >= darkLevel) break
                cut++
                i += if (fromStart) 1 else -1
            }
            return cut
        }
        return intArrayOf(scan(colMeans, true), scan(rowMeans, true), scan(colMeans, false), scan(rowMeans, false))
    }

}
