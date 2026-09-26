import Foundation

/// Xuất Word (.docx) giữ bố cục bản scan — port 1:1 từ Android convert/DocxWriter.kt:
/// mỗi trang 1 section (A4 dọc/ngang), lề suy từ vùng nội dung, đoạn văn đúng căn lề/cỡ/đậm/nghiêng/thụt lề,
/// bảng thật (ô gộp ngang/dọc, độ rộng cột theo tỉ lệ gốc, hàng tiêu đề tô nền), header nhiều cột dạng bảng
/// không viền, ghi chú cạnh bảng là hộp chữ neo tuyệt đối, ảnh con dấu/logo đặt đúng chỗ.
enum DocxWriter {
    private static let a4W = 11906
    private static let a4H = 16838

    static func write(_ doc: DocModel) -> Data {
        let pkg = OoxmlPackage()
        var body = ""
        var rels = ""
        var imageCounter = 0
        var shapeCounter = 1000

        func clampMargin(_ v: Double) -> Int { clampInt(roundInt(v), 340, 2268) }
        var lastSectPr = ""
        for (pi, page) in doc.pages.enumerated() {
            let twPerPx = page.ptPerPx * 20
            let landscape = page.isLandscape
            let pageW = landscape ? a4H : a4W
            let pageH: Int = {
                let h = roundInt(Double(page.height) * twPerPx)
                let a4 = landscape ? a4W : a4H
                return abs(h - a4) < Int(Double(a4) * 0.06) ? a4 : h
            }()
            let mL = clampMargin(page.content.left * twPerPx)
            let mR = clampMargin((Double(page.width) - page.content.right) * twPerPx)
            let mT = clampMargin(page.content.top * twPerPx)
            let mB = clampMargin((Double(page.height) - page.content.bottom) * twPerPx)
            let textWidth = pageW - mL - mR
            let orient = landscape ? " w:orient=\"landscape\"" : ""
            let sectPr = [
                "<w:sectPr><w:pgSz w:w=\"\(pageW)\" w:h=\"\(pageH)\"\(orient)/>",
                "<w:pgMar w:top=\"\(mT)\" w:right=\"\(mR)\" w:bottom=\"\(mB)\" w:left=\"\(mL)\" w:header=\"0\" w:footer=\"0\" w:gutter=\"0\"/></w:sectPr>",
            ].joined()
            if pi > 0 {
                body += "<w:p><w:pPr><w:spacing w:before=\"0\" w:after=\"0\" w:line=\"20\" w:lineRule=\"exact\"/>\(lastSectPr)</w:pPr></w:p>"
            }
            lastSectPr = sectPr
            let leftOffsetTw = max(0, roundInt(page.content.left * twPerPx - Double(mL)))
            var lastWasTable = false

            // Trang dạng sổ/biểu mẫu viết tay: mọi chữ ngoài bảng đặt tuyệt đối, chỉ bảng nằm trong dòng chảy.
            let bordered = page.blocks.compactMap { $0.table }.filter { $0.bordered }
            let freeParas = page.blocks.compactMap { $0.paragraph }.filter { !$0.floating }.count
            let borderedHeight = bordered.reduce(0.0) { $0 + $1.box.height }
            let absolute = !bordered.isEmpty
                && (page.ocrConfidence < 0.8 || (freeParas <= 8 && borderedHeight > Double(page.height) * 0.45))
            var blocksToFlow: [Block]
            var floats: [Paragraph]
            if absolute {
                var extra: [Paragraph] = []
                var flow: [Block] = []
                for b in page.blocks {
                    switch b {
                    case .paragraph(let p): extra.append(p)
                    case .table(let t) where !t.bordered: t.cells.forEach { extra.append(contentsOf: $0.paragraphs) }
                    default: flow.append(b)
                    }
                }
                blocksToFlow = flow.enumerated().map { (i, b) -> Block in
                    if i == 0, case .table(var t) = b {
                        t.spaceBeforePx = max(0, t.box.top - Double(mT) / twPerPx)
                        return .table(t)
                    }
                    return b
                }
                let cellFonts = bordered.flatMap { t in t.cells.flatMap { c in c.paragraphs.map { $0.fontPt } } }.sorted()
                let cap = cellFonts.isEmpty ? 14 : clampDouble(cellFonts[cellFonts.count / 2] * 1.3, 11, 16)
                floats = extra.map { p in
                    var q = p
                    q.floating = true
                    q.fontPt = min(p.fontPt, cap)
                    return q
                }
            } else {
                blocksToFlow = page.blocks
                floats = page.blocks.compactMap { $0.paragraph }.filter { $0.floating }
            }
            if !floats.isEmpty {
                body += "<w:p><w:pPr><w:spacing w:before=\"0\" w:after=\"0\" w:line=\"20\" w:lineRule=\"exact\"/></w:pPr>"
                for fp in floats {
                    shapeCounter += 1
                    body += floatingTextBoxRun(fp, twPerPx: twPerPx, id: shapeCounter, pageWidthTw: pageW)
                }
                body += "</w:p>"
            }
            for block in blocksToFlow {
                switch block {
                case .paragraph(let p):
                    if p.floating { continue }
                    let before = spacingBeforeTw(p.spaceBeforePx, twPerPx: twPerPx, fontPt: p.fontPt)
                    body += paragraphXml(
                        p,
                        beforeTw: before,
                        leftTw: leftOffsetTw + roundInt(p.indentPx * twPerPx),
                        firstLineTw: roundInt(p.firstLineIndentPx * twPerPx)
                    )
                    lastWasTable = false
                case .table(let t):
                    let gapTw = roundInt(t.spaceBeforePx * twPerPx)
                    if gapTw > 60 || lastWasTable { body += spacerXml(max(0, gapTw - 20)) }
                    let indent = roundInt(t.box.left * twPerPx - Double(mL))
                    body += tableXml(t, twPerPx: twPerPx, indentTw: max(0, indent), textWidth: textWidth)
                    lastWasTable = true
                case .image(let im):
                    imageCounter += 1
                    let rid = "rIdImg\(imageCounter)"
                    pkg.putBinary("word/media/image\(imageCounter).jpeg", im.figure.jpeg)
                    rels += "<Relationship Id=\"\(rid)\" Type=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships/image\" Target=\"media/image\(imageCounter).jpeg\"/>"
                    let emuPerPx = page.ptPerPx * 12700
                    var cx = roundInt(im.box.width * emuPerPx)
                    var cy = roundInt(im.box.height * emuPerPx)
                    let maxCx = textWidth * 635
                    if cx > maxCx && cx > 0 {
                        cy = Int(Int64(cy) * Int64(maxCx) / Int64(cx))
                        cx = maxCx
                    }
                    let before = spacingBeforeTw(im.spaceBeforePx, twPerPx: twPerPx, fontPt: 12)
                    body += imageParagraphXml(rid: rid, id: imageCounter, cx: cx, cy: cy, align: im.align, beforeTw: before)
                    lastWasTable = false
                }
            }
        }
        body += "<w:p><w:pPr><w:spacing w:before=\"0\" w:after=\"0\" w:line=\"20\" w:lineRule=\"exact\"/></w:pPr></w:p>"

        let document = [
            xmlHeader,
            "<w:document xmlns:w=\"http://schemas.openxmlformats.org/wordprocessingml/2006/main\" ",
            "xmlns:r=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships\" ",
            "xmlns:wp=\"http://schemas.openxmlformats.org/drawingml/2006/wordprocessingDrawing\" ",
            "xmlns:a=\"http://schemas.openxmlformats.org/drawingml/2006/main\" ",
            "xmlns:pic=\"http://schemas.openxmlformats.org/drawingml/2006/picture\" ",
            "xmlns:wps=\"http://schemas.microsoft.com/office/word/2010/wordprocessingShape\">",
            "<w:body>\(body)\(lastSectPr)</w:body></w:document>",
        ].joined()

        pkg.put("[Content_Types].xml", [
            xmlHeader,
            "<Types xmlns=\"http://schemas.openxmlformats.org/package/2006/content-types\">",
            "<Default Extension=\"rels\" ContentType=\"application/vnd.openxmlformats-package.relationships+xml\"/>",
            "<Default Extension=\"xml\" ContentType=\"application/xml\"/>",
            "<Default Extension=\"jpeg\" ContentType=\"image/jpeg\"/>",
            "<Override PartName=\"/word/document.xml\" ContentType=\"application/vnd.openxmlformats-officedocument.wordprocessingml.document.main+xml\"/>",
            "<Override PartName=\"/word/styles.xml\" ContentType=\"application/vnd.openxmlformats-officedocument.wordprocessingml.styles+xml\"/>",
            "<Override PartName=\"/docProps/core.xml\" ContentType=\"application/vnd.openxmlformats-package.core-properties+xml\"/>",
            "<Override PartName=\"/docProps/app.xml\" ContentType=\"application/vnd.openxmlformats-officedocument.extended-properties+xml\"/>",
            "</Types>",
        ].joined())
        pkg.put("_rels/.rels", [
            xmlHeader,
            "<Relationships xmlns=\"http://schemas.openxmlformats.org/package/2006/relationships\">",
            "<Relationship Id=\"rId1\" Type=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships/officeDocument\" Target=\"word/document.xml\"/>",
            "<Relationship Id=\"rId2\" Type=\"http://schemas.openxmlformats.org/package/2006/relationships/metadata/core-properties\" Target=\"docProps/core.xml\"/>",
            "<Relationship Id=\"rId3\" Type=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships/extended-properties\" Target=\"docProps/app.xml\"/>",
            "</Relationships>",
        ].joined())
        pkg.put("word/_rels/document.xml.rels", [
            xmlHeader,
            "<Relationships xmlns=\"http://schemas.openxmlformats.org/package/2006/relationships\">",
            "<Relationship Id=\"rIdStyles\" Type=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships/styles\" Target=\"styles.xml\"/>",
            rels + "</Relationships>",
        ].joined())
        pkg.put("word/styles.xml", stylesXml())
        pkg.put("word/document.xml", document)
        pkg.put("docProps/core.xml", corePropsXml(doc.title))
        pkg.put("docProps/app.xml", [
            xmlHeader,
            "<Properties xmlns=\"http://schemas.openxmlformats.org/officeDocument/2006/extended-properties\"><Application>ScanX</Application></Properties>",
        ].joined())
        return pkg.archive()
    }

