package com.scanx.app.scan

import org.opencv.core.Core
import org.opencv.core.CvType
import org.opencv.core.Mat
import org.opencv.core.Point
import org.opencv.core.Rect
import org.opencv.core.Scalar
import org.opencv.core.Size
import org.opencv.imgproc.Imgproc
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Nắn trang lần 2 sau khi làm phẳng bằng 4 góc — bước mà các app scan thương mại làm để trang "thẳng
 * hàng, cân đối" (xem [TextLineGeometry] về thuật toán và số đo).
 *
 * 1. Xoay bù góc nghiêng chung còn sót (theo trung vị độ dốc các dòng chữ, chỉ nhận |góc| ≤ 3°).
 * 2. Nắn từng dòng chữ về đường ngang (trường dịch dọc nội suy giữa các dòng), giới hạn 1,2% chiều cao.
 * 3. Cắt viền tối còn sót (bóng gáy sách / mép bàn lọt vào khung), tối đa 4% mỗi cạnh.
 *
 * Chạy theo dải ngang 256 dòng để bản đồ remap không chiếm nhiều RAM với ảnh 8 MP.
 */
object PageRectifier {

    private const val WORK_WIDTH = 1000.0
    private const val MAX_SKEW_DEG = 3.0
    private const val MIN_SKEW_DEG = 0.12
    private const val MAX_SHIFT_FRAC = 0.012f
    private const val BAND = 256

    /** [rgba] ảnh trang đã làm phẳng (CV_8UC4). Trả về ảnh MỚI đã nắn; người gọi giải phóng cả 2. */
    fun rectify(rgba: Mat): Mat {
        var owned: Mat? = null
        var current: Mat = rgba
        fun swap(next: Mat) {
            val old = owned
            owned = next
            current = next
            old?.release()
        }
        try {
            var lines = detectLines(current)
            val skew = TextLineGeometry.medianSkewDegrees(lines)
            if (lines.size >= 6 && abs(skew) >= MIN_SKEW_DEG && abs(skew) <= MAX_SKEW_DEG) {
                val center = Point(current.cols() / 2.0, current.rows() / 2.0)
                val m = Imgproc.getRotationMatrix2D(center, skew, 1.0)
                val dst = Mat()
                Imgproc.warpAffine(
                    current, dst, m, Size(current.cols().toDouble(), current.rows().toDouble()),
                    Imgproc.INTER_CUBIC, Core.BORDER_REPLICATE, Scalar(255.0, 255.0, 255.0, 255.0),
                )
                m.release()
                swap(dst)
                lines = detectLines(current)
            }
            if (lines.size >= 6) flatten(current, lines)?.let { swap(it) }
            trimDarkBorder(current)?.let { swap(it) }
            return owned ?: current.clone()
        } catch (e: Throwable) {
            owned?.release()
            return rgba.clone()
        }
    }

    /** Dòng chữ đo trên ảnh thu nhỏ (toạ độ theo ảnh thu nhỏ). */
    private fun detectLines(rgba: Mat): List<TextLineGeometry.Baseline> {
        val gray = ScanFilters.normalizedGray(rgba)
        // Luôn đưa về đúng WORK_WIDTH để toạ độ dòng chữ khớp với hệ số quy đổi ở [flatten].
        val k = WORK_WIDTH / rgba.cols()
        val small = Mat()
        Imgproc.resize(gray, small, Size(WORK_WIDTH, rgba.rows() * k), 0.0, 0.0, if (k < 1.0) Imgproc.INTER_AREA else Imgproc.INTER_LINEAR)
        gray.release()
        val bw = Mat()
        Imgproc.threshold(small, bw, 190.0, 255.0, Imgproc.THRESH_BINARY_INV)
        val kernel = Imgproc.getStructuringElement(Imgproc.MORPH_RECT, Size(max(15.0, small.cols() / 60.0), 1.0))
        Imgproc.morphologyEx(bw, bw, Imgproc.MORPH_CLOSE, kernel)
        val bytes = ByteArray(bw.cols() * bw.rows())
        bw.get(0, 0, bytes)
        val w = bw.cols()
        val h = bw.rows()
        small.release(); bw.release(); kernel.release()
        return TextLineGeometry.detect(bytes, w, h)
    }

