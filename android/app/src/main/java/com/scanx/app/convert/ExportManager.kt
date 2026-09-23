package com.scanx.app.convert

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.pdf.PdfRenderer
import android.net.Uri
import android.os.ParcelFileDescriptor
import com.google.mlkit.vision.text.TextRecognizer
import com.scanx.app.data.DocumentRepository
import com.scanx.app.data.PdfExportMode
import com.scanx.app.scan.OrientationDetector
import com.scanx.app.scan.ScanFilters
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.opencv.android.Utils
import org.opencv.core.Mat
import java.io.File

enum class ExportFormat(val ext: String, val mime: String, val label: String) {
    PDF("pdf", "application/pdf", "PDF"),
    DOCX("docx", "application/vnd.openxmlformats-officedocument.wordprocessingml.document", "Word (.docx)"),
    XLSX("xlsx", "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet", "Excel (.xlsx)"),
    PPTX("pptx", "application/vnd.openxmlformats-officedocument.presentationml.presentation", "PowerPoint (.pptx)"),
    JPG("jpg", "image/jpeg", "Ảnh JPG"),
    TXT("txt", "text/plain", "Văn bản (.txt)"),
}

/** Cấu hình AI Cloud cho 1 lần xuất (null = chỉ xử lý trên máy). */
class CloudConfig(val apiKey: String, val model: String)

/**
 * Điều phối xuất / chuyển đổi tài liệu:
 *  - PDF: dựng lại từ ảnh master theo 1 trong 4 chế độ (A1/A2/B1/B2) — tài liệu cũ thì sao chép PDF sẵn có.
 *  - JPG: ảnh từng trang theo chế độ màu/đen trắng đã chọn.
 *  - Word/Excel/PowerPoint: master (màu, đúng chiều) → [PageLayoutExtractor] (OCR + đường kẻ) →
 *    [LayoutAnalyzer] → (tuỳ chọn) [CloudAiClient] đọc lại chữ viết tay → Docx/Xlsx/PptxWriter.
 *    Kết quả 100% chữ + bảng thật (không dán ảnh chụp).
 * Xử lý tuần tự từng trang và giải phóng ảnh ngay → không tràn RAM với tài liệu nhiều trang.
 */
class ExportManager(private val context: Context) {

    private val exportDir: File get() = File(context.cacheDir, "exports").apply { mkdirs() }

    /** Thông báo cho người dùng sau lần xuất gần nhất (vd gợi ý bật AI Cloud), null nếu không có. */
    @Volatile var lastNotice: String? = null
        private set

    suspend fun exportDocument(
        repo: DocumentRepository,
        id: String,
        title: String,
        ocrText: String,
        format: ExportFormat,
        pdfMode: PdfExportMode,
        cloud: CloudConfig? = null,
        onProgress: (Int, Int) -> Unit = { _, _ -> },
    ): List<File> = withContext(Dispatchers.IO) {
        lastNotice = null
        val base = safeName(title)
        exportDir.listFiles()?.forEach { it.delete() }
        val masters = repo.getPageFiles(id)
        val legacyPdf = repo.getPdfFile(id)
        when (format) {
            ExportFormat.PDF -> {
                val out = File(exportDir, "${base}_${pdfMode.code}.pdf")
                if (!repo.buildPdf(id, pdfMode, out, title)) legacyPdf.copyTo(out, overwrite = true)
                listOf(out)
            }
            ExportFormat.TXT -> listOf(File(exportDir, "$base.txt").also { it.writeText(ocrText) })
            ExportFormat.JPG -> {
                val consumer: (Int, Int, Bitmap) -> File = { index, total, bmp ->
                    onProgress(index + 1, total)
                    val shown = ScanFilters.renderBitmap(bmp, pdfMode, 4000)
                    val f = File(exportDir, if (total > 1) "${base}_trang${index + 1}.jpg" else "$base.jpg")
                    f.outputStream().use { shown.compress(Bitmap.CompressFormat.JPEG, if (pdfMode == PdfExportMode.COLOR_SMALL || pdfMode == PdfExportMode.BW_SMALL) 75 else 92, it) }
                    shown.recycle()
                    f
                }
                if (masters.isNotEmpty()) forEachMaster(masters, consumer) else renderPdf(legacyPdf, consumer)
            }
            else -> listOf(
                convertPages(base, title, format, cloud, onProgress) { recognizer, consumer ->
                    if (masters.isNotEmpty()) {
                        forEachMaster(masters, consumer)
                    } else {
                        renderPdf(legacyPdf) { i, n, bmp -> withUpright(recognizer, bmp) { consumer(i, n, it) } }
                    }
                },
            )
        }
    }

