package com.scanx.app.convert

import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Phần Kotlin thuần của chế độ "AI Cloud" cho Word/Excel/PowerPoint (kiểm thử được trên JVM).
 *
 * Nguyên tắc: BỐ CỤC do máy tự dựng (OpenCV: lưới bảng, ô gộp, vị trí đoạn/ghi chú — chính xác
 * từng pixel), CHỮ do AI thị giác (Claude) đọc lại từ ảnh trang — kể cả chữ viết tay tiếng Việt mà
 * OCR trên máy không đọc được. Mỗi "ô chữ" (đoạn văn, ô bảng) có 1 mã + khung toạ độ + bản nháp OCR;
 * AI trả về đúng nội dung cho từng mã → ghép lại vào bố cục → Word/Excel 100% chữ + bảng thật.
 */
object CloudTranscription {

    class Slot(val id: String, val box: Box, val draft: String, val inTable: Boolean, val lang: String = "")

    class ExtraText(val text: String, val box: Box)

    /** Liệt kê các ô chữ của trang theo thứ tự đọc. */
    fun slots(page: DocPage): List<Slot> {
        val out = ArrayList<Slot>()
        page.blocks.forEachIndexed { bi, b ->
            when (b) {
                is ParagraphBlock -> out.add(Slot("p$bi", b.paragraph.box, b.paragraph.text, false, b.paragraph.lang))
                is TableBlock -> for (cell in b.cells) {
                    val r1 = min(b.rowCount, cell.row + cell.rowSpan)
                    val c1 = min(b.colCount, cell.col + cell.colSpan)
                    val box = Box(b.colEdges[cell.col], b.rowEdges[cell.row], b.colEdges[c1], b.rowEdges[r1])
                    out.add(Slot("t${bi}r${cell.row}c${cell.col}", box, cell.paragraphs.joinToString("\n") { it.text }, b.bordered, cell.paragraphs.firstOrNull()?.lang.orEmpty()))
                }
                is ImageBlock -> Unit
            }
        }
        return out
    }

    /** Nội dung chỉ dẫn gửi kèm ảnh trang (toạ độ quy về thang 0–1000 theo chiều rộng/cao trang). */
    fun prompt(page: DocPage): String {
        val sb = StringBuilder()
        sb.append(
            "Bạn là hệ thống nhận dạng tài liệu. Ảnh đính kèm là 1 trang tài liệu đã scan (có thể có chữ in, " +
                "chữ viết tay tiếng Việt, số tiền, bảng biểu). Bố cục đã được máy phân tích sẵn thành các Ô CHỮ " +
                "dưới đây, mỗi ô có mã, khung toạ độ [trái, trên, phải, dưới] theo thang 0–1000 của chiều rộng/chiều cao " +
                "trang, và bản nháp OCR (thường sai với chữ viết tay).\n\n" +
                taskRules() +
                "Chỉ trả về 1 đối tượng JSON, không kèm chữ nào khác, dạng:\n" +
                "{\"slots\": {\"<mã>\": \"<nội dung>\", ...}, \"extra\": [{\"text\": \"...\", \"box\": [l, t, r, b]}]}\n\n" +
                "Các ô chữ:\n",
        )
        sb.append(slotListing(page))
        return sb.toString()
    }

