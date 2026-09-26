import Foundation

/// Tái dựng bố cục trang từ OCR + đường kẻ — port 1:1 từ Android convert/LayoutAnalyzer.kt:
///  1. Bảng kẻ ô → TableDetector; từng TỪ OCR gán vào đúng ô theo toạ độ tâm.
///  2. Chữ còn lại gom theo dải ngang; dải có khoảng trống lớn → vùng nhiều cột (bảng không viền).
///  3. Dòng thường → đoạn văn (căn lề, cỡ chữ, đậm, thụt lề, khoảng cách đoạn).
///  4. Con dấu/logo giữ dạng ảnh, đặt đúng vị trí.
enum LayoutAnalyzer {
    private static let commonSizes: [Double] = [8, 9, 10, 10.5, 11, 12, 13, 14, 15, 16, 18, 20, 22, 24, 28]
    private static let listMarker = try! NSRegularExpression(
        pattern: "^([-•+*–]|\\d{1,2}[.)]|[a-zđ][.)]|[IVX]{1,4}\\.)\\s"
    )

    typealias CellWord = (word: OcrWord, stroke: Double, italic: Bool)

    static func isListStart(_ text: String) -> Bool {
        let t = text.trimmingCharacters(in: .whitespacesAndNewlines)
        return listMarker.firstMatch(in: t, range: NSRange(t.startIndex..., in: t)) != nil
    }