    /** Công cụ "Chuyển đổi file": PDF hoặc ảnh import từ máy → Word/Excel/PowerPoint. */
    suspend fun convertImported(
        uris: List<Uri>,
        title: String,
        format: ExportFormat,
        cloud: CloudConfig? = null,
        onProgress: (Int, Int) -> Unit = { _, _ -> },
    ): File = withContext(Dispatchers.IO) {
        lastNotice = null
        exportDir.listFiles()?.forEach { it.delete() }
        val base = safeName(title)
        val first = uris.first()
        val isPdf = context.contentResolver.getType(first)?.contains("pdf") == true || first.toString().endsWith(".pdf", true)
        if (isPdf) {
            val tmp = File(context.cacheDir, "import_src.pdf")
            context.contentResolver.openInputStream(first)?.use { input -> tmp.outputStream().use { input.copyTo(it) } }
                ?: error("Không đọc được file đã chọn")
            try {
                convertPages(base, title, format, cloud, onProgress) { recognizer, consumer ->
                    renderPdf(tmp) { i, n, bmp -> withUpright(recognizer, bmp) { consumer(i, n, it) } }
                }
            } finally {
                tmp.delete()
            }
        } else {
            convertPages(base, title, format, cloud, onProgress) { recognizer, consumer ->
                uris.mapIndexed { i, uri ->
                    val bmp = decodeImage(uri) ?: error("Không đọc được ảnh ${i + 1}")
                    try { withUpright(recognizer, bmp) { consumer(i, uris.size, it) } } finally { bmp.recycle() }
                }
            }
        }
    }

    private fun convertPages(
        base: String,
        title: String,
        format: ExportFormat,
        cloud: CloudConfig?,
        onProgress: (Int, Int) -> Unit,
        source: (TextRecognizer, (Int, Int, Bitmap) -> DocPage) -> List<DocPage>,
    ): File = writeDoc(buildDoc(title, cloud, onProgress, source), base, format)

    /**
     * Dựng mô hình bố cục (bảng, đoạn, ghi chú…) cho mọi trang của nguồn. Khi có AI Cloud: đi 2 lượt —
     * lượt 1 dựng bố cục + gom sẵn ảnh JPEG nhỏ gọn từng trang (giải phóng Bitmap gốc ngay, không giữ
     * trong RAM), lượt 2 gọi AI theo LÔ [BATCH_SIZE] trang/lần (bản 0.7 — trước đó gọi riêng từng
     * trang) để giảm số lần gọi mạng, nhanh hơn với tài liệu nhiều trang viết tay.
     */
    private fun buildDoc(
        title: String,
        cloud: CloudConfig?,
        onProgress: (Int, Int) -> Unit,
        source: (TextRecognizer, (Int, Int, Bitmap) -> DocPage) -> List<DocPage>,
    ): DocModel {
        val ocr = MultiScriptOcr()
        val recognizer = ocr.latinRecognizer
        try {
            val extractor = PageLayoutExtractor(ocr)
            val client = cloud?.let { CloudAiClient(it.apiKey, it.model) }
            val jpegBuffer = client?.let { ArrayList<ByteArray>() }
            var lowConfidencePages = 0
            val rawPages = source(recognizer) { index, total, bmp ->
                onProgress(index + 1, total)
                val input = runBlocking { extractor.extract(bmp) }
                val page = LayoutAnalyzer.analyze(input)
                if (page.ocrConfidence < 0.75f) lowConfidencePages++
                if (client != null && jpegBuffer != null) jpegBuffer.add(client.encode(bmp))
                page
            }
            if (rawPages.isEmpty()) error("Không có trang nào để chuyển đổi")
            val pages = if (client != null && jpegBuffer != null) {
                transcribeInBatches(client, rawPages, jpegBuffer, onProgress)
            } else {
                rawPages
            }
            if (client == null && lowConfidencePages > 0) {
                lastNotice = "Có $lowConfidencePages trang chữ viết tay/khó đọc — bật \"AI Cloud\" khi xuất để nhận dạng chính xác."
            }
            return DocModel(title, pages)
        } finally {
            ocr.close()
        }
    }

    /** Gọi [CloudAiClient.transcribeBatch] theo từng lô [BATCH_SIZE] trang, báo lại tiến độ theo số
     *  trang đã xong sau mỗi lô (không đứng hình tới khi xong hết mới nhảy số). */
    private fun transcribeInBatches(
        client: CloudAiClient,
        pages: List<DocPage>,
        jpegs: List<ByteArray>,
        onProgress: (Int, Int) -> Unit,
    ): List<DocPage> {
        val total = pages.size
        val out = ArrayList<DocPage>(total)
        var index = 0
        while (index < total) {
            val end = minOf(index + BATCH_SIZE, total)
            out.addAll(client.transcribeBatch(pages.subList(index, end), jpegs.subList(index, end)))
            onProgress(end, total)
            index = end
        }
        return out
    }

