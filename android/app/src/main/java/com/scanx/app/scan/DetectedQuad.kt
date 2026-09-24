package com.scanx.app.scan

import android.graphics.PointF
import kotlin.math.hypot

/**
 * 4 góc tài liệu, thứ tự top-left / top-right / bottom-right / bottom-left, chuẩn hoá [0,1] theo
 * khung ảnh đã xoay đứng (upright). [frameWidth]/[frameHeight] là kích thước khung upright gốc để
 * overlay map đúng tỉ lệ ra PreviewView. [confidence] = độ tin cậy thấp nhất trong 4 góc (0..1).
 *
 * Bản 0.9: tách ra file riêng (thuần Kotlin, chỉ dùng PointF) để bộ theo dõi khung
 * ([DocumentTracker]) và máy trạng thái tự chụp ([AutoCaptureController]) test được trên JVM.
 */
data class DetectedQuad(
    val points: List<PointF>,
    val confidence: Float,
    val areaRatio: Float,
    val frameWidth: Int,
    val frameHeight: Int,
)

/** Khoảng lệch lớn nhất giữa 2 góc cùng vị trí của 2 khung (đơn vị: tỉ lệ khung). */
fun maxCornerDistance(a: DetectedQuad, b: DetectedQuad): Float {
    var m = 0f
    for (i in 0 until 4) {
        val d = hypot(a.points[i].x - b.points[i].x, a.points[i].y - b.points[i].y)
        if (d > m) m = d
    }
    return m
}
