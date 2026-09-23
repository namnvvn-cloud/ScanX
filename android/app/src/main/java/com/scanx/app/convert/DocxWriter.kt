package com.scanx.app.convert

import java.io.OutputStream
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Xuất Word (.docx) giữ bố cục bản scan: khổ giấy + lề suy từ vùng nội dung, đoạn văn đúng căn lề/
 * cỡ chữ/đậm/thụt lề/khoảng cách, bảng thật (có ô gộp ngang/dọc, độ rộng cột theo tỉ lệ bản gốc),
 * header nhiều cột dạng bảng không viền, ảnh con dấu/chữ ký đặt đúng chỗ. Người dùng sửa được như
 * văn bản soạn tay, không phải ảnh dán vào Word.
 */
object DocxWriter {

    private const val A4_W = 11906
    private const val A4_H = 16838

    fun write(doc: DocModel, out: OutputStream) {
        val pkg = OoxmlPackage()
        val body = StringBuilder()
        val rels = StringBuilder()
        var imageCounter = 0
        var shapeCounter = 1000

        // Mỗi trang scan = 1 section Word riêng: khổ A4 dọc/ngang đúng theo trang gốc, lề suy từ vùng nội dung.
        fun clampMargin(v: Float) = v.roundToInt().coerceIn(340, 2268)
        var lastSectPr = ""
        doc.pages.forEachIndexed { pi, page ->
            val twPerPx = page.ptPerPx * 20f
            val landscape = page.isLandscape
            val pageW = if (landscape) A4_H else A4_W
            val pageH = run {
                val h = (page.height * twPerPx).roundToInt()
                val a4 = if (landscape) A4_W else A4_H
                if (abs(h - a4) < a4 * 0.06f) a4 else h
            }
            val mL = clampMargin(page.content.left * twPerPx)
            val mR = clampMargin((page.width - page.content.right) * twPerPx)
            val mT = clampMargin(page.content.top * twPerPx)
            val mB = clampMargin((page.height - page.content.bottom) * twPerPx)
            val textWidth = pageW - mL - mR
            val orient = if (landscape) " w:orient=\"landscape\"" else ""
            val sectPr = "<w:sectPr><w:pgSz w:w=\"$pageW\" w:h=\"$pageH\"$orient/>" +
                "<w:pgMar w:top=\"$mT\" w:right=\"$mR\" w:bottom=\"$mB\" w:left=\"$mL\" w:header=\"0\" w:footer=\"0\" w:gutter=\"0\"/></w:sectPr>"
            // Kết thúc section của trang trước (sectPr đặt trong đoạn cuối trang trước → sang trang mới).
            if (pi > 0) body.append("<w:p><w:pPr><w:spacing w:before=\"0\" w:after=\"0\" w:line=\"20\" w:lineRule=\"exact\"/>$lastSectPr</w:pPr></w:p>")
            lastSectPr = sectPr
            val pageLeftPx = page.content.left
            val leftOffsetTw = ((pageLeftPx * twPerPx) - mL).roundToInt().coerceAtLeast(0)
            var lastWasTable = false
            // Trang dạng "sổ ghi chép/biểu mẫu viết tay": bảng kẻ ô chiếm phần lớn trang, chữ ngoài bảng thưa
            // hoặc là chữ viết tay → mọi chữ ngoài bảng đặt tuyệt đối đúng toạ độ, chỉ bảng nằm trong dòng
            // chảy → trang Word giống bản gốc 1:1 và không tràn trang. Văn bản thường vẫn là đoạn văn chảy.
            val bordered = page.blocks.filterIsInstance<TableBlock>().filter { it.bordered }
            val freeParas = page.blocks.filterIsInstance<ParagraphBlock>().count { !it.paragraph.floating }
            val absolute = bordered.isNotEmpty() && (page.ocrConfidence < 0.8f ||
                (freeParas <= 8 && bordered.sumOf { it.box.height.toDouble() } > page.height * 0.45))
            val blocksToFlow: List<Block>
            val floats: List<Paragraph>
            if (absolute) {
                val extra = ArrayList<Paragraph>()
                val flow = ArrayList<Block>()
                for (b in page.blocks) when {
                    b is ParagraphBlock -> extra.add(b.paragraph)
                    b is TableBlock && !b.bordered -> b.cells.forEach { c -> extra.addAll(c.paragraphs) }
                    else -> flow.add(b)
                }
                // Khoảng cách đầu trang tới bảng đầu tiên đúng như bản gốc.
                blocksToFlow = flow.mapIndexed { i, b ->
                    if (i == 0 && b is TableBlock) TableBlock(b.box, b.colEdges, b.rowEdges, b.cells, b.bordered, max(0f, b.box.top - mT / twPerPx)) else b
                }
                // Cỡ chữ ghi chú theo cỡ chữ trong bảng (hộp chữ viết tay cao hơn nhiều so với cỡ chữ thật).
                val cellFonts = bordered.flatMap { t -> t.cells.flatMap { c -> c.paragraphs.map { it.fontPt } } }.sorted()
                val cap = if (cellFonts.isEmpty()) 14f else (cellFonts[cellFonts.size / 2] * 1.3f).coerceIn(11f, 16f)
                floats = extra.map { it.copy(floating = true, fontPt = min(it.fontPt, cap)) }
            } else {
                blocksToFlow = page.blocks
                floats = page.blocks.filterIsInstance<ParagraphBlock>().map { it.paragraph }.filter { it.floating }
            }
            // Ghi chú định vị tuyệt đối ghi trước để neo vào đúng trang này (không bị đẩy sang trang sau).
            if (floats.isNotEmpty()) {
                body.append("<w:p><w:pPr><w:spacing w:before=\"0\" w:after=\"0\" w:line=\"20\" w:lineRule=\"exact\"/></w:pPr>")
                for (fp in floats) body.append(floatingTextBoxRun(fp, twPerPx, ++shapeCounter, pageW))
                body.append("</w:p>")
            }
            for (block in blocksToFlow) {
                when (block) {
                    is ParagraphBlock -> if (block.paragraph.floating) {
                        Unit
                    } else {
                        val p = block.paragraph
                        val before = spacingBeforeTw(p.spaceBeforePx, twPerPx, p.fontPt)
                        body.append(paragraphXml(p, before, leftOffsetTw + (p.indentPx * twPerPx).roundToInt(), (p.firstLineIndentPx * twPerPx).roundToInt()))
                        lastWasTable = false
                    }
                    is TableBlock -> {
                        // Đoạn đệm: tạo khoảng cách phía trên + ngăn Word tự gộp 2 bảng liền nhau.
                        val gapTw = (block.spaceBeforePx * twPerPx).roundToInt()
                        if (gapTw > 60 || lastWasTable) body.append(spacerXml(max(0, gapTw - 20)))
                        val indent = ((block.box.left * twPerPx) - mL).roundToInt()
                        body.append(tableXml(block, twPerPx, indent.coerceAtLeast(0), textWidth))
                        lastWasTable = true
                    }
                    is ImageBlock -> {
                        imageCounter++
                        val rid = "rIdImg$imageCounter"
                        pkg.putBinary("word/media/image$imageCounter.jpeg", block.figure.jpeg)
                        rels.append("<Relationship Id=\"$rid\" Type=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships/image\" Target=\"media/image$imageCounter.jpeg\"/>")
                        val emuPerPx = page.ptPerPx * 12700f
                        var cx = (block.box.width * emuPerPx).roundToInt()
                        var cy = (block.box.height * emuPerPx).roundToInt()
                        val maxCx = textWidth * 635 // 1 twip = 635 EMU
                        if (cx > maxCx) { cy = (cy.toLong() * maxCx / cx).toInt(); cx = maxCx }
                        val before = spacingBeforeTw(block.spaceBeforePx, twPerPx, 12f)
                        body.append(imageParagraphXml(rid, imageCounter, cx, cy, block.align, before))
                        lastWasTable = false
                    }
                }
            }
        }
        body.append("<w:p><w:pPr><w:spacing w:before=\"0\" w:after=\"0\" w:line=\"20\" w:lineRule=\"exact\"/></w:pPr></w:p>")

        val document = XML_HEADER +
            "<w:document xmlns:w=\"http://schemas.openxmlformats.org/wordprocessingml/2006/main\" " +
            "xmlns:r=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships\" " +
            "xmlns:wp=\"http://schemas.openxmlformats.org/drawingml/2006/wordprocessingDrawing\" " +
            "xmlns:a=\"http://schemas.openxmlformats.org/drawingml/2006/main\" " +
            "xmlns:pic=\"http://schemas.openxmlformats.org/drawingml/2006/picture\" " +
            "xmlns:wps=\"http://schemas.microsoft.com/office/word/2010/wordprocessingShape\">" +
            "<w:body>$body$lastSectPr</w:body></w:document>"

        pkg.put("[Content_Types].xml", XML_HEADER +
            "<Types xmlns=\"http://schemas.openxmlformats.org/package/2006/content-types\">" +
            "<Default Extension=\"rels\" ContentType=\"application/vnd.openxmlformats-package.relationships+xml\"/>" +
            "<Default Extension=\"xml\" ContentType=\"application/xml\"/>" +
            "<Default Extension=\"jpeg\" ContentType=\"image/jpeg\"/>" +
            "<Override PartName=\"/word/document.xml\" ContentType=\"application/vnd.openxmlformats-officedocument.wordprocessingml.document.main+xml\"/>" +
            "<Override PartName=\"/word/styles.xml\" ContentType=\"application/vnd.openxmlformats-officedocument.wordprocessingml.styles+xml\"/>" +
            "<Override PartName=\"/docProps/core.xml\" ContentType=\"application/vnd.openxmlformats-package.core-properties+xml\"/>" +
            "<Override PartName=\"/docProps/app.xml\" ContentType=\"application/vnd.openxmlformats-officedocument.extended-properties+xml\"/>" +
            "</Types>")
        pkg.put("_rels/.rels", XML_HEADER +
            "<Relationships xmlns=\"http://schemas.openxmlformats.org/package/2006/relationships\">" +
            "<Relationship Id=\"rId1\" Type=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships/officeDocument\" Target=\"word/document.xml\"/>" +
            "<Relationship Id=\"rId2\" Type=\"http://schemas.openxmlformats.org/package/2006/relationships/metadata/core-properties\" Target=\"docProps/core.xml\"/>" +
            "<Relationship Id=\"rId3\" Type=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships/extended-properties\" Target=\"docProps/app.xml\"/>" +
            "</Relationships>")
        pkg.put("word/_rels/document.xml.rels", XML_HEADER +
            "<Relationships xmlns=\"http://schemas.openxmlformats.org/package/2006/relationships\">" +
            "<Relationship Id=\"rIdStyles\" Type=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships/styles\" Target=\"styles.xml\"/>" +
            rels + "</Relationships>")
        pkg.put("word/styles.xml", stylesXml())
        pkg.put("word/document.xml", document)
        pkg.put("docProps/core.xml", corePropsXml(doc.title))
        pkg.put("docProps/app.xml", XML_HEADER +
            "<Properties xmlns=\"http://schemas.openxmlformats.org/officeDocument/2006/extended-properties\"><Application>ScanX</Application></Properties>")
        pkg.writeTo(out)
    }

