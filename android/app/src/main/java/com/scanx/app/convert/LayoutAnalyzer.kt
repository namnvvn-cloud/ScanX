package com.scanx.app.convert

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Tái dựng bố cục trang từ kết quả OCR + đường kẻ, theo hướng "layout recovery" của các công cụ
 * chuyển đổi hiện đại (PaddleOCR PP-Structure, Docling, pdf2docx):
 *
 *  1. Bảng kẻ ô → [TableDetector]; từng TỪ OCR được gán vào đúng ô theo toạ độ tâm (không gán cả
 *     dòng, vì OCR hay nối chữ của 2 ô sát nhau thành 1 dòng).
 *  2. Phần chữ còn lại gom theo "dải ngang" (các dòng cùng độ cao). Dải có khoảng trống lớn ở giữa
 *     → bố cục nhiều cột (điển hình: header văn bản hành chính "BỘ CÔNG AN… | CỘNG HÒA XÃ HỘI…")
 *     → dựng thành bảng không viền, đúng cách người soạn văn bản Word vẫn làm.
 *  3. Dòng thường → đoạn văn: suy căn lề (trái/giữa/phải/đều 2 bên) từ vị trí so với vùng nội dung,
 *     cỡ chữ từ chiều cao dòng, chữ đậm từ độ dày nét (mật độ mực), thụt lề, khoảng cách đoạn.
 *  4. Vùng ảnh (con dấu, chữ ký, logo) giữ nguyên dạng ảnh, đặt đúng vị trí.
 */
object LayoutAnalyzer {

    private val COMMON_SIZES = floatArrayOf(8f, 9f, 10f, 10.5f, 11f, 12f, 13f, 14f, 15f, 16f, 18f, 20f, 22f, 24f, 28f)
    private val LIST_MARKER = Regex("^([-•+*–]|\\d{1,2}[.)]|[a-zđ][.)]|[IVX]{1,4}\\.)\\s")

