package com.scanx.app.convert

import java.io.OutputStream
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Xuất Excel (.xlsx) giữ đúng form bản scan: mỗi trang 1 sheet; dựng "lưới cột chủ" từ toàn bộ biên
 * cột của các bảng + header nhiều cột trên trang, rồi đặt mọi khối (đoạn văn, bảng, header) lên
 * lưới đó bằng ô gộp (merge) → độ rộng cột, ô gộp, viền, căn lề, chữ đậm, cỡ chữ giống bản gốc.
 * Số nguyên / số có phân cách hàng nghìn kiểu Việt Nam được ghi dạng số để tính toán được;
 * chỉ mục kiểu "1.1", ngày tháng, mã có số 0 đầu giữ nguyên dạng chữ.
 */
object XlsxWriter {

    private class CellData(val text: String, val style: Int)
    private data class StyleKey(val fontId: Int, val border: Int, val h: String, val v: String, val indent: Int, val fill: Int = 0)
    private data class FontKey(val size: Float, val bold: Boolean, val color: Int = 0, val name: String = DEFAULT_FONT, val italic: Boolean = false)

    private val NUMBER_VN = Regex("^-?\\d{1,3}(\\.\\d{3})+(,\\d+)?$|^-?\\d+(,\\d+)?$")

    fun write(doc: DocModel, out: OutputStream) {
        val pkg = OoxmlPackage()
        val fonts = LinkedHashMap<FontKey, Int>()
        val styles = LinkedHashMap<StyleKey, Int>()
        fun fontId(size: Float, bold: Boolean, color: Int, name: String, italic: Boolean = false) = fonts.getOrPut(FontKey(size, bold, color, name, italic)) { fonts.size }
        // Font 0 = font mặc định của sổ → quyết định đơn vị độ rộng cột (Calibri 11: 7 px/ký tự) → quy đổi
        // độ rộng cột từ pt chính xác, bảng không tràn lề khi in.
        fontId(11f, false, 0, "Calibri")
        styles[StyleKey(0, 0, "general", "bottom", 0)] = 0
        fun styleId(size: Float, bold: Boolean, color: Int, lang: String, border: Boolean, align: Align, vAlign: VAlign, indent: Int, fill: Boolean = false, italic: Boolean = false): Int {
            val h = when (align) { Align.LEFT -> "left"; Align.CENTER -> "center"; Align.RIGHT -> "right"; Align.JUSTIFY -> "justify" }
            val v = when (vAlign) { VAlign.TOP -> "top"; VAlign.CENTER -> "center"; VAlign.BOTTOM -> "bottom" }
            return styles.getOrPut(StyleKey(fontId(size, bold, color, Lang.fontFor(lang), italic), if (border) 1 else 0, h, v, indent, if (fill) 2 else 0)) { styles.size }
        }

        val sheetNames = ArrayList<String>()
        doc.pages.forEachIndexed { pi, page ->
            sheetNames.add("Trang ${pi + 1}")
            pkg.put("xl/worksheets/sheet${pi + 1}.xml", sheetXml(page, ::styleId))
        }

        pkg.put("[Content_Types].xml", XML_HEADER +
            "<Types xmlns=\"http://schemas.openxmlformats.org/package/2006/content-types\">" +
            "<Default Extension=\"rels\" ContentType=\"application/vnd.openxmlformats-package.relationships+xml\"/>" +
            "<Default Extension=\"xml\" ContentType=\"application/xml\"/>" +
            "<Override PartName=\"/xl/workbook.xml\" ContentType=\"application/vnd.openxmlformats-officedocument.spreadsheetml.sheet.main+xml\"/>" +
            "<Override PartName=\"/xl/styles.xml\" ContentType=\"application/vnd.openxmlformats-officedocument.spreadsheetml.styles+xml\"/>" +
            sheetNames.indices.joinToString("") {
                "<Override PartName=\"/xl/worksheets/sheet${it + 1}.xml\" ContentType=\"application/vnd.openxmlformats-officedocument.spreadsheetml.worksheet+xml\"/>"
            } +
            "<Override PartName=\"/docProps/core.xml\" ContentType=\"application/vnd.openxmlformats-package.core-properties+xml\"/>" +
            "</Types>")
        pkg.put("_rels/.rels", XML_HEADER +
            "<Relationships xmlns=\"http://schemas.openxmlformats.org/package/2006/relationships\">" +
            "<Relationship Id=\"rId1\" Type=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships/officeDocument\" Target=\"xl/workbook.xml\"/>" +
            "<Relationship Id=\"rId2\" Type=\"http://schemas.openxmlformats.org/package/2006/relationships/metadata/core-properties\" Target=\"docProps/core.xml\"/>" +
            "</Relationships>")
        pkg.put("xl/workbook.xml", XML_HEADER +
            "<workbook xmlns=\"http://schemas.openxmlformats.org/spreadsheetml/2006/main\" xmlns:r=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships\">" +
            "<sheets>" + sheetNames.mapIndexed { i, n -> "<sheet name=\"${xmlEscape(n)}\" sheetId=\"${i + 1}\" r:id=\"rId${i + 1}\"/>" }.joinToString("") + "</sheets></workbook>")
        pkg.put("xl/_rels/workbook.xml.rels", XML_HEADER +
            "<Relationships xmlns=\"http://schemas.openxmlformats.org/package/2006/relationships\">" +
            sheetNames.indices.joinToString("") {
                "<Relationship Id=\"rId${it + 1}\" Type=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships/worksheet\" Target=\"worksheets/sheet${it + 1}.xml\"/>"
            } +
            "<Relationship Id=\"rIdStyles\" Type=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships/styles\" Target=\"styles.xml\"/>" +
            "</Relationships>")
        pkg.put("xl/styles.xml", stylesXml(fonts.keys.toList(), styles.keys.toList()))
        pkg.put("docProps/core.xml", DocxWriter.corePropsXml(doc.title))
        pkg.writeTo(out)
    }