    private static func spacingBeforeTw(_ gapPx: Double, twPerPx: Double, fontPt: Double) -> Int {
        let gapPt = gapPx * twPerPx / 20 - fontPt * 0.35
        return gapPt < 2 ? 0 : roundInt(min(gapPt, 72) * 20)
    }

    private static func jc(_ a: Align) -> String {
        switch a {
        case .left: return "left"
        case .center: return "center"
        case .right: return "right"
        case .justify: return "both"
        }
    }

    /// 1 run chữ; chữ Hàn/Nhật/Trung có font + mã ngôn ngữ Đông Á riêng (không bị ô vuông trong Word).
    static func runXml(_ text: String, fontPt: Double, bold: Bool, color: Int = 0, lang: String = "", italic: Bool = false) -> String {
        let sz = roundInt(fontPt * 2)
        let ea = Lang.isEastAsian(lang)
        let eaFont = ea ? Lang.fontFor(lang) : defaultFont
        let langAttr: String
        if ea {
            langAttr = "<w:lang w:val=\"vi-VN\" w:eastAsia=\"\(Lang.ooxml(lang))\"/>"
        } else if !lang.isEmpty {
            langAttr = "<w:lang w:val=\"\(Lang.ooxml(lang))\"/>"
        } else {
            langAttr = ""
        }
        return [
            "<w:r><w:rPr><w:rFonts w:ascii=\"\(defaultFont)\" w:hAnsi=\"\(defaultFont)\" w:eastAsia=\"\(eaFont)\" w:cs=\"\(defaultFont)\"",
            (ea ? " w:hint=\"eastAsia\"" : "") + "/>",
            (bold ? "<w:b/><w:bCs/>" : ""),
            (italic ? "<w:i/><w:iCs/>" : ""),
            (color != 0 ? "<w:color w:val=\"\(hexColor(color))\"/>" : ""),
            "<w:sz w:val=\"\(sz)\"/><w:szCs w:val=\"\(sz)\"/>\(langAttr)</w:rPr><w:t xml:space=\"preserve\">\(xmlEscape(text))</w:t></w:r>",
        ].joined()
    }