    static func analyze(_ input: PageInput) -> DocPage {
        let w = Double(input.width)
        let pageH = Double(input.height)
        let ptPerPx = (input.width <= input.height ? 595.0 : 842.0) / w

        // Lọc nét chữ bị nhận nhầm là đường kẻ: đường dọc thật ≥ 1,5 × chiều cao dòng, ngang ≥ 3 ×.
        let medLineH = median(input.lines.map { $0.box.height }) ?? 0
        let rules = medLineH <= 0 ? input.rules : input.rules.filter { r in
            let len = r.isHorizontal ? abs(r.x2 - r.x1) : abs(r.y2 - r.y1)
            return len >= medLineH * (r.isHorizontal ? 3 : 1.5)
        }
        let grids = TableDetector.detect(rules, pageW: input.width, pageH: input.height)

        // --- 1. Tách từ thuộc bảng / ngoài bảng ---
        var cellWords: [[Int: [CellWord]]] = grids.map { _ in [:] }
        var freeLines: [OcrLine] = []
        for line in input.lines {
            let words = line.words.isEmpty ? [OcrWord(text: line.text, box: line.box)] : line.words
            var rest: [OcrWord] = []
            for word in words {
                if let gi = grids.firstIndex(where: { $0.box.containsPoint(x: word.box.cx, y: word.box.cy) }) {
                    let g = grids[gi]
                    let id = g.anchorOf[g.rowIndexOf(word.box.cy)][g.colIndexOf(word.box.cx)]
                    cellWords[gi][id, default: []].append((word: word, stroke: line.strokeWidth, italic: line.italic))
                } else {
                    rest.append(word)
                }
            }
            if !rest.isEmpty, let box = Box.unionOf(rest.map { $0.box }) {
                freeLines.append(OcrLine(
                    text: rest.map { $0.text }.joined(separator: " "),
                    box: box,
                    words: rest,
                    strokeWidth: line.strokeWidth,
                    color: line.color,
                    confidence: line.confidence,
                    lang: line.lang,
                    italic: line.italic
                ))
            }
        }

        // Ghi chú cạnh bảng → đoạn định vị tuyệt đối.
        let sideTol = w * 0.005
        let isSide: (OcrLine) -> Bool = { l in
            grids.contains { g in
                let b = g.box
                return l.box.cy > b.top && l.box.cy < b.bottom
                    && (l.box.left >= b.right - sideTol || l.box.right <= b.left + sideTol)
            }
        }
        let sideLines = freeLines.filter(isSide)
        freeLines.removeAll(where: isSide)

        let allLines = input.lines
        let totalChars = max(allLines.reduce(0) { $0 + $1.text.count }, 1)
        let ocrConfidence = allLines.reduce(0.0) { $0 + $1.confidence * Double($1.text.count) } / Double(totalChars)
        let medianStroke = median(allLines.map { $0.strokeWidth }.filter { $0 > 0 }) ?? 0
        let isBold: (Double) -> Bool = { stroke in medianStroke > 0 && stroke > medianStroke * 1.15 }

        let contentBox = Box.unionOf(allLines.map { $0.box } + grids.map { $0.box })
            ?? Box(left: 0, top: 0, right: w, bottom: pageH)

        // Lề phải "thực" của thân văn bản: phân vị 90% mép phải các dòng dài.
        let longRights = freeLines.filter { $0.box.width > contentBox.width * 0.45 }.map { $0.box.right }.sorted()
        let bodyRight = longRights.count >= 3
            ? longRights[min(Int(Double(longRights.count) * 0.9), longRights.count - 1)]
            : contentBox.right

        var blocks: [Block] = []

        // --- 2. Bảng kẻ ô ---
        for (gi, g) in grids.enumerated() {
            var cells: [TableCell] = []
            for (id, s) in g.spans.enumerated() {
                let cellBox = Box(left: g.colEdges[s[1]], top: g.rowEdges[s[0]], right: g.colEdges[s[3] + 1], bottom: g.rowEdges[s[2] + 1])
                let words = cellWords[gi][id] ?? []
                let (paras, vAlign) = cellParagraphs(words, cell: cellBox, ptPerPx: ptPerPx, isBold: isBold)
                cells.append(TableCell(row: s[0], col: s[1], rowSpan: s[2] - s[0] + 1, colSpan: s[3] - s[1] + 1, paragraphs: paras, vAlign: vAlign))
            }
            blocks.append(.table(TableBlock(box: g.box, colEdges: g.colEdges, rowEdges: g.rowEdges, cells: cells, bordered: true, spaceBeforePx: 0)))
        }

        // --- 3. Dải ngang → vùng nhiều cột / đoạn văn ---
        let bands = buildBands(freeLines, pageW: w)
        var i = 0
        var pending: [[OcrLine]] = []
        func flushParagraphs() {
            if pending.isEmpty { return }
            let lines = pending.map { $0[0] }
            for p in buildParagraphs(lines, cl: contentBox.left, cr: contentBox.right, bodyRight: bodyRight, ptPerPx: ptPerPx, isBold: isBold) {
                blocks.append(.paragraph(p))
            }
            pending.removeAll()
        }
        while i < bands.count {
            let band = bands[i]
            if band.count >= 2 {
                let gutters = (0..<(band.count - 1)).map { (band[$0].box.right + band[$0 + 1].box.left) / 2 }
                let crossesGutter: (OcrLine) -> Bool = { l in
                    gutters.contains { gx in l.box.left < gx - w * 0.01 && l.box.right > gx + w * 0.01 }
                }
                var regionBands: [[OcrLine]] = [band]
                // Nhìn ngược: các dòng 1 cột ngay phía trên (vd "BỘ CÔNG AN" khi OCR sót dòng bên phải).
                while let prevBand = pending.last {
                    let prevLine = prevBand[0]
                    let topNow = regionBands[0].map { $0.box.top }.min() ?? prevLine.box.bottom
                    if crossesGutter(prevLine) || topNow - prevLine.box.bottom > prevLine.box.height * 1.8 { break }
                    regionBands.insert(pending.removeLast(), at: 0)
                }
                var j = i + 1
                while j < bands.count {
                    let next = bands[j]
                    let prevBottom = regionBands[regionBands.count - 1].map { $0.box.bottom }.max() ?? 0
                    let lineH = next.map { $0.box.height }.max() ?? 0
                    let gapOk = (next.map { $0.box.top }.min() ?? 0) - prevBottom < lineH * 1.8
                    let crosses = next.contains(where: crossesGutter)
                    if !gapOk || crosses { break }
                    regionBands.append(next)
                    j += 1
                }
                flushParagraphs()
                blocks.append(.table(buildColumnRegion(regionBands, gutters: gutters, content: contentBox, ptPerPx: ptPerPx, isBold: isBold)))
                i = j
            } else {
                pending.append(band)
                i += 1
            }
        }
        flushParagraphs()

        for l in sideLines {
            var p = lineParagraph(l, cl: l.box.left, cr: l.box.right, ptPerPx: ptPerPx, isBold: isBold, centeredTolerance: 0)
            p.align = .left
            p.indentPx = 0
            p.floating = true
            blocks.append(.paragraph(p))
        }

        // --- 4. Ảnh con dấu / logo / hình (vùng màu có chữ KHÔNG cắt thành ảnh) ---
        let pageMin = min(w, pageH)
        let keptFigures = input.figures.filter { f in
            if f.isStamp { return true }
            let aspect = max(f.box.width, f.box.height) / max(1, min(f.box.width, f.box.height))
            if aspect > 5 || min(f.box.width, f.box.height) < pageMin * 0.03 { return false }
            let area = max(1, f.box.width * f.box.height)
            let textArea = allLines.reduce(0.0) { acc, l in
                let ow = min(f.box.right, l.box.right) - max(f.box.left, l.box.left)
                let oh = min(f.box.bottom, l.box.bottom) - max(f.box.top, l.box.top)
                return acc + ((ow > 0 && oh > 0) ? ow * oh : 0)
            }
            return textArea / area < 0.15
        }
        for f in keptFigures {
            let align: Align
            if abs(f.box.cx - contentBox.cx) < w * 0.1 {
                align = .center
            } else if f.box.cx > contentBox.cx {
                align = .right
            } else {
                align = .left
            }
            blocks.append(.image(ImageBlock(box: f.box, figure: f, align: align, spaceBeforePx: 0)))
        }

        // --- 5. Thứ tự đọc + khoảng cách giữa các khối ---
        let ordered = stableSorted(normalizeFonts(blocks)) { $0.box.top }
        var withSpacing: [Block] = []
        var prevBottom = contentBox.top
        for b in ordered {
            if let p = b.paragraph, p.floating {
                withSpacing.append(b)
                continue
            }
            let gap = max(0, b.box.top - prevBottom)
            switch b {
            case .paragraph(var p):
                p.spaceBeforePx = gap
                withSpacing.append(.paragraph(p))
            case .table(var t):
                t.spaceBeforePx = gap
                withSpacing.append(.table(t))
            case .image(var im):
                im.spaceBeforePx = gap
                withSpacing.append(.image(im))
            }
            prevBottom = max(prevBottom, b.box.bottom)
        }
        return DocPage(
            width: input.width,
            height: input.height,
            blocks: withSpacing,
            content: contentBox,
            ptPerPx: ptPerPx,
            ocrConfidence: ocrConfidence
        )
    }