    private fun spacingBeforeTw(gapPx: Float, twPerPx: Float, fontPt: Float): Int {
        val gapPt = gapPx * twPerPx / 20f - fontPt * 0.35f
        return if (gapPt < 2f) 0 else (min(gapPt, 72f) * 20f).roundToInt()
    }

    private fun jc(a: Align) = when (a) {
        Align.LEFT -> "left"
        Align.CENTER -> "center"
        Align.RIGHT -> "right"
        Align.JUSTIFY -> "both"
    }

    /**
     * 1 run chữ. Chữ Hàn/Nhật/Trung: font Đông Á riêng (w:eastAsia + hint) và mã ngôn ngữ Đông Á → Word
     * hiển thị đúng glyph, không ô vuông; chữ Latin trong cùng run vẫn dùng Times New Roman.
     */
    private fun runXml(text: String, fontPt: Float, bold: Boolean, color: Int = 0, lang: String = "", italic: Boolean = false): String {
        val sz = (fontPt * 2).roundToInt()
        val ea = Lang.isEastAsian(lang)
        val eaFont = if (ea) Lang.fontFor(lang) else DEFAULT_FONT
        val langAttr = if (ea) "<w:lang w:val=\"vi-VN\" w:eastAsia=\"${Lang.ooxml(lang)}\"/>"
            else if (lang.isNotEmpty()) "<w:lang w:val=\"${Lang.ooxml(lang)}\"/>" else ""
        return "<w:r><w:rPr><w:rFonts w:ascii=\"$DEFAULT_FONT\" w:hAnsi=\"$DEFAULT_FONT\" w:eastAsia=\"$eaFont\" w:cs=\"$DEFAULT_FONT\"" +
            (if (ea) " w:hint=\"eastAsia\"" else "") + "/>" +
            (if (bold) "<w:b/><w:bCs/>" else "") +
            (if (italic) "<w:i/><w:iCs/>" else "") +
            (if (color != 0) "<w:color w:val=\"${hexColor(color)}\"/>" else "") +
            "<w:sz w:val=\"$sz\"/><w:szCs w:val=\"$sz\"/>$langAttr</w:rPr><w:t xml:space=\"preserve\">${xmlEscape(text)}</w:t></w:r>"
    }

