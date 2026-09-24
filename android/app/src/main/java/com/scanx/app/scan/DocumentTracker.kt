package com.scanx.app.scan

import android.graphics.PointF
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.hypot

/**
 * Bộ theo dõi khung tài liệu giữa các khung hình (bản 0.9) — thay bộ làm mượt QuadSmoother cũ. Thuần Kotlin.
 *
 * Vấn đề của bản cũ (đo bằng mô phỏng tay cầm máy trên cảnh thật + trang tổng hợp, 12 khung/s):
 *  - Mỗi khung AI trả về 4 góc độc lập → góc rung 0,5–1% khung, thỉnh thoảng nhảy ~10% (heatmap 2
 *    đỉnh) → khung xanh giật, tiến độ giữ yên về 0 liên tục.
 *  - Một khung mất phát hiện (độ tin cậy tụt thoáng qua) → khung biến mất, tiến độ về 0 → "nháy".
 *
 * Cách làm mới:
 *  1. Bắt khung ([acquireConfidence] ≥ 0,45) rồi GIỮ khung với ngưỡng thấp hơn ([keepConfidence]
 *     0,25) — trễ (hysteresis) chống chập chờn quanh ngưỡng.
 *  2. Mất phát hiện ≤ [coastMillis] (0,4 s): giữ nguyên khung cũ ("trôi"), báo `fresh = false` để
 *     máy trạng thái không chụp trên khung đó nhưng cũng không xoá tiến độ.
 *  3. Một góc lệch bất thường (> [outlierDistance]) trong khi 3 góc kia đứng yên: bỏ qua góc đó cho
 *     tới khi lệch liên tục [outlierConfirmFrames] khung (thật sự dịch chuyển).
 *  4. Khi máy đang đứng yên (độ dịch ảnh đo trực tiếp, [CameraMotionMeter]) lấy TRUNG VỊ 5 lần đo gần
 *     nhất của mỗi góc — trên video thật anh Nam quay, AI trả góc nhiễu tới 10–40% khung/giây dù máy
 *     gần như bất động (đo bằng tương quan pha chỉ 0,5–3%/s) → trung vị loại các cú nhảy lẻ.
 *  5. Làm mượt bằng bộ lọc One-Euro (Casiez 2012 — chuẩn dùng trong MediaPipe cho điểm mốc rung):
 *     đứng yên thì lọc mạnh (hết rung), di chuyển nhanh thì bám sát (không trễ).
 * Kết quả mô phỏng: rung khung sau lọc 0,1–0,2% (trước 0,5% + các cú nhảy 10%); trên 38 s video thật
 * của ScanX 0.8: khung hiện 75% thời gian (bản cũ 53%), bật/tắt 7 lần (bản cũ 34).
 */