    static func paragraphXml(_ p: Paragraph, beforeTw: Int, leftTw: Int, firstLineTw: Int) -> String {
        var sb = "<w:p><w:pPr>"
        sb += "<w:spacing w:before=\"\(beforeTw)\" w:after=\"0\" w:line=\"240\" w:lineRule=\"auto\"/>"
        let left = (p.align == .left || p.align == .justify) ? max(0, leftTw) : 0
        if left > 40 || firstLineTw > 40 {
            sb += "<w:ind w:left=\"\(left)\"" + (firstLineTw > 40 ? " w:firstLine=\"\(firstLineTw)\"" : "") + "/>"
        }
        sb += "<w:jc w:val=\"\(jc(p.align))\"/></w:pPr>"
        sb += runXml(p.text, fontPt: p.fontPt, bold: p.bold, color: p.color, lang: p.lang, italic: p.italic)
        sb += "</w:p>"
        return sb
    }

    /// Ghi chú cạnh bảng: hộp văn bản (DrawingML wps) neo tuyệt đối theo trang, wrapNone, tự co giãn.
    private static func floatingTextBoxRun(_ p: Paragraph, twPerPx: Double, id: Int, pageWidthTw: Int) -> String {
        let emuPerTw = 635
        let cxTw = max(roundInt(p.box.width * twPerPx), roundInt(Double(p.text.count) * 0.5 * p.fontPt * 20))
        let xTw = min(roundInt(p.box.left * twPerPx), max(0, pageWidthTw - cxTw - 200))
        let x = xTw * emuPerTw
        let y = roundInt(p.box.top * twPerPx) * emuPerTw
        let cx = cxTw * emuPerTw
        let cy = roundInt(p.fontPt * 1.3 * 20) * emuPerTw
        return [
            "<w:r><w:drawing><wp:anchor distT=\"0\" distB=\"0\" distL=\"0\" distR=\"0\" simplePos=\"0\" relativeHeight=\"\(id)\" ",
            "behindDoc=\"0\" locked=\"0\" layoutInCell=\"1\" allowOverlap=\"1\"><wp:simplePos x=\"0\" y=\"0\"/>",
            "<wp:positionH relativeFrom=\"page\"><wp:posOffset>\(x)</wp:posOffset></wp:positionH>",
            "<wp:positionV relativeFrom=\"page\"><wp:posOffset>\(y)</wp:posOffset></wp:positionV>",
            "<wp:extent cx=\"\(cx)\" cy=\"\(cy)\"/><wp:effectExtent l=\"0\" t=\"0\" r=\"0\" b=\"0\"/><wp:wrapNone/>",
            "<wp:docPr id=\"\(id)\" name=\"Ghi chú \(id)\"/><wp:cNvGraphicFramePr/>",
            "<a:graphic><a:graphicData uri=\"http://schemas.microsoft.com/office/word/2010/wordprocessingShape\">",
            "<wps:wsp><wps:cNvSpPr txBox=\"1\"/><wps:spPr><a:xfrm><a:off x=\"0\" y=\"0\"/><a:ext cx=\"\(cx)\" cy=\"\(cy)\"/></a:xfrm>",
            "<a:prstGeom prst=\"rect\"><a:avLst/></a:prstGeom><a:noFill/><a:ln><a:noFill/></a:ln></wps:spPr>",
            "<wps:txbx><w:txbxContent><w:p><w:pPr><w:spacing w:before=\"0\" w:after=\"0\"/></w:pPr>",
            runXml(p.text, fontPt: p.fontPt, bold: p.bold, color: p.color, lang: p.lang, italic: p.italic) + "</w:p></w:txbxContent></wps:txbx>",
            "<wps:bodyPr rot=\"0\" wrap=\"none\" lIns=\"0\" tIns=\"0\" rIns=\"0\" bIns=\"0\" anchor=\"t\"><a:spAutoFit/></wps:bodyPr>",
            "</wps:wsp></a:graphicData></a:graphic></wp:anchor></w:drawing></w:r>",
        ].joined()
    }

