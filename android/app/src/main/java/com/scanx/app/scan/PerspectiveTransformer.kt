package com.scanx.app.scan

import android.graphics.Bitmap
import android.graphics.PointF
import org.opencv.android.Utils
import org.opencv.core.Mat
import org.opencv.core.MatOfPoint2f
import org.opencv.core.Point
import org.opencv.core.Size
import org.opencv.imgproc.Imgproc
import kotlin.math.max

/**
 * Làm phẳng trang chụp nghiêng, cho ra ảnh gốc MÀU (master) đúng tỉ lệ giấy thật:
 *  1. Tinh chỉnh 4 góc AI xuống dưới-pixel trên ảnh độ phân giải cao ([CornerRefiner]).
 *  2. Tính kích thước đầu ra theo tỉ lệ thật khôi phục từ phối cảnh ([PageGeometry]).
 *  3. Nắn phối cảnh bằng nội suy bicubic (nét chữ mượt hơn bilinear khi phóng/thu).
 * Bộ lọc đen trắng/màu áp dụng sau, lúc hiển thị và xuất file ([ScanFilters]).
 */
object PerspectiveTransformer {

    /** [normalizedCorners] TL, TR, BR, BL trong [0,1]. Trả về Mat RGBA master (người gọi release). */
    fun warp(source: Bitmap, normalizedCorners: List<PointF>, maxSide: Int): Mat {
        val src = Mat()
        Utils.bitmapToMat(source, src)
        val w = source.width
        val h = source.height
        var corners = normalizedCorners.map { doubleArrayOf(it.x * w.toDouble(), it.y * h.toDouble()) }
        corners = refineCorners(src, corners)

        val (outW, outH) = PageGeometry.outputSize(corners, w, h, maxSide)
        val srcQuad = MatOfPoint2f(*corners.map { Point(it[0], it[1]) }.toTypedArray())
        val dstQuad = MatOfPoint2f(
            Point(0.0, 0.0),
            Point(outW - 1.0, 0.0),
            Point(outW - 1.0, outH - 1.0),
            Point(0.0, outH - 1.0),
        )
        val transform = Imgproc.getPerspectiveTransform(srcQuad, dstQuad)
        val warped = Mat()
        Imgproc.warpPerspective(src, warped, transform, Size(outW.toDouble(), outH.toDouble()), Imgproc.INTER_CUBIC)
        src.release(); transform.release(); srcQuad.release(); dstQuad.release()
        return warped
    }

    /** Không có khung (chụp tay không nhận diện được): giữ nguyên khung hình, chỉ giới hạn kích thước. */
    fun whole(source: Bitmap, maxSide: Int): Mat {
        val src = Mat()
        Utils.bitmapToMat(source, src)
        val k = maxSide.toDouble() / max(src.cols(), src.rows())
        if (k < 1.0) Imgproc.resize(src, src, Size(src.cols() * k, src.rows() * k), 0.0, 0.0, Imgproc.INTER_AREA)
        return src
    }

    /** Tinh chỉnh góc trên ảnh xám thu về ≤ 1600 px (đủ chính xác, nhanh, ít RAM). */
    private fun refineCorners(rgba: Mat, corners: List<DoubleArray>): List<DoubleArray> {
        return try {
            val k = minOf(1.0, 1600.0 / max(rgba.cols(), rgba.rows()))
            val gray = Mat()
            Imgproc.cvtColor(rgba, gray, Imgproc.COLOR_RGBA2GRAY)
            if (k < 1.0) Imgproc.resize(gray, gray, Size(rgba.cols() * k, rgba.rows() * k), 0.0, 0.0, Imgproc.INTER_AREA)
            Imgproc.GaussianBlur(gray, gray, Size(5.0, 5.0), 0.0)
            val bytes = ByteArray(gray.cols() * gray.rows())
            gray.get(0, 0, bytes)
            val refined = CornerRefiner.refine(bytes, gray.cols(), gray.rows(), corners.map { doubleArrayOf(it[0] * k, it[1] * k) })
            gray.release()
            refined.map { doubleArrayOf(it[0] / k, it[1] / k) }
        } catch (e: Throwable) {
            corners
        }
    }
}