    fun analyze(input: PageInput): DocPage {
        val w = input.width.toFloat()
        val ptPerPx = (if (input.width <= input.height) 595f else 842f) / w

        // Lọc nét chữ (nhất là chữ viết tay: nét sổ "1", "k", ngoặc…) bị morphology nhận nhầm là đường
        // kẻ: đường kẻ dọc thật dài ≥ 1,5 lần chiều cao dòng chữ, đường ngang thật ≥ 3 lần.
        val medLineH = median(input.lines.map { it.box.height }) ?: 0f
        val rules = if (medLineH <= 0f) input.rules else input.rules.filter { r ->
            val len = if (r.isHorizontal) abs(r.x2 - r.x1) else abs(r.y2 - r.y1)
            len >= medLineH * (if (r.isHorizontal) 3f else 1.5f)
        }
        val grids = TableDetector.detect(rules, input.width, input.height)

        // --- 1. Tách từ thuộc bảng / ngoài bảng ---------------------------------------------
        // Mỗi từ mang theo (độ dày nét, chữ nghiêng) của DÒNG OCR gốc chứa nó — dùng suy đậm/nghiêng khi
        // gộp từ thành dòng/đoạn trong ô bảng ([cellParagraphs]).
        val cellWords = grids.map { HashMap<Int, MutableList<Triple<OcrWord, Float, Boolean>>>() }
        val freeLines = ArrayList<OcrLine>()
        for (line in input.lines) {
            val words = line.words.ifEmpty { listOf(OcrWord(line.text, line.box)) }
            val rest = ArrayList<OcrWord>()
            for (word in words) {
                val gi = grids.indexOfFirst { it.box.containsPoint(word.box.cx, word.box.cy) }
                if (gi >= 0) {
                    val g = grids[gi]
                    val id = g.anchorOf[g.rowIndexOf(word.box.cy)][g.colIndexOf(word.box.cx)]
                    cellWords[gi].getOrPut(id) { mutableListOf() }.add(Triple(word, line.strokeWidth, line.italic))
                } else {
                    rest.add(word)
                }
            }
            if (rest.isNotEmpty()) {
                val box = Box.unionOf(rest.map { it.box })!!
                freeLines.add(OcrLine(rest.joinToString(" ") { it.text }, box, rest, line.strokeWidth, line.color, line.confidence, line.lang, line.italic))
            }
        }

        // Ghi chú cạnh bảng: dòng nằm ngoài bảng theo chiều ngang nhưng trong khoảng chiều cao của bảng
        // (ghi chú lề, tổng cộng viết tay bên phải…) → đoạn định vị tuyệt đối, không chen vào dòng chảy.
        val sideTol = w * 0.005f
        val sideLines = freeLines.filter { l ->
            grids.any { g ->
                val b = g.box
                l.box.cy > b.top && l.box.cy < b.bottom && (l.box.left >= b.right - sideTol || l.box.right <= b.left + sideTol)
            }
        }.toSet()
        freeLines.removeAll(sideLines)

        val allLines = input.lines
        val totalChars = allLines.sumOf { it.text.length }.coerceAtLeast(1)
        val ocrConfidence = allLines.sumOf { (it.confidence * it.text.length).toDouble() }.toFloat() / totalChars
        val medianStroke = median(allLines.map { it.strokeWidth }.filter { it > 0f }) ?: 0f
        fun isBold(stroke: Float) = medianStroke > 0f && stroke > medianStroke * 1.15f

        val contentBox = Box.unionOf(
            allLines.map { it.box } + grids.map { it.box },
        ) ?: Box(0f, 0f, w, input.height.toFloat())

        // Lề phải "thực" của thân văn bản (bỏ qua header/ghi chú lấn lề): phân vị 90% mép phải các dòng dài.
        val longRights = freeLines.filter { it.box.width > contentBox.width * 0.45f }.map { it.box.right }.sorted()
        val bodyRight = if (longRights.size >= 3) longRights[(longRights.size * 0.9f).toInt().coerceAtMost(longRights.size - 1)] else contentBox.right

        val blocks = ArrayList<Block>()

        // --- 2. Bảng kẻ ô --------------------------------------------------------------------
        grids.forEachIndexed { gi, g ->
            val cells = g.spans.mapIndexed { id, s ->
                val cellBox = Box(g.colEdges[s[1]], g.rowEdges[s[0]], g.colEdges[s[3] + 1], g.rowEdges[s[2] + 1])
                val words = cellWords[gi][id].orEmpty()
                val (paras, vAlign) = cellParagraphs(words, cellBox, ptPerPx, ::isBold)
                TableCell(s[0], s[1], s[2] - s[0] + 1, s[3] - s[1] + 1, paras, vAlign)
            }
            blocks.add(TableBlock(g.box, g.colEdges, g.rowEdges, cells, bordered = true, spaceBeforePx = 0f))
        }

        // --- 3. Dải ngang → vùng nhiều cột / đoạn văn ----------------------------------------
        val bands = buildBands(freeLines, w)
        var i = 0
        val pending = ArrayList<List<OcrLine>>() // các dải 1 cột chờ gom thành đoạn văn
        fun flushParagraphs() {
            if (pending.isEmpty()) return
            val lines = pending.map { it.first() }
            buildParagraphs(lines, contentBox.left, contentBox.right, bodyRight, ptPerPx, ::isBold).forEach { blocks.add(ParagraphBlock(it)) }
            pending.clear()
        }
        while (i < bands.size) {
            val band = bands[i]
            if (band.size >= 2) {
                // Bắt đầu vùng nhiều cột: rãnh phân cột = tâm các khoảng trống giữa các cụm.
                val gutters = (0 until band.size - 1).map { (band[it].box.right + band[it + 1].box.left) / 2f }
                fun crossesGutter(l: OcrLine) = gutters.any { gx -> l.box.left < gx - w * 0.01f && l.box.right > gx + w * 0.01f }
                // Nhìn ngược: các dòng 1 cột ngay phía trên (vd "BỘ CÔNG AN" khi OCR sót dòng bên phải).
                val regionBands = mutableListOf(band)
                while (pending.isNotEmpty()) {
                    val prevLine = pending.last().first()
                    val topNow = regionBands.first().minOf { it.box.top }
                    if (crossesGutter(prevLine) || topNow - prevLine.box.bottom > prevLine.box.height * 1.8f) break
                    regionBands.add(0, pending.removeAt(pending.size - 1))
                }
                var j = i + 1
                while (j < bands.size) {
                    val next = bands[j]
                    val prevBottom = regionBands.last().maxOf { it.box.bottom }
                    val lineH = next.maxOf { it.box.height }
                    val gapOk = next.minOf { it.box.top } - prevBottom < lineH * 1.8f
                    val crosses = next.any { crossesGutter(it) }
                    if (!gapOk || crosses) break
                    regionBands.add(next)
                    j++
                }
                flushParagraphs()
                blocks.add(buildColumnRegion(regionBands, gutters, contentBox, ptPerPx, ::isBold))
                i = j
            } else {
                pending.add(band)
                i++
            }
        }
        flushParagraphs()

        for (l in sideLines) {
            blocks.add(ParagraphBlock(lineParagraph(l, l.box.left, l.box.right, ptPerPx, ::isBold, 0f).copy(align = Align.LEFT, indentPx = 0f, floating = true)))
        }

        // --- 4. Ảnh con dấu / logo / hình -------------------------------------------------------
        // Bản Word/Excel phải là CHỮ thật: vùng màu chứa chữ (mực xanh/tím viết tay, bút dạ quang tô,
        // khoanh tròn bằng bút màu) KHÔNG được cắt thành ảnh — chữ trong đó đã nằm trong đoạn văn/ô bảng.
        // Chỉ giữ ảnh cho con dấu và vùng hình gần như không có chữ (logo, hình minh hoạ).
        val keptFigures = input.figures.filter { f ->
            if (f.isStamp) return@filter true
            // Nét bút dạ/gạch chân màu dài mảnh: không phải hình.
            val aspect = max(f.box.width, f.box.height) / max(1f, min(f.box.width, f.box.height))
            if (aspect > 5f || min(f.box.width, f.box.height) < min(w, input.height.toFloat()) * 0.03f) return@filter false
            val area = max(1f, f.box.width * f.box.height)
            val textArea = allLines.sumOf { l ->
                val ow = min(f.box.right, l.box.right) - max(f.box.left, l.box.left)
                val oh = min(f.box.bottom, l.box.bottom) - max(f.box.top, l.box.top)
                if (ow > 0 && oh > 0) (ow * oh).toDouble() else 0.0
            }.toFloat()
            textArea / area < 0.15f
        }
        for (f in keptFigures) {
            val align = when {
                abs(f.box.cx - contentBox.cx) < w * 0.1f -> Align.CENTER
                f.box.cx > contentBox.cx -> Align.RIGHT
                else -> Align.LEFT
            }
            blocks.add(ImageBlock(f.box, f, align, 0f))
        }

        // --- 5. Thứ tự đọc + khoảng cách giữa các khối ---------------------------------------
        val ordered = normalizeFonts(blocks).sortedBy { it.box.top }
        val withSpacing = ArrayList<Block>(ordered.size)
        var prevBottom = contentBox.top
        for (b in ordered) {
            if (b is ParagraphBlock && b.paragraph.floating) {
                withSpacing.add(b)
                continue
            }
            val gap = max(0f, b.box.top - prevBottom)
            withSpacing.add(
                when (b) {
                    is ParagraphBlock -> ParagraphBlock(b.paragraph.copy(spaceBeforePx = gap))
                    is TableBlock -> TableBlock(b.box, b.colEdges, b.rowEdges, b.cells, b.bordered, gap)
                    is ImageBlock -> ImageBlock(b.box, b.figure, b.align, gap)
                },
            )
            prevBottom = max(prevBottom, b.box.bottom)
        }
        return DocPage(input.width, input.height, withSpacing, contentBox, input.pageJpeg, ptPerPx, ocrConfidence)
    }

