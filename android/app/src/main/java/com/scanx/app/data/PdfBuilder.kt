package com.scanx.app.data

import android.graphics.Bitmap
import android.graphics.pdf.PdfDocument
import java.io.File
import java.io.FileOutputStream

/**
 * Ghép danh sách ảnh (đã chụp + làm phẳng/tăng nét) thành 1 file PDF, mỗi ảnh 1 trang.
 * Dùng android.graphics.pdf.PdfDocument có sẵn trong Android SDK — không cần thêm thư viện PDF
 * ngoài (giảm rủi ro version, phù hợp triết lý của project: hạn chế dependency khi có thể).
 */
object PdfBuilder {

    /** 72 DPI theo chuẩn PDF point; scale kích thước ảnh (px, thường ~200-300 DPI) về point tương ứng. */
    private const val PDF_DPI = 150.0
    private const val POINTS_PER_INCH = 72.0

    fun buildPdf(pages: List<Bitmap>, outFile: File) {
        require(pages.isNotEmpty()) { "Không có trang nào để tạo PDF" }
        val document = PdfDocument()
        try {
            pages.forEachIndexed { index, bitmap ->
                val widthPt = (bitmap.width / PDF_DPI * POINTS_PER_INCH).toInt().coerceAtLeast(1)
                val heightPt = (bitmap.height / PDF_DPI * POINTS_PER_INCH).toInt().coerceAtLeast(1)
                val pageInfo = PdfDocument.PageInfo.Builder(widthPt, heightPt, index + 1).create()
                val page = document.startPage(pageInfo)
                val scaledBitmap = if (bitmap.width != widthPt || bitmap.height != heightPt) {
                    Bitmap.createScaledBitmap(bitmap, widthPt, heightPt, true)
                } else {
                    bitmap
                }
                page.canvas.drawBitmap(scaledBitmap, 0f, 0f, null)
                document.finishPage(page)
                if (scaledBitmap !== bitmap) scaledBitmap.recycle()
            }
            outFile.parentFile?.mkdirs()
            FileOutputStream(outFile).use { out -> document.writeTo(out) }
        } finally {
            document.close()
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
