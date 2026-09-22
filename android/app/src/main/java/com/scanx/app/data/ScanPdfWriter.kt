package com.scanx.app.data

import java.io.ByteArrayOutputStream
import java.io.OutputStream
import java.text.Normalizer
import java.util.Base64
import java.util.Locale
import java.util.zip.Deflater

/**
 * 4 chế độ xuất PDF của bản scan (người dùng chọn khi lưu/xuất; mặc định [BW_HQ]).
 * Kích thước tham khảo đo trên trang A4 viết tay dày chữ / văn bản in (ảnh ~1,2–2 MP):
 *  - [BW_SMALL]  (A1) đen trắng 1-bit nén Flate:          ~30–60 KB/trang
 *  - [BW_HQ]     (A2) đen trắng xám mịn (JPEG xám):       ~150–300 KB/trang
 *  - [COLOR_SMALL] (B1) màu, cạnh dài 1600 px, JPEG q60:  ~130–220 KB/trang
 *  - [COLOR_HQ]  (B2) màu độ phân giải gốc, JPEG q88:     ~300–450 KB/trang
 * (Bản cũ: ảnh RGB không nén tối ưu ~3 MB/trang.)
 */
enum class PdfExportMode(val code: String, val label: String, val description: String) {
    BW_SMALL("A1", "Đen trắng – Nhỏ gọn", "Nhẹ nhất, chữ đen nền trắng tuyệt đối"),
    BW_HQ("A2", "Đen trắng – Chất lượng cao", "Chữ, nét viết mịn và rõ; mặc định"),
    COLOR_SMALL("B1", "Màu – Nhỏ gọn", "Giữ màu mực, dấu đỏ; dung lượng thấp"),
    COLOR_HQ("B2", "Màu – Chất lượng cao", "Màu và chi tiết đầy đủ nhất"),
    ;

    val isColor: Boolean get() = this == COLOR_SMALL || this == COLOR_HQ

    companion object {
        val DEFAULT = BW_HQ
        fun fromCode(code: String?): PdfExportMode = values().firstOrNull { it.code == code } ?: DEFAULT
    }
}

/** Ảnh 1 trang đã mã hoá sẵn cho PDF. */
class PdfImage(val width: Int, val height: Int, val kind: Kind, val data: ByteArray) {
    enum class Kind {
        /** JPEG xám 8-bit (DCTDecode). */
        JPEG_GRAY,
        /** JPEG màu (DCTDecode). */
        JPEG_RGB,
        /** Ảnh 1-bit chưa nén, mỗi hàng đệm đủ byte, bit 1 = trắng (writer tự nén Flate). */
        BITMAP_1BIT,
    }
}

/** 1 dòng chữ OCR, toạ độ chuẩn hoá 0..1 theo trang (gốc trên-trái). */
class PdfTextLine(val text: String, val left: Float, val top: Float, val right: Float, val bottom: Float)

class PdfPageSpec(val widthPt: Float, val heightPt: Float, val image: PdfImage, val lines: List<PdfTextLine>)

/**
 * Bộ ghi PDF 1.4 tối giản viết tay (Kotlin thuần — kiểm thử được trên JVM, không phụ thuộc Android).
 * Lý do không dùng android.graphics.pdf.PdfDocument: Skia luôn nhúng ảnh dạng RGB nén Flate, không
 * cho chọn JPEG/xám/1-bit → PDF 3 MB/trang. Ở đây mỗi trang = 1 ảnh XObject đúng định dạng của chế
 * độ xuất + lớp chữ OCR vô hình (render mode 3) dùng font "GlyphLess" 616 byte với mã CID = Unicode
 * (Identity-H + ToUnicode) — kỹ thuật của Tesseract/OCRmyPDF → tìm kiếm, bôi đen, copy chữ tiếng
 * Việt có dấu chính xác trong mọi trình đọc PDF. Ghi tuần tự từng trang (giải phóng ngay) → không
 * tốn RAM với tài liệu nhiều trang.
 */
object ScanPdfWriter {

