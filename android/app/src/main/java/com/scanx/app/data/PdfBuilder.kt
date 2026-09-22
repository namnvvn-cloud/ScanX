package com.scanx.app.data

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import com.scanx.app.scan.ScanFilters
import java.io.File
import java.io.FileOutputStream
import kotlin.math.abs

/**
 * Ghép các trang master (ảnh màu đã làm phẳng, lưu JPEG) thành PDF theo [PdfExportMode] bằng
 * [ScanPdfWriter]. Mỗi trang được giải mã → lọc → mã hoá → ghi → giải phóng ngay, nên tài liệu
 * nhiều trang không tốn RAM.
 *
 * Khổ trang PDF: tỉ lệ gần A4 (±3%) → đúng 595×842 pt (dọc/ngang); gần Letter → 612×792 pt; khác
 * (hoá đơn, thẻ…) → cạnh dài 842 pt, cạnh kia theo tỉ lệ ảnh. In ra đúng khổ giấy thật.
 */
object PdfBuilder {

    fun buildPdf(
        masters: List<File>,
        outFile: File,
        mode: PdfExportMode,
        title: String,
        textLayers: List<List<PdfTextLine>> = emptyList(),
    ) {
        require(masters.isNotEmpty()) { "Không có trang nào để tạo PDF" }
        outFile.parentFile?.mkdirs()
        val tmp = File(outFile.parentFile, outFile.name + ".tmp")
        FileOutputStream(tmp).use { out ->
            ScanPdfWriter.write(out, masters.size, title) { i ->
                val bmp = BitmapFactory.decodeFile(masters[i].absolutePath)
                    ?: error("Không đọc được ảnh trang ${i + 1}")
                try {
                    val (wPt, hPt) = pageSizePt(bmp.width, bmp.height)
                    PdfPageSpec(wPt, hPt, ScanFilters.encodeForPdf(bmp, mode), textLayers.getOrNull(i).orEmpty())
                } finally {
                    bmp.recycle()
                }
            }
        }
        if (outFile.exists()) outFile.delete()
        if (!tmp.renameTo(outFile)) {
            tmp.copyTo(outFile, overwrite = true)
            tmp.delete()
        }
    }

    fun pageSizePt(w: Int, h: Int): Pair<Float, Float> {
        val portrait = h >= w
        val ratio = if (portrait) h.toFloat() / w else w.toFloat() / h
        val (shortPt, longPt) = when {
            abs(ratio - 1.4142f) / 1.4142f < 0.03f -> 595f to 842f
            abs(ratio - 1.2941f) / 1.2941f < 0.03f -> 612f to 792f
            else -> 842f / ratio to 842f
        }
        return if (portrait) shortPt to longPt else longPt to shortPt
    }

    /** Ảnh thu nhỏ trang đầu (theo chế độ hiển thị) cho lưới My Scans. */
    fun saveThumbnail(firstMaster: File, mode: PdfExportMode, outFile: File) {
        outFile.parentFile?.mkdirs()
        val opts = BitmapFactory.Options().apply { inSampleSize = 2 }
        val src = BitmapFactory.decodeFile(firstMaster.absolutePath, opts) ?: return
        val thumb = ScanFilters.renderBitmap(src, mode, 480)
        src.recycle()
        FileOutputStream(outFile).use { out -> thumb.compress(Bitmap.CompressFormat.JPEG, 85, out) }
        thumb.recycle()
    }
}