class DocumentTracker(
    private val acquireConfidence: Float = 0.45f,
    private val keepConfidence: Float = 0.25f,
    private val coastMillis: Long = 400L,
    private val outlierDistance: Float = 0.05f,
    private val outlierConfirmFrames: Int = 3,
    private val snapDistance: Float = 0.15f,
    private val minArea: Float = 0.08f,
    private val minCutoffHz: Float = 1.2f,
    private val beta: Float = 6f,
    private val derivativeCutoffHz: Float = 1f,
    /** Máy coi là đứng yên khi độ dịch ảnh < mức này (tỉ lệ khung/giây) → bật lọc trung vị. */
    private val stillMotion: Float = 0.07f,
    private val medianWindow: Int = 5,
) {
    /**
     * [fresh] = khung này có phát hiện mới hợp lệ (false = đang "trôi" qua khung mất phát hiện).
     * [speed] = tốc độ dịch chuyển lớn nhất của 4 góc sau lọc (tỉ lệ khung/giây).
     */
    data class Result(val quad: DetectedQuad, val fresh: Boolean, val speed: Float, val confidence: Float)

    private class OneEuro(val minCutoff: Float, val beta: Float, val dCutoff: Float) {
        var x = Float.NaN
            private set
        var dx = 0f
            private set

        private fun alpha(cutoff: Float, dt: Float): Float {
            val tau = 1f / (2f * PI.toFloat() * cutoff)
            return 1f / (1f + tau / dt)
        }

        fun filter(value: Float, dt: Float): Float {
            if (x.isNaN()) { x = value; dx = 0f; return value }
            val d = (value - x) / dt
            dx += alpha(dCutoff, dt) * (d - dx)
            val cutoff = minCutoff + beta * abs(dx)
            x += alpha(cutoff, dt) * (value - x)
            return x
        }
    }

    private var tracked: DetectedQuad? = null
    private var filters: Array<OneEuro>? = null
    private var lastTime = -1L
    private var lastGood = Long.MIN_VALUE / 2
    private val outlierCount = IntArray(4)
    private var speed = 0f
    /** Các lần đo góc gần nhất (đã qua lọc góc lạc) — cho lọc trung vị khi máy đứng yên. */
    private val history = ArrayDeque<List<PointF>>()

    /** 4 góc đang theo dõi — làm "mồi" chọn đỉnh heatmap cục bộ ([HeatmapPeaks.extract]). */
    val priors: List<PointF>? get() = tracked?.points

    fun reset() {
        tracked = null
        filters = null
        lastTime = -1L
        lastGood = Long.MIN_VALUE / 2
        outlierCount.fill(0)
        speed = 0f
        history.clear()
    }

    private fun median(values: FloatArray): Float {
        values.sort()
        val n = values.size
        return if (n % 2 == 1) values[n / 2] else (values[n / 2 - 1] + values[n / 2]) / 2f
    }

    /** Trung vị từng toạ độ của các lần đo trong [history]. */
    private fun medianOfHistory(): List<PointF> = List(4) { i ->
        PointF(
            median(FloatArray(history.size) { k -> history.elementAt(k)[i].x }),
            median(FloatArray(history.size) { k -> history.elementAt(k)[i].y }),
        )
    }

    private fun newFilters() = Array(8) { OneEuro(minCutoffHz, beta, derivativeCutoffHz) }

    private fun filtered(points: List<PointF>, dt: Float): List<PointF> {
        val f = filters ?: newFilters().also { filters = it }
        return List(4) { i -> PointF(f[2 * i].filter(points[i].x, dt), f[2 * i + 1].filter(points[i].y, dt)) }
    }

    private fun currentSpeed(): Float {
        val f = filters ?: return 0f
        var m = 0f
        for (i in 0 until 4) {
            val v = hypot(f[2 * i].dx, f[2 * i + 1].dx)
            if (v > m) m = v
        }
        return m
    }

    /**
     * Cập nhật với kết quả AI của khung mới ([corners] null = model không chạy được). Trả về null khi
     * không còn theo dõi tài liệu nào.
     */
    fun update(
        corners: HeatmapPeaks.Corners?,
        frameWidth: Int,
        frameHeight: Int,
        nowMillis: Long,
        /** Độ dịch ảnh giữa các khung (tỉ lệ khung/giây, [CameraMotionMeter]); không đo được → dùng số lớn. */
        cameraMotion: Float = Float.MAX_VALUE,
    ): Result? {
        val dt = if (lastTime < 0) 0.083f else ((nowMillis - lastTime).coerceAtLeast(1L) / 1000f)
        lastTime = nowMillis
        val current = tracked

        if (corners != null) {
            val pts = corners.points
            val conf = corners.minConfidence
            val valid = HeatmapPeaks.area(pts) >= minArea && HeatmapPeaks.isConvex(pts)
            if (current == null) {
                if (valid && conf >= acquireConfidence) {
                    filters = newFilters()
                    outlierCount.fill(0)
                    history.clear()
                    history.addLast(pts)
                    val q = quadOf(filtered(pts, dt), conf, frameWidth, frameHeight)
                    tracked = q
                    lastGood = nowMillis
                    speed = 0f
                    return Result(q, fresh = true, speed = 0f, confidence = conf)
                }
            } else if (valid && conf >= keepConfidence) {
                val d = FloatArray(4) { i -> hypot(pts[i].x - current.points[i].x, pts[i].y - current.points[i].y) }
                val farSnap = d.count { it > snapDistance }
                if (farSnap >= 3 && conf >= acquireConfidence) {
                    // Cả tài liệu đổi chỗ (đặt tờ khác, lia máy mạnh) → bắt lại từ đầu, không trượt chậm theo.
                    filters = newFilters()
                    outlierCount.fill(0)
                    history.clear()
                }
                val farOutlier = d.count { it > outlierDistance }
                val use = List(4) { i ->
                    if (d[i] > outlierDistance && farOutlier <= 1) {
                        outlierCount[i]++
                        if (outlierCount[i] >= outlierConfirmFrames) pts[i] else current.points[i]
                    } else {
                        outlierCount[i] = 0
                        pts[i]
                    }
                }
                history.addLast(use)
                while (history.size > medianWindow) history.removeFirst()
                val input = if (cameraMotion < stillMotion && history.size >= 3) medianOfHistory() else use
                val q = quadOf(filtered(input, dt), conf, frameWidth, frameHeight)
                speed = currentSpeed()
                tracked = q
                lastGood = nowMillis
                return Result(q, fresh = true, speed = speed, confidence = conf)
            }
        }

        // Khung này không có phát hiện hợp lệ: giữ khung cũ trong thời gian ngắn, quá thì bỏ.
        if (current != null && nowMillis - lastGood <= coastMillis) {
            return Result(current, fresh = false, speed = speed, confidence = 0f)
        }
        reset()
        lastTime = nowMillis
        return null
    }

    private fun quadOf(points: List<PointF>, conf: Float, w: Int, h: Int) =
        DetectedQuad(points, conf, HeatmapPeaks.area(points), w, h)
}
