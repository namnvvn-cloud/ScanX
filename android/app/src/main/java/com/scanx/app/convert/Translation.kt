package com.scanx.app.convert

/**
 * Phần Kotlin thuần của tính năng "Dịch sang tiếng Việt" (kiểm thử được trên JVM).
 *
 * Nguyên tắc giống chế độ AI Cloud: GIỮ NGUYÊN BỐ CỤC đã dựng (bảng, ô gộp, vị trí, cỡ chữ, màu, đậm),
 * chỉ thay CHỮ của từng đoạn / ô bằng bản dịch → file Word bản dịch nhìn như bản gốc nhưng bằng
 * tiếng Việt. Đoạn đã là tiếng Việt, số liệu, ký hiệu thuần tuý được giữ nguyên (không gửi đi dịch).
 */
object Translation {

    /** 1 đoạn chữ cần dịch: [id] ổn định theo vị trí trong tài liệu, [lang] = ngôn ngữ nguồn đoán được. */
    class Item(val id: String, val text: String, val lang: String)

    const val TARGET = "vi"

    /** Các đoạn cần dịch theo thứ tự đọc. */
    fun collect(doc: DocModel): List<Item> {
        val out = ArrayList<Item>()
        doc.pages.forEachIndexed { pi, page ->
            page.blocks.forEachIndexed { bi, b ->
                when (b) {
                    is ParagraphBlock -> add(out, "g${pi}b$bi", b.paragraph)
                    is TableBlock -> b.cells.forEach { c ->
                        c.paragraphs.forEachIndexed { k, p -> add(out, "g${pi}b${bi}r${c.row}c${c.col}k$k", p) }
                    }
                    is ImageBlock -> Unit
                }
            }
        }
        return out
    }

    private fun add(out: MutableList<Item>, id: String, p: Paragraph) {
        val text = p.text.trim()
        if (!needsTranslation(text, p.lang)) return
        out.add(Item(id, text, p.lang.ifEmpty { Lang.detect(text) }))
    }

    /** Có chữ cái và không phải tiếng Việt → cần dịch. */
    fun needsTranslation(text: String, lang: String): Boolean {
        val c = Lang.count(text)
        if (c.letters < 2) return false
        val l = if (lang.isEmpty()) Lang.detect(text) else lang
        // Dòng Latin ngắn kiểu mã hiệu/viết tắt ("GIS", "KPI 2026") giữ nguyên.
        if (l != TARGET && c.dominant == Lang.Script.LATIN && c.latin <= 4 && !text.contains(' ')) return false
        return l != TARGET || c.hangul + c.kana + c.han > 0
    }

    /** Thay chữ bằng bản dịch; đoạn đã dịch gán ngôn ngữ tiếng Việt (font Times New Roman). */
    fun apply(doc: DocModel, translated: Map<String, String>, titleSuffix: String = " (tiếng Việt)"): DocModel {
        fun tr(id: String, p: Paragraph): Paragraph {
            val t = translated[id]?.trim()
            return if (t.isNullOrEmpty()) p else p.copy(text = t, lang = TARGET)
        }
        val pages = doc.pages.mapIndexed { pi, page ->
            val blocks = page.blocks.mapIndexed { bi, b ->
                when (b) {
                    is ParagraphBlock -> ParagraphBlock(tr("g${pi}b$bi", b.paragraph))
                    is TableBlock -> TableBlock(
                        b.box, b.colEdges, b.rowEdges,
                        b.cells.map { c -> c.copy(paragraphs = c.paragraphs.mapIndexed { k, p -> tr("g${pi}b${bi}r${c.row}c${c.col}k$k", p) }) },
                        b.bordered, b.spaceBeforePx,
                    )
                    is ImageBlock -> b
                }
            }
            DocPage(page.width, page.height, blocks, page.content, page.pageJpeg, page.ptPerPx, page.ocrConfidence)
        }
        return DocModel(doc.title + titleSuffix, pages)
    }

    /** Văn bản thuần của bản dịch (xuất .txt / xem nhanh), giữ thứ tự đọc, bảng tách bằng tab. */
    fun plainText(doc: DocModel): String {
        val sb = StringBuilder()
        doc.pages.forEachIndexed { pi, page ->
            if (doc.pages.size > 1) sb.append("--- Trang ").append(pi + 1).append(" ---\n")
            for (b in page.blocks) when (b) {
                is ParagraphBlock -> sb.append(b.paragraph.text).append('\n')
                is TableBlock -> for (r in 0 until b.rowCount) {
                    val row = b.cells.filter { it.row == r }.sortedBy { it.col }
                    if (row.isEmpty()) continue
                    sb.append(row.joinToString("\t") { c -> c.paragraphs.joinToString(" ") { it.text } }).append('\n')
                }
                is ImageBlock -> Unit
            }
            sb.append('\n')
        }
        return sb.toString().trim()
    }

    /** Chia lô để gửi dịch: mỗi lô ≤ [maxChars] ký tự và ≤ [maxItems] đoạn. */
    fun batches(items: List<Item>, maxChars: Int = 6000, maxItems: Int = 80): List<List<Item>> {
        val out = ArrayList<List<Item>>()
        var cur = ArrayList<Item>()
        var chars = 0
        for (it in items) {
            if (cur.isNotEmpty() && (chars + it.text.length > maxChars || cur.size >= maxItems)) {
                out.add(cur); cur = ArrayList(); chars = 0
            }
            cur.add(it); chars += it.text.length
        }
        if (cur.isNotEmpty()) out.add(cur)
        return out
    }

    /**
     * Chỉ dẫn dịch cho mô hình ngôn ngữ lớn (Claude): dịch chuẩn văn phong tài liệu tiếng Việt, đúng
     * thuật ngữ chuyên ngành, giữ số liệu/ký hiệu/mã, trả về JSON theo mã đoạn để ghép lại bố cục.
     */
    fun llmPrompt(batch: List<Item>, context: String): String {
        val sb = StringBuilder()
        sb.append(
            "Bạn là biên dịch viên chuyên nghiệp. Dịch các đoạn văn bản dưới đây (trích từ 1 tài liệu scan: $context) " +
                "sang TIẾNG VIỆT chuẩn mực, tự nhiên, đúng văn phong tài liệu hành chính/kỹ thuật Việt Nam.\n" +
                "Quy tắc:\n" +
                "- Dịch đúng nghĩa theo ngữ cảnh cả tài liệu; dùng thuật ngữ chuyên ngành chuẩn tiếng Việt (điện lực, viễn thông, CNTT, tài chính, pháp lý…); " +
                "tên riêng/tên hệ thống/viết tắt thông dụng (GIS, KPI, 5G…) giữ nguyên.\n" +
                "- Giữ nguyên số, đơn vị, ngày tháng, ký hiệu tiền, mã hiệu, dấu đầu dòng, nhãn trong ngoặc như [KR], [VN].\n" +
                "- Đoạn đã là tiếng Việt hoặc không cần dịch → trả lại nguyên văn.\n" +
                "- Không thêm giải thích, không gộp/tách đoạn.\n" +
                "Chỉ trả về 1 đối tượng JSON {\"<mã>\": \"<bản dịch>\", ...} cho đủ mọi mã, không kèm chữ nào khác.\n\n" +
                "Các đoạn (mã | ngôn ngữ nguồn | nội dung):\n",
        )
        for (it in batch) {
            sb.append(it.id).append(" | ").append(it.lang.ifEmpty { "?" }).append(" | ")
                .append(it.text.replace('\n', ' ')).append('\n')
        }
        return sb.toString()
    }
}