    private fun paragraphXml(p: Paragraph, beforeTw: Int, leftTw: Int, firstLineTw: Int): String {
        val sb = StringBuilder("<w:p><w:pPr>")
        sb.append("<w:spacing w:before=\"$beforeTw\" w:after=\"0\" w:line=\"240\" w:lineRule=\"auto\"/>")
        val left = if (p.align == Align.LEFT || p.align == Align.JUSTIFY) leftTw.coerceAtLeast(0) else 0
        if (left > 40 || firstLineTw > 40) {
            sb.append("<w:ind w:left=\"$left\"" + (if (firstLineTw > 40) " w:firstLine=\"$firstLineTw\"" else "") + "/>")
        }
        sb.append("<w:jc w:val=\"${jc(p.align)}\"/></w:pPr>")
        sb.append(runXml(p.text, p.fontPt, p.bold, p.color, p.lang, p.italic))
        sb.append("</w:p>")
        return sb.toString()
    }

    /**
     * Ghi chú cạnh bảng: hộp văn bản (DrawingML wps) neo tuyệt đối theo trang, không bao quanh chữ
     * (wrapNone) và tự co giãn theo nội dung → nằm đúng vị trí như trên giấy, không đẩy bảng xuống,
     * vẫn sửa chữ được. Hỗ trợ Word 2010+, LibreOffice, WPS.
     */
    private fun floatingTextBoxRun(p: Paragraph, twPerPx: Float, id: Int, pageWidthTw: Int): String {
        val emuPerTw = 635L
        val cxTw = max((p.box.width * twPerPx).roundToInt(), (p.text.length * 0.5f * p.fontPt * 20f).roundToInt())
        // Không để hộp chữ tràn ra ngoài mép phải trang.
        val xTw = min((p.box.left * twPerPx).roundToInt(), max(0, pageWidthTw - cxTw - 200))
        val x = xTw * emuPerTw
        val y = (p.box.top * twPerPx).roundToInt() * emuPerTw
        val cx = cxTw * emuPerTw
        val cy = (p.fontPt * 1.3f * 20f).roundToInt() * emuPerTw
        return "<w:r><w:drawing><wp:anchor distT=\"0\" distB=\"0\" distL=\"0\" distR=\"0\" simplePos=\"0\" relativeHeight=\"$id\" " +
            "behindDoc=\"0\" locked=\"0\" layoutInCell=\"1\" allowOverlap=\"1\"><wp:simplePos x=\"0\" y=\"0\"/>" +
            "<wp:positionH relativeFrom=\"page\"><wp:posOffset>$x</wp:posOffset></wp:positionH>" +
            "<wp:positionV relativeFrom=\"page\"><wp:posOffset>$y</wp:posOffset></wp:positionV>" +
            "<wp:extent cx=\"$cx\" cy=\"$cy\"/><wp:effectExtent l=\"0\" t=\"0\" r=\"0\" b=\"0\"/><wp:wrapNone/>" +
            "<wp:docPr id=\"$id\" name=\"Ghi chú $id\"/><wp:cNvGraphicFramePr/>" +
            "<a:graphic><a:graphicData uri=\"http://schemas.microsoft.com/office/word/2010/wordprocessingShape\">" +
            "<wps:wsp><wps:cNvSpPr txBox=\"1\"/><wps:spPr><a:xfrm><a:off x=\"0\" y=\"0\"/><a:ext cx=\"$cx\" cy=\"$cy\"/></a:xfrm>" +
            "<a:prstGeom prst=\"rect\"><a:avLst/></a:prstGeom><a:noFill/><a:ln><a:noFill/></a:ln></wps:spPr>" +
            "<wps:txbx><w:txbxContent><w:p><w:pPr><w:spacing w:before=\"0\" w:after=\"0\"/></w:pPr>" +
            runXml(p.text, p.fontPt, p.bold, p.color, p.lang, p.italic) + "</w:p></w:txbxContent></wps:txbx>" +
            "<wps:bodyPr rot=\"0\" wrap=\"none\" lIns=\"0\" tIns=\"0\" rIns=\"0\" bIns=\"0\" anchor=\"t\"><a:spAutoFit/></wps:bodyPr>" +
            "</wps:wsp></a:graphicData></a:graphic></wp:anchor></w:drawing></w:r>"
    }