    /** Nắn dòng chữ về ngang bằng remap theo dải ngang (tiết kiệm RAM). */
    private fun flatten(src: Mat, lines: List<TextLineGeometry.Baseline>): Mat? {
        val w = src.cols()
        val h = src.rows()
        val scale = w / WORK_WIDTH                     // ảnh thu nhỏ → ảnh gốc
        val maxShiftSmall = (h / scale * MAX_SHIFT_FRAC).toFloat()
        val rowSmall = FloatArray((WORK_WIDTH).toInt())
        val dst = Mat(h, w, src.type(), Scalar(255.0, 255.0, 255.0, 255.0))
        val mapX = Mat(BAND, w, CvType.CV_32F)
        val mapY = Mat(BAND, w, CvType.CV_32F)
        val rowX = FloatArray(w) { it.toFloat() }
        val rowY = FloatArray(w)
        var used = false
        var y0 = 0
        while (y0 < h) {
            val rows = min(BAND, h - y0)
            for (r in 0 until rows) {
                val y = y0 + r
                val ySmall = (y / scale).roundToInt().coerceIn(0, (h / scale).toInt())
                val ok = TextLineGeometry.rowOffsets(lines, rowSmall.size, ySmall, rowSmall, maxShiftSmall)
                if (!ok) return null
                used = true
                for (x in 0 until w) {
                    val xs = (x / scale).toInt().coerceIn(0, rowSmall.size - 1)
                    rowY[x] = (y + rowSmall[xs] * scale).toFloat()
                }
                mapX.put(r, 0, rowX)
                mapY.put(r, 0, rowY)
            }
            val bandX = if (rows == BAND) mapX else mapX.submat(0, rows, 0, w)
            val bandY = if (rows == BAND) mapY else mapY.submat(0, rows, 0, w)
            val out = dst.submat(y0, y0 + rows, 0, w)
            Imgproc.remap(src, out, bandX, bandY, Imgproc.INTER_CUBIC, Core.BORDER_REPLICATE, Scalar(255.0, 255.0, 255.0, 255.0))
            if (bandX !== mapX) { bandX.release(); bandY.release() }
            out.release()
            y0 += rows
        }
        mapX.release(); mapY.release()
        return if (used) dst else { dst.release(); null }
    }

    /** Cắt các dải tối sát mép (bóng, mép bàn). Trả về null nếu không có gì để cắt. */
    private fun trimDarkBorder(src: Mat): Mat? {
        val gray = Mat()
        Imgproc.cvtColor(src, gray, Imgproc.COLOR_RGBA2GRAY)
        val small = Mat()
        val k = 600.0 / max(src.cols(), src.rows())
        if (k < 1.0) Imgproc.resize(gray, small, Size(src.cols() * k, src.rows() * k), 0.0, 0.0, Imgproc.INTER_AREA)
        else gray.copyTo(small)
        gray.release()
        val rowMeans = FloatArray(small.rows())
        val colMeans = FloatArray(small.cols())
        val buf = ByteArray(small.cols() * small.rows())
        small.get(0, 0, buf)
        for (y in 0 until small.rows()) {
            var s = 0
            for (x in 0 until small.cols()) s += buf[y * small.cols() + x].toInt() and 0xFF
            rowMeans[y] = s.toFloat() / small.cols()
        }
        for (x in 0 until small.cols()) {
            var s = 0
            for (y in 0 until small.rows()) s += buf[y * small.cols() + x].toInt() and 0xFF
            colMeans[x] = s.toFloat() / small.rows()
        }
        val trim = TextLineGeometry.darkBorderTrim(rowMeans, colMeans)
        val sx = src.cols().toFloat() / small.cols()
        val sy = src.rows().toFloat() / small.rows()
        small.release()
        val l = (trim[0] * sx).roundToInt()
        val t = (trim[1] * sy).roundToInt()
        val r = (trim[2] * sx).roundToInt()
        val b = (trim[3] * sy).roundToInt()
        if (l + t + r + b <= 0) return null
        val wNew = src.cols() - l - r
        val hNew = src.rows() - t - b
        if (wNew < src.cols() * 0.7 || hNew < src.rows() * 0.7) return null
        return Mat(src, Rect(l, t, wNew, hNew)).clone()
    }
}