    private const val GLYPHLESS_TTF_B64 =
        "AAEAAAAKAIAAAwAgT1MvMkTeRSAAAAEoAAAAYGNtYXAADABGAAABkAAAACxnbHlmAAAAAAAAAcQAAAABaGVhZCzGsLYAAACsAAAANmhoZWED6QH2AAAA5AAAACRobXR4AfQAAAAAAYgAAAAGbG9jYQAAAAAAAAG8AAAABm1heHAAAwACAAABCAAAACBuYW1lGZ8ZNAAAAcgAAABycG9zdDIGAmcAAAI8AAAAKQABAAAAAQAAHyx1w18PPPUAAwPoAAAAAObYN+sAAAAA5tg36wAAAAAAAAAAAAAAAwACAAAAAAAAAAEAAAPoAAAAAAH0AAAAAAAAAAEAAAAAAAAAAAAAAAAAAAABAAEAAAACAAAAAAAAAAAAAgAAAAAAAAAAAAAAAAAAAAAAAwH0AZAABQAEAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAPz8/PwAAAAAAAAPoAAAAAAPoAAAAAAAAAAAAAAAAAAAAAAAgAAAB9AAAAAAAAAAAAAIAAAADAAAAFAADAAEAAAAUAAQAGAAAAAIAAgAAAAD//wAA//8AAQAAAAAAAAAAAAAAAAAAAAAABAA2AAEAAAAAAAEADQAAAAEAAAAAAAIABwANAAMAAQQJAAEAGgAUAAMAAQQJAAIADgAuR2x5cGhMZXNzRm9udFJlZ3VsYXIARwBsAHkAcABoAEwAZQBzAHMARgBvAG4AdABSAGUAZwB1AGwAYQByAAAAAgAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAACAAABAgJnMQAAAA=="

    /** Glyph của font GlyphLess rộng 500/1000 em. */
    private const val GLYPH_ADVANCE = 0.5f

    private const val OBJ_CATALOG = 1
    private const val OBJ_PAGES = 2
    private const val OBJ_FONT = 3
    private const val OBJ_CIDFONT = 4
    private const val OBJ_DESCRIPTOR = 5
    private const val OBJ_FONTFILE = 6
    private const val OBJ_CIDTOGID = 7
    private const val OBJ_TOUNICODE = 8
    private const val OBJ_INFO = 9
    private const val FIRST_PAGE_OBJ = 10