    /** Gom dòng thành dải ngang; trong mỗi dải nối các mẩu sát nhau, tách các cụm cách xa (cột). */
    private fun buildBands(lines: List<OcrLine>, pageW: Float): List<List<OcrLine>> {
        val sorted = lines.sortedBy { it.box.top }
        val bands = ArrayList<MutableList<OcrLine>>()
        for (l in sorted) {
            val band = bands.lastOrNull()
            val overlaps = band != null && band.any { o ->
                o.box.verticalOverlap(l.box) >= 0.5f * min(o.box.height, l.box.height)
            }
            if (overlaps) band.add(l) else bands.add(mutableListOf(l))
        }
        return bands.map { band ->
            val byX = band.sortedBy { it.box.left }
            val merged = ArrayList<OcrLine>()
            for (l in byX) {
                val last = merged.lastOrNull()
                if (last != null && l.box.left - last.box.right < pageW * 0.06f) {
                    merged[merged.size - 1] = OcrLine(
                        last.text + " " + l.text, last.box.union(l.box), last.words + l.words,
                        (last.strokeWidth * last.box.width + l.strokeWidth * l.box.width) / max(1f, last.box.width + l.box.width),
                        if (last.box.width >= l.box.width) last.color else l.color,
                        min(last.confidence, l.confidence),
                        if (last.lang == l.lang || l.lang.isEmpty()) last.lang else if (last.lang.isEmpty()) l.lang else if (last.text.length >= l.text.length) last.lang else l.lang,
                        italic = if (last.box.width >= l.box.width) last.italic else l.italic,
                    )
                } else {
                    merged.add(l)
                }
            }
            merged
        }
    }

