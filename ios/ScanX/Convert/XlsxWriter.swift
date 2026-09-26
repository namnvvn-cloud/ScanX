import Foundation

/// Xuất Excel (.xlsx) giữ form bản scan — port 1:1 từ Android convert/XlsxWriter.kt: mỗi trang 1 sheet,
/// "lưới cột chủ" từ biên cột của mọi bảng/header, đặt mọi khối lên lưới bằng ô gộp; số kiểu Việt Nam
/// (1.234.567,89) ghi dạng số để tính toán được; chỉ mục "1.1", mã có số 0 đầu giữ dạng chữ.
enum XlsxWriter {
    private struct CellData {
        let text: String
        let style: Int
    }

    private struct StyleKey: Hashable {
        let fontId: Int
        let border: Int
        let h: String
        let v: String
        let indent: Int
        let fill: Int
    }

    private struct FontKey: Hashable {
        let size: Double
        let bold: Bool
        let color: Int
        let name: String
        let italic: Bool
    }

    private final class Registry {
        var fonts: [FontKey: Int] = [:]
        var fontOrder: [FontKey] = []
        var styles: [StyleKey: Int] = [:]
        var styleOrder: [StyleKey] = []

        init() {
            // Font 0 = font mặc định của sổ (Calibri 11: 7 px/ký tự) → quy đổi độ rộng cột chính xác.
            _ = fontId(size: 11, bold: false, color: 0, name: "Calibri", italic: false)
            let base = StyleKey(fontId: 0, border: 0, h: "general", v: "bottom", indent: 0, fill: 0)
            styles[base] = 0
            styleOrder.append(base)
        }

        func fontId(size: Double, bold: Bool, color: Int, name: String, italic: Bool) -> Int {
            let key = FontKey(size: size, bold: bold, color: color, name: name, italic: italic)
            if let id = fonts[key] { return id }
            let id = fontOrder.count
            fonts[key] = id
            fontOrder.append(key)
            return id
        }

        func styleId(
            size: Double, bold: Bool, color: Int, lang: String, border: Bool,
            align: Align, vAlign: VAlign, indent: Int, fill: Bool, italic: Bool
        ) -> Int {
            let h: String
            switch align {
            case .left: h = "left"
            case .center: h = "center"
            case .right: h = "right"
            case .justify: h = "justify"
            }
            let v: String
            switch vAlign {
            case .top: v = "top"
            case .center: v = "center"
            case .bottom: v = "bottom"
            }
            let font = fontId(size: size, bold: bold, color: color, name: Lang.fontFor(lang), italic: italic)
            let key = StyleKey(fontId: font, border: border ? 1 : 0, h: h, v: v, indent: indent, fill: fill ? 2 : 0)
            if let id = styles[key] { return id }
            let id = styleOrder.count
            styles[key] = id
            styleOrder.append(key)
            return id
        }
    }

    private static let numberVN = try! NSRegularExpression(pattern: "^-?\\d{1,3}(\\.\\d{3})+(,\\d+)?$|^-?\\d+(,\\d+)?$")

    private static func isNumberVN(_ t: String) -> Bool {
        numberVN.firstMatch(in: t, range: NSRange(t.startIndex..., in: t)) != nil
    }