    /// Gom dòng thành dải ngang; trong dải nối mẩu sát nhau, tách cụm cách xa (cột).
    private static func buildBands(_ lines: [OcrLine], pageW: Double) -> [[OcrLine]] {
        var bands: [[OcrLine]] = []
        for l in stableSorted(lines, by: { $0.box.top }) {
            if let band = bands.last,
               band.contains(where: { o in o.box.verticalOverlap(l.box) >= 0.5 * min(o.box.height, l.box.height) }) {
                bands[bands.count - 1].append(l)
            } else {
                bands.append([l])
            }
        }
        return bands.map { band in
            var merged: [OcrLine] = []
            for l in stableSorted(band, by: { $0.box.left }) {
                if let last = merged.last, l.box.left - last.box.right < pageW * 0.06 {
                    let lang: String
                    if last.lang == l.lang || l.lang.isEmpty {
                        lang = last.lang
                    } else if last.lang.isEmpty {
                        lang = l.lang
                    } else {
                        lang = last.text.count >= l.text.count ? last.lang : l.lang
                    }
                    let lastWider = last.box.width >= l.box.width
                    merged[merged.count - 1] = OcrLine(
                        text: last.text + " " + l.text,
                        box: last.box.union(l.box),
                        words: last.words + l.words,
                        strokeWidth: (last.strokeWidth * last.box.width + l.strokeWidth * l.box.width) / max(1, last.box.width + l.box.width),
                        color: lastWider ? last.color : l.color,
                        confidence: min(last.confidence, l.confidence),
                        lang: lang,
                        italic: lastWider ? last.italic : l.italic
                    )
                } else {
                    merged.append(l)
                }
            }
            return merged
        }
    }