    /**
     * Chỉ dẫn cho NHIỀU trang trong 1 lần gọi — ảnh đính kèm theo đúng thứ tự Trang 1, Trang 2…
     * Gộp trang giúp giảm số lần gọi AI Cloud (nhanh hơn, ít tốn hạn mức) khi xuất/dịch tài liệu
     * nhiều trang viết tay. JSON trả về là 1 mảng "pages" theo đúng thứ tự ảnh đính kèm.
     */
    fun batchPrompt(pages: List<DocPage>): String {
        val sb = StringBuilder()
        sb.append(
            "Bạn là hệ thống nhận dạng tài liệu. ${pages.size} ảnh đính kèm theo đúng thứ tự là ${pages.size} TRANG " +
                "tài liệu đã scan (Trang 1, Trang 2, …) — MỖI ẢNH LÀ 1 TRANG RIÊNG, không liên quan nội dung nhau. " +
                "Có thể có chữ in, chữ viết tay tiếng Việt, số tiền, bảng biểu. Bố cục mỗi trang đã được máy phân tích " +
                "sẵn thành các Ô CHỮ dưới đây, mỗi ô có mã, khung toạ độ [trái, trên, phải, dưới] theo thang 0–1000 của " +
                "chiều rộng/chiều cao TRANG ĐÓ, và bản nháp OCR (thường sai với chữ viết tay).\n\n" +
                taskRules() +
                "Chỉ trả về 1 đối tượng JSON, không kèm chữ nào khác, dạng:\n" +
                "{\"pages\": [ {\"slots\": {\"<mã>\": \"<nội dung>\", ...}, \"extra\": [{\"text\": \"...\", \"box\": [l, t, r, b]}]}, ... ]}\n" +
                "Mảng \"pages\" phải có đúng ${pages.size} phần tử, theo đúng thứ tự Trang 1 → Trang ${pages.size}.\n\n",
        )
        pages.forEachIndexed { i, page ->
            sb.append("=== Trang ${i + 1} (ảnh thứ ${i + 1}) — các ô chữ ===\n")
            sb.append(slotListing(page))
        }
        return sb.toString()
    }

    private fun taskRules(): String =
        "Nhiệm vụ: đọc chính xác chữ nằm TRONG TỪNG KHUNG trên ảnh tương ứng và trả về đúng nội dung đó.\n" +
            "Quy tắc:\n" +
            "- Giữ nguyên ngôn ngữ và hệ chữ gốc của từng ô: tiếng Việt có dấu đầy đủ, chính xác; tiếng Hàn bằng Hangul; " +
            "tiếng Nhật bằng Kanji/Kana; tiếng Trung bằng Hán tự; tiếng Anh/Đức/Pháp… đúng chính tả và dấu (ä ö ü ß é è ç…). " +
            "Mục \"ngôn ngữ\" chỉ là gợi ý của máy, ảnh mới là chuẩn. Giữ nguyên số, " +
            "ký hiệu tiền (k, tr, đ, M…), dấu ngoặc, dấu +, /, ngày tháng như trên giấy. Không dịch, không giải thích, không sửa nội dung.\n" +
            "- Ô không có chữ → chuỗi rỗng \"\". Chữ bị gạch xoá → bỏ qua.\n" +
            "- Nhiều dòng trong 1 ô → nối bằng \\n.\n" +
            "- Chữ nằm ngoài mọi khung (ghi chú lề, chữ OCR bỏ sót) → đưa vào \"extra\" kèm khung 0–1000.\n"

    private fun slotListing(page: DocPage): String {
        val sb = StringBuilder()
        val w = max(1f, page.width.toFloat())
        val h = max(1f, page.height.toFloat())
        for (s in slots(page)) {
            fun n(v: Float, d: Float) = (v / d * 1000f).roundToInt().coerceIn(0, 1000)
            sb.append(s.id).append(if (s.inTable) " (ô bảng)" else "").append(" [")
                .append(n(s.box.left, w)).append(", ").append(n(s.box.top, h)).append(", ")
                .append(n(s.box.right, w)).append(", ").append(n(s.box.bottom, h)).append("]")
                .append(if (s.lang.isNotEmpty()) " ngôn ngữ: ${s.lang}" else "").append(" nháp: \"")
                .append(s.draft.replace("\"", "'").replace("\n", " ").take(120)).append("\"\n")
        }
        return sb.toString()
    }

