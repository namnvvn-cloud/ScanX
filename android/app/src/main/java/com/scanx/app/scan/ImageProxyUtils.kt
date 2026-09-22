package com.scanx.app.scan

import android.graphics.Bitmap
import android.graphics.Matrix
import androidx.camera.core.ImageProxy
import org.opencv.android.Utils
import org.opencv.core.Core
import org.opencv.core.CvType
import org.opencv.core.Mat
import org.opencv.core.Size
import org.opencv.imgproc.Imgproc

/**
 * Chuyển đổi khung hình CameraX cho pipeline quét.
 *
 * - Khung phân tích: yêu cầu CameraX xuất sẵn RGBA_8888 (chuyển YUV→RGB bằng libyuv native, nhanh
 *   hơn hẳn vòng lặp Kotlin cũ), thu nhỏ trước rồi mới đổi màu/xoay → chỉ tốn ~2–4 ms mỗi khung.
 * - Ảnh chụp (JPEG từ ImageCapture): decode + scale + xoay đứng trong 1 lần tạo Bitmap để tiết kiệm RAM.
 */
object ImageProxyUtils {

    /**
     * Khung RGBA_8888 → Mat BGR (CV_8UC3) đã xoay đứng, cạnh dài tối đa [maxSide].
     * BGR vì model DocAligner được huấn luyện với ảnh đọc bằng OpenCV (BGR): kiểm thử trên ảnh thật,
     * đưa RGB vào có góc tụt độ tin cậy xuống 0,35 trong khi BGR giữ ≥ 0,87 ở mọi độ phân giải.
     */
    fun rgbaToUprightBgr(image: ImageProxy, maxSide: Int = 640): Mat {
        val width = image.width
        val height = image.height
        val plane = image.planes[0]
        val buffer = plane.buffer
        val rowStride = plane.rowStride
        val rowBytes = width * 4
        val bytes = ByteArray(rowBytes * height)
        buffer.rewind()
        if (rowStride == rowBytes) {
            buffer.get(bytes, 0, bytes.size)
        } else {
            for (row in 0 until height) {
                buffer.position(row * rowStride)
                buffer.get(bytes, row * rowBytes, rowBytes)
            }
        }
        val rgba = Mat(height, width, CvType.CV_8UC4)
        rgba.put(0, 0, bytes)

        val scale = maxSide.toDouble() / maxOf(width, height)
        val small = if (scale < 1.0) {
            Mat().also {
                Imgproc.resize(rgba, it, Size(width * scale, height * scale), 0.0, 0.0, Imgproc.INTER_AREA)
                rgba.release()
            }
        } else {
            rgba
        }

        val bgr = Mat()
        Imgproc.cvtColor(small, bgr, Imgproc.COLOR_RGBA2BGR)
        small.release()
        return rotateUpright(bgr, image.imageInfo.rotationDegrees)
    }

    /** Ảnh chụp từ ImageCapture → Bitmap đứng, cạnh dài tối đa [maxSide]. */
    fun capturedToUprightBitmap(image: ImageProxy, maxSide: Int): Bitmap {
        val raw = image.toBitmap()
        val rotation = image.imageInfo.rotationDegrees
        val scale = (maxSide.toFloat() / maxOf(raw.width, raw.height)).coerceAtMost(1f)
        if (rotation == 0 && scale >= 1f) return raw
        val matrix = Matrix().apply {
            postScale(scale, scale)
            postRotate(rotation.toFloat())
        }
        val out = Bitmap.createBitmap(raw, 0, 0, raw.width, raw.height, matrix, true)
        if (out !== raw) raw.recycle()
        return out
    }

    /**
     * Bitmap → Mat BGR thu nhỏ (chạy AI lại trên ảnh chụp để tinh chỉnh góc). Không thu nhỏ dưới
     * ~640 px: kiểm thử cho thấy ảnh quá nhỏ làm model mất độ tin cậy ở góc.
     */
    fun bitmapToSmallBgr(bitmap: Bitmap, maxSide: Int = 960): Mat {
        val rgba = Mat()
        Utils.bitmapToMat(bitmap, rgba)
        val scale = maxSide.toDouble() / maxOf(bitmap.width, bitmap.height)
        if (scale < 1.0) {
            val small = Mat()
            Imgproc.resize(rgba, small, Size(bitmap.width * scale, bitmap.height * scale), 0.0, 0.0, Imgproc.INTER_AREA)
            rgba.release()
            val bgr = Mat()
            Imgproc.cvtColor(small, bgr, Imgproc.COLOR_RGBA2BGR)
            small.release()
            return bgr
        }
        val bgr = Mat()
        Imgproc.cvtColor(rgba, bgr, Imgproc.COLOR_RGBA2BGR)
        rgba.release()
        return bgr
    }

    private fun rotateUpright(src: Mat, degrees: Int): Mat {
        val code = when (degrees) {
            90 -> Core.ROTATE_90_CLOCKWISE
            180 -> Core.ROTATE_180
            270 -> Core.ROTATE_90_COUNTERCLOCKWISE
            else -> return src
        }
        val dst = Mat()
        Core.rotate(src, dst, code)
        src.release()
        return dst
    }
}
