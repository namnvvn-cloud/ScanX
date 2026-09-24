package com.scanx.app.scan

/**
 * Máy trạng thái tự chụp — nhịp kiểu Scanner Pro: chỉ chụp khi trang ĐỦ ĐIỀU KIỆN và LÀ TRANG MỚI.
 *
 * Bản 0.9 — viết lại phần "giữ yên" sau khi đo bằng mô phỏng (tay cầm máy rung, 5 cảnh: 2 cảnh thật +
 * giấy trên bàn gỗ/xám/trắng) và trên chính video anh Nam quay ScanX 0.8. Nguyên nhân bản 0.8 gần như
 * không tự chụp được:
 *  (1) ~90% lần reset tiến độ đến từ phép so "nội dung giữa 2 khung liền nhau" — chữ ký 48×64 lấy
 *      mẫu không chống răng cưa → lệch khung 1 px là tương quan tụt dưới ngưỡng 0,75;
 *  (2) phần còn lại do so lệch góc AI thô với mốc cố định 2% — trong khi góc AI trên ảnh thật nhiễu
 *      10–40% khung/giây ngay cả khi máy gần như bất động.
 * Cách làm mới:
 *  - "Máy đứng yên" đo TRỰC TIẾP độ dịch của ảnh giữa 2 khung ([CameraMotionMeter], tương quan pha —
 *    chính là thứ gây nhoè ảnh), không suy từ góc AI: < [stillMotion] (7% khung/giây).
 *  - Khung tài liệu lấy từ [DocumentTracker] (trung vị + One-Euro, chịu được khung mất phát hiện);
 *    tổng trôi so với mốc < [maxDrift] (6%) để bắt trường hợp tờ giấy bị kéo đi khi máy đứng yên.
 *  - Nội dung chỉ PHỦ QUYẾT khi đổi rất mạnh (tương quan < [contentVetoSimilarity] trên chữ ký chống
 *    răng cưa) — vd. đang lật trang — không còn làm hỏng các lần giữ yên hợp lệ.
 *  - Chụp nhanh theo độ yên: rất yên (< [calmMotion]) ≥ 2 khung → 60% thời gian giữ; cực yên
 *    (< [ultraMotion]) → 40% (~0,18 s với mặc định 0,45 s). Bấm chụp đúng lúc máy đang yên
 *    (< [calmMotion]) để ảnh không nhoè.
 *  - Độ nét: chữ ký chống răng cưa ≥ [sharpnessKeep] × tốt nhất trong lúc giữ; quá hạn thì bỏ qua.
 *  - Lấy nét tự động chỉ gửi tối đa 1 lần / [focusIntervalMillis] (bản cũ gửi mỗi lần reset → ống kính
 *    liên tục dò nét, ảnh nhoè theo nhịp, càng khó giữ yên).
 * Kết quả (chạy chính code Kotlin này trên heatmap AI thật xuất từ 20 s video anh Nam quay, máy cầm
 * tay trên bàn gỗ): tự chụp lần đầu ở giây 2,5 — bản 0.8 trên cùng dữ liệu: giây 6,8 sau 86 lần reset.
 * Mô phỏng cảnh thật: chụp 0,45–1,1 s sau khi tay dừng.
 *
 * Chống chụp trùng (GIỮ NGUYÊN như bản 0.4+): trang chỉ được coi là "mới" khi:
 *  - nội dung khác hẳn trang vừa chụp (tương quan < [newPageSimilarity]); hoặc
 *  - tài liệu đã rời khỏi khung ≥ [removedMillis] (rút giấy ra, đặt tờ khác vào) VÀ tờ mới không
 *    giống hệt tờ cũ (tương quan < [samePageSimilarity]); trang trắng thì chỉ cần rút ra/đặt lại.
 */
