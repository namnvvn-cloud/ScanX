package com.scanx.app.convert

import java.io.OutputStream
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Xuất PowerPoint (.pptx): mỗi trang scan = 1 slide cùng tỉ lệ khổ giấy; mọi đoạn chữ thành hộp chữ
 * sửa được đặt ĐÚNG VỊ TRÍ tuyệt đối như bản gốc, bảng thành bảng PowerPoint thật (ô gộp, viền),
 * ảnh con dấu/chữ ký/logo giữ nguyên chỗ → nhìn giống hệt bản scan nhưng chỉnh sửa được.
 */
object PptxWriter {

    private const val NS = "xmlns:a=\"http://schemas.openxmlformats.org/drawingml/2006/main\" " +
        "xmlns:r=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships\" " +
        "xmlns:p=\"http://schemas.openxmlformats.org/presentationml/2006/main\""

    private const val GROUP_HEADER = "<p:nvGrpSpPr><p:cNvPr id=\"1\" name=\"\"/><p:cNvGrpSpPr/><p:nvPr/></p:nvGrpSpPr>" +
        "<p:grpSpPr><a:xfrm><a:off x=\"0\" y=\"0\"/><a:ext cx=\"0\" cy=\"0\"/><a:chOff x=\"0\" y=\"0\"/><a:chExt cx=\"0\" cy=\"0\"/></a:xfrm></p:grpSpPr>"