    /// Bảng không viền nhiều cột — mỗi dải ngang khớp rãnh cột là 1 HÀNG thật của bảng (bản 0.7).
    private static func buildColumnRegion(
        _ regionBands: [[OcrLine]],
        gutters: [Double],
        content: Box,
        ptPerPx: Double,
        isBold: @escaping (Double) -> Bool
    ) -> TableBlock {
        let edges = [content.left] + gutters + [content.right]
        var rowEdges: [Double] = [regionBands[0].map { $0.box.top }.min() ?? content.top]
        if regionBands.count > 1 {
            for k in 0..<(regionBands.count - 1) {
                let bottom = regionBands[k].map { $0.box.bottom }.max() ?? 0
                let nextTop = regionBands[k + 1].map { $0.box.top }.min() ?? bottom
                rowEdges.append((bottom + nextTop) / 2)
            }
        }
        rowEdges.append(regionBands[regionBands.count - 1].map { $0.box.bottom }.max() ?? content.bottom)
        var cells: [TableCell] = []
        for (r, band) in regionBands.enumerated() {
            for c in 0..<(edges.count - 1) {
                let colLines = stableSorted(band.filter { $0.box.cx >= edges[c] && $0.box.cx < edges[c + 1] }, by: { $0.box.top })
                let paras = colLines.map {
                    lineParagraph($0, cl: edges[c], cr: edges[c + 1], ptPerPx: ptPerPx, isBold: isBold, centeredTolerance: 0.12)
                }
                cells.append(TableCell(row: r, col: c, rowSpan: 1, colSpan: 1, paragraphs: paras, vAlign: .top))
            }
        }
        return TableBlock(
            box: Box(left: content.left, top: rowEdges[0], right: content.right, bottom: rowEdges[rowEdges.count - 1]),
            colEdges: edges,
            rowEdges: rowEdges,
            cells: cells,
            bordered: false,
            spaceBeforePx: 0
        )
    }

    private static func lineParagraph(
        _ l: OcrLine,
        cl: Double,
        cr: Double,
        ptPerPx: Double,
        isBold: (Double) -> Bool,
        centeredTolerance: Double
    ) -> Paragraph {
        let cw = max(1, cr - cl)
        let lg = l.box.left - cl
        let rg = cr - l.box.right
        let align: Align
        if abs(lg - rg) < centeredTolerance * cw && lg > 0.04 * cw && l.box.width < 0.82 * cw {
            align = .center
        } else if rg < 0.05 * cw && lg > 0.3 * cw {
            align = .right
        } else {
            align = .left
        }
        return Paragraph(
            text: l.text,
            align: align,
            fontPt: lineFont(l, ptPerPx: ptPerPx),
            bold: isBold(l.strokeWidth),
            italic: l.italic,
            indentPx: align == .left ? max(0, lg) : 0,
            spaceBeforePx: 0,
            box: l.box,
            lineBoxes: [l.box],
            color: l.color,
            lang: l.lang
        )
    }

