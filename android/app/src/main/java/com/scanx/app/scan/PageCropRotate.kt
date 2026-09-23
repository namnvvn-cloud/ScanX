package com.scanx.app.scan

import android.graphics.Bitmap
import android.graphics.PointF
import org.opencv.android.Utils
import org.opencv.core.Core
import org.opencv.core.Mat
import org.opencv.core.MatOfPoint2f
import org.opencv.core.Point
import org.opencv.core.Scalar
import org.opencv.core.Size
import org.opencv.imgproc.Imgproc
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.sin

/**
 * "Cắt và xoay" (bản 0.8, màn "Chỉnh sửa trang") — chỉnh TIẾP trên ảnh master ĐÃ làm phẳng, đúng
 * theo quyết định đã chốt: không giữ lại ảnh gốc trước khi làm phẳng (tránh tốn thêm bộ nhớ/độ phức
 * tạp lưu trữ cho 1 chỉnh sửa phụ, ít dùng). Dùng khi khung tự động lúc quét lấy thừa/thiếu mép giấy,
 * hoặc ảnh còn nghiêng nhẹ cần chỉnh lại. Nền mới lộ ra sau khi xoay luôn tô trắng (khớp màu giấy).
 */
object PageCropRotate {

    /**
     * Dò lại 4 góc tài liệu trên ảnh master hiện tại (đã làm phẳng, có thể đã xoay) — dùng khi bấm
     * "Tự động" trong tab Cắt và xoay. Trả về null nếu không thấy rõ tài liệu (ảnh đã sát mép hẳn,
     * hoặc nội dung không phải trang giấy) — UI tự quay về toàn khung ảnh khi đó.
     */
    fun autoDetectQuad(bitmap: Bitmap): List<PointF>? {
        val rgba = Mat()
        Utils.bitmapToMat(bitmap, rgba)
        try {
            val bgr = Mat()
            Imgproc.cvtColor(rgba, bgr, Imgproc.COLOR_RGBA2BGR)
            try {
                val k = 640.0 / max(bgr.cols(), bgr.rows())
                if (k < 1.0) Imgproc.resize(bgr, bgr, Size(bgr.cols() * k, bgr.rows() * k), 0.0, 0.0, Imgproc.INTER_AREA)
                return AiDocumentDetector.detectRefine(bgr)?.points
            } finally {
                bgr.release()
            }
        } finally {
            rgba.release()
        }
    }

    /**
     * Xoay tự do (thanh trượt "nghiêng", thường ±45°) quanh tâm ảnh, mở rộng khung để không mất góc.
     * [angleDeg] > 0 = xoay thuận chiều kim đồng hồ. Nền mới lộ ra tô trắng.
     */
    fun rotateFree(bitmap: Bitmap, angleDeg: Float): Bitmap {
        if (abs(angleDeg) < 0.05f) return bitmap.copy(bitmap.config ?: Bitmap.Config.ARGB_8888, false)
        val src = Mat()
        Utils.bitmapToMat(bitmap, src)
        try {
            val w = src.cols(); val h = src.rows()
            val rad = Math.toRadians(angleDeg.toDouble())
            val newW = (abs(w * cos(rad)) + abs(h * sin(rad))).toInt().coerceAtLeast(1)
            val newH = (abs(w * sin(rad)) + abs(h * cos(rad))).toInt().coerceAtLeast(1)
            val m = Imgproc.getRotationMatrix2D(Point(w / 2.0, h / 2.0), -angleDeg.toDouble(), 1.0)
            try {
                // Dịch tâm xoay sang tâm khung mới (đã mở rộng) để ảnh không bị cắt góc.
                m.put(0, 2, m.get(0, 2)[0] + (newW - w) / 2.0)
                m.put(1, 2, m.get(1, 2)[0] + (newH - h) / 2.0)
                val dst = Mat()
                Imgproc.warpAffine(
                    src, dst, m, Size(newW.toDouble(), newH.toDouble()), Imgproc.INTER_CUBIC,
                    Core.BORDER_CONSTANT, Scalar(255.0, 255.0, 255.0, 255.0),
                )
                try {
                    val out = Bitmap.createBitmap(dst.cols(), dst.rows(), Bitmap.Config.ARGB_8888)
                    Utils.matToBitmap(dst, out)
                    return out
                } finally {
                    dst.release()
                }
            } finally {
                m.release()
            }
        } finally {
            src.release()
        }
    }