class AutoCaptureController(
    var holdMillis: Long = 450L,
    private val edgeMargin: Float = 0.012f,
    private val minAreaRatio: Float = 0.12f,
    private val maxAreaRatio: Float = 0.97f,
    private val newPageSimilarity: Float = 0.3f,
    private val samePageSimilarity: Float = 0.85f,
    private val removedMillis: Long = 700L,
    private val minIntervalMillis: Long = 600L,
    private val stillMotion: Float = 0.07f,
    private val calmMotion: Float = 0.035f,
    private val ultraMotion: Float = 0.018f,
    private val maxDrift: Float = 0.06f,
    private val contentVetoSimilarity: Float = 0.12f,
    private val sharpnessKeep: Float = 0.75f,
    private val earlyFactor: Float = 0.6f,
    private val ultraFactor: Float = 0.4f,
    private val calmMinFrames: Int = 2,
    private val focusIntervalMillis: Long = 2000L,
) {
    enum class Hint { NONE, NO_DOCUMENT, EDGE, TOO_SMALL, HOLD_STILL, WAIT_NEW_PAGE }

    data class Decision(
        val progress: Float,
        val shouldCapture: Boolean,
        val waitingForNewPage: Boolean,
        val hint: Hint,
        /** true = nên lấy nét + đo sáng vào tâm tài liệu (đã giới hạn tần suất). */
        val holdStarted: Boolean = false,
    )

    private var anchor: DetectedQuad? = null
    private var holdStart = 0L
    private var holdMaxTexture = 0f
    private var calmFrames = 0
    private var prevSharpSignature: FloatArray? = null
    private var lastProgress = 0f
    private var lastWaiting = false
    private var lastFocusAt = Long.MIN_VALUE / 2

    private var lastCaptureAt = Long.MIN_VALUE / 2
    private var lastCapturedSignature: FloatArray? = null
    private var lastCapturedQuad: DetectedQuad? = null
    private var absentSince = 0L
    private var removedSinceCapture = false

    /** Lý do cho phép chụp của lần chụp gần nhất: true = do tài liệu đã được rút ra/đặt lại. */
    var lastCaptureAfterRemoval = false
        private set

    /**
     * @param track kết quả [DocumentTracker] của khung này (null = không có tài liệu).
     * @param signature chữ ký dùng chống trùng trang ([AiDocumentDetector.pageSignature]) — chỉ cần
     *   khi [DocumentTracker.Result.fresh].
     * @param sharpSignature chữ ký chống răng cưa ([AiDocumentDetector.pageSignatureSharp]) — đo độ nét
     *   và phát hiện nội dung đổi mạnh.
     * @param cameraMotion độ dịch ảnh giữa 2 khung đã làm mượt ([CameraMotionMeter]), tỉ lệ khung/giây.
     */
    fun onFrame(
        track: DocumentTracker.Result?,
        signature: FloatArray?,
        sharpSignature: FloatArray?,
        cameraMotion: Float,
        nowMillis: Long,
    ): Decision {
        val waiting = lastCapturedSignature != null
        if (track == null) {
            resetHold()
            if (absentSince == 0L) absentSince = nowMillis
            if (waiting && nowMillis - absentSince >= removedMillis) removedSinceCapture = true
            val w = waiting && !removedSinceCapture
            return remember(Decision(0f, false, w, if (w) Hint.WAIT_NEW_PAGE else Hint.NO_DOCUMENT))
        }
        absentSince = 0L
        val quad = track.quad

        if (quad.areaRatio < minAreaRatio) {
            resetHold()
            return remember(Decision(0f, false, false, Hint.TOO_SMALL))
        }
        if (!isFullyInside(quad)) {
            resetHold()
            return remember(Decision(0f, false, false, Hint.EDGE))
        }

        // Khung "trôi" (mất phát hiện thoáng qua): giữ nguyên trạng thái, không chụp, không xoá tiến độ.
        if (!track.fresh) {
            return Decision(lastProgress, false, lastWaiting, if (lastWaiting) Hint.WAIT_NEW_PAGE else Hint.HOLD_STILL)
        }

        if (!isNewPage(quad, signature)) {
            resetHold()
            return remember(Decision(0f, false, true, Hint.WAIT_NEW_PAGE))
        }

        val texture = sharpSignature?.let { PageSignature.texture(it) } ?: 0f
        val prev = prevSharpSignature
        val bigContentChange = prev != null && sharpSignature != null &&
            texture > 0.02f && PageSignature.texture(prev) > 0.02f &&
            PageSignature.similarity(prev, sharpSignature) < contentVetoSimilarity
        prevSharpSignature = sharpSignature

        val a = anchor
        if (a == null || cameraMotion > stillMotion || maxCornerDistance(a, quad) > maxDrift || bigContentChange) {
            anchor = quad
            holdStart = nowMillis
            holdMaxTexture = texture
            calmFrames = 0
            val focus = nowMillis - lastFocusAt >= focusIntervalMillis
            if (focus) lastFocusAt = nowMillis
            return remember(Decision(0f, false, false, Hint.HOLD_STILL, holdStarted = focus))
        }
        if (texture > holdMaxTexture) holdMaxTexture = texture
        calmFrames = if (cameraMotion < calmMotion) calmFrames + 1 else 0

        val needed = when {
            calmFrames >= calmMinFrames && cameraMotion < ultraMotion -> (holdMillis * ultraFactor).toLong()
            calmFrames >= calmMinFrames -> (holdMillis * earlyFactor).toLong()
            else -> holdMillis
        }.coerceAtLeast(150L)
        val elapsed = nowMillis - holdStart
        val progress = (elapsed.toFloat() / needed).coerceIn(0f, 1f)
        val sharpEnough = holdMaxTexture < PageSignature.BLANK_TEXTURE * 2 ||
            texture >= holdMaxTexture * sharpnessKeep || elapsed > needed + 500
        val calmNow = cameraMotion < calmMotion || elapsed > needed + 400
        if (progress >= 1f && sharpEnough && calmNow && nowMillis - lastCaptureAt >= minIntervalMillis) {
            markCaptured(quad, signature, nowMillis)
            return remember(Decision(1f, true, false, Hint.NONE))
        }
        return remember(Decision(progress, false, false, Hint.HOLD_STILL))
    }

    private fun remember(d: Decision): Decision {
        lastProgress = d.progress
        lastWaiting = d.waitingForNewPage
        return d
    }

    private fun isFullyInside(q: DetectedQuad): Boolean {
        if (q.areaRatio > maxAreaRatio) return false
        return q.points.all { it.x >= edgeMargin && it.x <= 1f - edgeMargin && it.y >= edgeMargin && it.y <= 1f - edgeMargin }
    }

    private fun isNewPage(quad: DetectedQuad, signature: FloatArray?): Boolean {
        val last = lastCapturedSignature ?: return true
        if (signature == null) return removedSinceCapture
        val sim = PageSignature.similarity(last, signature)
        val bothBlank = PageSignature.texture(last) < PageSignature.BLANK_TEXTURE &&
            PageSignature.texture(signature) < PageSignature.BLANK_TEXTURE
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
        prevSharpSignature = null
        holdMaxTexture = 0f
        calmFrames = 0
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
        lastProgress = 0f
        lastWaiting = false
    }
}