    /**
     * Bảng không viền nhiều cột (header hành chính 2 cột, hoặc bản 0.7: danh sách/sổ chi tiêu không kẻ
     * ô — mỗi dải ngang [regionBands] khớp gutters cột trở thành 1 HÀNG thật của bảng, không gộp hết
     * vào 1 hàng như bản cũ). Dải chỉ có 1 mẩu (dòng nhìn ngược từ [pending]) vẫn thành 1 hàng, chỉ có
     * 1 ô có chữ — đúng cách 1 dòng tiêu đề nằm trên phần chia cột.
     */
    private fun buildColumnRegion(
        regionBands: List<List<OcrLine>>,
        gutters: List<Float>,
        content: Box,
        ptPerPx: Float,
        isBold: (Float) -> Boolean,
    ): TableBlock {
        val edges = listOf(content.left) + gutters + listOf(content.right)
        val rowEdges = ArrayList<Float>()
        rowEdges.add(regionBands.first().minOf { it.box.top })
        for (k in 0 until regionBands.size - 1) {
            rowEdges.add((regionBands[k].maxOf { it.box.bottom } + regionBands[k + 1].minOf { it.box.top }) / 2f)
        }
        rowEdges.add(regionBands.last().maxOf { it.box.bottom })
        val cells = ArrayList<TableCell>()
        for ((r, band) in regionBands.withIndex()) {
            for (c in 0 until edges.size - 1) {
                val colLines = band.filter { it.box.cx >= edges[c] && it.box.cx < edges[c + 1] }.sortedBy { it.box.top }
                val paras = colLines.map { l -> lineParagraph(l, edges[c], edges[c + 1], ptPerPx, isBold, centeredTolerance = 0.12f) }
                cells.add(TableCell(r, c, 1, 1, paras, VAlign.TOP))
            }
        }
        return TableBlock(Box(content.left, rowEdges.first(), content.right, rowEdges.last()), edges, rowEdges, cells, bordered = false, spaceBeforePx = 0f)
    }

    private fun lineParagraph(l: OcrLine, cl: Float, cr: Float, ptPerPx: Float, isBold: (Float) -> Boolean, centeredTolerance: Float): Paragraph {
        val cw = max(1f, cr - cl)
        val lg = l.box.left - cl
        val rg = cr - l.box.right
        val align = when {
            abs(lg - rg) < centeredTolerance * cw && lg > 0.04f * cw && l.box.width < 0.82f * cw -> Align.CENTER
            rg < 0.05f * cw && lg > 0.3f * cw -> Align.RIGHT
            else -> Align.LEFT
        }
        return Paragraph(
            text = l.text, align = align, fontPt = lineFont(l, ptPerPx), bold = isBold(l.strokeWidth), italic = l.italic,
            indentPx = if (align == Align.LEFT) max(0f, lg) else 0f, spaceBeforePx = 0f, box = l.box,
            lineBoxes = listOf(l.box), color = l.color, lang = l.lang,
        )
    }