    /**
     * Ghép kết quả AI vào bố cục: thay chữ từng đoạn/ô (giữ nguyên căn lề, cỡ, đậm, màu của ô), ô trống
     * nay có chữ thì tạo đoạn mới theo cỡ chữ chủ đạo của bảng; chữ "extra" thành ghi chú định vị tuyệt đối.
     * Khung của [extras] theo thang 0–1000.
     */
    fun apply(page: DocPage, answers: Map<String, String>, extras: List<ExtraText>): DocPage {
        val cellParas = page.blocks.filterIsInstance<TableBlock>().flatMap { t -> t.cells.flatMap { it.paragraphs } }
        val allParas = page.blocks.filterIsInstance<ParagraphBlock>().map { it.paragraph } + cellParas
        val domFont = dominant(cellParas.map { it.fontPt to it.text.length }) ?: dominant(allParas.map { it.fontPt to it.text.length }) ?: 12f
        val domColor = allParas.groupBy { it.color }.maxByOrNull { e -> e.value.sumOf { it.text.length } }?.key ?: 0

        val blocks = ArrayList<Block>()
        page.blocks.forEachIndexed { bi, b ->
            when (b) {
                is ParagraphBlock -> {
                    val ans = answers["p$bi"]
                    when {
                        ans == null -> blocks.add(b)
                        ans.isBlank() -> Unit
                        else -> blocks.add(ParagraphBlock(b.paragraph.copy(text = clean(ans), lang = Lang.detect(clean(ans), b.paragraph.lang))))
                    }
                }
                is TableBlock -> {
                    val cells = b.cells.map { cell ->
                        val ans = answers["t${bi}r${cell.row}c${cell.col}"] ?: return@map cell
                        val lines = clean(ans).split('\n').map { it.trim() }.filter { it.isNotEmpty() }
                        if (lines.isEmpty()) return@map cell.copy(paragraphs = emptyList())
                        val r1 = min(b.rowCount, cell.row + cell.rowSpan)
                        val c1 = min(b.colCount, cell.col + cell.colSpan)
                        val cellBox = Box(b.colEdges[cell.col], b.rowEdges[cell.row], b.colEdges[c1], b.rowEdges[r1])
                        val template = cell.paragraphs.firstOrNull()
                        val rowPt = cellBox.height * page.ptPerPx
                        val font = template?.fontPt ?: min(domFont, max(8f, snap(rowPt * 0.5f / lines.size)))
                        val paras = lines.mapIndexed { i, text ->
                            val base = cell.paragraphs.getOrNull(i) ?: template
                            base?.copy(text = text, fontPt = font, lang = Lang.detect(text, base.lang)) ?: Paragraph(
                                text = text, align = Align.CENTER, fontPt = font, bold = false, indentPx = 0f,
                                spaceBeforePx = 0f, box = cellBox, color = domColor, lang = Lang.detect(text),
                            )
                        }
                        cell.copy(paragraphs = paras, vAlign = if (cell.paragraphs.isEmpty()) VAlign.CENTER else cell.vAlign)
                    }
                    blocks.add(TableBlock(b.box, b.colEdges, b.rowEdges, cells, b.bordered, b.spaceBeforePx))
                }
                is ImageBlock -> blocks.add(b)
            }
        }
        for (e in extras) {
            val text = clean(e.text).replace('\n', ' ').trim()
            if (text.isEmpty()) continue
            val box = Box(
                e.box.left / 1000f * page.width, e.box.top / 1000f * page.height,
                e.box.right / 1000f * page.width, e.box.bottom / 1000f * page.height,
            )
            if (box.width <= 0f || box.height <= 0f) continue
            blocks.add(
                ParagraphBlock(
                    Paragraph(
                        text = text, align = Align.LEFT, fontPt = domFont, bold = false, indentPx = 0f, spaceBeforePx = 0f,
                        box = box, lineBoxes = listOf(box), color = domColor, floating = true, lang = Lang.detect(text),
                    ),
                ),
            )
        }
        return DocPage(page.width, page.height, blocks, page.content, page.pageJpeg, page.ptPerPx, page.ocrConfidence)
    }

    private fun clean(s: String) = s.replace("\r", "").replace("\\n", "\n").trim()

    private val SIZES = floatArrayOf(8f, 9f, 10f, 10.5f, 11f, 12f, 13f, 14f)
    private fun snap(v: Float): Float = SIZES.minByOrNull { kotlin.math.abs(it - v) }!!

    private fun dominant(sizes: List<Pair<Float, Int>>): Float? =
        sizes.filter { it.second > 0 }.groupBy { it.first }.maxByOrNull { e -> e.value.sumOf { it.second } }?.key
}
