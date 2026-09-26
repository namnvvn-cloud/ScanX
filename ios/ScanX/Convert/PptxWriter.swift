import Foundation

/// Xuất PowerPoint (.pptx) — port 1:1 từ Android convert/PptxWriter.kt: mỗi trang = 1 slide cùng tỉ lệ khổ giấy,
/// đoạn chữ thành hộp chữ đặt ĐÚNG VỊ TRÍ tuyệt đối, bảng thành bảng PowerPoint thật (ô gộp, viền),
/// ảnh con dấu/chữ ký/logo giữ nguyên chỗ.
enum PptxWriter {
    private static let ns = "xmlns:a=\"http://schemas.openxmlformats.org/drawingml/2006/main\" "
        + "xmlns:r=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships\" "
        + "xmlns:p=\"http://schemas.openxmlformats.org/presentationml/2006/main\""

    private static let groupHeader = "<p:nvGrpSpPr><p:cNvPr id=\"1\" name=\"\"/><p:cNvGrpSpPr/><p:nvPr/></p:nvGrpSpPr>"
        + "<p:grpSpPr><a:xfrm><a:off x=\"0\" y=\"0\"/><a:ext cx=\"0\" cy=\"0\"/><a:chOff x=\"0\" y=\"0\"/><a:chExt cx=\"0\" cy=\"0\"/></a:xfrm></p:grpSpPr>"

