package com.scanx.app.scan

import org.opencv.core.CvType
import org.opencv.core.Mat
import org.opencv.core.Size
import org.opencv.imgproc.Imgproc
import kotlin.math.hypot

/**
 * Đo độ dịch của ẢNH giữa 2 khung phân tích liên tiếp (bản 0.9) — thước đo "máy có đang đứng yên
 * không" cho tự chụp. Dùng tương quan pha (phase correlation, FFT) trên ảnh xám 120×160: ~1 ms/khung,
 * không phụ thuộc AI nhận góc (vốn nhiễu trên ảnh thật), phản ánh đúng thứ gây nhoè ảnh chụp.
 *
 * Đơn vị trả về: tỉ lệ khung/giây (0,02 = ảnh trôi 2% bề ngang mỗi giây), đã làm mượt EMA α = 0,5.
 * Trên video thật cầm tay: máy "đứng yên" đo được 0,5–3%/s; đang lia máy thường > 10%/s.
 */
class CameraMotionMeter {
    private var prev: Mat? = null
    private var lastTime = -1L
    private var smoothed = Float.MAX_VALUE

    /** [bgrUpright] = khung BGR đã xoay đứng (không bị giữ lại). Trả về độ dịch đã làm mượt. */
    fun update(bgrUpright: Mat, nowMillis: Long): Float {
        val gray = Mat()
        Imgproc.cvtColor(bgrUpright, gray, Imgproc.COLOR_BGR2GRAY)
        val w = if (bgrUpright.cols() <= bgrUpright.rows()) SMALL_SHORT else SMALL_LONG
        val h = if (bgrUpright.cols() <= bgrUpright.rows()) SMALL_LONG else SMALL_SHORT
        val small = Mat()
        Imgproc.resize(gray, small, Size(w.toDouble(), h.toDouble()), 0.0, 0.0, Imgproc.INTER_AREA)
        gray.release()
        val cur = Mat()
        small.convertTo(cur, CvType.CV_32F)
        small.release()

        val p = prev
        val dtSec = if (lastTime < 0) 0f else ((nowMillis - lastTime).coerceAtLeast(1L) / 1000f)
        lastTime = nowMillis
        if (p != null && dtSec > 0f && p.size() == cur.size()) {
            val shift = Imgproc.phaseCorrelate(p, cur)
            val perSecond = (hypot(shift.x / w, shift.y / h) / dtSec).toFloat()
            smoothed = if (smoothed == Float.MAX_VALUE) perSecond else 0.5f * smoothed + 0.5f * perSecond
        }
        p?.release()
        prev = cur
        return smoothed
    }

    fun reset() {
        prev?.release()
        prev = null
        lastTime = -1L
        smoothed = Float.MAX_VALUE
    }

    private companion object {
        const val SMALL_SHORT = 120
        const val SMALL_LONG = 160
    }
}
