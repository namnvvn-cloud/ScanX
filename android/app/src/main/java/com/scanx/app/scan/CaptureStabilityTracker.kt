package com.scanx.app.scan

import android.graphics.PointF
import kotlin.math.sqrt

/**
 * Theo dõi độ ổn định của khung tài liệu phát hiện được qua nhiều khung hình liên tiếp để quyết
 * định thời điểm tự động chụp (chế độ Auto): khi 4 góc gần như không đổi vị trí trong N khung hình
 * liên tiếp (mặc định 6, tương đương ~0.3-0.5s ở tốc độ phân tích thông thường) VÀ tài liệu chiếm
 * đủ diện tích khung hình thì chụp ngay — không đợi hẹn giờ cố định như UI camera cũ, nên mỗi khi
 * người dùng lật sang trang mới, hệ thống tự nhận biết & chụp lại gần như tức thời: ngay khi trang
 * mới di chuyển, `isStable` trả về false và bộ đếm tự khởi động lại từ đầu.
 */
class CaptureStabilityTracker(
    private val requiredStableFrames: Int = 6,
    private val minAreaRatio: Float = 0.20f,
    private val positionToleranceRatio: Float = 0.015f,
) {
    private var lastQuad: DetectedQuad? = null
    private var stableCount = 0
    private var consumedForCurrentQuad = false

    /** Gọi với kết quả phát hiện của mỗi khung hình. Trả về true đúng 1 lần khi nên tự động chụp. */
    fun onFrame(detected: DetectedQuad?): Boolean {
        if (detected == null || detected.areaRatio < minAreaRatio) {
            reset()
            return false
        }

        val previous = lastQuad
        val isSamePosition = previous != null && isStable(previous, detected)
        lastQuad = detected

        if (isSamePosition) {
            stableCount++
        } else {
            stableCount = 1
            consumedForCurrentQuad = false
        }

        if (stableCount >= requiredStableFrames && !consumedForCurrentQuad) {
            consumedForCurrentQuad = true
            return true
        }
        return false
    }

    /** Gọi khi rời màn hình camera hoặc chuyển hẳn sang chế độ thủ công, để bắt đầu lại từ đầu. */
    fun reset() {
        lastQuad = null
        stableCount = 0
        consumedForCurrentQuad = false
    }

    private fun isStable(a: DetectedQuad, b: DetectedQuad): Boolean {
        if (a.points.size != 4 || b.points.size != 4) return false
        for (i in 0 until 4) {
            if (distance(a.points[i], b.points[i]) > positionToleranceRatio) return false
        }
        return true
    }

    private fun distance(a: PointF, b: PointF): Float {
        val dx = a.x - b.x
        val dy = a.y - b.y
        return sqrt(dx * dx + dy * dy)
    }
}