    fun write(doc: DocModel, out: OutputStream) {
        val pkg = OoxmlPackage()
        val first = doc.pages.firstOrNull()
        val landscape = first != null && first.width > first.height
        val slideCx = if (landscape) 10692000 else 7560000 // A4: 297 × 210 mm
        val slideCy = first?.let { (it.height.toLong() * slideCx / it.width).toInt() }?.coerceIn(914400, 51206400)
            ?: if (landscape) 7560000 else 10692000
        var imageCounter = 0

        doc.pages.forEachIndexed { pi, page ->
            val emu = slideCx.toFloat() / page.width
            val shapes = StringBuilder()
            val rels = StringBuilder("<Relationship Id=\"rId1\" Type=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships/slideLayout\" Target=\"../slideLayouts/slideLayout1.xml\"/>")
            var id = 2
            fun e(v: Float) = (v * emu).roundToInt()

            for (block in page.blocks) {
                when (block) {
                    is ParagraphBlock -> shapes.append(textBox(id++, block.paragraph, e(block.box.left), e(block.box.top), e(block.box.width * 1.06f), e(block.box.height), slideCx))
                    is TableBlock -> if (block.bordered) {
                        shapes.append(tableFrame(id++, block, emu))
                    } else {
                        for (cell in block.cells) for (p in cell.paragraphs) {
                            shapes.append(textBox(id++, p, e(p.box.left), e(p.box.top), e(p.box.width * 1.06f), e(p.box.height), slideCx))
                        }
                    }
                    is ImageBlock -> {
                        imageCounter++
                        val rid = "rIdImg$imageCounter"
                        pkg.putBinary("ppt/media/image$imageCounter.jpeg", block.figure.jpeg)
                        rels.append("<Relationship Id=\"$rid\" Type=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships/image\" Target=\"../media/image$imageCounter.jpeg\"/>")
                        shapes.append(
                            "<p:pic><p:nvPicPr><p:cNvPr id=\"$id\" name=\"Picture $id\"/><p:cNvPicPr><a:picLocks noChangeAspect=\"1\"/></p:cNvPicPr><p:nvPr/></p:nvPicPr>" +
                                "<p:blipFill><a:blip r:embed=\"$rid\"/><a:stretch><a:fillRect/></a:stretch></p:blipFill>" +
                                "<p:spPr><a:xfrm><a:off x=\"${e(block.box.left)}\" y=\"${e(block.box.top)}\"/><a:ext cx=\"${e(block.box.width)}\" cy=\"${e(block.box.height)}\"/></a:xfrm>" +
                                "<a:prstGeom prst=\"rect\"><a:avLst/></a:prstGeom></p:spPr></p:pic>",
                        )
                        id++
                    }
                }
            }
            pkg.put("ppt/slides/slide${pi + 1}.xml", XML_HEADER +
                "<p:sld $NS><p:cSld><p:spTree>$GROUP_HEADER$shapes</p:spTree></p:cSld><p:clrMapOvr><a:masterClrMapping/></p:clrMapOvr></p:sld>")
            pkg.put("ppt/slides/_rels/slide${pi + 1}.xml.rels", XML_HEADER +
                "<Relationships xmlns=\"http://schemas.openxmlformats.org/package/2006/relationships\">$rels</Relationships>")
        }

        val n = doc.pages.size
        pkg.put("[Content_Types].xml", XML_HEADER +
            "<Types xmlns=\"http://schemas.openxmlformats.org/package/2006/content-types\">" +
            "<Default Extension=\"rels\" ContentType=\"application/vnd.openxmlformats-package.relationships+xml\"/>" +
            "<Default Extension=\"xml\" ContentType=\"application/xml\"/>" +
            "<Default Extension=\"jpeg\" ContentType=\"image/jpeg\"/>" +
            "<Override PartName=\"/ppt/presentation.xml\" ContentType=\"application/vnd.openxmlformats-officedocument.presentationml.presentation.main+xml\"/>" +
            "<Override PartName=\"/ppt/slideMasters/slideMaster1.xml\" ContentType=\"application/vnd.openxmlformats-officedocument.presentationml.slideMaster+xml\"/>" +
            "<Override PartName=\"/ppt/slideLayouts/slideLayout1.xml\" ContentType=\"application/vnd.openxmlformats-officedocument.presentationml.slideLayout+xml\"/>" +
            "<Override PartName=\"/ppt/theme/theme1.xml\" ContentType=\"application/vnd.openxmlformats-officedocument.theme+xml\"/>" +
            (1..n).joinToString("") { "<Override PartName=\"/ppt/slides/slide$it.xml\" ContentType=\"application/vnd.openxmlformats-officedocument.presentationml.slide+xml\"/>" } +
            "<Override PartName=\"/docProps/core.xml\" ContentType=\"application/vnd.openxmlformats-package.core-properties+xml\"/>" +
            "</Types>")
        pkg.put("_rels/.rels", XML_HEADER +
            "<Relationships xmlns=\"http://schemas.openxmlformats.org/package/2006/relationships\">" +
            "<Relationship Id=\"rId1\" Type=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships/officeDocument\" Target=\"ppt/presentation.xml\"/>" +
            "<Relationship Id=\"rId2\" Type=\"http://schemas.openxmlformats.org/package/2006/relationships/metadata/core-properties\" Target=\"docProps/core.xml\"/>" +
            "</Relationships>")
        pkg.put("ppt/presentation.xml", XML_HEADER +
            "<p:presentation $NS><p:sldMasterIdLst><p:sldMasterId id=\"2147483648\" r:id=\"rId1\"/></p:sldMasterIdLst>" +
            "<p:sldIdLst>" + (1..n).joinToString("") { "<p:sldId id=\"${255 + it}\" r:id=\"rId${it + 1}\"/>" } + "</p:sldIdLst>" +
            "<p:sldSz cx=\"$slideCx\" cy=\"$slideCy\" type=\"custom\"/><p:notesSz cx=\"6858000\" cy=\"9144000\"/></p:presentation>")
        pkg.put("ppt/_rels/presentation.xml.rels", XML_HEADER +
            "<Relationships xmlns=\"http://schemas.openxmlformats.org/package/2006/relationships\">" +
            "<Relationship Id=\"rId1\" Type=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships/slideMaster\" Target=\"slideMasters/slideMaster1.xml\"/>" +
            (1..n).joinToString("") { "<Relationship Id=\"rId${it + 1}\" Type=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships/slide\" Target=\"slides/slide$it.xml\"/>" } +
            "<Relationship Id=\"rIdTheme\" Type=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships/theme\" Target=\"theme/theme1.xml\"/>" +
            "</Relationships>")
        pkg.put("ppt/slideMasters/slideMaster1.xml", XML_HEADER +
            "<p:sldMaster $NS><p:cSld><p:spTree>$GROUP_HEADER</p:spTree></p:cSld>" +
            "<p:clrMap bg1=\"lt1\" tx1=\"dk1\" bg2=\"lt2\" tx2=\"dk2\" accent1=\"accent1\" accent2=\"accent2\" accent3=\"accent3\" " +
            "accent4=\"accent4\" accent5=\"accent5\" accent6=\"accent6\" hlink=\"hlink\" folHlink=\"folHlink\"/>" +
            "<p:sldLayoutIdLst><p:sldLayoutId id=\"2147483649\" r:id=\"rId1\"/></p:sldLayoutIdLst></p:sldMaster>")
        pkg.put("ppt/slideMasters/_rels/slideMaster1.xml.rels", XML_HEADER +
            "<Relationships xmlns=\"http://schemas.openxmlformats.org/package/2006/relationships\">" +
            "<Relationship Id=\"rId1\" Type=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships/slideLayout\" Target=\"../slideLayouts/slideLayout1.xml\"/>" +
            "<Relationship Id=\"rId2\" Type=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships/theme\" Target=\"../theme/theme1.xml\"/>" +
            "</Relationships>")
        pkg.put("ppt/slideLayouts/slideLayout1.xml", XML_HEADER +
            "<p:sldLayout $NS type=\"blank\" preserve=\"1\"><p:cSld name=\"Blank\"><p:spTree>$GROUP_HEADER</p:spTree></p:cSld>" +
            "<p:clrMapOvr><a:masterClrMapping/></p:clrMapOvr></p:sldLayout>")
        pkg.put("ppt/slideLayouts/_rels/slideLayout1.xml.rels", XML_HEADER +
            "<Relationships xmlns=\"http://schemas.openxmlformats.org/package/2006/relationships\">" +
            "<Relationship Id=\"rId1\" Type=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships/slideMaster\" Target=\"../slideMasters/slideMaster1.xml\"/>" +
            "</Relationships>")
        pkg.put("ppt/theme/theme1.xml", themeXml())
        pkg.put("docProps/core.xml", DocxWriter.corePropsXml(doc.title))
        pkg.writeTo(out)
    }