    /** Dòng ngoài bảng → đoạn văn (nối các dòng bị ngắt do xuống dòng tự nhiên). */
    private fun buildParagraphs(lines: List<OcrLine>, cl: Float, cr: Float, bodyRight: Float, ptPerPx: Float, isBold: (Float) -> Boolean): List<Paragraph> {
        val cw = max(1f, cr - cl)
        val fullRight = bodyRight - 0.06f * max(1f, bodyRight - cl)
        val out = ArrayList<Paragraph>()
        var cur: MutableList<OcrLine>? = null

        fun finish() {
            val group = cur ?: return
            if (group.size == 1) {
                out.add(lineParagraph(group[0], cl, cr, ptPerPx, isBold, centeredTolerance = 0.09f))
            } else {
                val first = group.first()
                val restLeft = group.drop(1).minOf { it.box.left }
                val box = Box.unionOf(group.map { it.box })!!
                val heights = group.map { it.box.height }
                out.add(
                    Paragraph(
                        text = group.joinToString(" ") { it.text.trim() },
                        align = Align.JUSTIFY,
                        fontPt = snapSize(median(group.map { lineFontRaw(it, ptPerPx) }) ?: fontRaw(first.box.height, ptPerPx)),
                        bold = group.count { isBold(it.strokeWidth) } * 2 > group.size,
                        italic = group.count { it.italic } * 2 > group.size,
                        indentPx = max(0f, restLeft - cl),
                        spaceBeforePx = 0f,
                        box = box,
                        firstLineIndentPx = max(0f, first.box.left - restLeft).let { if (it > cw * 0.02f) it else 0f },
                        lineBoxes = group.map { it.box },
                        color = majorityColor(group.map { it.color to it.text.length }),
                        lang = majorityLang(group.map { it.lang to it.text.length }),
                    ),
                )
            }
            cur = null
        }

        for (l in lines) {
            val group = cur
            if (group == null) { cur = mutableListOf(l); continue }
            val prev = group.last()
            val prevFull = prev.box.right >= fullRight
            val lineH = max(prev.box.height, l.box.height)
            val gapOk = l.box.top - prev.box.bottom < lineH * 0.7f
            val sizeOk = l.box.height / max(1f, prev.box.height) in 0.75f..1.33f
            val prevLeft = if (group.size == 1) prev.box.left else group.drop(1).minOf { it.box.left }
            val leftOk = abs(l.box.left - prevLeft) < 0.03f * cw || (group.size == 1 && l.box.left < prev.box.left)
            val startsList = LIST_MARKER.containsMatchIn(l.text.trim())
            val centered = abs((l.box.left - cl) - (cr - l.box.right)) < 0.09f * cw && l.box.left - cl > 0.06f * cw
            val sameLang = prev.lang.isEmpty() || l.lang.isEmpty() || prev.lang == l.lang
            if (prevFull && gapOk && sizeOk && leftOk && !startsList && !centered && sameLang && isBold(prev.strokeWidth) == isBold(l.strokeWidth)) {
                group.add(l)
            } else {
                finish()
                cur = mutableListOf(l)
            }
        }
        finish()
        return out
    }