    /// Thu nhỏ cỡ chữ cho vừa ô ("Shrink to fit"), tối thiểu 7 pt.
    private static func fitFont(_ p: Paragraph, cellWidthTw: Int, rowHeightPt: Double) -> Double {
        let avail = Double(max(cellWidthTw - 114, 100)) / 20
        var size = p.fontPt
        let em = Double(p.text.count) * 0.5
        let usableH = rowHeightPt - 2
        while size > 7 {
            let lines = max((em * size * 1.12 / avail).rounded(.up), 1)
            let maxLines = max((usableH / (size * 1.25)).rounded(.down), 1)
            if lines <= maxLines { break }
            size -= 0.5
        }
        return size
    }

    private static func spacerXml(_ beforeTw: Int) -> String {
        "<w:p><w:pPr><w:spacing w:before=\"\(beforeTw)\" w:after=\"0\" w:line=\"20\" w:lineRule=\"exact\"/></w:pPr></w:p>"
    }

    private static func tableXml(_ t: TableBlock, twPerPx: Double, indentTw: Int, textWidth: Int) -> String {
        var colW = (0..<t.colCount).map { max(roundInt((t.colEdges[$0 + 1] - t.colEdges[$0]) * twPerPx), 120) }
        let total = colW.reduce(0, +)
        let maxW = textWidth - indentTw
        if total > maxW && maxW > 0 {
            colW = colW.map { max(Int(Int64($0) * Int64(maxW) / Int64(total)), 100) }
        }
        let tblW = colW.reduce(0, +)

        var covering: [[TableCell?]] = Array(repeating: Array(repeating: nil, count: t.colCount), count: t.rowCount)
        for cell in t.cells {
            for r in cell.row..<min(t.rowCount, cell.row + cell.rowSpan) {
                for c in cell.col..<min(t.colCount, cell.col + cell.colSpan) { covering[r][c] = cell }
            }
        }

        // Cỡ chữ đồng nhất cho cả bảng: trung vị cỡ vừa ô; ô chật hơn mới thu nhỏ riêng.
        var fitted: [Paragraph: Double] = [:]
        for cell in t.cells where !cell.paragraphs.isEmpty {
            let span = min(cell.colSpan, t.colCount - cell.col)
            let width = (cell.col..<(cell.col + span)).reduce(0) { $0 + colW[$1] }
            let rEnd = min(t.rowCount, cell.row + cell.rowSpan)
            let hPt = (t.rowEdges[rEnd] - t.rowEdges[cell.row]) * twPerPx / 20 / Double(max(1, cell.paragraphs.count))
            for p in cell.paragraphs { fitted[p] = fitFont(p, cellWidthTw: width, rowHeightPt: hPt) }
        }
        let fittedSorted = fitted.values.sorted()
        let uniform = fittedSorted.isEmpty ? 12 : fittedSorted[fittedSorted.count / 2]

        let headerRows = TableStyle.headerRows(t)
        let border = t.bordered ? "w:val=\"single\" w:sz=\"4\" w:space=\"0\" w:color=\"000000\"" : "w:val=\"nil\""
        var sb = "<w:tbl><w:tblPr>"
        sb += "<w:tblW w:w=\"\(tblW)\" w:type=\"dxa\"/>"
        if indentTw > 20 { sb += "<w:tblInd w:w=\"\(indentTw)\" w:type=\"dxa\"/>" }
        sb += "<w:tblBorders>"
        for side in ["top", "left", "bottom", "right", "insideH", "insideV"] { sb += "<w:\(side) \(border)/>" }
        sb += "</w:tblBorders><w:tblLayout w:type=\"fixed\"/>"
        sb += "<w:tblCellMar><w:left w:w=\"57\" w:type=\"dxa\"/><w:right w:w=\"57\" w:type=\"dxa\"/></w:tblCellMar>"
        sb += "</w:tblPr><w:tblGrid>"
        for w in colW { sb += "<w:gridCol w:w=\"\(w)\"/>" }
        sb += "</w:tblGrid>"

        for r in 0..<t.rowCount {
            // Bảng kẻ ô: chiều cao hàng CỐ ĐỊNH như bản gốc; bảng không viền: tối thiểu.
            let h = max(roundInt((t.rowEdges[r + 1] - t.rowEdges[r]) * twPerPx * (t.bordered ? 1 : 0.9)), 200)
            let rule = t.bordered ? "exact" : "atLeast"
            sb += "<w:tr><w:trPr><w:cantSplit/>" + (r < headerRows ? "<w:tblHeader/>" : "") + "<w:trHeight w:val=\"\(h)\" w:hRule=\"\(rule)\"/></w:trPr>"
            var c = 0
            while c < t.colCount {
                guard let cell = covering[r][c] else {
                    sb += "<w:tc><w:tcPr><w:tcW w:w=\"\(colW[c])\" w:type=\"dxa\"/></w:tcPr><w:p/></w:tc>"
                    c += 1
                    continue
                }
                let span = max(1, min(cell.colSpan, t.colCount - c))
                let width = (c..<(c + span)).reduce(0) { $0 + colW[$1] }
                sb += "<w:tc><w:tcPr><w:tcW w:w=\"\(width)\" w:type=\"dxa\"/>"
                if span > 1 { sb += "<w:gridSpan w:val=\"\(span)\"/>" }
                let isOrigin = cell.row == r
                if cell.rowSpan > 1 { sb += isOrigin ? "<w:vMerge w:val=\"restart\"/>" : "<w:vMerge/>" }
                let va: String
                switch cell.vAlign {
                case .top: va = "top"
                case .center: va = "center"
                case .bottom: va = "bottom"
                }
                if cell.row < headerRows {
                    sb += "<w:shd w:val=\"clear\" w:color=\"auto\" w:fill=\"\(TableStyle.headerFill)\"/>"
                }
                sb += "<w:vAlign w:val=\"\(va)\"/></w:tcPr>"
                if isOrigin && !cell.paragraphs.isEmpty {
                    let cellLeftPx = t.colEdges[cell.col]
                    let rEnd = min(t.rowCount, cell.row + cell.rowSpan)
                    let cellHeightPt = (t.rowEdges[rEnd] - t.rowEdges[cell.row]) * twPerPx / 20 / Double(max(1, cell.paragraphs.count))
                    for p in cell.paragraphs {
                        let ind = p.align == .left ? max(0, roundInt((p.box.left - cellLeftPx) * twPerPx - 57)) : 0
                        let own = min(fitFont(p, cellWidthTw: width - ind, rowHeightPt: cellHeightPt), fitted[p] ?? p.fontPt)
                        let size = (t.bordered && !p.bold) ? min(own, uniform) : own
                        var q = p
                        q.indentPx = 0
                        q.fontPt = size
                        sb += paragraphXml(q, beforeTw: 0, leftTw: ind > 150 ? ind : 0, firstLineTw: 0)
                    }
                } else {
                    sb += "<w:p/>"
                }
                sb += "</w:tc>"
                c += span
            }
            sb += "</w:tr>"
        }
        sb += "</w:tbl>"
        return sb
    }

