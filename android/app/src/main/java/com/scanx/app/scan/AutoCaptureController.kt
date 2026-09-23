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
 * Máy trạng thái tự chụp — nhịp kiểu Scanner Pro: chỉ chụp khi trang ĐỦ ĐIỀU KIỆN và LÀ TRANG MỚI.
 *
 * Điều kiện chụp (tất cả phải đạt liên tục trong [holdMillis], mặc định 0,45 s; nếu khung gần như
 * bất động và ảnh ổn định thì chụp sớm sau ~0,27 s — nhịp tương đương CamScanner/Genius Scan; bản
 * 0.8: máy gần như bất động tuyệt đối — vd. tì tay lên bàn, giá đỡ — ≥2 khung liên tiếp thì chỉ cần
 * ~0,18 s, không nới lỏng bất kỳ điều kiện an toàn nào khác bên dưới):
 *  1. AI thấy đủ 4 góc với độ tin cậy ≥ [minConfidence]; cả 4 góc cách mép khung ≥ [edgeMargin]
 *     (không bị cắt mất góc giấy) và trang chiếm 12–97% khung.
 *  2. 4 góc đứng yên trong [stillTolerance]; nội dung trang không đổi giữa các khung (không có tay
 *     đang lướt qua, không rung) — so bằng chữ ký nội dung [AiDocumentDetector.pageSimilarity].
 *  3. Độ nét ở thời điểm chụp ≥ 80% độ nét tốt nhất đã thấy trong lúc giữ (tránh chụp khung nhoè).
 *
 * Chống chụp trùng: sau mỗi lần chụp, trang chỉ được coi là "mới" khi:
 *  - nội dung khác hẳn trang vừa chụp (tương quan < [newPageSimilarity]); hoặc
 *  - tài liệu đã rời khỏi khung ≥ [removedMillis] (rút giấy ra, đặt tờ khác vào) VÀ tờ mới không
 *    giống hệt tờ cũ (tương quan < [samePageSimilarity]); trang trắng thì chỉ cần rút ra/đặt lại.
 *  Tay che, rung, mất khung chớp nhoáng KHÔNG còn làm chụp lại trang cũ như bản trước.
 */