    /** Chữ trong 1 ô bảng: gom từ → dòng → đoạn; suy căn lề/căn dọc theo vị trí trong ô. */
    private fun cellParagraphs(
        words: List<Triple<OcrWord, Float, Boolean>>,
        cell: Box,
        ptPerPx: Float,
        isBold: (Float) -> Boolean,
    ): Pair<List<Paragraph>, VAlign> {
        if (words.isEmpty()) return emptyList<Paragraph>() to VAlign.CENTER
        val medH = median(words.map { it.first.box.height }) ?: 10f
        val sorted = words.sortedBy { it.first.box.cy }
        val rows = ArrayList<MutableList<Triple<OcrWord, Float, Boolean>>>()
        for (wd in sorted) {
            val row = rows.lastOrNull()
            if (row != null && abs(wd.first.box.cy - row.map { it.first.box.cy }.average().toFloat()) < medH * 0.5f) row.add(wd) else rows.add(mutableListOf(wd))
        }
        val lines = rows.map { r ->
            val byX = r.sortedBy { it.first.box.left }
            val box = Box.unionOf(byX.map { it.first.box })!!
            val ink = byX.map { it.second }.average().toFloat()
            OcrLine(
                byX.joinToString(" ") { it.first.text }, box, byX.map { it.first }, ink,
                majorityColor(byX.map { it.first.color to it.first.text.length }),
                byX.minOf { it.first.confidence },
                majorityLang(byX.map { it.first.lang to it.first.text.length }),
                byX.count { it.third } * 2 > byX.size,
            )
        }
        val cw = max(1f, cell.width)
        val paras = ArrayList<Paragraph>()
        var group = ArrayList<OcrLine>()
        fun finish() {
            if (group.isEmpty()) return
            val box = Box.unionOf(group.map { it.box })!!
            val first = group.first()
            val lg = first.box.left - cell.left
            val rg = cell.right - first.box.right
            val align = when {
                group.size > 1 -> Align.JUSTIFY
                abs(lg - rg) < 0.14f * cw && box.width < 0.9f * cw -> Align.CENTER
                rg < 0.12f * cw && lg > 0.35f * cw -> Align.RIGHT
                else -> Align.LEFT
            }
            // Cỡ chữ trong ô không vượt quá ~62% chiều cao hàng (chữ viết tay to/nghiêng làm hộp OCR phình).
            val cap = cell.height * ptPerPx * 0.62f / max(1, group.size)
            val raw = median(group.map { lineFontRaw(it, ptPerPx) }) ?: fontRaw(medH, ptPerPx)
            paras.add(
                Paragraph(
                    text = group.joinToString(" ") { it.text.trim() },
                    align = align,
                    fontPt = snapSize(min(raw, max(8f, cap))),
                    bold = group.count { isBold(it.strokeWidth) } * 2 > group.size,
                    italic = group.count { it.italic } * 2 > group.size,
                    indentPx = 0f, spaceBeforePx = 0f, box = box,
                    lineBoxes = group.map { it.box },
                    color = majorityColor(group.map { it.color to it.text.length }),
                    lang = majorityLang(group.map { it.lang to it.text.length }),
                ),
            )
            group = ArrayList()
        }
        for (l in lines) {
            val prev = group.lastOrNull()
            val prevShort = prev != null && prev.box.right < cell.right - 0.3f * cw
            if (prev != null && (LIST_MARKER.containsMatchIn(l.text.trim()) || prevShort)) finish()
            group.add(l)
        }
        finish()
        val text = Box.unionOf(lines.map { it.box })!!
        val vAlign = when {
            abs(text.cy - cell.cy) < cell.height * 0.18f -> VAlign.CENTER
            text.cy < cell.cy -> VAlign.TOP
            else -> VAlign.BOTTOM
        }
        return paras to vAlign
    }

    /**
     * Đồng nhất cỡ chữ: văn bản thật thường chỉ dùng 1 cỡ cho thân bài (vd 13–14pt); sai số chiều cao
     * hộp OCR khiến từng dòng lệch ±1–2pt → gom các cỡ gần cỡ chủ đạo (±20%) về đúng cỡ chủ đạo,
     * riêng cho thân văn bản và cho bảng. Tiêu đề lớn hơn hẳn vẫn giữ nguyên.
     */
    private fun normalizeFonts(blocks: List<Block>): List<Block> {
        fun dominant(sizes: List<Pair<Float, Int>>): Float? =
            sizes.groupBy { it.first }.maxByOrNull { e -> e.value.sumOf { it.second } }?.key
        fun snap(p: Paragraph, d: Float?) =
            if (d != null && (p.fontPt / d in 0.75f..1.35f ||
                    (!p.bold && p.align != Align.CENTER && p.fontPt / d in 0.6f..1.6f))) p.copy(fontPt = d) else p
        val bodyDom = dominant(blocks.filterIsInstance<ParagraphBlock>().map { it.paragraph.fontPt to it.paragraph.text.length })
        val cellParas = blocks.filterIsInstance<TableBlock>().flatMap { t -> t.cells.flatMap { it.paragraphs } }
        val cellDom = dominant(cellParas.map { it.fontPt to it.text.length }) ?: bodyDom
        return blocks.map { b ->
            when (b) {
                is ParagraphBlock -> ParagraphBlock(
                    if (b.paragraph.floating) {
                        // Ghi chú cạnh bảng viết cùng cỡ với chữ trong bảng.
                        val d = cellDom ?: bodyDom
                        if (d != null && b.paragraph.fontPt > d) b.paragraph.copy(fontPt = d) else b.paragraph
                    } else {
                        snap(b.paragraph, bodyDom)
                    },
                )
                is TableBlock -> TableBlock(
                    b.box, b.colEdges, b.rowEdges,
                    b.cells.map { c -> c.copy(paragraphs = c.paragraphs.map { snap(it, if (b.bordered) cellDom else bodyDom) }) },
                    b.bordered, b.spaceBeforePx,
                )
                is ImageBlock -> b
            }
        }
    }

