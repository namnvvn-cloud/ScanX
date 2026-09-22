package com.scanx.app.scan

import android.graphics.Bitmap
import android.graphics.PointF
import org.opencv.android.Utils
import org.opencv.core.Core
import org.opencv.core.Mat
import org.opencv.core.MatOfPoint2f
import org.opencv.core.Point
import org.opencv.core.Size
import org.opencv.imgproc.Imgproc
import kotlin.math.max
import kotlin.math.sqrt

/**
 * Làm phẳng ảnh chụp nghiêng theo 4 góc tài liệu đã phát hiện (perspective transform), sau đó
 * tăng cường độ tương phản bằng CLAHE trên kênh sáng (Y trong YCrCb) để chữ rõ nét hơn — giống
 * bước "Auto Enhance" của Scanner Pro/Adobe Scan chạy ngay sau khi chụp, không cần thao tác thêm.
 */
object PerspectiveTransformer {

    /** [normalizedCorners] theo thứ tự top-left, top-right, bottom-right, bottom-left, giá trị [0,1]. */
    fun warpAndEnhance(source: Bitmap, normalizedCorners: List<PointF>): Bitmap {
        val src = Mat()
        Utils.bitmapToMat(source, src)

        val w = source.width.toDouble()
        val h = source.height.toDouble()
        val corners = normalizedCorners.map { Point(it.x * w, it.y * h) }
        val tl = corners[0]
        val tr = corners[1]
        val br = corners[2]
        val bl = corners[3]

        val widthTop = distance(tl, tr)
        val widthBottom = distance(bl, br)
        val outWidth = max(widthTop, widthBottom).toInt().coerceAtLeast(1)

        val heightLeft = distance(tl, bl)
        val heightRight = distance(tr, br)
        val outHeight = max(heightLeft, heightRight).toInt().coerceAtLeast(1)

        val srcQuad = MatOfPoint2f(tl, tr, br, bl)
        val dstQuad = MatOfPoint2f(
            Point(0.0, 0.0),
            Point((outWidth - 1).toDouble(), 0.0),
            Point((outWidth - 1).toDouble(), (outHeight - 1).toDouble()),
            Point(0.0, (outHeight - 1).toDouble()),
        )

        val transform = Imgproc.getPerspectiveTransform(srcQuad, dstQuad)
        val warped = Mat()
        Imgproc.warpPerspective(src, warped, transform, Size(outWidth.toDouble(), outHeight.toDouble()))

        val enhanced = enhance(warped)
        val result = Bitmap.createBitmap(enhanced.cols(), enhanced.rows(), Bitmap.Config.ARGB_8888)
        Utils.matToBitmap(enhanced, result)

        src.release()
        transform.release()
        warped.release()
        enhanced.release()
        srcQuad.release()
        dstQuad.release()
        return result
    }

    /** Dùng khi không phát hiện được góc (chụp thủ công không có khung nhận diện): giữ nguyên khung hình, chỉ tăng cường. */
    fun enhanceOnly(source: Bitmap): Bitmap {
        val src = Mat()
        Utils.bitmapToMat(source, src)
        val enhanced = enhance(src)
        val result = Bitmap.createBitmap(enhanced.cols(), enhanced.rows(), Bitmap.Config.ARGB_8888)
        Utils.matToBitmap(enhanced, result)
        src.release()
        enhanced.release()
        return result
    }

    private fun enhance(input: Mat): Mat {
        val rgb = Mat()
        Imgproc.cvtColor(input, rgb, Imgproc.COLOR_RGBA2RGB)
        val ycrcb = Mat()
        Imgproc.cvtColor(rgb, ycrcb, Imgproc.COLOR_RGB2YCrCb)
        rgb.release()

        val channels = mutableListOf<Mat>()
        Core.split(ycrcb, channels)
        val clahe = Imgproc.createCLAHE(2.0, Size(8.0, 8.0))
        clahe.apply(channels[0], channels[0])
        Core.merge(channels, ycrcb)

        val result = Mat()
        Imgproc.cvtColor(ycrcb, result, Imgproc.COLOR_YCrCb2RGB)
        ycrcb.release()
        channels.forEach { it.release() }
        return result
    }

    private fun distance(a: Point, b: Point): Double {
        val dx = a.x - b.x
        val dy = a.y - b.y
        return sqrt(dx * dx + dy * dy)
    }
}