    private fun sheetXml(page: DocPage, styleId: (Float, Boolean, Int, String, Boolean, Align, VAlign, Int, Boolean, Boolean) -> Int): String {
        val pt = page.ptPerPx
        // --- Lưới cột chủ -------------------------------------------------------------------
        val rawEdges = ArrayList<Float>()
        rawEdges.add(page.content.left)
        rawEdges.add(page.content.right)
        page.blocks.filterIsInstance<TableBlock>().forEach { rawEdges.addAll(it.colEdges) }
        val floats = page.blocks.filterIsInstance<ParagraphBlock>().map { it.paragraph }.filter { it.floating }
        floats.forEach { rawEdges.add(it.box.left); rawEdges.add(it.box.right) }
        val tol = page.width * 0.012f
        val edges = ArrayList<Float>()
        for (e in rawEdges.sorted()) if (edges.isEmpty() || e - edges.last() > tol) edges.add(e) else edges[edges.size - 1] = (edges.last() + e) / 2f
        if (edges.size < 2) edges.add(edges.first() + 100f)
        fun edgeIndex(x: Float): Int {
            var best = 0
            for (i in edges.indices) if (abs(edges[i] - x) < abs(edges[best] - x)) best = i
            return best
        }
        val lastCol = edges.size - 2
        // Tổng độ rộng cột không vượt vùng in của khổ A4 (trừ lề 0,5" + 0,4") → không tràn sang trang bên.
        val printableW = (if (page.width > page.height) 842f else 595f) - 65f
        val totalW = (edges.last() - edges.first()) * pt
        val fitScale = if (totalW > printableW) printableW / totalW else 1f

        val rows = java.util.TreeMap<Int, java.util.TreeMap<Int, CellData>>()
        val rowHeights = HashMap<Int, Float>()
        val merges = ArrayList<String>()
        var row = 1

        fun put(r: Int, c: Int, d: CellData) { rows.getOrPut(r) { java.util.TreeMap() }[c] = d }
        fun area(r0: Int, c0: Int, r1: Int, c1: Int, text: String, style: Int) {
            for (r in r0..r1) for (c in c0..c1) put(r, c, CellData(if (r == r0 && c == c0) text else "", style))
            if (r1 > r0 || c1 > c0) merges.add("${ref(r0, c0)}:${ref(r1, c1)}")
        }

        // Vị trí các bảng đã đặt (hàng bắt đầu) để gắn ghi chú cạnh bảng vào đúng hàng.
        val tablePlacements = ArrayList<Pair<TableBlock, Int>>()
        for (block in page.blocks) {
            if (block is ParagraphBlock && block.paragraph.floating) continue
            val gapPt = when (block) {
                is ParagraphBlock -> block.paragraph.spaceBeforePx
                is TableBlock -> block.spaceBeforePx
                is ImageBlock -> block.spaceBeforePx
            } * pt
            if (gapPt > 10f && row > 1) { rowHeights[row] = min(gapPt, 60f); row++ }
            when (block) {
                is ParagraphBlock -> {
                    val p = block.paragraph
                    val indent = (p.indentPx * pt / 9f).roundToInt().coerceIn(0, 15)
                    val align = if (p.align == Align.JUSTIFY) Align.LEFT else p.align
                    // Số dòng khi hiển thị trong vùng gộp (đã co theo khổ giấy): ước lượng theo bề rộng chữ.
                    val areaW = max(20f, (edges.last() - edges.first()) * pt * fitScale - 6f)
                    val est = kotlin.math.ceil(p.text.length * 0.5f * p.fontPt / areaW).toInt()
                    val lines = max(max(1, p.lineBoxes.size), est)
                    area(row, 0, row, lastCol, p.text, styleId(p.fontPt, p.bold, p.color, p.lang, false, align, VAlign.CENTER, if (align == Align.LEFT) indent else 0, false, p.italic))
                    rowHeights[row] = max(p.fontPt * 1.35f * lines, 15f)
                    row++
                }
                is TableBlock -> {
                    val base = row
                    tablePlacements.add(block to base)
                    // Cỡ chữ đồng nhất trong bảng kẻ ô (trung vị), không vượt ~60% chiều cao hàng thấp nhất.
                    val fonts = block.cells.flatMap { c -> c.paragraphs.filter { !it.bold }.map { it.fontPt } }.sorted()
                    val minRowPt = (0 until block.rowCount).minOfOrNull { (block.rowEdges[it + 1] - block.rowEdges[it]) * pt } ?: 20f
                    val uniform = if (fonts.isEmpty()) 12f else min(fonts[fonts.size / 2], max(8f, minRowPt * 0.45f))
                    for (r in 0 until block.rowCount) {
                        rowHeights[base + r] = max(15f, (block.rowEdges[r + 1] - block.rowEdges[r]) * pt)
                    }
                    for (cell in block.cells) {
                        val c0 = edgeIndex(block.colEdges[cell.col]).coerceAtMost(lastCol)
                        val c1 = (edgeIndex(block.colEdges[min(block.colCount, cell.col + cell.colSpan)]) - 1).coerceIn(c0, lastCol)
                        val first = cell.paragraphs.firstOrNull()
                        val text = cell.paragraphs.joinToString("\n") { it.text }
                        val align = when (first?.align) { null, Align.JUSTIFY -> if (cell.paragraphs.size > 0 && text.length > 30) Align.JUSTIFY else Align.LEFT; else -> first.align }
                        val size = when {
                            first == null -> uniform
                            block.bordered && !first.bold -> uniform
                            else -> first.fontPt
                        }
                        val header = block.bordered && isHeaderRow(block, cell)
                        val style = styleId(size, (first?.bold ?: false) || header, first?.color ?: 0, first?.lang ?: "", block.bordered, align, cell.vAlign, 0, header, first?.italic ?: false)
                        area(base + cell.row, c0, base + cell.row + cell.rowSpan - 1, c1, text, style)
                    }
                    row = base + block.rowCount
                }
                is ImageBlock -> Unit // Ảnh con dấu/chữ ký: Excel không cần, giữ bố cục chữ + bảng.
            }
        }

        // Ghi chú cạnh bảng → ô cùng hàng, đúng cột bên cạnh bảng (như vị trí trên giấy).
        for (p in floats) {
            val placed = tablePlacements.firstOrNull { (t, _) -> p.box.cy >= t.box.top && p.box.cy <= t.box.bottom } ?: continue
            val (t, base) = placed
            var ri = 0
            while (ri < t.rowCount - 1 && p.box.cy > t.rowEdges[ri + 1]) ri++
            val r = base + ri
            val c = edgeIndex(p.box.left).coerceAtMost(lastCol)
            val existing = rows[r]?.get(c)?.text.orEmpty()
            val text = if (existing.isBlank()) p.text else existing + "\n" + p.text
            put(r, c, CellData(text, styleId(p.fontPt, p.bold, p.color, p.lang, false, Align.LEFT, VAlign.CENTER, 0, false, p.italic)))
        }

        val sb = StringBuilder(XML_HEADER)
        sb.append("<worksheet xmlns=\"http://schemas.openxmlformats.org/spreadsheetml/2006/main\" xmlns:r=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships\">")
        sb.append("<sheetPr><pageSetUpPr fitToPage=\"1\"/></sheetPr>")
        sb.append("<dimension ref=\"A1:${ref(max(1, row - 1), lastCol)}\"/>")
        sb.append("<sheetViews><sheetView showGridLines=\"0\" workbookViewId=\"0\"/></sheetViews>")
        sb.append("<sheetFormatPr defaultRowHeight=\"16.5\"/>")
        sb.append("<cols>")
        for (c in 0..lastCol) {
            val widthPt = (edges[c + 1] - edges[c]) * pt * fitScale
            val chars = ((widthPt * 96f / 72f - 5f) / 7f).coerceIn(1f, 255f)
            sb.append("<col min=\"${c + 1}\" max=\"${c + 1}\" width=\"${"%.2f".format(java.util.Locale.US, chars)}\" customWidth=\"1\"/>")
        }
        sb.append("</cols><sheetData>")
        for (r in 1 until row) {
            val h = rowHeights[r] ?: 16.5f
            sb.append("<row r=\"$r\" ht=\"${"%.1f".format(java.util.Locale.US, min(h, 409f))}\" customHeight=\"1\">")
            rows[r]?.forEach { (c, d) ->
                val t = d.text.trim()
                if (t.isNotEmpty() && NUMBER_VN.matches(t) && !(t.length > 1 && t.startsWith("0") && !t.startsWith("0,"))) {
                    val num = t.replace(".", "").replace(",", ".")
                    sb.append("<c r=\"${ref(r, c)}\" s=\"${d.style}\"><v>$num</v></c>")
                } else if (t.isNotEmpty()) {
                    sb.append("<c r=\"${ref(r, c)}\" s=\"${d.style}\" t=\"inlineStr\"><is><t xml:space=\"preserve\">${xmlEscape(t)}</t></is></c>")
                } else {
                    sb.append("<c r=\"${ref(r, c)}\" s=\"${d.style}\"/>")
                }
            }
            sb.append("</row>")
        }
        sb.append("</sheetData>")
        if (merges.isNotEmpty()) {
            sb.append("<mergeCells count=\"${merges.size}\">")
            merges.forEach { sb.append("<mergeCell ref=\"$it\"/>") }
            sb.append("</mergeCells>")
        }
        sb.append("<pageMargins left=\"0.5\" right=\"0.4\" top=\"0.5\" bottom=\"0.5\" header=\"0.3\" footer=\"0.3\"/>")
        val orient = if (page.width > page.height) "landscape" else "portrait"
        sb.append("<pageSetup paperSize=\"9\" orientation=\"$orient\" fitToWidth=\"1\" fitToHeight=\"0\"/>")
        sb.append("</worksheet>")
        return sb.toString()
    }

