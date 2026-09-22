package com.scanx.app.scan

import android.graphics.PointF
import kotlin.math.hypot

/**
 * Làm mượt 4 góc giữa các khung (EMA) để khung xanh bám mép giấy êm, không rung; khi tài liệu di
 * chuyển mạnh (> 6% khung) thì nhảy ngay tới vị trí mới thay vì trượt chậm theo.
 */
class QuadSmoother(private val alpha: Float = 0.55f, private val snapDistance: Float = 0.06f) {
    private var current: DetectedQuad? = null

    fun update(q: DetectedQuad?): DetectedQuad? {
        if (q == null) {
            current = null
            return null
        }
        val prev = current
        val next = if (prev == null || maxCornerDistance(prev, q) > snapDistance) {
            q
        } else {
            q.copy(points = prev.points.zip(q.points) { a, b ->
                PointF(a.x + alpha * (b.x - a.x), a.y + alpha * (b.y - a.y))
            })
        }
        current = next
        return next
    }

    fun reset() {
        current = null
    }
}

fun maxCornerDistance(a: DetectedQuad, b: DetectedQuad): Float {
    var m = 0f
    for (i in 0 until 4) {
        val d = hypot(a.points[i].x - b.points[i].x, a.points[i].y - b.points[i].y)
        if (d > m) m = d
    }
    return m
}

/**
 * Máy trạng thái tự động chụp, theo THỜI GIAN giữ yên (không đếm số khung như bản cũ → không phụ
 * thuộc tốc độ máy):
 *
 * 1. Tài liệu được AI nhận diện với độ tin cậy đủ cao (ảnh nét, đủ sáng).
 * 2. 4 góc giữ yên trong phạm vi [stillTolerance] liên tục đủ [holdMillis] → chụp.
 * 3. Sau khi chụp, chỉ cho chụp tiếp khi phát hiện ĐÃ LẬT TRANG: nội dung trang khác trang vừa
 *    chụp (chữ ký ảnh khác), hoặc có xáo trộn rõ (mất khung/tay che/giấy dịch chuyển mạnh).
 *    → Lật trang là tự chụp trang mới, không cần bấm, và không bao giờ chụp trùng 1 trang 2 lần.
 */
class AutoCaptureController(
    var holdMillis: Long = 240L,
    private val minConfidence: Float = 0.55f,
    private val stillTolerance: Float = 0.025f,
    private val newPageSignatureDistance: Float = 0.07f,
    private val disruptionDistance: Float = 0.08f,
    private val minIntervalMillis: Long = 400L,
    /** Ảnh rất nét (AI tin cậy ≥ 0,8) → chỉ cần giữ yên 60% thời gian cấu hình. */
    private val highConfidence: Float = 0.8f,
    private val highConfidenceHoldFactor: Float = 0.6f,
) {
    data class Decision(val progress: Float, val shouldCapture: Boolean, val waitingForNewPage: Boolean)

    private var anchor: DetectedQuad? = null
    private var holdStart = 0L
    private var lastCaptureAt = 0L
    private var lastCapturedQuad: DetectedQuad? = null
    private var lastSignature: FloatArray? = null
    private var needNewPage = false
    private var missedFrames = 0

    fun onFrame(quad: DetectedQuad?, signature: FloatArray?, nowMillis: Long): Decision {
        if (quad == null || quad.confidence < minConfidence) {
            anchor = null
            missedFrames++
            if (needNewPage && missedFrames >= 2) needNewPage = false
            return Decision(0f, false, needNewPage)
        }
        missedFrames = 0

        if (needNewPage) {
            val last = lastCapturedQuad
            val moved = last != null && maxCornerDistance(last, quad) > disruptionDistance
            val sigA = lastSignature
            val changed = sigA != null && signature != null &&
                AiDocumentDetector.signatureDistance(sigA, signature) > newPageSignatureDistance
            if (moved || changed) {
                needNewPage = false
                anchor = null
            } else {
                return Decision(0f, false, true)
            }
        }

        val a = anchor
        if (a == null || maxCornerDistance(a, quad) > stillTolerance) {
            anchor = quad
            holdStart = nowMillis
            return Decision(0f, false, false)
        }

        val effectiveHold = if (quad.confidence >= highConfidence) {
            (holdMillis * highConfidenceHoldFactor).toLong()
        } else {
            holdMillis
        }.coerceAtLeast(80L)
        val progress = ((nowMillis - holdStart).toFloat() / effectiveHold).coerceIn(0f, 1f)
        if (progress >= 1f && nowMillis - lastCaptureAt >= minIntervalMillis) {
            markCaptured(quad, signature, nowMillis)
            return Decision(1f, true, false)
        }
        return Decision(progress, false, false)
    }

    /** Gọi cả khi chụp thủ công để chế độ tự động không chụp lại đúng trang vừa chụp tay. */
    fun markCaptured(quad: DetectedQuad?, signature: FloatArray?, nowMillis: Long) {
        lastCaptureAt = nowMillis
        lastCapturedQuad = quad
        lastSignature = signature
        needNewPage = true
        anchor = null
        missedFrames = 0
    }

    fun reset() {
        anchor = null
        needNewPage = false
        lastSignature = null
        lastCapturedQuad = null
        missedFrames = 0
    }
}