    static func write(_ doc: DocModel) -> Data {
        let pkg = OoxmlPackage()
        let reg = Registry()
        var sheetNames: [String] = []
        for (pi, page) in doc.pages.enumerated() {
            sheetNames.append("Trang \(pi + 1)")
            pkg.put("xl/worksheets/sheet\(pi + 1).xml", sheetXml(page, reg))
        }
        let sheetOverrides = sheetNames.indices.map {
            "<Override PartName=\"/xl/worksheets/sheet\($0 + 1).xml\" ContentType=\"application/vnd.openxmlformats-officedocument.spreadsheetml.worksheet+xml\"/>"
        }.joined()
        pkg.put("[Content_Types].xml", [
            xmlHeader,
            "<Types xmlns=\"http://schemas.openxmlformats.org/package/2006/content-types\">",
            "<Default Extension=\"rels\" ContentType=\"application/vnd.openxmlformats-package.relationships+xml\"/>",
            "<Default Extension=\"xml\" ContentType=\"application/xml\"/>",
            "<Override PartName=\"/xl/workbook.xml\" ContentType=\"application/vnd.openxmlformats-officedocument.spreadsheetml.sheet.main+xml\"/>",
            "<Override PartName=\"/xl/styles.xml\" ContentType=\"application/vnd.openxmlformats-officedocument.spreadsheetml.styles+xml\"/>",
            sheetOverrides,
            "<Override PartName=\"/docProps/core.xml\" ContentType=\"application/vnd.openxmlformats-package.core-properties+xml\"/>",
            "</Types>",
        ].joined())
        pkg.put("_rels/.rels", [
            xmlHeader,
            "<Relationships xmlns=\"http://schemas.openxmlformats.org/package/2006/relationships\">",
            "<Relationship Id=\"rId1\" Type=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships/officeDocument\" Target=\"xl/workbook.xml\"/>",
            "<Relationship Id=\"rId2\" Type=\"http://schemas.openxmlformats.org/package/2006/relationships/metadata/core-properties\" Target=\"docProps/core.xml\"/>",
            "</Relationships>",
        ].joined())
        let sheetsXml = sheetNames.enumerated().map { i, name in
            "<sheet name=\"\(xmlEscape(name))\" sheetId=\"\(i + 1)\" r:id=\"rId\(i + 1)\"/>"
        }.joined()
        pkg.put("xl/workbook.xml", [
            xmlHeader,
            "<workbook xmlns=\"http://schemas.openxmlformats.org/spreadsheetml/2006/main\" xmlns:r=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships\">",
            "<sheets>", sheetsXml, "</sheets></workbook>",
        ].joined())
        let sheetRels = sheetNames.indices.map {
            "<Relationship Id=\"rId\($0 + 1)\" Type=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships/worksheet\" Target=\"worksheets/sheet\($0 + 1).xml\"/>"
        }.joined()
        pkg.put("xl/_rels/workbook.xml.rels", [
            xmlHeader,
            "<Relationships xmlns=\"http://schemas.openxmlformats.org/package/2006/relationships\">",
            sheetRels,
            "<Relationship Id=\"rIdStyles\" Type=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships/styles\" Target=\"styles.xml\"/>",
            "</Relationships>",
        ].joined())
        pkg.put("xl/styles.xml", stylesXml(reg))
        pkg.put("docProps/core.xml", corePropsXml(doc.title))
        return pkg.archive()
    }