    /** Ngôn ngữ chiếm đa số (theo số ký tự). */
    private fun majorityLang(items: List<Pair<String, Int>>): String =
        items.filter { it.first.isNotEmpty() }.groupBy { it.first }.maxByOrNull { e -> e.value.sumOf { it.second } }?.key ?: ""

    /** Màu chiếm đa số (theo số ký tự); 0 = đen. */
    private fun majorityColor(items: List<Pair<Int, Int>>): Int =
        items.groupBy { it.first }.maxByOrNull { e -> e.value.sumOf { it.second } }?.key ?: 0

    /** Chiều cao hộp chữ OCR (px) → cỡ chữ (pt) thô. */
    private fun fontRaw(boxHeightPx: Float, ptPerPx: Float): Float = boxHeightPx * ptPerPx / 1.28f

    /**
     * Độ rộng tương đối của chuỗi theo em (bảng độ rộng ký tự xấp xỉ của Times New Roman). Dấu tiếng
     * Việt không làm thay đổi bề rộng ký tự nên ước lượng vẫn đúng.
     */
    private fun widthEm(text: String): Float {
        var w = 0f
        for (ch in text) {
            w += when {
                ch == ' ' -> 0.25f
                ch.code in 0x1100..0x11FF || ch.code in 0x2E80..0x9FFF || ch.code in 0xAC00..0xD7AF || ch.code in 0xF900..0xFAFF || ch.code in 0xFF00..0xFFEF -> 1.0f
                ch.isUpperCase() -> if (ch == 'I') 0.33f else if (ch == 'M' || ch == 'W') 0.89f else 0.68f
                ch.isLowerCase() -> if (ch in "ijlft") 0.28f else if (ch == 'm' || ch == 'w') 0.72f else 0.47f
                ch.isDigit() -> 0.5f
                else -> 0.33f
            }
        }
        return w
    }

    /**
     * Cỡ chữ thô của 1 dòng: kết hợp chiều cao hộp (bị phóng đại bởi dấu tiếng Việt, hộp OCR rộng)
     * với bề rộng dòng chia cho độ rộng chuỗi (ổn định hơn nhiều khi dòng đủ dài).
     */
    private fun lineFontRaw(l: OcrLine, ptPerPx: Float): Float {
        val byHeight = fontRaw(l.box.height, ptPerPx)
        val text = l.text.trim()
        if (text.length < 6) return byHeight
        val byWidth = l.box.width * ptPerPx / max(1f, widthEm(text))
        // Dòng dài: bề rộng dòng đáng tin hơn chiều cao hộp (hộp OCR phình vì dấu tiếng Việt / chữ Hàn).
        return if (text.length >= 30) byWidth.coerceIn(byHeight * 0.6f, byHeight * 1.1f)
        else byWidth.coerceIn(byHeight * 0.7f, byHeight * 1.15f)
    }

    private fun lineFont(l: OcrLine, ptPerPx: Float) = snapSize(lineFontRaw(l, ptPerPx))

    private fun snapSize(raw: Float): Float {
        var best = COMMON_SIZES[0]
        for (s in COMMON_SIZES) if (abs(s - raw) < abs(best - raw)) best = s
        return best
    }

    /** Chiều cao hộp chữ OCR (px) → cỡ chữ (pt), làm tròn về cỡ chữ thông dụng. */
    fun fontSize(boxHeightPx: Float, ptPerPx: Float): Float = snapSize(fontRaw(boxHeightPx, ptPerPx))

    private fun median(values: List<Float>): Float? {
        if (values.isEmpty()) return null
        val s = values.sorted()
        return if (s.size % 2 == 1) s[s.size / 2] else (s[s.size / 2 - 1] + s[s.size / 2]) / 2f
    }

    @Suppress("unused")
    private fun Float.r(): Int = roundToInt()
}