    static func write(_ doc: DocModel) -> Data {
        let pkg = OoxmlPackage()
        let first = doc.pages.first
        let landscape = first.map { $0.width > $0.height } ?? false
        let slideCx = landscape ? 10_692_000 : 7_560_000  // A4: 297 × 210 mm
        let slideCy: Int
        if let first, first.width > 0 {
            slideCy = clampInt(Int(Int64(first.height) * Int64(slideCx) / Int64(first.width)), 914_400, 51_206_400)
        } else {
            slideCy = landscape ? 7_560_000 : 10_692_000
        }
        var imageCounter = 0

        for (pi, page) in doc.pages.enumerated() {
            let emu = Double(slideCx) / Double(max(page.width, 1))
            var shapes = ""
            var rels = "<Relationship Id=\"rId1\" Type=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships/slideLayout\" Target=\"../slideLayouts/slideLayout1.xml\"/>"
            var id = 2
            func e(_ v: Double) -> Int { roundInt(v * emu) }

            for block in page.blocks {
                switch block {
                case .paragraph(let p):
                    shapes += textBox(id: id, p, x: e(p.box.left), y: e(p.box.top), cx: e(p.box.width * 1.06), cy: e(p.box.height), slideCx: slideCx)
                    id += 1
                case .table(let t):
                    if t.bordered {
                        shapes += tableFrame(id: id, t, emu: emu)
                        id += 1
                    } else {
                        for cell in t.cells {
                            for p in cell.paragraphs {
                                shapes += textBox(id: id, p, x: e(p.box.left), y: e(p.box.top), cx: e(p.box.width * 1.06), cy: e(p.box.height), slideCx: slideCx)
                                id += 1
                            }
                        }
                    }
                case .image(let im):
                    imageCounter += 1
                    let rid = "rIdImg\(imageCounter)"
                    pkg.putBinary("ppt/media/image\(imageCounter).jpeg", im.figure.jpeg)
                    rels += "<Relationship Id=\"\(rid)\" Type=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships/image\" Target=\"../media/image\(imageCounter).jpeg\"/>"
                    shapes += [
                        "<p:pic><p:nvPicPr><p:cNvPr id=\"\(id)\" name=\"Picture \(id)\"/><p:cNvPicPr><a:picLocks noChangeAspect=\"1\"/></p:cNvPicPr><p:nvPr/></p:nvPicPr>",
                        "<p:blipFill><a:blip r:embed=\"\(rid)\"/><a:stretch><a:fillRect/></a:stretch></p:blipFill>",
                        "<p:spPr><a:xfrm><a:off x=\"\(e(im.box.left))\" y=\"\(e(im.box.top))\"/><a:ext cx=\"\(e(im.box.width))\" cy=\"\(e(im.box.height))\"/></a:xfrm>",
                        "<a:prstGeom prst=\"rect\"><a:avLst/></a:prstGeom></p:spPr></p:pic>",
                    ].joined()
                    id += 1
                }
            }
            pkg.put("ppt/slides/slide\(pi + 1).xml", [
                xmlHeader,
                "<p:sld \(ns)><p:cSld><p:spTree>\(groupHeader)\(shapes)</p:spTree></p:cSld><p:clrMapOvr><a:masterClrMapping/></p:clrMapOvr></p:sld>",
            ].joined())
            pkg.put("ppt/slides/_rels/slide\(pi + 1).xml.rels", [
                xmlHeader,
                "<Relationships xmlns=\"http://schemas.openxmlformats.org/package/2006/relationships\">\(rels)</Relationships>",
            ].joined())
        }

        let n = doc.pages.count
        let slideOverrides = (0..<n).map {
            "<Override PartName=\"/ppt/slides/slide\($0 + 1).xml\" ContentType=\"application/vnd.openxmlformats-officedocument.presentationml.slide+xml\"/>"
        }.joined()
        pkg.put("[Content_Types].xml", [
            xmlHeader,
            "<Types xmlns=\"http://schemas.openxmlformats.org/package/2006/content-types\">",
            "<Default Extension=\"rels\" ContentType=\"application/vnd.openxmlformats-package.relationships+xml\"/>",
            "<Default Extension=\"xml\" ContentType=\"application/xml\"/>",
            "<Default Extension=\"jpeg\" ContentType=\"image/jpeg\"/>",
            "<Override PartName=\"/ppt/presentation.xml\" ContentType=\"application/vnd.openxmlformats-officedocument.presentationml.presentation.main+xml\"/>",
            "<Override PartName=\"/ppt/slideMasters/slideMaster1.xml\" ContentType=\"application/vnd.openxmlformats-officedocument.presentationml.slideMaster+xml\"/>",
            "<Override PartName=\"/ppt/slideLayouts/slideLayout1.xml\" ContentType=\"application/vnd.openxmlformats-officedocument.presentationml.slideLayout+xml\"/>",
            "<Override PartName=\"/ppt/theme/theme1.xml\" ContentType=\"application/vnd.openxmlformats-officedocument.theme+xml\"/>",
            slideOverrides,
            "<Override PartName=\"/docProps/core.xml\" ContentType=\"application/vnd.openxmlformats-package.core-properties+xml\"/>",
            "</Types>",
        ].joined())
        pkg.put("_rels/.rels", [
            xmlHeader,
            "<Relationships xmlns=\"http://schemas.openxmlformats.org/package/2006/relationships\">",
            "<Relationship Id=\"rId1\" Type=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships/officeDocument\" Target=\"ppt/presentation.xml\"/>",
            "<Relationship Id=\"rId2\" Type=\"http://schemas.openxmlformats.org/package/2006/relationships/metadata/core-properties\" Target=\"docProps/core.xml\"/>",
            "</Relationships>",
        ].joined())
        let sldIds = (0..<n).map { "<p:sldId id=\"\(256 + $0)\" r:id=\"rId\($0 + 2)\"/>" }.joined()
        pkg.put("ppt/presentation.xml", [
            xmlHeader,
            "<p:presentation \(ns)><p:sldMasterIdLst><p:sldMasterId id=\"2147483648\" r:id=\"rId1\"/></p:sldMasterIdLst>",
            "<p:sldIdLst>", sldIds, "</p:sldIdLst>",
            "<p:sldSz cx=\"\(slideCx)\" cy=\"\(slideCy)\" type=\"custom\"/><p:notesSz cx=\"6858000\" cy=\"9144000\"/></p:presentation>",
        ].joined())
        let slideRels = (0..<n).map {
            "<Relationship Id=\"rId\($0 + 2)\" Type=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships/slide\" Target=\"slides/slide\($0 + 1).xml\"/>"
        }.joined()
        pkg.put("ppt/_rels/presentation.xml.rels", [
            xmlHeader,
            "<Relationships xmlns=\"http://schemas.openxmlformats.org/package/2006/relationships\">",
            "<Relationship Id=\"rId1\" Type=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships/slideMaster\" Target=\"slideMasters/slideMaster1.xml\"/>",
            slideRels,
            "<Relationship Id=\"rIdTheme\" Type=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships/theme\" Target=\"theme/theme1.xml\"/>",
            "</Relationships>",
        ].joined())
        pkg.put("ppt/slideMasters/slideMaster1.xml", [
            xmlHeader,
            "<p:sldMaster \(ns)><p:cSld><p:spTree>\(groupHeader)</p:spTree></p:cSld>",
            "<p:clrMap bg1=\"lt1\" tx1=\"dk1\" bg2=\"lt2\" tx2=\"dk2\" accent1=\"accent1\" accent2=\"accent2\" accent3=\"accent3\" ",
            "accent4=\"accent4\" accent5=\"accent5\" accent6=\"accent6\" hlink=\"hlink\" folHlink=\"folHlink\"/>",
            "<p:sldLayoutIdLst><p:sldLayoutId id=\"2147483649\" r:id=\"rId1\"/></p:sldLayoutIdLst></p:sldMaster>",
        ].joined())
        pkg.put("ppt/slideMasters/_rels/slideMaster1.xml.rels", [
            xmlHeader,
            "<Relationships xmlns=\"http://schemas.openxmlformats.org/package/2006/relationships\">",
            "<Relationship Id=\"rId1\" Type=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships/slideLayout\" Target=\"../slideLayouts/slideLayout1.xml\"/>",
            "<Relationship Id=\"rId2\" Type=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships/theme\" Target=\"../theme/theme1.xml\"/>",
            "</Relationships>",
        ].joined())
        pkg.put("ppt/slideLayouts/slideLayout1.xml", [
            xmlHeader,
            "<p:sldLayout \(ns) type=\"blank\" preserve=\"1\"><p:cSld name=\"Blank\"><p:spTree>\(groupHeader)</p:spTree></p:cSld>",
            "<p:clrMapOvr><a:masterClrMapping/></p:clrMapOvr></p:sldLayout>",
        ].joined())
        pkg.put("ppt/slideLayouts/_rels/slideLayout1.xml.rels", [
            xmlHeader,
            "<Relationships xmlns=\"http://schemas.openxmlformats.org/package/2006/relationships\">",
            "<Relationship Id=\"rId1\" Type=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships/slideMaster\" Target=\"../slideMasters/slideMaster1.xml\"/>",
            "</Relationships>",
        ].joined())
        pkg.put("ppt/theme/theme1.xml", themeXml())
        pkg.put("docProps/core.xml", corePropsXml(doc.title))
        return pkg.archive()
    }

