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

        val first = doc.pages.firstOrNull()
        val twPerPx = (first?.ptPerPx ?: 0.5f) * 20f
        val landscape = first != null && first.width > first.height
        val pageW = if (landscape) A4_H else A4_W
        val pageH = first?.let {
            val h = (it.height * twPerPx).roundToInt()
            val a4 = if (landscape) A4_W else A4_H
            if (abs(h - a4) < a4 * 0.06f) a4 else h
        } ?: A4_H

        // Lề trang = khoảng trắng nhỏ nhất quanh vùng nội dung trên mọi trang.
        fun clampMargin(v: Float) = v.roundToInt().coerceIn(567, 2268)
        val mL = clampMargin(doc.pages.minOfOrNull { it.content.left * twPerPx } ?: 1134f)
        val mR = clampMargin(doc.pages.minOfOrNull { (it.width - it.content.right) * twPerPx } ?: 850f)
        val mT = clampMargin(doc.pages.minOfOrNull { it.content.top * twPerPx } ?: 1134f)
        val mB = clampMargin(doc.pages.minOfOrNull { (it.height - it.content.bottom) * twPerPx } ?: 1134f)
        val textWidth = pageW - mL - mR

        doc.pages.forEachIndexed { pi, page ->
            if (pi > 0) body.append("<w:p><w:r><w:br w:type=\"page\"/></w:r></w:p>")
            val pageLeftPx = page.content.left
            val leftOffsetTw = ((pageLeftPx * twPerPx) - mL).roundToInt().coerceAtLeast(0)
            var lastWasTable = false
            for (block in page.blocks) {
                when (block) {
                    is ParagraphBlock -> {
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

        val orient = if (landscape) " w:orient=\"landscape\"" else ""
        val document = XML_HEADER +
            "<w:document xmlns:w=\"http://schemas.openxmlformats.org/wordprocessingml/2006/main\" " +
            "xmlns:r=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships\" " +
            "xmlns:wp=\"http://schemas.openxmlformats.org/drawingml/2006/wordprocessingDrawing\" " +
            "xmlns:a=\"http://schemas.openxmlformats.org/drawingml/2006/main\" " +
            "xmlns:pic=\"http://schemas.openxmlformats.org/drawingml/2006/picture\">" +
            "<w:body>$body<w:sectPr><w:pgSz w:w=\"$pageW\" w:h=\"$pageH\"$orient/>" +
            "<w:pgMar w:top=\"$mT\" w:right=\"$mR\" w:bottom=\"$mB\" w:left=\"$mL\" w:header=\"0\" w:footer=\"0\" w:gutter=\"0\"/>" +
            "</w:sectPr></w:body></w:document>"

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

    private fun runXml(text: String, fontPt: Float, bold: Boolean): String {
        val sz = (fontPt * 2).roundToInt()
        return "<w:r><w:rPr><w:rFonts w:ascii=\"$DEFAULT_FONT\" w:hAnsi=\"$DEFAULT_FONT\" w:cs=\"$DEFAULT_FONT\"/>" +
            (if (bold) "<w:b/><w:bCs/>" else "") +
            "<w:sz w:val=\"$sz\"/><w:szCs w:val=\"$sz\"/></w:rPr><w:t xml:space=\"preserve\">${xmlEscape(text)}</w:t></w:r>"
    }

    private fun paragraphXml(p: Paragraph, beforeTw: Int, leftTw: Int, firstLineTw: Int): String {
        val sb = StringBuilder("<w:p><w:pPr>")
        sb.append("<w:spacing w:before=\"$beforeTw\" w:after=\"0\" w:line=\"240\" w:lineRule=\"auto\"/>")
        val left = if (p.align == Align.LEFT || p.align == Align.JUSTIFY) leftTw.coerceAtLeast(0) else 0
        if (left > 40 || firstLineTw > 40) {
            sb.append("<w:ind w:left=\"$left\"" + (if (firstLineTw > 40) " w:firstLine=\"$firstLineTw\"" else "") + "/>")
        }
        sb.append("<w:jc w:val=\"${jc(p.align)}\"/></w:pPr>")
        sb.append(runXml(p.text, p.fontPt, p.bold))
        sb.append("</w:p>")
        return sb.toString()
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
            val h = ((t.rowEdges[r + 1] - t.rowEdges[r]) * twPerPx * 0.9f).roundToInt().coerceAtLeast(200)
            sb.append("<w:tr><w:trPr><w:trHeight w:val=\"$h\" w:hRule=\"atLeast\"/></w:trPr>")
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
                sb.append("<w:vAlign w:val=\"$va\"/></w:tcPr>")
                if (isOrigin && cell.paragraphs.isNotEmpty()) {
                    val cellLeftPx = t.colEdges[cell.col]
                    for (p in cell.paragraphs) {
                        val ind = if (p.align == Align.LEFT) ((p.box.left - cellLeftPx) * twPerPx - 57).roundToInt().coerceAtLeast(0) else 0
                        sb.append(paragraphXml(p.copy(indentPx = 0f), 0, if (ind > 150) ind else 0, 0))
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
