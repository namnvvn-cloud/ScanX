import Foundation

/// Lưới bảng tìm được từ đường kẻ: vị trí các đường + ô gộp.
struct TableGrid {
    let colEdges: [Double]
    let rowEdges: [Double]
    /// anchorOf[r][c] = chỉ số ô gốc chứa ô lưới (r,c); spans[i] = [r0, c0, r1, c1] bao gồm.
    let anchorOf: [[Int]]
    let spans: [[Int]]

    var box: Box {
        Box(left: colEdges.first!, top: rowEdges.first!, right: colEdges.last!, bottom: rowEdges.last!)
    }

    func rowIndexOf(_ y: Double) -> Int {
        for r in 0..<(rowEdges.count - 1) where y < rowEdges[r + 1] { return r }
        return rowEdges.count - 2
    }

    func colIndexOf(_ x: Double) -> Int {
        for c in 0..<(colEdges.count - 1) where x < colEdges[c + 1] { return c }
        return colEdges.count - 2
    }
}

/// Nhận diện bảng kẻ ô từ các đoạn đường ngang/dọc — port 1:1 từ Android convert/TableDetector.kt.
enum TableDetector {
    private struct Seg {
        var start: Double
        var end: Double
        var pos: Double
    }

    static func detect(_ rules: [RuleSegment], pageW: Int, pageH: Int) -> [TableGrid] {
        let w = Double(pageW)
        let h = Double(pageH)
        let tol = max(w, h) * 0.008
        // Bỏ các đường sát mép ảnh: thường là bóng/mép giấy.
        let edge = min(w, h) * 0.012
        let inner = rules.filter { r in
            let xs = min(r.x1, r.x2), xe = max(r.x1, r.x2), ys = min(r.y1, r.y2), ye = max(r.y1, r.y2)
            return r.isHorizontal ? (ys > edge && ye < h - edge) : (xs > edge && xe < w - edge)
        }
        let hs0 = mergeCollinear(
            inner.filter { $0.isHorizontal }.map { Seg(start: min($0.x1, $0.x2), end: max($0.x1, $0.x2), pos: ($0.y1 + $0.y2) / 2) },
            posTol: tol, gapTol: w * 0.004
        )
        let vs0 = mergeCollinear(
            inner.filter { !$0.isHorizontal }.map { Seg(start: min($0.y1, $0.y2), end: max($0.y1, $0.y2), pos: ($0.x1 + $0.x2) / 2) },
            posTol: tol, gapTol: h * 0.004
        )
        func touches(_ hz: Seg, _ v: Seg) -> Bool {
            v.pos >= hz.start - tol && v.pos <= hz.end + tol && hz.pos >= v.start - tol && hz.pos <= v.end + tol
        }
        // Chỉ giữ đường ngang chạm ≥ 2 đường dọc và ngược lại → loại gạch chân, nét chữ dài…
        var hs = hs0
        var vs = vs0
        while true {
            let nh = hs.filter { hz in vs.filter { touches(hz, $0) }.count >= 2 }
            let nv = vs.filter { v in nh.filter { touches($0, v) }.count >= 2 }
            if nh.count == hs.count && nv.count == vs.count { break }
            hs = nh
            vs = nv
        }
        if hs.count < 2 || vs.count < 2 { return [] }

        var parent = Array(0..<(hs.count + vs.count))
        func find(_ i: Int) -> Int {
            var x = i
            while parent[x] != x {
                parent[x] = parent[parent[x]]
                x = parent[x]
            }
            return x
        }
        for (i, hz) in hs.enumerated() {
            for (j, v) in vs.enumerated() where touches(hz, v) {
                let a = find(i)
                let b = find(hs.count + j)
                parent[a] = b
            }
        }
        var groupH: [Int: [Seg]] = [:]
        var groupV: [Int: [Seg]] = [:]
        var order: [Int] = []
        for (i, s) in hs.enumerated() {
            let root = find(i)
            if groupH[root] == nil && groupV[root] == nil { order.append(root) }
            groupH[root, default: []].append(s)
        }
        for (j, s) in vs.enumerated() {
            let root = find(hs.count + j)
            if groupH[root] == nil && groupV[root] == nil { order.append(root) }
            groupV[root, default: []].append(s)
        }

        var tables: [TableGrid] = []
        for root in order {
            let gh = groupH[root] ?? []
            let gv = groupV[root] ?? []
            if gh.count < 2 || gv.count < 2 { continue }
            guard let grid = buildGrid(gh, gv, tol: tol) else { continue }
            let b = grid.box
            if b.width * b.height < w * h * 0.015 { continue }
            tables.append(grid)
        }
        return tables.sorted { $0.rowEdges.first! < $1.rowEdges.first! }
    }

    private static func mergeCollinear(_ segs: [Seg], posTol: Double, gapTol: Double) -> [Seg] {
        let sorted = segs.sorted { $0.pos != $1.pos ? $0.pos < $1.pos : $0.start < $1.start }
        var out: [Seg] = []
        for s in sorted {
            if let idx = out.lastIndex(where: { abs($0.pos - s.pos) <= posTol && s.start <= $0.end + gapTol && s.end >= $0.start - gapTol }) {
                let m = out[idx]
                let len1 = m.end - m.start
                let len2 = s.end - s.start
                out[idx] = Seg(
                    start: min(m.start, s.start),
                    end: max(m.end, s.end),
                    pos: (m.pos * len1 + s.pos * len2) / max(len1 + len2, 1)
                )
            } else {
                out.append(s)
            }
        }
        return out
    }