    /// Dòng ngoài bảng → đoạn văn (nối các dòng bị ngắt do xuống dòng tự nhiên).
    private static func buildParagraphs(
        _ lines: [OcrLine],
        cl: Double,
        cr: Double,
        bodyRight: Double,
        ptPerPx: Double,
        isBold: @escaping (Double) -> Bool
    ) -> [Paragraph] {
        let cw = max(1, cr - cl)
        let fullRight = bodyRight - 0.06 * max(1, bodyRight - cl)
        var out: [Paragraph] = []
        var cur: [OcrLine]?

        func finish() {
            guard let group = cur, let first = group.first else { return }
            if group.count == 1 {
                out.append(lineParagraph(first, cl: cl, cr: cr, ptPerPx: ptPerPx, isBold: isBold, centeredTolerance: 0.09))
            } else {
                let restLeft = group.dropFirst().map { $0.box.left }.min() ?? first.box.left
                let box = Box.unionOf(group.map { $0.box }) ?? first.box
                let firstIndent = max(0, first.box.left - restLeft)
                var p = Paragraph(
                    text: group.map { $0.text.trimmingCharacters(in: .whitespaces) }.joined(separator: " "),
                    align: .justify,
                    fontPt: snapSize(median(group.map { lineFontRaw($0, ptPerPx: ptPerPx) }) ?? fontRaw(first.box.height, ptPerPx: ptPerPx)),
                    bold: group.filter { isBold($0.strokeWidth) }.count * 2 > group.count,
                    italic: group.filter { $0.italic }.count * 2 > group.count,
                    indentPx: max(0, restLeft - cl),
                    spaceBeforePx: 0,
                    box: box
                )
                p.firstLineIndentPx = firstIndent > cw * 0.02 ? firstIndent : 0
                p.lineBoxes = group.map { $0.box }
                p.color = dominantKey(group.map { ($0.color, $0.text.count) }) ?? 0
                p.lang = dominantKey(group.filter { !$0.lang.isEmpty }.map { ($0.lang, $0.text.count) }) ?? ""
                out.append(p)
            }
            cur = nil
        }

        for l in lines {
            guard let group = cur, let prev = group.last else {
                cur = [l]
                continue
            }
            let prevFull = prev.box.right >= fullRight
            let lineH = max(prev.box.height, l.box.height)
            let gapOk = l.box.top - prev.box.bottom < lineH * 0.7
            let ratio = l.box.height / max(1, prev.box.height)
            let sizeOk = ratio >= 0.75 && ratio <= 1.33
            let prevLeft = group.count == 1 ? prev.box.left : (group.dropFirst().map { $0.box.left }.min() ?? prev.box.left)
            let leftOk = abs(l.box.left - prevLeft) < 0.03 * cw || (group.count == 1 && l.box.left < prev.box.left)
            let startsList = isListStart(l.text)
            let centered = abs((l.box.left - cl) - (cr - l.box.right)) < 0.09 * cw && l.box.left - cl > 0.06 * cw
            let sameLang = prev.lang.isEmpty || l.lang.isEmpty || prev.lang == l.lang
            if prevFull && gapOk && sizeOk && leftOk && !startsList && !centered && sameLang
                && isBold(prev.strokeWidth) == isBold(l.strokeWidth) {
                cur?.append(l)
            } else {
                finish()
                cur = [l]
            }
        }
        finish()
        return out
    }

