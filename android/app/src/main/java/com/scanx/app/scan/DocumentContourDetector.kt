package com.scanx.app.scan

import android.graphics.Bitmap
import android.graphics.PointF
import org.opencv.android.Utils
import org.opencv.core.Mat
import org.opencv.core.MatOfPoint
import org.opencv.core.MatOfPoint2f
import org.opencv.core.Point
import org.opencv.core.Size
import org.opencv.imgproc.Imgproc

/**
 * Toạ độ 4 góc tài liệu phát hiện được, thứ tự top-left/top-right/bottom-right/bottom-left,
 * chuẩn hoá về [0,1] theo chiều rộng/cao ảnh gốc — không phụ thuộc độ phân giải khung phân tích,
 * dùng để vừa vẽ overlay vừa map ngược ra ảnh full-res khi crop/làm phẳng.
 */
data class DetectedQuad(val points: List<PointF>, val areaRatio: Float)

/**
 * Phát hiện 4 góc tài liệu trong 1 khung hình bằng OpenCV: Canny edge detection + findContours,
 * chọn contour tứ giác lồi lớn nhất chiếm tối thiểu 15% diện tích khung hình. Cùng họ thuật toán
 * dùng trong CamScanner/Scanner Pro/Adobe Scan — khác với hộp đen ML Kit Document Scanner của
 * Google mà bản trước của app dùng, cho phép tự kiểm soát tốc độ/độ nhạy.
 *
 * Chạy trên ảnh đã downscale nhỏ (tối đa ~640px cạnh dài) để đảm bảo tốc độ real-time trên từng
 * khung camera (Canny + findContours trên ảnh nhỏ chỉ mất vài ms); toạ độ trả về chuẩn hoá [0,1]
 * nên vẫn map đúng ra ảnh full-res khi warp.
 */
object DocumentContourDetector {

    private const val WORK_MAX_DIMENSION = 640
    private const val MIN_AREA_RATIO = 0.15

    fun detect(source: Bitmap): DetectedQuad? {
        val scale = WORK_MAX_DIMENSION.toFloat() / maxOf(source.width, source.height)
        val working = if (scale < 1f) {
            Bitmap.createScaledBitmap(
                source,
                (source.width * scale).toInt().coerceAtLeast(1),
                (source.height * scale).toInt().coerceAtLeast(1),
                true,
            )
        } else {
            source
        }

        val rgba = Mat()
        Utils.bitmapToMat(working, rgba)
        val gray = Mat()
        Imgproc.cvtColor(rgba, gray, Imgproc.COLOR_RGBA2GRAY)
        Imgproc.GaussianBlur(gray, gray, Size(5.0, 5.0), 0.0)
        val edges = Mat()
        Imgproc.Canny(gray, edges, 60.0, 160.0)
        Imgproc.dilate(edges, edges, Mat(), Point(-1.0, -1.0), 2)

        val contours = mutableListOf<MatOfPoint>()
        val hierarchy = Mat()
        Imgproc.findContours(edges, contours, hierarchy, Imgproc.RETR_LIST, Imgproc.CHAIN_APPROX_SIMPLE)

        val imageArea = working.width.toDouble() * working.height.toDouble()
        var bestPoints: Array<Point>? = null
        var bestArea = 0.0

        for (contour in contours) {
            val area = Imgproc.contourArea(contour)
            if (area < imageArea * MIN_AREA_RATIO || area <= bestArea) continue
            val contour2f = MatOfPoint2f(*contour.toArray())
            val peri = Imgproc.arcLength(contour2f, true)
            val approx = MatOfPoint2f()
            Imgproc.approxPolyDP(contour2f, approx, 0.02 * peri, true)
            val approxPoints = approx.toArray()
            if (approxPoints.size == 4) {
                val ordered = orderPoints(approxPoints)
                if (Imgproc.isContourConvex(MatOfPoint(*ordered))) {
                    bestPoints = ordered
                    bestArea = area
                }
            }
            contour2f.release()
            approx.release()
        }

        val quad = bestPoints
        val result = if (quad != null) {
            val points = quad.map { PointF((it.x / working.width).toFloat(), (it.y / working.height).toFloat()) }
            DetectedQuad(points, (bestArea / imageArea).toFloat())
        } else {
            null
        }

        rgba.release()
        gray.release()
        edges.release()
        hierarchy.release()
        contours.forEach { it.release() }
        if (working !== source) working.recycle()

        return result
    }

    /** Sắp xếp 4 điểm theo thứ tự top-left, top-right, bottom-right, bottom-left. */
    private fun orderPoints(pts: Array<Point>): Array<Point> {
        val idxBySum = pts.indices.sortedBy { pts[it].x + pts[it].y }
        val topLeftIdx = idxBySum.first()
        val bottomRightIdx = idxBySum.last()
        val remaining = pts.indices.filter { it != topLeftIdx && it != bottomRightIdx }
        val topRightIdx = remaining.minByOrNull { pts[it].y - pts[it].x } ?: remaining[0]
        val bottomLeftIdx = remaining.first { it != topRightIdx }
        return arrayOf(pts[topLeftIdx], pts[topRightIdx], pts[bottomRightIdx], pts[bottomLeftIdx])
    }
}