    private fun hexColor(c: Int) = String.format(java.util.Locale.US, "%06X", c and 0xFFFFFF)

    /**
     * Thu nhỏ cỡ chữ cho vừa ô (giống "Shrink to fit"): ước lượng bề rộng chuỗi theo em của Times New
     * Roman; nếu vượt số dòng mà chiều cao hàng chứa được thì giảm cỡ, tối thiểu 7 pt → bảng không bị
     * giãn cao/dựng đứng chữ, giữ đúng kích thước như bản gốc.
     */
    private fun fitFont(p: Paragraph, cellWidthTw: Int, rowHeightPt: Float): Float {
        val avail = (cellWidthTw - 114).coerceAtLeast(100) / 20f
        var size = p.fontPt
        val em = p.text.length * 0.5f
        val usableH = rowHeightPt - 2f
        while (size > 7f) {
            // Hệ số 1,12 bù chữ đậm/hoa rộng hơn trung bình; mỗi dòng cao ~1,25 × cỡ chữ.
            val lines = kotlin.math.ceil(em * size * 1.12f / avail).coerceAtLeast(1f)
            val maxLines = kotlin.math.floor(usableH / (size * 1.25f)).coerceAtLeast(1f)
            if (lines <= maxLines) break
            size -= 0.5f
        }
        return size
    }