    /// Chữ trong 1 ô bảng: gom từ → dòng → đoạn; suy căn lề/căn dọc theo vị trí trong ô.
    private static func cellParagraphs(
        _ words: [CellWord],
        cell: Box,
        ptPerPx: Double,
        isBold: @escaping (Double) -> Bool
    ) -> ([Paragraph], VAlign) {
        if words.isEmpty { return ([], .center) }
        let medH = median(words.map { $0.word.box.height }) ?? 10
        var rows: [[CellWord]] = []
        for wd in stableSorted(words, by: { $0.word.box.cy }) {
            if let row = rows.last {
                let avg = row.map { $0.word.box.cy }.reduce(0, +) / Double(row.count)
                if abs(wd.word.box.cy - avg) < medH * 0.5 {
                    rows[rows.count - 1].append(wd)
                    continue
                }
            }
            rows.append([wd])
        }
        let lines: [OcrLine] = rows.map { r in
            let byX = stableSorted(r, by: { $0.word.box.left })
            let box = Box.unionOf(byX.map { $0.word.box })!
            return OcrLine(
                text: byX.map { $0.word.text }.joined(separator: " "),
                box: box,
                words: byX.map { $0.word },
                strokeWidth: byX.map { $0.stroke }.reduce(0, +) / Double(byX.count),
                color: dominantKey(byX.map { ($0.word.color, $0.word.text.count) }) ?? 0,
                confidence: byX.map { $0.word.confidence }.min() ?? 1,
                lang: dominantKey(byX.filter { !$0.word.lang.isEmpty }.map { ($0.word.lang, $0.word.text.count) }) ?? "",
                italic: byX.filter { $0.italic }.count * 2 > byX.count
            )
        }
        let cw = max(1, cell.width)
        var paras: [Paragraph] = []
        var group: [OcrLine] = []

        func finish() {
            guard let first = group.first, let box = Box.unionOf(group.map { $0.box }) else { return }
            let lg = first.box.left - cell.left
            let rg = cell.right - first.box.right
            let align: Align
            if group.count > 1 {
                align = .justify
            } else if abs(lg - rg) < 0.14 * cw && box.width < 0.9 * cw {
                align = .center
            } else if rg < 0.12 * cw && lg > 0.35 * cw {
                align = .right
            } else {
                align = .left
            }
            // Cỡ chữ trong ô không vượt ~62% chiều cao hàng.
            let cap = cell.height * ptPerPx * 0.62 / Double(max(1, group.count))
            let raw = median(group.map { lineFontRaw($0, ptPerPx: ptPerPx) }) ?? fontRaw(medH, ptPerPx: ptPerPx)
            var p = Paragraph(
                text: group.map { $0.text.trimmingCharacters(in: .whitespaces) }.joined(separator: " "),
                align: align,
                fontPt: snapSize(min(raw, max(8, cap))),
                bold: group.filter { isBold($0.strokeWidth) }.count * 2 > group.count,
                italic: group.filter { $0.italic }.count * 2 > group.count,
                indentPx: 0,
                spaceBeforePx: 0,
                box: box
            )
            p.lineBoxes = group.map { $0.box }
            p.color = dominantKey(group.map { ($0.color, $0.text.count) }) ?? 0
            p.lang = dominantKey(group.filter { !$0.lang.isEmpty }.map { ($0.lang, $0.text.count) }) ?? ""
            paras.append(p)
            group = []
        }

        for l in lines {
            if let prev = group.last {
                let prevShort = prev.box.right < cell.right - 0.3 * cw
                if isListStart(l.text) || prevShort { finish() }
            }
            group.append(l)
        }
        finish()
        let text = Box.unionOf(lines.map { $0.box }) ?? cell
        let vAlign: VAlign
        if abs(text.cy - cell.cy) < cell.height * 0.18 {
            vAlign = .center
        } else if text.cy < cell.cy {
            vAlign = .top
        } else {
            vAlign = .bottom
        }
        return (paras, vAlign)
    }

