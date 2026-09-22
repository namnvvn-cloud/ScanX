package com.scanx.app.data

import android.graphics.Bitmap
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.pdf.PdfDocument
import java.io.File
import java.io.FileOutputStream

/**
 * Ghép danh sách ảnh (đã chụp + làm phẳng/tăng nét) thành 1 file PDF, mỗi ảnh 1 trang.
 * Dùng android.graphics.pdf.PdfDocument có sẵn trong Android SDK — không cần thêm thư viện PDF
 * ngoài (giảm rủi ro version, phù hợp triết lý của project: hạn chế dependency khi có thể).
 */
/** 1 dòng chữ OCR (toạ độ pixel trên ảnh trang) dùng làm lớp chữ ẩn cho PDF tìm kiếm được. */
data class TextLayerLine(val text: String, val left: Float, val top: Float, val right: Float, val bottom: Float)

object PdfBuilder {

    /** 72 DPI theo chuẩn PDF point; scale kích thước ảnh (px, thường ~200-300 DPI) về point tương ứng. */
    private const val PDF_DPI = 170.0
    private const val POINTS_PER_INCH = 72.0

    /**
     * [textLayers] (tuỳ chọn, cùng thứ tự với [pages]): vẽ chữ OCR đúng vị trí NẰM DƯỚI ảnh trang → PDF
     * nhìn y hệt bản scan nhưng tìm kiếm / bôi đen copy chữ được (giống Adobe Scan). Vẽ dưới ảnh vì
     * Skia bỏ qua lệnh vẽ chữ trong suốt (alpha 0), còn chữ bị ảnh che vẫn được ghi vào PDF.
     */
    fun buildPdf(pages: List<Bitmap>, outFile: File, textLayers: List<List<TextLayerLine>> = emptyList()) {
        require(pages.isNotEmpty()) { "Không có trang nào để tạo PDF" }
        val document = PdfDocument()
        try {
            pages.forEachIndexed { index, bitmap ->
                val widthPt = (bitmap.width / PDF_DPI * POINTS_PER_INCH).toInt().coerceAtLeast(1)
                val heightPt = (bitmap.height / PDF_DPI * POINTS_PER_INCH).toInt().coerceAtLeast(1)
                val pageInfo = PdfDocument.PageInfo.Builder(widthPt, heightPt, index + 1).create()
                val page = document.startPage(pageInfo)
                // Vẽ ảnh gốc với ma trận co về kích thước trang (point) thay vì co ảnh trước khi vẽ:
                // PdfDocument nhúng nguyên ảnh độ phân giải cao → PDF nét khi phóng to/in ấn
                // (bản cũ co ảnh xuống ~72 DPI nên chữ bị nhoè).
                val sx = widthPt.toFloat() / bitmap.width
                val sy = heightPt.toFloat() / bitmap.height
                textLayers.getOrNull(index)?.let { lines -> drawTextLayer(page.canvas, lines, sx, sy) }
                val matrix = Matrix().apply { setScale(sx, sy) }
                page.canvas.drawBitmap(bitmap, matrix, Paint(Paint.FILTER_BITMAP_FLAG))
                document.finishPage(page)
            }
            outFile.parentFile?.mkdirs()
            FileOutputStream(outFile).use { out -> document.writeTo(out) }
        } finally {
            document.close()
        }
    }

    private fun drawTextLayer(canvas: android.graphics.Canvas, lines: List<TextLayerLine>, sx: Float, sy: Float) {
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = android.graphics.Color.BLACK }
        for (l in lines) {
            val text = l.text.trim()
            if (text.isEmpty()) continue
            val h = (l.bottom - l.top) * sy
            val w = (l.right - l.left) * sx
            if (h <= 0f || w <= 0f) continue
            paint.textScaleX = 1f
            paint.textSize = h * 0.8f
            val measured = paint.measureText(text)
            if (measured > 0f) paint.textScaleX = (w / measured).coerceIn(0.2f, 5f)
            canvas.drawText(text, l.left * sx, l.bottom * sy - h * 0.2f, paint)
        }
    }

    /** Lưu trang đầu tiên làm ảnh thumbnail để hiển thị nhanh trên lưới My Scans, không phải render lại PDF mỗi lần. */
    fun saveThumbnail(firstPage: Bitmap, outFile: File) {
        outFile.parentFile?.mkdirs()
        val maxDimension = 480
        val scale = maxDimension.toFloat() / maxOf(firstPage.width, firstPage.height)
        val thumb = if (scale < 1f) {
            Bitmap.createScaledBitmap(
                firstPage,
                (firstPage.width * scale).toInt().coerceAtLeast(1),
                (firstPage.height * scale).toInt().coerceAtLeast(1),
                true
            )
        } else {
            firstPage
        }
        FileOutputStream(outFile).use { out -> thumb.compress(Bitmap.CompressFormat.JPEG, 85, out) }
        if (thumb !== firstPage) thumb.recycle()
    }
}