    private static func cluster(_ values: [Double], tol: Double) -> [Double] {
        var groups: [[Double]] = []
        for v in values.sorted() {
            if let last = groups.last?.last, v - last <= tol {
                groups[groups.count - 1].append(v)
            } else {
                groups.append([v])
            }
        }
        return groups.map { $0.reduce(0, +) / Double($0.count) }
    }

    private static func coverage(_ segs: [Seg], pos: Double, _ a: Double, _ b: Double, tol: Double) -> Double {
        let span = b - a
        if span <= 0 { return 1 }
        let parts = segs.filter { abs($0.pos - pos) <= tol * 1.5 }
            .map { (max($0.start, a), min($0.end, b)) }
            .filter { $0.1 > $0.0 }
            .sorted { $0.0 < $1.0 }
        var covered = 0.0
        var curS = Double.nan
        var curE = Double.nan
        for (s, e) in parts {
            if curS.isNaN {
                curS = s
                curE = e
            } else if s <= curE {
                curE = max(curE, e)
            } else {
                covered += curE - curS
                curS = s
                curE = e
            }
        }
        if !curS.isNaN { covered += curE - curS }
        return covered / span
    }

    private static func buildGrid(_ hs: [Seg], _ vs: [Seg], tol: Double) -> TableGrid? {
        var rowEdges = cluster(hs.map { $0.pos }, tol: tol * 1.5)
        var colEdges = cluster(vs.map { $0.pos }, tol: tol * 1.5)
        guard !rowEdges.isEmpty, !colEdges.isEmpty else { return nil }
        // Bảng hở 2 bên: lấy đầu mút đường ngang/dọc làm biên.
        let hMin = hs.map { $0.start }.min()!
        let hMax = hs.map { $0.end }.max()!
        if hMin < colEdges.first! - tol * 3 { colEdges.insert(hMin, at: 0) }
        if hMax > colEdges.last! + tol * 3 { colEdges.append(hMax) }
        let vMin = vs.map { $0.start }.min()!
        let vMax = vs.map { $0.end }.max()!
        if vMin < rowEdges.first! - tol * 3 { rowEdges.insert(vMin, at: 0) }
        if vMax > rowEdges.last! + tol * 3 { rowEdges.append(vMax) }

        // Đường chia bên trong phải phủ ≥ 30% chiều cao/rộng của bảng.
        do {
            let top = rowEdges.first!, bottom = rowEdges.last!
            let left = colEdges.first!, right = colEdges.last!
            let cCount = colEdges.count
            let rCount = rowEdges.count
            colEdges = colEdges.enumerated().filter { i, e in
                i == 0 || i == cCount - 1 || coverage(vs, pos: e, top, bottom, tol: tol) >= 0.3
            }.map { $0.element }
            rowEdges = rowEdges.enumerated().filter { i, e in
                i == 0 || i == rCount - 1 || coverage(hs, pos: e, left, right, tol: tol) >= 0.3
            }.map { $0.element }
        }
        let rows = rowEdges.count - 1
        let cols = colEdges.count - 1
        if rows < 1 || cols < 1 { return nil }

        func hasV(_ r: Int, _ c: Int) -> Bool { coverage(vs, pos: colEdges[c], rowEdges[r], rowEdges[r + 1], tol: tol) >= 0.5 }
        func hasH(_ r: Int, _ c: Int) -> Bool { coverage(hs, pos: rowEdges[r], colEdges[c], colEdges[c + 1], tol: tol) >= 0.5 }

        let n = rows * cols
        var parent = Array(0..<n)
        func find(_ i: Int) -> Int {
            var x = i
            while parent[x] != x {
                parent[x] = parent[parent[x]]
                x = parent[x]
            }
            return x
        }
        for r in 0..<rows {
            for c in 0..<cols {
                if c + 1 < cols && !hasV(r, c + 1) {
                    let a = find(r * cols + c)
                    let b = find(r * cols + c + 1)
                    parent[a] = b
                }
                if r + 1 < rows && !hasH(r + 1, c) {
                    let a = find(r * cols + c)
                    let b = find((r + 1) * cols + c)
                    parent[a] = b
                }
            }
        }
        var comps: [Int: [Int]] = [:]
        for i in 0..<n { comps[find(i), default: []].append(i) }

        var anchor = Array(repeating: Array(repeating: -1, count: cols), count: rows)
        var spans: [[Int]] = []
        for members in comps.values.sorted(by: { $0.min()! < $1.min()! }) {
            let r0 = members.map { $0 / cols }.min()!
            let r1 = members.map { $0 / cols }.max()!
            let c0 = members.map { $0 % cols }.min()!
            let c1 = members.map { $0 % cols }.max()!
            var free = true
            for r in r0...r1 {
                for c in c0...c1 where anchor[r][c] != -1 { free = false }
            }
            let rectangular = (r1 - r0 + 1) * (c1 - c0 + 1) == members.count && free
            if rectangular {
                let id = spans.count
                spans.append([r0, c0, r1, c1])
                for r in r0...r1 {
                    for c in c0...c1 { anchor[r][c] = id }
                }
            } else {
                for m in members {
                    let r = m / cols
                    let c = m % cols
                    if anchor[r][c] == -1 {
                        anchor[r][c] = spans.count
                        spans.append([r, c, r, c])
                    }
                }
            }
        }
        return TableGrid(colEdges: colEdges, rowEdges: rowEdges, anchorOf: anchor, spans: spans)
    }
}