    private fun spacerXml(beforeTw: Int) =
        "<w:p><w:pPr><w:spacing w:before=\"$beforeTw\" w:after=\"0\" w:line=\"20\" w:lineRule=\"exact\"/></w:pPr></w:p>"

    private fun tableXml(t: TableBlock, twPerPx: Float, indentTw: Int, textWidth: Int): String {
        var colW = (0 until t.colCount).map { ((t.colEdges[it + 1] - t.colEdges[it]) * twPerPx).roundToInt().coerceAtLeast(120) }
        val total = colW.sum()
        val maxW = textWidth - indentTw
        if (total > maxW && maxW > 0) colW = colW.map { (it.toLong() * maxW / total).toInt().coerceAtLeast(100) }
        val tblW = colW.sum()

        val covering = Array(t.rowCount) { arrayOfNulls<TableCell>(t.colCount) }
        for (cell in t.cells) {
            for (r in cell.row until min(t.rowCount, cell.row + cell.rowSpan)) for (c in cell.col until min(t.colCount, cell.col + cell.colSpan)) covering[r][c] = cell
        }

        // Cỡ chữ đồng nhất cho cả bảng: cỡ vừa ô của từng ô → lấy trung vị làm cỡ chung, ô nào chật hơn
        // mới thu nhỏ riêng (bảng nhìn đều, giống bản in gốc).
        val fitted = HashMap<Paragraph, Float>()
        for (cell in t.cells) {
            if (cell.paragraphs.isEmpty()) continue
            val span = min(cell.colSpan, t.colCount - cell.col)
            val width = (cell.col until cell.col + span).sumOf { colW[it] }
            val rEnd = min(t.rowCount, cell.row + cell.rowSpan)
            val hPt = (t.rowEdges[rEnd] - t.rowEdges[cell.row]) * twPerPx / 20f / max(1, cell.paragraphs.size)
            for (p in cell.paragraphs) fitted[p] = fitFont(p, width, hPt)
        }
        val uniform = fitted.values.sorted().let { if (it.isEmpty()) 12f else it[it.size / 2] }

        val headerRows = TableStyle.headerRows(t)
        val border = if (t.bordered) "w:val=\"single\" w:sz=\"4\" w:space=\"0\" w:color=\"000000\"" else "w:val=\"nil\""
        val sb = StringBuilder("<w:tbl><w:tblPr>")
        sb.append("<w:tblW w:w=\"$tblW\" w:type=\"dxa\"/>")
        if (indentTw > 20) sb.append("<w:tblInd w:w=\"$indentTw\" w:type=\"dxa\"/>")
        sb.append("<w:tblBorders>")
        for (side in listOf("top", "left", "bottom", "right", "insideH", "insideV")) sb.append("<w:$side $border/>")
        sb.append("</w:tblBorders><w:tblLayout w:type=\"fixed\"/>")
        sb.append("<w:tblCellMar><w:left w:w=\"57\" w:type=\"dxa\"/><w:right w:w=\"57\" w:type=\"dxa\"/></w:tblCellMar>")
        sb.append("</w:tblPr><w:tblGrid>")
        colW.forEach { sb.append("<w:gridCol w:w=\"$it\"/>") }
        sb.append("</w:tblGrid>")

        for (r in 0 until t.rowCount) {
            // Bảng kẻ ô: chiều cao hàng CỐ ĐỊNH đúng như bản gốc (chữ đã được thu cho vừa ô) → bảng giữ
            // nguyên hình dạng, không giãn làm tràn sang trang sau. Bảng không viền: tối thiểu.
            val h = ((t.rowEdges[r + 1] - t.rowEdges[r]) * twPerPx * (if (t.bordered) 1f else 0.9f)).roundToInt().coerceAtLeast(200)
            val rule = if (t.bordered) "exact" else "atLeast"
            sb.append("<w:tr><w:trPr><w:cantSplit/>" + (if (r < headerRows) "<w:tblHeader/>" else "") + "<w:trHeight w:val=\"$h\" w:hRule=\"$rule\"/></w:trPr>")
            var c = 0
            while (c < t.colCount) {
                val cell = covering[r][c]
                if (cell == null) {
                    sb.append("<w:tc><w:tcPr><w:tcW w:w=\"${colW[c]}\" w:type=\"dxa\"/></w:tcPr><w:p/></w:tc>")
                    c++
                    continue
                }
                val span = min(cell.colSpan, t.colCount - c)
                val width = (c until c + span).sumOf { colW[it] }
                sb.append("<w:tc><w:tcPr><w:tcW w:w=\"$width\" w:type=\"dxa\"/>")
                if (span > 1) sb.append("<w:gridSpan w:val=\"$span\"/>")
                val isOrigin = cell.row == r
                if (cell.rowSpan > 1) sb.append(if (isOrigin) "<w:vMerge w:val=\"restart\"/>" else "<w:vMerge/>")
                val va = when (cell.vAlign) { VAlign.TOP -> "top"; VAlign.CENTER -> "center"; VAlign.BOTTOM -> "bottom" }
                if (cell.row < headerRows) sb.append("<w:shd w:val=\"clear\" w:color=\"auto\" w:fill=\"${TableStyle.HEADER_FILL}\"/>")
                sb.append("<w:vAlign w:val=\"$va\"/></w:tcPr>")
                if (isOrigin && cell.paragraphs.isNotEmpty()) {
                    val cellLeftPx = t.colEdges[cell.col]
                    val rEnd = min(t.rowCount, cell.row + cell.rowSpan)
                    val cellHeightPt = (t.rowEdges[rEnd] - t.rowEdges[cell.row]) * twPerPx / 20f / max(1, cell.paragraphs.size)
                    for (p in cell.paragraphs) {
                        val ind = if (p.align == Align.LEFT) ((p.box.left - cellLeftPx) * twPerPx - 57).roundToInt().coerceAtLeast(0) else 0
                        val own = min(fitFont(p, width - ind, cellHeightPt), fitted[p] ?: p.fontPt)
                        val size = if (t.bordered && !p.bold) min(own, uniform) else own
                        sb.append(paragraphXml(p.copy(indentPx = 0f, fontPt = size), 0, if (ind > 150) ind else 0, 0))
                    }
                } else {
                    sb.append("<w:p/>")
                }
                sb.append("</w:tc>")
                c += span
            }
            sb.append("</w:tr>")
        }
        sb.append("</w:tbl>")
        return sb.toString()
    }