    /**
     * Ghi PDF [pageCount] trang ra [out]; [pageAt] được gọi tuần tự cho từng trang (0-based) nên
     * có thể tạo ảnh trang ngay lúc cần rồi bỏ đi.
     */
    fun write(out: OutputStream, pageCount: Int, title: String, pageAt: (Int) -> PdfPageSpec) {
        require(pageCount > 0) { "Không có trang nào để tạo PDF" }
        val w = CountingWriter(out)
        val totalObjs = FIRST_PAGE_OBJ + pageCount * 3
        val offsets = LongArray(totalObjs)

        fun beginObj(n: Int) {
            offsets[n] = w.count
            w.ascii("$n 0 obj\n")
        }
        fun dictObj(n: Int, dict: String) {
            beginObj(n)
            w.ascii("$dict\nendobj\n")
        }
        fun streamObj(n: Int, dict: String, data: ByteArray) {
            beginObj(n)
            w.ascii("<<$dict /Length ${data.size}>>\nstream\n")
            w.bytes(data)
            w.ascii("\nendstream\nendobj\n")
        }

        w.ascii("%PDF-1.4\n")
        w.bytes(byteArrayOf('%'.code.toByte(), 0xE2.toByte(), 0xE3.toByte(), 0xCF.toByte(), 0xD3.toByte(), '\n'.code.toByte()))

        val kids = (0 until pageCount).joinToString(" ") { "${FIRST_PAGE_OBJ + it * 3} 0 R" }
        dictObj(OBJ_CATALOG, "<</Type /Catalog /Pages $OBJ_PAGES 0 R>>")
        dictObj(OBJ_PAGES, "<</Type /Pages /Count $pageCount /Kids [$kids]>>")
        dictObj(OBJ_FONT, "<</Type /Font /Subtype /Type0 /BaseFont /GlyphLessFont /Encoding /Identity-H " +
            "/DescendantFonts [$OBJ_CIDFONT 0 R] /ToUnicode $OBJ_TOUNICODE 0 R>>")
        dictObj(OBJ_CIDFONT, "<</Type /Font /Subtype /CIDFontType2 /BaseFont /GlyphLessFont " +
            "/CIDSystemInfo <</Registry (Adobe) /Ordering (Identity) /Supplement 0>> " +
            "/FontDescriptor $OBJ_DESCRIPTOR 0 R /DW ${(GLYPH_ADVANCE * 1000).toInt()} /CIDToGIDMap $OBJ_CIDTOGID 0 R>>")
        dictObj(OBJ_DESCRIPTOR, "<</Type /FontDescriptor /FontName /GlyphLessFont /Flags 5 /FontBBox [0 0 500 1000] " +
            "/ItalicAngle 0 /Ascent 1000 /Descent 0 /CapHeight 1000 /StemV 80 /FontFile2 $OBJ_FONTFILE 0 R>>")
        val ttf = Base64.getDecoder().decode(GLYPHLESS_TTF_B64)
        streamObj(OBJ_FONTFILE, "/Length1 ${ttf.size}", ttf)
        // Mọi CID (0..65535) → glyph 1 (rỗng).
        val gidMap = ByteArray(65536 * 2) { if (it % 2 == 1) 1 else 0 }
        streamObj(OBJ_CIDTOGID, "/Filter /FlateDecode", deflate(gidMap, Deflater.BEST_COMPRESSION))
        streamObj(OBJ_TOUNICODE, "/Filter /FlateDecode", deflate(toUnicodeCMap().toByteArray(Charsets.US_ASCII), Deflater.BEST_COMPRESSION))
        dictObj(OBJ_INFO, "<</Producer (ScanX) /Creator (ScanX) /Title ${pdfTextString(title)}>>")

        for (i in 0 until pageCount) {
            val spec = pageAt(i)
            val pageObj = FIRST_PAGE_OBJ + i * 3
            val contentObj = pageObj + 1
            val imageObj = pageObj + 2
            val wPt = fmt(spec.widthPt)
            val hPt = fmt(spec.heightPt)
            dictObj(pageObj, "<</Type /Page /Parent $OBJ_PAGES 0 R /MediaBox [0 0 $wPt $hPt] " +
                "/Resources <</XObject <</Im0 $imageObj 0 R>> /Font <</F1 $OBJ_FONT 0 R>> /ProcSet [/PDF /Text /ImageB /ImageC]>> " +
                "/Contents $contentObj 0 R>>")
            val content = buildString {
                append("q $wPt 0 0 $hPt 0 0 cm /Im0 Do Q\n")
                append(textLayer(spec))
            }
            streamObj(contentObj, "/Filter /FlateDecode", deflate(content.toByteArray(Charsets.US_ASCII), Deflater.DEFAULT_COMPRESSION))
            val img = spec.image
            when (img.kind) {
                PdfImage.Kind.JPEG_GRAY -> streamObj(imageObj, "/Type /XObject /Subtype /Image /Width ${img.width} /Height ${img.height} " +
                    "/ColorSpace /DeviceGray /BitsPerComponent 8 /Filter /DCTDecode", img.data)
                PdfImage.Kind.JPEG_RGB -> streamObj(imageObj, "/Type /XObject /Subtype /Image /Width ${img.width} /Height ${img.height} " +
                    "/ColorSpace /DeviceRGB /BitsPerComponent 8 /Filter /DCTDecode", img.data)
                PdfImage.Kind.BITMAP_1BIT -> streamObj(imageObj, "/Type /XObject /Subtype /Image /Width ${img.width} /Height ${img.height} " +
                    "/ColorSpace /DeviceGray /BitsPerComponent 1 /Filter /FlateDecode", deflate(img.data, Deflater.BEST_COMPRESSION))
            }
        }

        val xref = w.count
        w.ascii("xref\n0 $totalObjs\n0000000000 65535 f \n")
        for (n in 1 until totalObjs) w.ascii(String.format(Locale.US, "%010d 00000 n \n", offsets[n]))
        w.ascii("trailer\n<</Size $totalObjs /Root $OBJ_CATALOG 0 R /Info $OBJ_INFO 0 R>>\nstartxref\n$xref\n%%EOF\n")
        w.flush()
    }