    private static func algn(_ a: Align) -> String {
        switch a {
        case .left: return "l"
        case .center: return "ctr"
        case .right: return "r"
        case .justify: return "just"
        }
    }

    private static func runXml(_ p: Paragraph, sizePt: Double? = nil) -> String {
        let sz = clampInt(roundInt((sizePt ?? p.fontPt) * 100), 100, 400_000)
        var attrs = "lang=\"\(Lang.ooxml(p.lang))\""
        if Lang.isEastAsian(p.lang) { attrs += " altLang=\"vi-VN\"" }
        attrs += " sz=\"\(sz)\""
        if p.bold { attrs += " b=\"1\"" }
        if p.italic { attrs += " i=\"1\"" }
        attrs += " dirty=\"0\""
        return [
            "<a:p><a:pPr algn=\"\(algn(p.align))\"/><a:r><a:rPr \(attrs)>",
            "<a:solidFill><a:srgbClr val=\"\(hexColor(p.color))\"/></a:solidFill><a:latin typeface=\"\(defaultFont)\"/>",
            "<a:ea typeface=\"\(Lang.fontFor(p.lang))\"/><a:cs typeface=\"\(defaultFont)\"/></a:rPr>",
            "<a:t>\(xmlEscape(p.text))</a:t></a:r></a:p>",
        ].joined()
    }