    /// Đồng nhất cỡ chữ về cỡ chủ đạo (±20%) cho thân văn bản và cho bảng; tiêu đề lớn giữ nguyên.
    private static func normalizeFonts(_ blocks: [Block]) -> [Block] {
        func snap(_ p: Paragraph, _ d: Double?) -> Paragraph {
            guard let d, d > 0 else { return p }
            let r = p.fontPt / d
            if (r >= 0.75 && r <= 1.35) || (!p.bold && p.align != .center && r >= 0.6 && r <= 1.6) {
                var q = p
                q.fontPt = d
                return q
            }
            return p
        }
        let bodyDom = dominantKey(blocks.compactMap { $0.paragraph }.map { ($0.fontPt, $0.text.count) })
        let cellParas = blocks.compactMap { $0.table }.flatMap { t in t.cells.flatMap { $0.paragraphs } }
        let cellDom = dominantKey(cellParas.map { ($0.fontPt, $0.text.count) }) ?? bodyDom
        return blocks.map { b -> Block in
            switch b {
            case .paragraph(let p):
                if p.floating {
                    if let d = cellDom ?? bodyDom, p.fontPt > d {
                        var q = p
                        q.fontPt = d
                        return .paragraph(q)
                    }
                    return .paragraph(p)
                }
                return .paragraph(snap(p, bodyDom))
            case .table(var t):
                let dom = t.bordered ? cellDom : bodyDom
                t.cells = t.cells.map { c in
                    var copy = c
                    copy.paragraphs = c.paragraphs.map { snap($0, dom) }
                    return copy
                }
                return .table(t)
            case .image:
                return b
            }
        }
    }

    /// Chiều cao hộp chữ OCR (px) → cỡ chữ (pt) thô.
    private static func fontRaw(_ boxHeightPx: Double, ptPerPx: Double) -> Double {
        boxHeightPx * ptPerPx / 1.28
    }

    /// Độ rộng chuỗi theo em (bảng độ rộng ký tự xấp xỉ Times New Roman).
    private static func widthEm(_ text: String) -> Double {
        var w = 0.0
        for scalar in text.precomposedStringWithCanonicalMapping.unicodeScalars {
            let c = scalar.value
            if scalar == " " {
                w += 0.25
            } else if (0x1100...0x11FF).contains(c) || (0x2E80...0x9FFF).contains(c) || (0xAC00...0xD7AF).contains(c)
                        || (0xF900...0xFAFF).contains(c) || (0xFF00...0xFFEF).contains(c) {
                w += 1.0
            } else if scalar.properties.isUppercase {
                w += scalar == "I" ? 0.33 : ((scalar == "M" || scalar == "W") ? 0.89 : 0.68)
            } else if scalar.properties.isLowercase {
                if "ijlft".unicodeScalars.contains(scalar) {
                    w += 0.28
                } else {
                    w += (scalar == "m" || scalar == "w") ? 0.72 : 0.47
                }
            } else if scalar.properties.numericType == .decimal {
                w += 0.5
            } else {
                w += 0.33
            }
        }
        return w
    }

    /// Cỡ chữ thô 1 dòng: kết hợp chiều cao hộp với bề rộng dòng / độ rộng chuỗi.
    private static func lineFontRaw(_ l: OcrLine, ptPerPx: Double) -> Double {
        let byHeight = fontRaw(l.box.height, ptPerPx: ptPerPx)
        let text = l.text.trimmingCharacters(in: .whitespaces)
        if text.count < 6 { return byHeight }
        let byWidth = l.box.width * ptPerPx / max(1, widthEm(text))
        if text.count >= 30 {
            return min(max(byWidth, byHeight * 0.6), byHeight * 1.1)
        }
        return min(max(byWidth, byHeight * 0.7), byHeight * 1.15)
    }

    private static func lineFont(_ l: OcrLine, ptPerPx: Double) -> Double {
        snapSize(lineFontRaw(l, ptPerPx: ptPerPx))
    }

    private static func snapSize(_ raw: Double) -> Double {
        var best = commonSizes[0]
        for s in commonSizes where abs(s - raw) < abs(best - raw) { best = s }
        return best
    }
}