    private static func sheetXml(_ page: DocPage, _ reg: Registry) -> String {
        let pt = page.ptPerPx
        // --- Lưới cột chủ ---
        var rawEdges: [Double] = [page.content.left, page.content.right]
        for t in page.blocks.compactMap({ $0.table }) { rawEdges += t.colEdges }
        let floats = page.blocks.compactMap { $0.paragraph }.filter { $0.floating }
        for f in floats {
            rawEdges.append(f.box.left)
            rawEdges.append(f.box.right)
        }
        let tol = Double(page.width) * 0.012
        var edges: [Double] = []
        for e in rawEdges.sorted() {
            if let last = edges.last, e - last <= tol {
                edges[edges.count - 1] = (last + e) / 2
            } else {
                edges.append(e)
            }
        }
        if edges.count < 2 { edges.append((edges.first ?? 0) + 100) }
        func edgeIndex(_ x: Double) -> Int {
            var best = 0
            for i in edges.indices where abs(edges[i] - x) < abs(edges[best] - x) { best = i }
            return best
        }
        let lastCol = edges.count - 2
        // Tổng độ rộng cột không vượt vùng in A4 → không tràn sang trang bên khi in.
        let printableW = (page.width > page.height ? 842.0 : 595.0) - 65
        let totalW = (edges[edges.count - 1] - edges[0]) * pt
        let fitScale = totalW > printableW ? printableW / totalW : 1

        var rows: [Int: [Int: CellData]] = [:]
        var rowHeights: [Int: Double] = [:]
        var merges: [String] = []
        var row = 1

        func put(_ r: Int, _ c: Int, _ d: CellData) {
            rows[r, default: [:]][c] = d
        }
        func area(_ r0: Int, _ c0: Int, _ r1: Int, _ c1: Int, _ text: String, _ style: Int) {
            for r in r0...max(r0, r1) {
                for c in c0...max(c0, c1) {
                    put(r, c, CellData(text: (r == r0 && c == c0) ? text : "", style: style))
                }
            }
            if r1 > r0 || c1 > c0 { merges.append("\(ref(r0, c0)):\(ref(r1, c1))") }
        }

        var tablePlacements: [(table: TableBlock, base: Int)] = []
        for block in page.blocks {
            if let p = block.paragraph, p.floating { continue }
            let gapPx: Double
            switch block {
            case .paragraph(let p): gapPx = p.spaceBeforePx
            case .table(let t): gapPx = t.spaceBeforePx
            case .image(let im): gapPx = im.spaceBeforePx
            }
            let gapPt = gapPx * pt
            if gapPt > 10 && row > 1 {
                rowHeights[row] = min(gapPt, 60)
                row += 1
            }
            switch block {
            case .paragraph(let p):
                let indent = clampInt(roundInt(p.indentPx * pt / 9), 0, 15)
                let align: Align = p.align == .justify ? .left : p.align
                let areaW = max(20, (edges[edges.count - 1] - edges[0]) * pt * fitScale - 6)
                let est = Int((Double(p.text.count) * 0.5 * p.fontPt / areaW).rounded(.up))
                let lines = max(max(1, p.lineBoxes.count), est)
                let style = reg.styleId(
                    size: p.fontPt, bold: p.bold, color: p.color, lang: p.lang, border: false,
                    align: align, vAlign: .center, indent: align == .left ? indent : 0, fill: false, italic: p.italic
                )
                area(row, 0, row, lastCol, p.text, style)
                rowHeights[row] = max(p.fontPt * 1.35 * Double(lines), 15)
                row += 1
            case .table(let t):
                let base = row
                tablePlacements.append((table: t, base: base))
                let fonts = t.cells.flatMap { c in c.paragraphs.filter { !$0.bold }.map { $0.fontPt } }.sorted()
                let minRowPt = (0..<t.rowCount).map { (t.rowEdges[$0 + 1] - t.rowEdges[$0]) * pt }.min() ?? 20
                let uniform = fonts.isEmpty ? 12 : min(fonts[fonts.count / 2], max(8, minRowPt * 0.45))
                for r in 0..<t.rowCount {
                    rowHeights[base + r] = max(15, (t.rowEdges[r + 1] - t.rowEdges[r]) * pt)
                }
                for cell in t.cells {
                    let c0 = min(edgeIndex(t.colEdges[cell.col]), lastCol)
                    let c1 = clampInt(edgeIndex(t.colEdges[min(t.colCount, cell.col + cell.colSpan)]) - 1, c0, lastCol)
                    let first = cell.paragraphs.first
                    let text = cell.paragraphs.map { $0.text }.joined(separator: "\n")
                    let align: Align
                    if let first, first.align != .justify {
                        align = first.align
                    } else {
                        align = (!cell.paragraphs.isEmpty && text.count > 30) ? .justify : .left
                    }
                    let size: Double
                    if let first {
                        size = (t.bordered && !first.bold) ? uniform : first.fontPt
                    } else {
                        size = uniform
                    }
                    let header = t.bordered && TableStyle.isHeaderRow(t, cell)
                    let style = reg.styleId(
                        size: size, bold: (first?.bold ?? false) || header, color: first?.color ?? 0,
                        lang: first?.lang ?? "", border: t.bordered, align: align, vAlign: cell.vAlign,
                        indent: 0, fill: header, italic: first?.italic ?? false
                    )
                    area(base + cell.row, c0, base + cell.row + cell.rowSpan - 1, c1, text, style)
                }
                row = base + t.rowCount
            case .image:
                break  // Ảnh con dấu/chữ ký: Excel không cần, giữ bố cục chữ + bảng.
            }
        }

        // Ghi chú cạnh bảng → ô cùng hàng, đúng cột bên cạnh bảng.
        for p in floats {
            guard let placed = tablePlacements.first(where: { p.box.cy >= $0.table.box.top && p.box.cy <= $0.table.box.bottom }) else { continue }
            let t = placed.table
            var ri = 0
            while ri < t.rowCount - 1 && p.box.cy > t.rowEdges[ri + 1] { ri += 1 }
            let r = placed.base + ri
            let c = min(edgeIndex(p.box.left), lastCol)
            let existing = rows[r]?[c]?.text ?? ""
            let text = existing.trimmingCharacters(in: .whitespaces).isEmpty ? p.text : existing + "\n" + p.text
            let style = reg.styleId(
                size: p.fontPt, bold: p.bold, color: p.color, lang: p.lang, border: false,
                align: .left, vAlign: .center, indent: 0, fill: false, italic: p.italic
            )
            put(r, c, CellData(text: text, style: style))
        }

        var sb = xmlHeader
        sb += "<worksheet xmlns=\"http://schemas.openxmlformats.org/spreadsheetml/2006/main\" xmlns:r=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships\">"
        sb += "<sheetPr><pageSetUpPr fitToPage=\"1\"/></sheetPr>"
        sb += "<dimension ref=\"A1:\(ref(max(1, row - 1), lastCol))\"/>"
        sb += "<sheetViews><sheetView showGridLines=\"0\" workbookViewId=\"0\"/></sheetViews>"
        sb += "<sheetFormatPr defaultRowHeight=\"16.5\"/>"
        sb += "<cols>"
        for c in 0...lastCol {
            let widthPt = (edges[c + 1] - edges[c]) * pt * fitScale
            let chars = clampDouble((widthPt * 96 / 72 - 5) / 7, 1, 255)
            sb += "<col min=\"\(c + 1)\" max=\"\(c + 1)\" width=\"\(String(format: "%.2f", chars))\" customWidth=\"1\"/>"
        }
        sb += "</cols><sheetData>"
        if row > 1 {
            for r in 1..<row {
                let h = rowHeights[r] ?? 16.5
                sb += "<row r=\"\(r)\" ht=\"\(String(format: "%.1f", min(h, 409)))\" customHeight=\"1\">"
                if let cells = rows[r] {
                    for c in cells.keys.sorted() {
                        guard let d = cells[c] else { continue }
                        let t = d.text.trimmingCharacters(in: .whitespacesAndNewlines)
                        let leadingZero = t.count > 1 && t.hasPrefix("0") && !t.hasPrefix("0,")
                        if !t.isEmpty && isNumberVN(t) && !leadingZero {
                            let num = t.replacingOccurrences(of: ".", with: "").replacingOccurrences(of: ",", with: ".")
                            sb += "<c r=\"\(ref(r, c))\" s=\"\(d.style)\"><v>\(num)</v></c>"
                        } else if !t.isEmpty {
                            sb += "<c r=\"\(ref(r, c))\" s=\"\(d.style)\" t=\"inlineStr\"><is><t xml:space=\"preserve\">\(xmlEscape(t))</t></is></c>"
                        } else {
                            sb += "<c r=\"\(ref(r, c))\" s=\"\(d.style)\"/>"
                        }
                    }
                }
                sb += "</row>"
            }
        }
        sb += "</sheetData>"
        if !merges.isEmpty {
            sb += "<mergeCells count=\"\(merges.count)\">"
            for m in merges { sb += "<mergeCell ref=\"\(m)\"/>" }
            sb += "</mergeCells>"
        }
        sb += "<pageMargins left=\"0.5\" right=\"0.4\" top=\"0.5\" bottom=\"0.5\" header=\"0.3\" footer=\"0.3\"/>"
        let orient = page.width > page.height ? "landscape" : "portrait"
        sb += "<pageSetup paperSize=\"9\" orientation=\"\(orient)\" fitToWidth=\"1\" fitToHeight=\"0\"/>"
        sb += "</worksheet>"
        return sb
    }