    private fun algn(a: Align) = when (a) { Align.LEFT -> "l"; Align.CENTER -> "ctr"; Align.RIGHT -> "r"; Align.JUSTIFY -> "just" }

    private fun runXml(p: Paragraph): String {
        val sz = (p.fontPt * 100).roundToInt().coerceIn(100, 400000)
        return "<a:p><a:pPr algn=\"${algn(p.align)}\"/><a:r><a:rPr lang=\"vi-VN\" sz=\"$sz\"" + (if (p.bold) " b=\"1\"" else "") + " dirty=\"0\">" +
            "<a:solidFill><a:srgbClr val=\"000000\"/></a:solidFill><a:latin typeface=\"$DEFAULT_FONT\"/><a:cs typeface=\"$DEFAULT_FONT\"/></a:rPr>" +
            "<a:t>${xmlEscape(p.text)}</a:t></a:r></a:p>"
    }

    private fun textBox(id: Int, p: Paragraph, x: Int, y: Int, cx: Int, cy: Int, slideCx: Int): String {
        val w = min(max(cx, 1), max(1, slideCx - x))
        return "<p:sp><p:nvSpPr><p:cNvPr id=\"$id\" name=\"Text $id\"/><p:cNvSpPr txBox=\"1\"/><p:nvPr/></p:nvSpPr>" +
            "<p:spPr><a:xfrm><a:off x=\"$x\" y=\"$y\"/><a:ext cx=\"$w\" cy=\"${max(cy, 1)}\"/></a:xfrm><a:prstGeom prst=\"rect\"><a:avLst/></a:prstGeom><a:noFill/></p:spPr>" +
            "<p:txBody><a:bodyPr wrap=\"square\" lIns=\"0\" tIns=\"0\" rIns=\"0\" bIns=\"0\" rtlCol=\"0\" anchor=\"t\"><a:noAutofit/></a:bodyPr><a:lstStyle/>" +
            runXml(p) + "</p:txBody></p:sp>"
    }

