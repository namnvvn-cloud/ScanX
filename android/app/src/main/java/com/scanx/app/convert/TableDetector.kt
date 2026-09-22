package com.scanx.app.convert

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/** Lưới bảng tìm được từ đường kẻ: vị trí các đường + ô gộp. */
class TableGrid(
    val colEdges: List<Float>,
    val rowEdges: List<Float>,
    /** anchor[r][c] = chỉ số ô gốc chứa ô lưới (r,c); spans[i] = (r0, c0, r1, c1) bao gồm. */
    val anchorOf: Array<IntArray>,
    val spans: List<IntArray>,
) {
    val box: Box get() = Box(colEdges.first(), rowEdges.first(), colEdges.last(), rowEdges.last())

    fun rowIndexOf(y: Float): Int {
        for (r in 0 until rowEdges.size - 1) if (y < rowEdges[r + 1]) return r
        return rowEdges.size - 2
    }

    fun colIndexOf(x: Float): Int {
        for (c in 0 until colEdges.size - 1) if (x < colEdges[c + 1]) return c
        return colEdges.size - 2
    }
}

/**
 * Nhận diện bảng có kẻ đường (ruled table) từ các đoạn đường ngang/dọc — cách các công cụ chuyển
 * đổi (PaddleOCR PP-Structure recovery, pdf2docx) xử lý bảng kẻ ô:
 *  1. Nối các đoạn thẳng hàng bị đứt (do ảnh mờ, chữ đè lên đường kẻ).
 *  2. Gom đường ngang + dọc cắt nhau thành từng bảng (union-find).
 *  3. Phân cụm toạ độ → biên hàng/cột; kiểm tra đường kẻ có thật giữa 2 ô kề nhau hay không để
 *     suy ra ô gộp (merge) theo cả chiều ngang lẫn dọc.
 */
object TableDetector {

    fun detect(rules: List<RuleSegment>, pageW: Int, pageH: Int): List<TableGrid> {
        val tol = max(pageW, pageH) * 0.008f
        // Bỏ các đường sát mép ảnh: thường là bóng/mép tờ giấy, không phải đường kẻ bảng.
        val edge = min(pageW, pageH) * 0.012f
        val inner = rules.filter { r ->
            val xs = min(r.x1, r.x2); val xe = max(r.x1, r.x2); val ys = min(r.y1, r.y2); val ye = max(r.y1, r.y2)
            if (r.isHorizontal) ys > edge && ye < pageH - edge else xs > edge && xe < pageW - edge
        }
        val hs0 = mergeCollinear(
            inner.filter { it.isHorizontal }.map { Seg(min(it.x1, it.x2), max(it.x1, it.x2), (it.y1 + it.y2) / 2f) },
            tol, pageW * 0.004f,
        )
        val vs0 = mergeCollinear(
            inner.filter { !it.isHorizontal }.map { Seg(min(it.y1, it.y2), max(it.y1, it.y2), (it.x1 + it.x2) / 2f) },
            tol, pageH * 0.004f,
        )
        // Lọc lặp: chỉ giữ đường ngang chạm ≥ 2 đường dọc và ngược lại → loại gạch chân chữ, nét chữ
        // dài, đường viền lẻ… chỉ còn khung bảng thật.
        fun touches(h: Seg, v: Seg) = v.pos >= h.start - tol && v.pos <= h.end + tol && h.pos >= v.start - tol && h.pos <= v.end + tol
        var hs = hs0
        var vs = vs0
        while (true) {
            val nh = hs.filter { h -> vs.count { v -> touches(h, v) } >= 2 }
            val nv = vs.filter { v -> nh.count { h -> touches(h, v) } >= 2 }
            if (nh.size == hs.size && nv.size == vs.size) break
            hs = nh
            vs = nv
        }
        if (hs.size < 2 || vs.size < 2) return emptyList()

        // Union-find: phần tử 0..hs-1 là đường ngang, hs.. là đường dọc.
        val parent = IntArray(hs.size + vs.size) { it }
        fun find(i: Int): Int {
            var x = i
            while (parent[x] != x) { parent[x] = parent[parent[x]]; x = parent[x] }
            return x
        }
        for ((i, h) in hs.withIndex()) {
            for ((j, v) in vs.withIndex()) {
                if (touches(h, v)) parent[find(i)] = find(hs.size + j)
            }
        }
        val groups = HashMap<Int, Pair<MutableList<Seg>, MutableList<Seg>>>()
        hs.forEachIndexed { i, s -> groups.getOrPut(find(i)) { mutableListOf<Seg>() to mutableListOf() }.first.add(s) }
        vs.forEachIndexed { j, s -> groups.getOrPut(find(hs.size + j)) { mutableListOf<Seg>() to mutableListOf() }.second.add(s) }

        val tables = ArrayList<TableGrid>()
        for ((gh, gv) in groups.values) {
            if (gh.size < 2 || gv.size < 2) continue
            val grid = buildGrid(gh, gv, tol) ?: continue
            val b = grid.box
            if (b.width * b.height < pageW * pageH * 0.015f) continue
            tables.add(grid)
        }
        return tables.sortedBy { it.rowEdges.first() }
    }

    private data class Seg(val start: Float, val end: Float, val pos: Float)