    private static func textBox(id: Int, _ p: Paragraph, x: Int, y: Int, cx: Int, cy: Int, slideCx: Int) -> String {
        let w = min(max(cx, 1), max(1, slideCx - x))
        let wrap = p.floating ? "none" : "square"
        return [
            "<p:sp><p:nvSpPr><p:cNvPr id=\"\(id)\" name=\"Text \(id)\"/><p:cNvSpPr txBox=\"1\"/><p:nvPr/></p:nvSpPr>",
            "<p:spPr><a:xfrm><a:off x=\"\(x)\" y=\"\(y)\"/><a:ext cx=\"\(w)\" cy=\"\(max(cy, 1))\"/></a:xfrm><a:prstGeom prst=\"rect\"><a:avLst/></a:prstGeom><a:noFill/></p:spPr>",
            "<p:txBody><a:bodyPr wrap=\"\(wrap)\" lIns=\"0\" tIns=\"0\" rIns=\"0\" bIns=\"0\" rtlCol=\"0\" anchor=\"t\"><a:noAutofit/></a:bodyPr><a:lstStyle/>",
            runXml(p),
            "</p:txBody></p:sp>",
        ].joined()
    }

    private static func tableFrame(id: Int, _ t: TableBlock, emu: Double) -> String {
        let colW = (0..<t.colCount).map { max(roundInt((t.colEdges[$0 + 1] - t.colEdges[$0]) * emu), 12700) }
        let rowH = (0..<t.rowCount).map { max(roundInt((t.rowEdges[$0 + 1] - t.rowEdges[$0]) * emu), 12700) }
        var covering: [[TableCell?]] = Array(repeating: Array(repeating: nil, count: t.colCount), count: t.rowCount)
        for cell in t.cells {
            for r in cell.row..<min(t.rowCount, cell.row + cell.rowSpan) {
                for c in cell.col..<min(t.colCount, cell.col + cell.colSpan) { covering[r][c] = cell }
            }
        }
        let line = "w=\"12700\"><a:solidFill><a:srgbClr val=\"000000\"/></a:solidFill>"
        func tcPr(_ anchor: String, fill: Bool) -> String {
            [
                "<a:tcPr marL=\"45720\" marR=\"45720\" marT=\"0\" marB=\"0\" anchor=\"\(anchor)\">",
                "<a:lnL \(line)</a:lnL><a:lnR \(line)</a:lnR><a:lnT \(line)</a:lnT><a:lnB \(line)</a:lnB>",
                fill ? "<a:solidFill><a:srgbClr val=\"\(TableStyle.headerFill)\"/></a:solidFill>" : "<a:noFill/>",
                "</a:tcPr>",
            ].joined()
        }
        let headerRows = TableStyle.headerRows(t)
        let emptyBody = "<a:txBody><a:bodyPr/><a:lstStyle/><a:p><a:endParaRPr lang=\"vi-VN\" sz=\"1000\" dirty=\"0\"/></a:p></a:txBody>"
        // Cỡ chữ đồng nhất cho chữ thường trong bảng (trung vị), vừa hàng thấp nhất.
        let fonts = t.cells.flatMap { c in c.paragraphs.filter { !$0.bold }.map { $0.fontPt } }.sorted()
        let minRowPt = rowH.min().map { Double($0) / 12700 } ?? 20
        let uniform = fonts.isEmpty ? 12 : min(fonts[fonts.count / 2], max(7, minRowPt * 0.45))

        var sb = "<p:graphicFrame><p:nvGraphicFramePr><p:cNvPr id=\"\(id)\" name=\"Table \(id)\"/><p:cNvGraphicFramePr><a:graphicFrameLocks noGrp=\"1\"/></p:cNvGraphicFramePr><p:nvPr/></p:nvGraphicFramePr>"
        sb += "<p:xfrm><a:off x=\"\(roundInt(t.box.left * emu))\" y=\"\(roundInt(t.box.top * emu))\"/><a:ext cx=\"\(colW.reduce(0, +))\" cy=\"\(rowH.reduce(0, +))\"/></p:xfrm>"
        sb += "<a:graphic><a:graphicData uri=\"http://schemas.openxmlformats.org/drawingml/2006/table\"><a:tbl><a:tblPr firstRow=\"0\" bandRow=\"0\"/><a:tblGrid>"
        for w in colW { sb += "<a:gridCol w=\"\(w)\"/>" }
        sb += "</a:tblGrid>"
        for r in 0..<t.rowCount {
            sb += "<a:tr h=\"\(rowH[r])\">"
            for c in 0..<t.colCount {
                guard let cell = covering[r][c] else {
                    sb += "<a:tc>\(emptyBody)\(tcPr("t", fill: false))</a:tc>"
                    continue
                }
                let anchor: String
                switch cell.vAlign {
                case .top: anchor = "t"
                case .center: anchor = "ctr"
                case .bottom: anchor = "b"
                }
                if cell.row == r && cell.col == c {
                    var attrs = ""
                    if cell.colSpan > 1 { attrs += " gridSpan=\"\(cell.colSpan)\"" }
                    if cell.rowSpan > 1 { attrs += " rowSpan=\"\(cell.rowSpan)\"" }
                    let body: String
                    if cell.paragraphs.isEmpty {
                        body = emptyBody
                    } else {
                        let runs = cell.paragraphs.map { p in
                            runXml(p, sizePt: p.bold ? min(p.fontPt, minRowPt * 0.6) : uniform)
                        }.joined()
                        body = "<a:txBody><a:bodyPr/><a:lstStyle/>\(runs)</a:txBody>"
                    }
                    sb += "<a:tc\(attrs)>\(body)\(tcPr(anchor, fill: r < headerRows))</a:tc>"
                } else {
                    var attrs = ""
                    if c > cell.col { attrs += " hMerge=\"1\"" }
                    if r > cell.row { attrs += " vMerge=\"1\"" }
                    sb += "<a:tc\(attrs)>\(emptyBody)\(tcPr(anchor, fill: r < headerRows))</a:tc>"
                }
            }
            sb += "</a:tr>"
        }
        sb += "</a:tbl></a:graphicData></a:graphic></p:graphicFrame>"
        return sb
    }