    /** Lớp chữ vô hình: mỗi dòng đặt đúng khung, giãn ngang (Tz) cho khớp bề rộng dòng trên ảnh. */
    private fun textLayer(spec: PdfPageSpec): String {
        if (spec.lines.isEmpty()) return ""
        val sb = StringBuilder("BT 3 Tr\n")
        for (l in spec.lines) {
            val text = Normalizer.normalize(l.text.trim(), Normalizer.Form.NFC)
            if (text.isEmpty()) continue
            val hPt = (l.bottom - l.top) * spec.heightPt
            val wPt = (l.right - l.left) * spec.widthPt
            if (hPt <= 0.5f || wPt <= 0.5f) continue
            val hex = StringBuilder()
            var n = 0
            for (ch in text) {
                val code = if (Character.isSurrogate(ch)) '?'.code else ch.code
                hex.append(String.format(Locale.US, "%04X", code))
                n++
            }
            val size = hPt
            val tz = 100f * wPt / (n * GLYPH_ADVANCE * size)
            val x = l.left * spec.widthPt
            val y = spec.heightPt - l.bottom * spec.heightPt
            sb.append("/F1 ${fmt(size)} Tf ${fmt(tz)} Tz 1 0 0 1 ${fmt(x)} ${fmt(y)} Tm <$hex> Tj\n")
        }
        sb.append("ET\n")
        return sb.toString()
    }

    private fun toUnicodeCMap(): String = buildString {
        append("/CIDInit /ProcSet findresource begin\n12 dict begin\nbegincmap\n")
        append("/CIDSystemInfo <</Registry (Adobe) /Ordering (UCS) /Supplement 0>> def\n")
        append("/CMapName /Adobe-Identity-UCS def\n/CMapType 2 def\n")
        append("1 begincodespacerange\n<0000> <FFFF>\nendcodespacerange\n")
        val ranges = (0..255).filter { it !in 0xD8..0xDF }
        ranges.chunked(100).forEach { chunk ->
            append("${chunk.size} beginbfrange\n")
            chunk.forEach { hi ->
                val h = String.format(Locale.US, "%02X", hi)
                append("<${h}00> <${h}FF> <${h}00>\n")
            }
            append("endbfrange\n")
        }
        append("endcmap\nCMapName currentdict /CMap defineresource pop\nend\nend\n")
    }

    private fun pdfTextString(s: String): String {
        // UTF-16BE có BOM, dạng hex → an toàn với mọi ký tự tiếng Việt.
        val sb = StringBuilder("<FEFF")
        for (ch in s) sb.append(String.format(Locale.US, "%04X", ch.code))
        return sb.append(">").toString()
    }

    private fun fmt(v: Float): String {
        val s = String.format(Locale.US, "%.3f", v)
        return s.trimEnd('0').trimEnd('.')
    }

    fun deflate(data: ByteArray, level: Int): ByteArray {
        val d = Deflater(level)
        d.setInput(data)
        d.finish()
        val bos = ByteArrayOutputStream(maxOf(64, data.size / 4))
        val buf = ByteArray(64 * 1024)
        while (!d.finished()) {
            val n = d.deflate(buf)
            bos.write(buf, 0, n)
        }
        d.end()
        return bos.toByteArray()
    }

    private class CountingWriter(private val out: OutputStream) {
        var count = 0L
            private set

        fun ascii(s: String) = bytes(s.toByteArray(Charsets.ISO_8859_1))
        fun bytes(b: ByteArray) {
            out.write(b)
            count += b.size
        }
        fun flush() = out.flush()
    }
}