    private static func imageParagraphXml(rid: String, id: Int, cx: Int, cy: Int, align: Align, beforeTw: Int) -> String {
        [
            "<w:p><w:pPr><w:spacing w:before=\"\(beforeTw)\" w:after=\"0\"/><w:jc w:val=\"\(jc(align))\"/></w:pPr><w:r><w:drawing>",
            "<wp:inline distT=\"0\" distB=\"0\" distL=\"0\" distR=\"0\"><wp:extent cx=\"\(cx)\" cy=\"\(cy)\"/>",
            "<wp:docPr id=\"\(id)\" name=\"Picture \(id)\"/>",
            "<a:graphic><a:graphicData uri=\"http://schemas.openxmlformats.org/drawingml/2006/picture\">",
            "<pic:pic><pic:nvPicPr><pic:cNvPr id=\"\(id)\" name=\"image\(id).jpeg\"/><pic:cNvPicPr/></pic:nvPicPr>",
            "<pic:blipFill><a:blip r:embed=\"\(rid)\"/><a:stretch><a:fillRect/></a:stretch></pic:blipFill>",
            "<pic:spPr><a:xfrm><a:off x=\"0\" y=\"0\"/><a:ext cx=\"\(cx)\" cy=\"\(cy)\"/></a:xfrm><a:prstGeom prst=\"rect\"><a:avLst/></a:prstGeom></pic:spPr>",
            "</pic:pic></a:graphicData></a:graphic></wp:inline></w:drawing></w:r></w:p>",
        ].joined()
    }