class AutoCaptureController(
    var holdMillis: Long = 450L,
    private val minConfidence: Float = 0.5f,
    private val stillTolerance: Float = 0.02f,
    private val edgeMargin: Float = 0.012f,
    private val newPageSimilarity: Float = 0.3f,
    private val samePageSimilarity: Float = 0.85f,
    private val removedMillis: Long = 700L,
    private val minIntervalMillis: Long = 600L,
    private val contentStableSimilarity: Float = 0.75f,
    private val sharpnessKeep: Float = 0.8f,
    /** Chụp sớm: khung gần như bất động (< 0,6% khung) + nội dung rất ổn định → chỉ cần 60% thời gian giữ. */
    private val earlyJitter: Float = 0.006f,
    private val earlyFactor: Float = 0.6f,
    /** Bản 0.8: bậc "cực yên" (máy trên giá đỡ/tay rất vững) — lệch < 0,3% liên tục ≥2 khung kể từ
     *  lúc bắt đầu giữ → chỉ cần 40% thời gian giữ (nhanh hơn cả mức "rất yên" ở trên). */
    private val ultraJitter: Float = 0.003f,
    private val ultraFactor: Float = 0.4f,
    private val ultraMinFrames: Int = 2,
) {
    enum class Hint { NONE, NO_DOCUMENT, EDGE, HOLD_STILL, WAIT_NEW_PAGE }

    data class Decision(
        val progress: Float,
        val shouldCapture: Boolean,
        val waitingForNewPage: Boolean,
        val hint: Hint,
        /** true = vừa bắt đầu giữ yên trên trang mới → nên lấy nét vào tâm tài liệu. */
        val holdStarted: Boolean = false,
    )

    private var anchor: DetectedQuad? = null
    private var holdStart = 0L
    private var holdMaxTexture = 0f
    private var veryStill = true
    /** Số khung liên tiếp (kể từ lúc bắt đầu giữ) lệch dưới [ultraJitter] — bản 0.8. */
    private var ultraStableStreak = 0
    private var prevSignature: FloatArray? = null

    private var lastCaptureAt = 0L
    private var lastCapturedSignature: FloatArray? = null
    private var lastCapturedQuad: DetectedQuad? = null
    private var absentSince = 0L
    private var removedSinceCapture = false

    /** Lý do cho phép chụp của lần chụp gần nhất: true = do tài liệu đã được rút ra/đặt lại. */
    var lastCaptureAfterRemoval = false
        private set

    fun onFrame(quad: DetectedQuad?, signature: FloatArray?, nowMillis: Long): Decision {
        val waiting = lastCapturedSignature != null
        if (quad == null || quad.confidence < minConfidence) {
            resetHold()
            if (absentSince == 0L) absentSince = nowMillis
            if (waiting && nowMillis - absentSince >= removedMillis) removedSinceCapture = true
            return Decision(0f, false, waiting && !removedSinceCapture, if (waiting && !removedSinceCapture) Hint.WAIT_NEW_PAGE else Hint.NO_DOCUMENT)
        }
        absentSince = 0L

        if (!isFullyInside(quad)) {
            resetHold()
            return Decision(0f, false, false, Hint.EDGE)
        }

        if (!isNewPage(quad, signature)) {
            resetHold()
            return Decision(0f, false, true, Hint.WAIT_NEW_PAGE)
        }

        val texture = signature?.let { AiDocumentDetector.signatureTexture(it) } ?: 0f
        val prevSig = prevSignature
        val contentMoving = prevSig != null && signature != null &&
            texture > 0.02f && AiDocumentDetector.signatureTexture(prevSig) > 0.02f &&
            AiDocumentDetector.pageSimilarity(prevSig, signature) < contentStableSimilarity
        prevSignature = signature

        val a = anchor
        if (a == null || maxCornerDistance(a, quad) > stillTolerance || contentMoving) {
            anchor = quad
            holdStart = nowMillis
            holdMaxTexture = texture
            veryStill = true
            ultraStableStreak = 0
            return Decision(0f, false, false, Hint.HOLD_STILL, holdStarted = true)
        }
        if (texture > holdMaxTexture) holdMaxTexture = texture
        // Còn "rất yên" nếu mọi khung từ lúc bắt đầu giữ đều lệch < earlyJitter và nội dung gần như trùng khớp.
        val contentSteady = prevSig == null || signature == null || texture <= 0.02f ||
            AiDocumentDetector.pageSimilarity(prevSig, signature) > 0.9f
        val cornerDelta = maxCornerDistance(a, quad)
        if (cornerDelta > earlyJitter || !contentSteady) veryStill = false
        // Bản 0.8: đếm số khung liên tiếp "cực yên" (ngưỡng chặt hơn earlyJitter) để chụp còn nhanh hơn nữa
        // khi máy gần như bất động (giá đỡ, tì tay lên bàn) — không nới lỏng các điều kiện an toàn khác.
        ultraStableStreak = if (veryStill && contentSteady && cornerDelta <= ultraJitter) ultraStableStreak + 1 else 0

        val needed = when {
            veryStill && ultraStableStreak >= ultraMinFrames -> (holdMillis * ultraFactor).toLong()
            veryStill -> (holdMillis * earlyFactor).toLong()
            else -> holdMillis
        }
        val progress = ((nowMillis - holdStart).toFloat() / needed.coerceAtLeast(150L)).coerceIn(0f, 1f)
        val sharpEnough = holdMaxTexture < AiDocumentDetector.BLANK_TEXTURE * 2 || texture >= holdMaxTexture * sharpnessKeep
        if (progress >= 1f && sharpEnough && nowMillis - lastCaptureAt >= minIntervalMillis) {
            markCaptured(quad, signature, nowMillis)
            return Decision(1f, true, false, Hint.NONE)
        }
        return Decision(progress, false, false, Hint.HOLD_STILL)
    }

    private fun isFullyInside(q: DetectedQuad): Boolean {
        if (q.areaRatio < 0.12f || q.areaRatio > 0.97f) return false
        return q.points.all { it.x >= edgeMargin && it.x <= 1f - edgeMargin && it.y >= edgeMargin && it.y <= 1f - edgeMargin }
    }

    private fun isNewPage(quad: DetectedQuad, signature: FloatArray?): Boolean {
        val last = lastCapturedSignature ?: return true
        if (signature == null) return removedSinceCapture
        val sim = AiDocumentDetector.pageSimilarity(last, signature)
        val bothBlank = AiDocumentDetector.signatureTexture(last) < AiDocumentDetector.BLANK_TEXTURE &&
            AiDocumentDetector.signatureTexture(signature) < AiDocumentDetector.BLANK_TEXTURE
        if (bothBlank) {
            // Trang trắng: không so được nội dung → chỉ nhận là trang mới khi đã rút giấy ra, hoặc
            // tờ giấy nằm ở vị trí khác hẳn (đặt tờ mới lệch chỗ tờ cũ).
            val moved = lastCapturedQuad?.let { maxCornerDistance(it, quad) > 0.15f } ?: true
            return removedSinceCapture || moved
        }
        if (sim < newPageSimilarity) return true
        return removedSinceCapture && sim < samePageSimilarity
    }

    private fun resetHold() {
        anchor = null
        prevSignature = null
        holdMaxTexture = 0f
        ultraStableStreak = 0
    }

    /** Gọi cả khi chụp thủ công để chế độ tự động không chụp lại đúng trang vừa chụp tay. */
    fun markCaptured(quad: DetectedQuad?, signature: FloatArray?, nowMillis: Long) {
        lastCaptureAfterRemoval = removedSinceCapture && lastCapturedSignature != null
        lastCaptureAt = nowMillis
        lastCapturedQuad = quad
        lastCapturedSignature = signature
        removedSinceCapture = false
        absentSince = 0L
        resetHold()
    }

    /** Trang vừa chụp bị loại (trùng/lỗi) → cập nhật mốc so sánh nhưng không coi là đã rút giấy. */
    fun rememberPage(signature: FloatArray?) {
        if (signature != null) lastCapturedSignature = signature
    }

    fun reset() {
        resetHold()
        lastCapturedSignature = null
        lastCapturedQuad = null
        removedSinceCapture = false
        absentSince = 0L
        lastCaptureAfterRemoval = false
    }
}
