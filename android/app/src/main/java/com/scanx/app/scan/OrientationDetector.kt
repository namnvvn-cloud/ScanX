package com.scanx.app.scan

import android.graphics.Bitmap
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.Text
import com.google.mlkit.vision.text.TextRecognizer
import com.scanx.app.util.awaitTask
import org.opencv.android.Utils
import org.opencv.core.Core
import org.opencv.core.Mat
import org.opencv.core.Size
import org.opencv.imgproc.Imgproc
import kotlin.math.abs
import kotlin.math.max

/**
 * Tự xoay trang về đúng chiều đọc (0/90/180/270°) — ví dụ tờ khổ ngang chụp bằng điện thoại cầm dọc.
 *
 *  1. Phân loại nhanh "dòng chữ nằm ngang hay dọc" bằng hình chiếu: bỏ đường kẻ dài, nhoè nét theo
 *     từng hướng, đo độ sắc của biên dạng hàng/cột. Kiểm thử trên trang thật: trang nằm nghiêng 90°
 *     cho điểm dọc gấp 2,5–4 lần điểm ngang; văn bản in đứng cho điểm ngang gấp 4 lần.
 *  2. Phân biệt 2 chiều ngược nhau (0 vs 180, 90 vs 270) bằng OCR ML Kit trên 1 ảnh nhỏ, cho ML Kit
 *     tự xoay theo từng ứng viên: chiều đúng cho nhiều chữ đọc thẳng (góc dòng ≈ 0°) với độ tin cậy cao.
 *
 * Trả về số độ cần xoay THEO CHIỀU KIM ĐỒNG HỒ.
 */
object OrientationDetector {

    suspend fun detect(recognizer: TextRecognizer, rgba: Mat): Int {
        val k = 1280.0 / max(rgba.cols(), rgba.rows())
        val small = Mat()
        Imgproc.resize(rgba, small, Size(rgba.cols() * k, rgba.rows() * k), 0.0, 0.0, Imgproc.INTER_AREA)
        val gray = ScanFilters.bwHq(small)
        val (hScore, vScore) = projectionScores(gray)
        val vertical = vScore > hScore * 1.6f
        val bmp = Bitmap.createBitmap(small.cols(), small.rows(), Bitmap.Config.ARGB_8888)
        val shown = Mat()
        Imgproc.cvtColor(gray, shown, Imgproc.COLOR_GRAY2RGBA)
        Utils.matToBitmap(shown, bmp)
        small.release(); gray.release(); shown.release()
        try {
            return if (vertical) {
                val s90 = ocrScore(recognizer, bmp, 90)
                val s270 = ocrScore(recognizer, bmp, 270)
                when {
                    s90 <= 0f && s270 <= 0f -> 0
                    s270 > s90 -> 270
                    else -> 90
                }
            } else {
                val s0 = ocrScore(recognizer, bmp, 0)
                if (s0 >= 40f) return 0
                val s180 = ocrScore(recognizer, bmp, 180)
                if (s180 > s0 * 1.3f && s180 >= 10f) 180 else 0
            }
        } finally {
            bmp.recycle()
        }
    }

    /** Tổng (độ tin cậy × số ký tự) của các dòng đọc thẳng khi xoay [rotation]° theo chiều kim đồng hồ. */
    private suspend fun ocrScore(recognizer: TextRecognizer, bmp: Bitmap, rotation: Int): Float {
        val text: Text = runCatching { recognizer.process(InputImage.fromBitmap(bmp, rotation)).awaitTask() }.getOrNull() ?: return 0f
        var score = 0f
        for (block in text.textBlocks) for (line in block.lines) {
            if (abs(line.angle) > 25f) continue
            val chars = line.text.count { it.isLetterOrDigit() }
            score += line.confidence * chars
        }
        return score
    }

    /** (điểm dòng ngang, điểm dòng dọc) trên ảnh xám nền trắng. */
    private fun projectionScores(gray: Mat): Pair<Float, Float> {
        val w0 = gray.cols()
        val h0 = gray.rows()
        val half = Mat()
        Imgproc.resize(gray, half, Size(w0 / 2.0, h0 / 2.0), 0.0, 0.0, Imgproc.INTER_AREA)
        val w = half.cols()
        val h = half.rows()
        val ink = Mat()
        Imgproc.threshold(half, ink, 150.0, 255.0, Imgproc.THRESH_BINARY_INV)
        // Bỏ đường kẻ bảng dài để chỉ còn chữ quyết định hướng.
        val hl = Mat()
        val vl = Mat()
        Imgproc.morphologyEx(ink, hl, Imgproc.MORPH_OPEN, Imgproc.getStructuringElement(Imgproc.MORPH_RECT, Size(max(10, w / 20).toDouble(), 1.0)))
        Imgproc.morphologyEx(ink, vl, Imgproc.MORPH_OPEN, Imgproc.getStructuringElement(Imgproc.MORPH_RECT, Size(1.0, max(10, h / 20).toDouble())))
        Core.subtract(ink, hl, ink)
        Core.subtract(ink, vl, ink)
        val kk = max(3, w / 60).toDouble()
        val th = Mat()
        val tv = Mat()
        Imgproc.dilate(ink, th, Imgproc.getStructuringElement(Imgproc.MORPH_RECT, Size(kk, 1.0)))
        Imgproc.dilate(ink, tv, Imgproc.getStructuringElement(Imgproc.MORPH_RECT, Size(1.0, kk)))
        val hScore = profileSharpness(th, rows = true)
        val vScore = profileSharpness(tv, rows = false)
        listOf(half, ink, hl, vl, th, tv).forEach { it.release() }
        return hScore to vScore
    }

    private fun profileSharpness(m: Mat, rows: Boolean): Float {
        val w = m.cols()
        val h = m.rows()
        val bytes = ByteArray(w * h)
        m.get(0, 0, bytes)
        val n = if (rows) h else w
        val prof = DoubleArray(n)
        for (y in 0 until h) for (x in 0 until w) {
            if (bytes[y * w + x].toInt() != 0) prof[if (rows) y else x] += 1.0
        }
        val mean = prof.average()
        if (mean <= 0) return 0f
        var s = 0.0
        var m1 = 0.0
        val d = DoubleArray(n - 1) { (prof[it + 1] - prof[it]) / mean }
        for (v in d) m1 += v
        m1 /= d.size
        for (v in d) s += (v - m1) * (v - m1)
        return (s / d.size).toFloat()
    }
}