    /** Xoay nhanh theo bội số 90° (1 = 90°, 2 = 180°, 3 = 270°, chiều kim đồng hồ). */
    fun rotate90(bitmap: Bitmap, quarterTurns: Int): Bitmap {
        val q = ((quarterTurns % 4) + 4) % 4
        if (q == 0) return bitmap.copy(bitmap.config ?: Bitmap.Config.ARGB_8888, false)
        val src = Mat()
        Utils.bitmapToMat(bitmap, src)
        try {
            val dst = Mat()
            val code = when (q) {
                1 -> Core.ROTATE_90_CLOCKWISE
                2 -> Core.ROTATE_180
                else -> Core.ROTATE_90_COUNTERCLOCKWISE
            }
            Core.rotate(src, dst, code)
            try {
                val out = Bitmap.createBitmap(dst.cols(), dst.rows(), Bitmap.Config.ARGB_8888)
                Utils.matToBitmap(dst, out)
                return out
            } finally {
                dst.release()
            }
        } finally {
            src.release()
        }
    }

    /**
     * Cắt theo tứ giác TL/TR/BR/BL — toạ độ chuẩn hoá [0,1] theo đúng [bitmap] hiện tại (đã xoay nếu
     * có). Dùng lại [PageGeometry] (như lúc quét) để khôi phục đúng tỉ lệ hình chữ nhật thật của
     * trang thay vì lấy thẳng cạnh dài nhất — tứ giác người dùng kéo hơi méo vẫn ra khung thẳng đúng.
     */
    fun cropQuad(bitmap: Bitmap, normalizedCorners: List<PointF>, maxSide: Int = 3548): Bitmap {
        val src = Mat()
        Utils.bitmapToMat(bitmap, src)
        try {
            val w = bitmap.width; val h = bitmap.height
            val corners = normalizedCorners.map { doubleArrayOf((it.x * w).toDouble(), (it.y * h).toDouble()) }
            val (outW, outH) = PageGeometry.outputSize(corners, w, h, maxSide)
            val srcQuad = MatOfPoint2f(*corners.map { Point(it[0], it[1]) }.toTypedArray())
            val dstQuad = MatOfPoint2f(
                Point(0.0, 0.0), Point(outW - 1.0, 0.0), Point(outW - 1.0, outH - 1.0), Point(0.0, outH - 1.0),
            )
            try {
                val transform = Imgproc.getPerspectiveTransform(srcQuad, dstQuad)
                try {
                    val dst = Mat()
                    Imgproc.warpPerspective(src, dst, transform, Size(outW.toDouble(), outH.toDouble()), Imgproc.INTER_CUBIC)
                    try {
                        val out = Bitmap.createBitmap(dst.cols(), dst.rows(), Bitmap.Config.ARGB_8888)
                        Utils.matToBitmap(dst, out)
                        return out
                    } finally {
                        dst.release()
                    }
                } finally {
                    transform.release()
                }
            } finally {
                srcQuad.release(); dstQuad.release()
            }
        } finally {
            src.release()
        }
    }

    /** 4 góc mặc định (toàn khung, lùi vào 1,5% mỗi cạnh để nhìn rõ tay kéo góc trên UI). */
    fun fullFrameQuad(): List<PointF> {
        val m = 0.015f
        return listOf(PointF(m, m), PointF(1f - m, m), PointF(1f - m, 1f - m), PointF(m, 1f - m))
    }
}