    private fun imageParagraphXml(rid: String, id: Int, cx: Int, cy: Int, align: Align, beforeTw: Int): String =
        "<w:p><w:pPr><w:spacing w:before=\"$beforeTw\" w:after=\"0\"/><w:jc w:val=\"${jc(align)}\"/></w:pPr><w:r><w:drawing>" +
            "<wp:inline distT=\"0\" distB=\"0\" distL=\"0\" distR=\"0\"><wp:extent cx=\"$cx\" cy=\"$cy\"/>" +
            "<wp:docPr id=\"$id\" name=\"Picture $id\"/>" +
            "<a:graphic><a:graphicData uri=\"http://schemas.openxmlformats.org/drawingml/2006/picture\">" +
            "<pic:pic><pic:nvPicPr><pic:cNvPr id=\"$id\" name=\"image$id.jpeg\"/><pic:cNvPicPr/></pic:nvPicPr>" +
            "<pic:blipFill><a:blip r:embed=\"$rid\"/><a:stretch><a:fillRect/></a:stretch></pic:blipFill>" +
            "<pic:spPr><a:xfrm><a:off x=\"0\" y=\"0\"/><a:ext cx=\"$cx\" cy=\"$cy\"/></a:xfrm><a:prstGeom prst=\"rect\"><a:avLst/></a:prstGeom></pic:spPr>" +
            "</pic:pic></a:graphicData></a:graphic></wp:inline></w:drawing></w:r></w:p>"

