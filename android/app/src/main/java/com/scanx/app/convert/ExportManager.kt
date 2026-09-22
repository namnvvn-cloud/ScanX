package com.scanx.app.convert

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.pdf.PdfRenderer
import android.net.Uri
import android.os.ParcelFileDescriptor
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

enum class ExportFormat(val ext: String, val mime: String, val label: String) {
    PDF("pdf", "application/pdf", "PDF"),
    DOCX("docx", "application/vnd.openxmlformats-officedocument.wordprocessingml.document", "Word (.docx)"),
    XLSX("xlsx", "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet", "Excel (.xlsx)"),
    PPTX("pptx", "application/vnd.openxmlformats-officedocument.presentationml.presentation", "PowerPoint (.pptx)"),
    JPG("jpg", "image/jpeg", "Ảnh JPG"),
    TXT("txt", "text/plain", "Văn bản (.txt)"),
}

/**
 * Điều phối xuất / chuyển đổi tài liệu, chạy 100% trên máy (không gửi tài liệu ra ngoài):
 *   nguồn (PDF đã scan, PDF hoặc ảnh import) → dựng ảnh từng trang (PdfRenderer ~200 DPI)
 *   → [PageLayoutExtractor] (ML Kit OCR + OpenCV) → [LayoutAnalyzer] → Docx/Xlsx/PptxWriter.
 * Xử lý tuần tự từng trang và giải phóng ảnh ngay → không tràn RAM với tài liệu nhiều trang.
 */
class ExportManager(private val context: Context) {

    private val exportDir: File get() = File(context.cacheDir, "exports").apply { mkdirs() }

    /** Xuất tài liệu đã scan (file PDF trong app) sang [format]. Trả về các file kết quả (JPG có thể nhiều file). */
    suspend fun exportPdf(pdf: File, title: String, ocrText: String, format: ExportFormat, onProgress: (Int, Int) -> Unit = { _, _ -> }): List<File> =
        withContext(Dispatchers.IO) {
            val base = safeName(title)
            exportDir.listFiles()?.forEach { it.delete() }
            when (format) {
                ExportFormat.PDF -> listOf(File(exportDir, "$base.pdf").also { pdf.copyTo(it, overwrite = true) })
                ExportFormat.TXT -> listOf(File(exportDir, "$base.txt").also { it.writeText(ocrText) })
                ExportFormat.JPG -> renderPdf(pdf) { index, total, bmp ->
                    onProgress(index + 1, total)
                    val f = File(exportDir, if (total > 1) "${base}_trang${index + 1}.jpg" else "$base.jpg")
                    f.outputStream().use { bmp.compress(Bitmap.CompressFormat.JPEG, 92, it) }
                    f
                }
                else -> listOf(convertPages(base, title, format, onProgress) { consumer -> renderPdf(pdf, consumer) })
            }
        }

    /** Công cụ "Chuyển đổi file": PDF hoặc ảnh import từ máy → Word/Excel/PowerPoint. */
    suspend fun convertImported(uris: List<Uri>, title: String, format: ExportFormat, onProgress: (Int, Int) -> Unit = { _, _ -> }): File =
        withContext(Dispatchers.IO) {
            exportDir.listFiles()?.forEach { it.delete() }
            val base = safeName(title)
            val first = uris.first()
            val isPdf = context.contentResolver.getType(first)?.contains("pdf") == true || first.toString().endsWith(".pdf", true)
            if (isPdf) {
                val tmp = File(context.cacheDir, "import_src.pdf")
                context.contentResolver.openInputStream(first)?.use { input -> tmp.outputStream().use { input.copyTo(it) } }
                    ?: error("Không đọc được file đã chọn")
                try {
                    convertPages(base, title, format, onProgress) { consumer -> renderPdf(tmp, consumer) }
                } finally {
                    tmp.delete()
                }
            } else {
                convertPages(base, title, format, onProgress) { consumer ->
                    uris.mapIndexed { i, uri ->
                        val bmp = decodeImage(uri) ?: error("Không đọc được ảnh ${i + 1}")
                        try { consumer(i, uris.size, bmp) } finally { bmp.recycle() }
                    }
                }
            }
        }

    private fun convertPages(
        base: String,
        title: String,
        format: ExportFormat,
        onProgress: (Int, Int) -> Unit,
        source: (consumer: (Int, Int, Bitmap) -> DocPage) -> List<DocPage>,
    ): File {
        val recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
        try {
            val extractor = PageLayoutExtractor(recognizer)
            val pages = source { index, total, bmp ->
                onProgress(index + 1, total)
                val input = kotlinx.coroutines.runBlocking { extractor.extract(bmp) }
                LayoutAnalyzer.analyze(input)
            }
            if (pages.isEmpty()) error("Không có trang nào để chuyển đổi")
            val doc = DocModel(title, pages)
            val out = File(exportDir, "$base.${format.ext}")
            out.outputStream().use { stream ->
                when (format) {
                    ExportFormat.DOCX -> DocxWriter.write(doc, stream)
                    ExportFormat.XLSX -> XlsxWriter.write(doc, stream)
                    ExportFormat.PPTX -> PptxWriter.write(doc, stream)
                    else -> error("Định dạng không hỗ trợ chuyển đổi bố cục: $format")
                }
            }
            return out
        } finally {
            recognizer.close()
        }
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
}