    private fun writeDoc(doc: DocModel, base: String, format: ExportFormat): File {
        val out = File(exportDir, "$base.${format.ext}")
        out.outputStream().use { stream ->
            when (format) {
                ExportFormat.DOCX -> DocxWriter.write(doc, stream)
                ExportFormat.XLSX -> XlsxWriter.write(doc, stream)
                ExportFormat.PPTX -> PptxWriter.write(doc, stream)
                ExportFormat.TXT -> stream.write(Translation.plainText(doc).toByteArray(Charsets.UTF_8))
                else -> error("Định dạng không hỗ trợ chuyển đổi bố cục: $format")
            }
        }
        return out
    }

    // ------------------------------------------------------------------ Dịch sang tiếng Việt

    /**
     * Dịch tài liệu đã scan sang tiếng Việt, giữ nguyên bố cục: dựng bố cục như khi xuất Word (OCR đa
     * ngôn ngữ, tuỳ chọn AI Cloud đọc chữ) → dịch từng đoạn/ô bằng [engine] → Word (.docx) hoặc .txt.
     */
    suspend fun translateDocument(
        repo: DocumentRepository,
        id: String,
        title: String,
        engine: TranslationEngine,
        cloud: CloudConfig?,
        bilingual: Boolean = false,
        output: ExportFormat,
        onStatus: (String) -> Unit,
    ): File = withContext(Dispatchers.IO) {
        lastNotice = null
        exportDir.listFiles()?.forEach { it.delete() }
        val masters = repo.getPageFiles(id)
        val legacyPdf = repo.getPdfFile(id)
        val doc = buildDoc(title, cloud, { i, n -> onStatus("Đang nhận dạng chữ trang $i/$n…") }) { recognizer, consumer ->
            if (masters.isNotEmpty()) forEachMaster(masters, consumer)
            else renderPdf(legacyPdf) { i, n, bmp -> withUpright(recognizer, bmp) { consumer(i, n, it) } }
        }
        translateAndWrite(doc, safeName(title), engine, bilingual, output, onStatus)
    }

    /** Dịch file PDF/ảnh import từ máy sang tiếng Việt. */
    suspend fun translateImported(
        uris: List<Uri>,
        title: String,
        engine: TranslationEngine,
        cloud: CloudConfig?,
        bilingual: Boolean = false,
        output: ExportFormat,
        onStatus: (String) -> Unit,
    ): File = withContext(Dispatchers.IO) {
        lastNotice = null
        exportDir.listFiles()?.forEach { it.delete() }
        val first = uris.first()
        val isPdf = context.contentResolver.getType(first)?.contains("pdf") == true || first.toString().endsWith(".pdf", true)
        val progress: (Int, Int) -> Unit = { i, n -> onStatus("Đang nhận dạng chữ trang $i/$n…") }
        val doc = if (isPdf) {
            val tmp = File(context.cacheDir, "import_src.pdf")
            context.contentResolver.openInputStream(first)?.use { input -> tmp.outputStream().use { input.copyTo(it) } }
                ?: error("Không đọc được file đã chọn")
            try {
                buildDoc(title, cloud, progress) { recognizer, consumer ->
                    renderPdf(tmp) { i, n, bmp -> withUpright(recognizer, bmp) { consumer(i, n, it) } }
                }
            } finally {
                tmp.delete()
            }
        } else {
            buildDoc(title, cloud, progress) { recognizer, consumer ->
                uris.mapIndexed { i, uri ->
                    val bmp = decodeImage(uri) ?: error("Không đọc được ảnh ${i + 1}")
                    try { withUpright(recognizer, bmp) { consumer(i, uris.size, it) } } finally { bmp.recycle() }
                }
            }
        }
        translateAndWrite(doc, safeName(title), engine, bilingual, output, onStatus)
    }