    private fun mergeCollinear(segs: List<Seg>, posTol: Float, gapTol: Float): List<Seg> {
        val sorted = segs.sortedWith(compareBy({ it.pos }, { it.start }))
        val out = ArrayList<Seg>()
        for (s in sorted) {
            val idx = out.indexOfLast { abs(it.pos - s.pos) <= posTol && s.start <= it.end + gapTol && s.end >= it.start - gapTol }
            if (idx >= 0) {
                val m = out[idx]
                val len1 = m.end - m.start
                val len2 = s.end - s.start
                out[idx] = Seg(min(m.start, s.start), max(m.end, s.end), (m.pos * len1 + s.pos * len2) / max(len1 + len2, 1f))
            } else {
                out.add(s)
            }
        }
        return out
    }

    private fun cluster(values: List<Float>, tol: Float): List<Float> {
        val sorted = values.sorted()
        val out = ArrayList<MutableList<Float>>()
        for (v in sorted) {
            if (out.isNotEmpty() && v - out.last().last() <= tol) out.last().add(v) else out.add(mutableListOf(v))
        }
        return out.map { it.average().toFloat() }
    }

    private fun buildGrid(hs: List<Seg>, vs: List<Seg>, tol: Float): TableGrid? {
        val rowEdges = cluster(hs.map { it.pos }, tol * 1.5f).toMutableList()
        val colEdges = cluster(vs.map { it.pos }, tol * 1.5f).toMutableList()
        // Bảng hở 2 bên (không có đường dọc ngoài cùng): lấy đầu mút đường ngang làm biên.
        val hMin = hs.minOf { it.start }
        val hMax = hs.maxOf { it.end }
        if (hMin < colEdges.first() - tol * 3) colEdges.add(0, hMin)
        if (hMax > colEdges.last() + tol * 3) colEdges.add(hMax)
        val vMin = vs.minOf { it.start }
        val vMax = vs.maxOf { it.end }
        if (vMin < rowEdges.first() - tol * 3) rowEdges.add(0, vMin)
        if (vMax > rowEdges.last() + tol * 3) rowEdges.add(vMax)
        val rows = rowEdges.size - 1
        val cols = colEdges.size - 1
        if (rows < 1 || cols < 1) return null

        fun coverage(segs: List<Seg>, pos: Float, a: Float, b: Float): Float {
            val span = b - a
            if (span <= 0f) return 1f
            val parts = segs.filter { abs(it.pos - pos) <= tol * 1.5f }
                .map { max(it.start, a) to min(it.end, b) }
                .filter { it.second > it.first }
                .sortedBy { it.first }
            var covered = 0f
            var curS = Float.NaN
            var curE = Float.NaN
            for ((s, e) in parts) {
                if (curS.isNaN()) { curS = s; curE = e } else if (s <= curE) { curE = max(curE, e) } else { covered += curE - curS; curS = s; curE = e }
            }
            if (!curS.isNaN()) covered += curE - curS
            return covered / span
        }

        // Có đường dọc ở biên cột c (giữa cột c-1 và c) trong hàng r?
        fun hasV(r: Int, c: Int) = coverage(vs, colEdges[c], rowEdges[r], rowEdges[r + 1]) >= 0.5f
        // Có đường ngang ở biên hàng r (giữa hàng r-1 và r) trong cột c?
        fun hasH(r: Int, c: Int) = coverage(hs, rowEdges[r], colEdges[c], colEdges[c + 1]) >= 0.5f

        val n = rows * cols
        val parent = IntArray(n) { it }
        fun find(i: Int): Int {
            var x = i
            while (parent[x] != x) { parent[x] = parent[parent[x]]; x = parent[x] }
            return x
        }
        for (r in 0 until rows) for (c in 0 until cols) {
            if (c + 1 < cols && !hasV(r, c + 1)) parent[find(r * cols + c)] = find(r * cols + c + 1)
            if (r + 1 < rows && !hasH(r + 1, c)) parent[find(r * cols + c)] = find((r + 1) * cols + c)
        }
        val comps = HashMap<Int, MutableList<Int>>()
        for (i in 0 until n) comps.getOrPut(find(i)) { mutableListOf() }.add(i)

        val anchor = Array(rows) { IntArray(cols) { -1 } }
        val spans = ArrayList<IntArray>()
        for (members in comps.values.sortedBy { it.min() }) {
            val r0 = members.minOf { it / cols }
            val r1 = members.maxOf { it / cols }
            val c0 = members.minOf { it % cols }
            val c1 = members.maxOf { it % cols }
            val rectangular = (r1 - r0 + 1) * (c1 - c0 + 1) == members.size &&
                (r0..r1).all { r -> (c0..c1).all { c -> anchor[r][c] == -1 } }
            if (rectangular) {
                val id = spans.size
                spans.add(intArrayOf(r0, c0, r1, c1))
                for (r in r0..r1) for (c in c0..c1) anchor[r][c] = id
            } else {
                for (m in members) {
                    val r = m / cols
                    val c = m % cols
                    if (anchor[r][c] == -1) {
                        anchor[r][c] = spans.size
                        spans.add(intArrayOf(r, c, r, c))
                    }
                }
            }
        }
        return TableGrid(colEdges, rowEdges, anchor, spans)
    }
}