    private fun tableFrame(id: Int, t: TableBlock, emu: Float): String {
        val colW = (0 until t.colCount).map { ((t.colEdges[it + 1] - t.colEdges[it]) * emu).roundToInt().coerceAtLeast(12700) }
        val rowH = (0 until t.rowCount).map { ((t.rowEdges[it + 1] - t.rowEdges[it]) * emu).roundToInt().coerceAtLeast(12700) }
        val covering = Array(t.rowCount) { arrayOfNulls<TableCell>(t.colCount) }
        for (cell in t.cells) for (r in cell.row until min(t.rowCount, cell.row + cell.rowSpan)) for (c in cell.col until min(t.colCount, cell.col + cell.colSpan)) covering[r][c] = cell
        val line = "w=\"12700\"><a:solidFill><a:srgbClr val=\"000000\"/></a:solidFill>"
        fun tcPr(anchor: String) = "<a:tcPr marL=\"45720\" marR=\"45720\" marT=\"0\" marB=\"0\" anchor=\"$anchor\">" +
            "<a:lnL $line</a:lnL><a:lnR $line</a:lnR><a:lnT $line</a:lnT><a:lnB $line</a:lnB><a:noFill/></a:tcPr>"
        val emptyBody = "<a:txBody><a:bodyPr/><a:lstStyle/><a:p><a:endParaRPr lang=\"vi-VN\" sz=\"1000\" dirty=\"0\"/></a:p></a:txBody>"

        val sb = StringBuilder()
        sb.append("<p:graphicFrame><p:nvGraphicFramePr><p:cNvPr id=\"$id\" name=\"Table $id\"/><p:cNvGraphicFramePr><a:graphicFrameLocks noGrp=\"1\"/></p:cNvGraphicFramePr><p:nvPr/></p:nvGraphicFramePr>")
        sb.append("<p:xfrm><a:off x=\"${(t.box.left * emu).roundToInt()}\" y=\"${(t.box.top * emu).roundToInt()}\"/><a:ext cx=\"${colW.sum()}\" cy=\"${rowH.sum()}\"/></p:xfrm>")
        sb.append("<a:graphic><a:graphicData uri=\"http://schemas.openxmlformats.org/drawingml/2006/table\"><a:tbl><a:tblPr firstRow=\"0\" bandRow=\"0\"/><a:tblGrid>")
        colW.forEach { sb.append("<a:gridCol w=\"$it\"/>") }
        sb.append("</a:tblGrid>")
        for (r in 0 until t.rowCount) {
            sb.append("<a:tr h=\"${rowH[r]}\">")
            for (c in 0 until t.colCount) {
                val cell = covering[r][c]
                if (cell == null) { sb.append("<a:tc>$emptyBody${tcPr("t")}</a:tc>"); continue }
                val anchor = when (cell.vAlign) { VAlign.TOP -> "t"; VAlign.CENTER -> "ctr"; VAlign.BOTTOM -> "b" }
                if (cell.row == r && cell.col == c) {
                    val attrs = (if (cell.colSpan > 1) " gridSpan=\"${cell.colSpan}\"" else "") + (if (cell.rowSpan > 1) " rowSpan=\"${cell.rowSpan}\"" else "")
                    val body = if (cell.paragraphs.isEmpty()) emptyBody
                    else "<a:txBody><a:bodyPr/><a:lstStyle/>" + cell.paragraphs.joinToString("") { runXml(it) } + "</a:txBody>"
                    sb.append("<a:tc$attrs>$body${tcPr(anchor)}</a:tc>")
                } else {
                    val attrs = (if (c > cell.col) " hMerge=\"1\"" else "") + (if (r > cell.row) " vMerge=\"1\"" else "")
                    sb.append("<a:tc$attrs>$emptyBody${tcPr(anchor)}</a:tc>")
                }
            }
            sb.append("</a:tr>")
        }
        sb.append("</a:tbl></a:graphicData></a:graphic></p:graphicFrame>")
        return sb.toString()
    }

    private fun themeXml(): String {
        fun c(tag: String, hex: String) = "<a:$tag><a:srgbClr val=\"$hex\"/></a:$tag>"
        val solid = "<a:solidFill><a:schemeClr val=\"phClr\"/></a:solidFill>"
        return XML_HEADER +
            "<a:theme xmlns:a=\"http://schemas.openxmlformats.org/drawingml/2006/main\" name=\"ScanX\"><a:themeElements>" +
            "<a:clrScheme name=\"ScanX\"><a:dk1><a:sysClr val=\"windowText\" lastClr=\"000000\"/></a:dk1><a:lt1><a:sysClr val=\"window\" lastClr=\"FFFFFF\"/></a:lt1>" +
            c("dk2", "1F497D") + c("lt2", "EEECE1") + c("accent1", "4F81BD") + c("accent2", "C0504D") + c("accent3", "9BBB59") +
            c("accent4", "8064A2") + c("accent5", "4BACC6") + c("accent6", "F79646") + c("hlink", "0000FF") + c("folHlink", "800080") +
            "</a:clrScheme>" +
            "<a:fontScheme name=\"ScanX\"><a:majorFont><a:latin typeface=\"$DEFAULT_FONT\"/><a:ea typeface=\"\"/><a:cs typeface=\"\"/></a:majorFont>" +
            "<a:minorFont><a:latin typeface=\"$DEFAULT_FONT\"/><a:ea typeface=\"\"/><a:cs typeface=\"\"/></a:minorFont></a:fontScheme>" +
            "<a:fmtScheme name=\"ScanX\"><a:fillStyleLst>$solid$solid$solid</a:fillStyleLst>" +
            "<a:lnStyleLst>" + "<a:ln w=\"9525\">$solid</a:ln>".repeat(3) + "</a:lnStyleLst>" +
            "<a:effectStyleLst>" + "<a:effectStyle><a:effectLst/></a:effectStyle>".repeat(3) + "</a:effectStyleLst>" +
            "<a:bgFillStyleLst>$solid$solid$solid</a:bgFillStyleLst></a:fmtScheme>" +
            "</a:themeElements><a:objectDefaults/><a:extraClrSchemeLst/></a:theme>"
    }
}