    /**
     * [bilingual] = true (bản 0.7): GIỮ nguyên đoạn/ô gốc, chèn bản dịch (in nghiêng) ngay sau thay vì
     * thay thế chữ — xem [Translation.applyBilingual]. Tên file thêm hậu tố khác để phân biệt.
     */
    private suspend fun translateAndWrite(doc: DocModel, base: String, engine: TranslationEngine, bilingual: Boolean, output: ExportFormat, onStatus: (String) -> Unit): File {
        val items = Translation.collect(doc)
        if (items.isEmpty()) {
            lastNotice = "Tài liệu đã là tiếng Việt (hoặc không có chữ cần dịch) — xuất nguyên văn."
        }
        val langs = items.groupBy { it.lang }.entries.sortedByDescending { e -> e.value.sumOf { it.text.length } }
            .joinToString(", ") { Lang.displayName(it.key) }
        val translated = if (items.isEmpty()) emptyMap() else engine.translate(items, "ngôn ngữ nguồn: $langs; tiêu đề: ${doc.title}") { i, n ->
            onStatus("${engine.label} đang dịch ($langs → Tiếng Việt) $i/$n…")
        }
        val missing = items.count { translated[it.id].isNullOrBlank() }
        if (items.isNotEmpty() && missing > 0) lastNotice = "Còn $missing đoạn chưa dịch được (giữ nguyên văn gốc)."
        val translatedDoc = if (bilingual) Translation.applyBilingual(doc, translated) else Translation.apply(doc, translated)
        return writeDoc(translatedDoc, base + (if (bilingual) "_SongNgu" else "_TiengViet"), output)
    }

    private fun <T> forEachMaster(masters: List<File>, consumer: (Int, Int, Bitmap) -> T): List<T> =
        masters.mapIndexed { i, f ->
            val bmp = BitmapFactory.decodeFile(f.absolutePath) ?: error("Không đọc được ảnh trang ${i + 1}")
            try { consumer(i, masters.size, bmp) } finally { bmp.recycle() }
        }

    /** Trang PDF/ảnh import có thể nằm ngang/ngược → xoay đúng chiều đọc trước khi phân tích. */
    private fun <T> withUpright(recognizer: TextRecognizer, bmp: Bitmap, block: (Bitmap) -> T): T {
        val rotation = runCatching {
            val m = Mat()
            Utils.bitmapToMat(bmp, m)
            try { runBlocking { OrientationDetector.detect(recognizer, m) } } finally { m.release() }
        }.getOrDefault(0)
        if (rotation == 0) return block(bmp)
        val rotated = Bitmap.createBitmap(bmp, 0, 0, bmp.width, bmp.height, Matrix().apply { postRotate(rotation.toFloat()) }, true)
        try { return block(rotated) } finally { if (rotated !== bmp) rotated.recycle() }
    }

    /** Dựng từng trang PDF thành ảnh trắng nền (~200 DPI, cạnh dài ≤ 2339 px) rồi giải phóng ngay. */
    private fun <T> renderPdf(pdf: File, consumer: (Int, Int, Bitmap) -> T): List<T> {
        val results = ArrayList<T>()
        ParcelFileDescriptor.open(pdf, ParcelFileDescriptor.MODE_READ_ONLY).use { pfd ->
            PdfRenderer(pfd).use { renderer ->
                val total = renderer.pageCount
                for (i in 0 until total) {
                    renderer.openPage(i).use { page ->
                        val scale = 200f / 72f
                        var w = (page.width * scale).toInt()
                        var h = (page.height * scale).toInt()
                        val maxSide = 2339
                        if (maxOf(w, h) > maxSide) {
                            val k = maxSide.toFloat() / maxOf(w, h)
                            w = (w * k).toInt(); h = (h * k).toInt()
                        }
                        val bmp = Bitmap.createBitmap(w.coerceAtLeast(1), h.coerceAtLeast(1), Bitmap.Config.ARGB_8888)
                        bmp.eraseColor(Color.WHITE)
                        page.render(bmp, null, null, PdfRenderer.Page.RENDER_MODE_FOR_PRINT)
                        try {
                            results.add(consumer(i, total, bmp))
                        } finally {
                            bmp.recycle()
                        }
                    }
                }
            }
        }
        return results
    }

    private fun decodeImage(uri: Uri): Bitmap? {
        val resolver = context.contentResolver
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        resolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
        var sample = 1
        while (maxOf(bounds.outWidth, bounds.outHeight) / (sample * 2) >= 2000) sample *= 2
        val opts = BitmapFactory.Options().apply { inSampleSize = sample }
        return resolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, opts) }
    }

    private fun safeName(title: String): String =
        title.replace(Regex("[\\\\/:*?\"<>|]"), "_").trim().ifBlank { "ScanX" }.take(80)

    companion object {
        /** Số trang gộp trong 1 lần gọi AI Cloud (bản 0.7) — cân bằng giữa giảm số lần gọi mạng và giữ
         *  prompt/kích thước ảnh đính kèm vừa phải để AI đọc chính xác từng trang. */
        private const val BATCH_SIZE = 3
    }
}