    private static func ref(_ row: Int, _ col: Int) -> String {
        var c = col
        var letters = ""
        repeat {
            letters = String(UnicodeScalar(UInt8(65 + c % 26))) + letters
            c = c / 26 - 1
        } while c >= 0
        return letters + String(row)
    }

    private static func stylesXml(_ reg: Registry) -> String {
        var sb = xmlHeader
        sb += "<styleSheet xmlns=\"http://schemas.openxmlformats.org/spreadsheetml/2006/main\">"
        sb += "<fonts count=\"\(reg.fontOrder.count)\">"
        for f in reg.fontOrder {
            sb += "<font>"
            if f.bold { sb += "<b/>" }
            if f.italic { sb += "<i/>" }
            sb += "<sz val=\"\(f.size)\"/>"
            if f.color != 0 { sb += "<color rgb=\"FF\(hexColor(f.color))\"/>" }
            sb += "<name val=\"\(f.name)\"/><family val=\"\(f.name == defaultFont ? 1 : 2)\"/></font>"
        }
        sb += "</fonts>"
        sb += "<fills count=\"3\"><fill><patternFill patternType=\"none\"/></fill><fill><patternFill patternType=\"gray125\"/></fill>"
        sb += "<fill><patternFill patternType=\"solid\"><fgColor rgb=\"FFF2F2F2\"/><bgColor indexed=\"64\"/></patternFill></fill></fills>"
        sb += "<borders count=\"2\"><border><left/><right/><top/><bottom/><diagonal/></border>"
        sb += "<border><left style=\"thin\"><color auto=\"1\"/></left><right style=\"thin\"><color auto=\"1\"/></right>"
        sb += "<top style=\"thin\"><color auto=\"1\"/></top><bottom style=\"thin\"><color auto=\"1\"/></bottom><diagonal/></border></borders>"
        sb += "<cellStyleXfs count=\"1\"><xf numFmtId=\"0\" fontId=\"0\" fillId=\"0\" borderId=\"0\"/></cellStyleXfs>"
        sb += "<cellXfs count=\"\(reg.styleOrder.count)\">"
        for (i, s) in reg.styleOrder.enumerated() {
            if i == 0 {
                sb += "<xf numFmtId=\"0\" fontId=\"0\" fillId=\"0\" borderId=\"0\" xfId=\"0\"/>"
                continue
            }
            sb += "<xf numFmtId=\"0\" fontId=\"\(s.fontId)\" fillId=\"\(s.fill)\" borderId=\"\(s.border)\" xfId=\"0\" applyFont=\"1\" applyBorder=\"1\" applyAlignment=\"1\""
            sb += s.fill > 0 ? " applyFill=\"1\">" : ">"
            sb += "<alignment horizontal=\"\(s.h)\" vertical=\"\(s.v)\" wrapText=\"1\""
            sb += s.indent > 0 ? " indent=\"\(s.indent)\"/></xf>" : "/></xf>"
        }
        sb += "</cellXfs><cellStyles count=\"1\"><cellStyle name=\"Normal\" xfId=\"0\" builtinId=\"0\"/></cellStyles>"
        sb += "</styleSheet>"
        return sb
    }
}