    private fun ref(row: Int, col: Int): String {
        var c = col
        val sb = StringBuilder()
        do {
            sb.insert(0, ('A' + c % 26))
            c = c / 26 - 1
        } while (c >= 0)
        return "$sb$row"
    }

    private fun stylesXml(fonts: List<FontKey>, styles: List<StyleKey>): String {
        val sb = StringBuilder(XML_HEADER)
        sb.append("<styleSheet xmlns=\"http://schemas.openxmlformats.org/spreadsheetml/2006/main\">")
        sb.append("<fonts count=\"${fonts.size}\">")
        for (f in fonts) {
            sb.append("<font>")
            if (f.bold) sb.append("<b/>")
            if (f.italic) sb.append("<i/>")
            sb.append("<sz val=\"${f.size}\"/>")
            if (f.color != 0) sb.append("<color rgb=\"FF${String.format(java.util.Locale.US, "%06X", f.color and 0xFFFFFF)}\"/>")
            sb.append("<name val=\"${f.name}\"/><family val=\"${if (f.name == DEFAULT_FONT) 1 else 2}\"/></font>")
        }
        sb.append("</fonts>")
        sb.append("<fills count=\"3\"><fill><patternFill patternType=\"none\"/></fill><fill><patternFill patternType=\"gray125\"/></fill>")
        sb.append("<fill><patternFill patternType=\"solid\"><fgColor rgb=\"FFF2F2F2\"/><bgColor indexed=\"64\"/></patternFill></fill></fills>")
        sb.append("<borders count=\"2\"><border><left/><right/><top/><bottom/><diagonal/></border>")
        sb.append("<border><left style=\"thin\"><color auto=\"1\"/></left><right style=\"thin\"><color auto=\"1\"/></right>")
        sb.append("<top style=\"thin\"><color auto=\"1\"/></top><bottom style=\"thin\"><color auto=\"1\"/></bottom><diagonal/></border></borders>")
        sb.append("<cellStyleXfs count=\"1\"><xf numFmtId=\"0\" fontId=\"0\" fillId=\"0\" borderId=\"0\"/></cellStyleXfs>")
        sb.append("<cellXfs count=\"${styles.size}\">")
        for ((i, s) in styles.withIndex()) {
            if (i == 0) {
                sb.append("<xf numFmtId=\"0\" fontId=\"0\" fillId=\"0\" borderId=\"0\" xfId=\"0\"/>")
                continue
            }
            sb.append("<xf numFmtId=\"0\" fontId=\"${s.fontId}\" fillId=\"${s.fill}\" borderId=\"${s.border}\" xfId=\"0\" applyFont=\"1\" applyBorder=\"1\" applyAlignment=\"1\"" + (if (s.fill > 0) " applyFill=\"1\"" else "") + ">")
            sb.append("<alignment horizontal=\"${s.h}\" vertical=\"${s.v}\" wrapText=\"1\"" + (if (s.indent > 0) " indent=\"${s.indent}\"" else "") + "/></xf>")
        }
        sb.append("</cellXfs><cellStyles count=\"1\"><cellStyle name=\"Normal\" xfId=\"0\" builtinId=\"0\"/></cellStyles>")
        sb.append("</styleSheet>")
        return sb.toString()
    }
}