    private static func stylesXml() -> String {
        [
            xmlHeader,
            "<w:styles xmlns:w=\"http://schemas.openxmlformats.org/wordprocessingml/2006/main\">",
            "<w:docDefaults><w:rPrDefault><w:rPr><w:rFonts w:ascii=\"\(defaultFont)\" w:eastAsia=\"\(defaultFont)\" w:hAnsi=\"\(defaultFont)\" w:cs=\"\(defaultFont)\"/>",
            "<w:sz w:val=\"26\"/><w:szCs w:val=\"26\"/><w:lang w:val=\"vi-VN\" w:eastAsia=\"en-US\" w:bidi=\"ar-SA\"/></w:rPr></w:rPrDefault>",
            "<w:pPrDefault><w:pPr><w:spacing w:after=\"0\" w:line=\"240\" w:lineRule=\"auto\"/></w:pPr></w:pPrDefault></w:docDefaults>",
            "<w:style w:type=\"paragraph\" w:default=\"1\" w:styleId=\"Normal\"><w:name w:val=\"Normal\"/><w:qFormat/></w:style>",
            "<w:style w:type=\"table\" w:default=\"1\" w:styleId=\"TableNormal\"><w:name w:val=\"Normal Table\"/><w:tblPr><w:tblInd w:w=\"0\" w:type=\"dxa\"/>",
            "<w:tblCellMar><w:top w:w=\"0\" w:type=\"dxa\"/><w:left w:w=\"108\" w:type=\"dxa\"/><w:bottom w:w=\"0\" w:type=\"dxa\"/><w:right w:w=\"108\" w:type=\"dxa\"/></w:tblCellMar></w:tblPr></w:style>",
            "</w:styles>",
        ].joined()
    }
}