    private fun stylesXml() = XML_HEADER +
        "<w:styles xmlns:w=\"http://schemas.openxmlformats.org/wordprocessingml/2006/main\">" +
        "<w:docDefaults><w:rPrDefault><w:rPr><w:rFonts w:ascii=\"$DEFAULT_FONT\" w:eastAsia=\"$DEFAULT_FONT\" w:hAnsi=\"$DEFAULT_FONT\" w:cs=\"$DEFAULT_FONT\"/>" +
        "<w:sz w:val=\"26\"/><w:szCs w:val=\"26\"/><w:lang w:val=\"vi-VN\" w:eastAsia=\"en-US\" w:bidi=\"ar-SA\"/></w:rPr></w:rPrDefault>" +
        "<w:pPrDefault><w:pPr><w:spacing w:after=\"0\" w:line=\"240\" w:lineRule=\"auto\"/></w:pPr></w:pPrDefault></w:docDefaults>" +
        "<w:style w:type=\"paragraph\" w:default=\"1\" w:styleId=\"Normal\"><w:name w:val=\"Normal\"/><w:qFormat/></w:style>" +
        "<w:style w:type=\"table\" w:default=\"1\" w:styleId=\"TableNormal\"><w:name w:val=\"Normal Table\"/><w:tblPr><w:tblInd w:w=\"0\" w:type=\"dxa\"/>" +
        "<w:tblCellMar><w:top w:w=\"0\" w:type=\"dxa\"/><w:left w:w=\"108\" w:type=\"dxa\"/><w:bottom w:w=\"0\" w:type=\"dxa\"/><w:right w:w=\"108\" w:type=\"dxa\"/></w:tblCellMar></w:tblPr></w:style>" +
        "</w:styles>"

    internal fun corePropsXml(title: String) = XML_HEADER +
        "<cp:coreProperties xmlns:cp=\"http://schemas.openxmlformats.org/package/2006/metadata/core-properties\" " +
        "xmlns:dc=\"http://purl.org/dc/elements/1.1/\" xmlns:dcterms=\"http://purl.org/dc/terms/\" " +
        "xmlns:xsi=\"http://www.w3.org/2001/XMLSchema-instance\">" +
        "<dc:title>${xmlEscape(title)}</dc:title><dc:creator>ScanX</dc:creator></cp:coreProperties>"
}