    private static func themeXml() -> String {
        func c(_ tag: String, _ hex: String) -> String { "<a:\(tag)><a:srgbClr val=\"\(hex)\"/></a:\(tag)>" }
        let solid = "<a:solidFill><a:schemeClr val=\"phClr\"/></a:solidFill>"
        let colors = [
            c("dk2", "1F497D"), c("lt2", "EEECE1"), c("accent1", "4F81BD"), c("accent2", "C0504D"), c("accent3", "9BBB59"),
            c("accent4", "8064A2"), c("accent5", "4BACC6"), c("accent6", "F79646"), c("hlink", "0000FF"), c("folHlink", "800080"),
        ].joined()
        return [
            xmlHeader,
            "<a:theme xmlns:a=\"http://schemas.openxmlformats.org/drawingml/2006/main\" name=\"ScanX\"><a:themeElements>",
            "<a:clrScheme name=\"ScanX\"><a:dk1><a:sysClr val=\"windowText\" lastClr=\"000000\"/></a:dk1><a:lt1><a:sysClr val=\"window\" lastClr=\"FFFFFF\"/></a:lt1>",
            colors,
            "</a:clrScheme>",
            "<a:fontScheme name=\"ScanX\"><a:majorFont><a:latin typeface=\"\(defaultFont)\"/><a:ea typeface=\"\"/><a:cs typeface=\"\"/></a:majorFont>",
            "<a:minorFont><a:latin typeface=\"\(defaultFont)\"/><a:ea typeface=\"\"/><a:cs typeface=\"\"/></a:minorFont></a:fontScheme>",
            "<a:fmtScheme name=\"ScanX\"><a:fillStyleLst>\(solid)\(solid)\(solid)</a:fillStyleLst>",
            "<a:lnStyleLst>", String(repeating: "<a:ln w=\"9525\">\(solid)</a:ln>", count: 3), "</a:lnStyleLst>",
            "<a:effectStyleLst>", String(repeating: "<a:effectStyle><a:effectLst/></a:effectStyle>", count: 3), "</a:effectStyleLst>",
            "<a:bgFillStyleLst>\(solid)\(solid)\(solid)</a:bgFillStyleLst></a:fmtScheme>",
            "</a:themeElements><a:objectDefaults/><a:extraClrSchemeLst/></a:theme>",
        ].joined()
    }
}
