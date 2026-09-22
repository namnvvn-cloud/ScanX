package com.scanx.app.ui

import android.app.Application
import android.graphics.Bitmap
import android.graphics.PointF
import android.os.SystemClock
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.ImageProxy
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.scanx.app.ScanXApp
import com.scanx.app.data.AppPreferences
import com.scanx.app.data.CaptureMode
import com.scanx.app.scan.AiDocumentDetector
import com.scanx.app.scan.AutoCaptureController
import com.scanx.app.scan.CapturedPage
import com.scanx.app.scan.DetectedQuad
import com.scanx.app.scan.ImageProxyUtils
import com.scanx.app.scan.OrientationDetector
import com.scanx.app.scan.PerspectiveTransformer
import com.scanx.app.scan.QuadSmoother
import com.scanx.app.scan.ScanFilters
import com.scanx.app.scan.maxCornerDistance
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import com.scanx.app.data.PdfExportMode
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.opencv.core.Core
import org.opencv.core.Mat
import org.opencv.imgcodecs.Imgcodecs
import org.opencv.imgproc.Imgproc
import java.io.File
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.util.concurrent.Executors

/**
 * Điều phối 1 phiên quét:
 *  - Luồng phân tích (ImageAnalysis RGBA 640×480): AI DocAligner phát hiện 4 góc → làm mượt →
 *    [AutoCaptureController] (giữ yên 0,45 s — rất yên thì 0,27 s, đủ nét, đủ 4 góc, chỉ chụp TRANG MỚI).
 *  - Khi chụp: ảnh ~8 MP → AI chạy lại trên ảnh chụp → tinh chỉnh góc dưới-pixel → làm phẳng đúng
 *    tỉ lệ giấy thật → tự xoay đúng chiều đọc → kiểm tra trùng trang lần 2 trên ảnh đã làm phẳng →
 *    lưu master màu ra đĩa, hiển thị bản đen trắng (chế độ mặc định). Xử lý nối tiếp theo thứ tự chụp.
 */
class ScanCameraViewModel(application: Application) : AndroidViewModel(application) {

    private val prefs = AppPreferences(application)
    private val smoother = QuadSmoother()
    private val autoController = AutoCaptureController(holdMillis = prefs.autoCaptureStableFrames * HOLD_MS_PER_STEP)
    private val captureExecutor = Executors.newSingleThreadExecutor()
    private val recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
    private val processMutex = Mutex()
    private val sessionDir: File get() = File(getApplication<Application>().cacheDir, "scan_session").apply { mkdirs() }
    private val captured = ArrayList<CapturedPage>()
    @Volatile private var lastPageSignature: FloatArray? = null

    @Volatile private var imageCapture: ImageCapture? = null
    @Volatile private var isCapturing = false
    @Volatile private var latestQuad: DetectedQuad? = null
    @Volatile private var latestSignature: FloatArray? = null

    private val _captureMode = MutableStateFlow(prefs.captureMode)
    val captureMode: StateFlow<CaptureMode> = _captureMode.asStateFlow()

    private val _isFlashOn = MutableStateFlow(prefs.flashEnabled)
    val isFlashOn: StateFlow<Boolean> = _isFlashOn.asStateFlow()

    private val _detectedQuad = MutableStateFlow<DetectedQuad?>(null)
    val detectedQuad: StateFlow<DetectedQuad?> = _detectedQuad.asStateFlow()

    /** 0..1 — tiến độ giữ yên trước khi tự chụp (UI vẽ vòng tiến độ quanh nút chụp). */
    private val _autoProgress = MutableStateFlow(0f)
    val autoProgress: StateFlow<Float> = _autoProgress.asStateFlow()

    /** Đã chụp trang hiện tại, đang chờ người dùng lật sang trang mới. */
    private val _waitingForNewPage = MutableStateFlow(false)
    val waitingForNewPage: StateFlow<Boolean> = _waitingForNewPage.asStateFlow()

    private val _isAiReady = MutableStateFlow(false)
    val isAiReady: StateFlow<Boolean> = _isAiReady.asStateFlow()

    private val _processingCount = MutableStateFlow(0)
    val processingCount: StateFlow<Int> = _processingCount.asStateFlow()

    /** Ảnh xem trước (đen trắng) các trang đã chụp. */
    private val _pages = MutableStateFlow<List<Bitmap>>(emptyList())
    val pages: StateFlow<List<Bitmap>> = _pages.asStateFlow()

    private val _captureHint = MutableStateFlow(AutoCaptureController.Hint.NONE)
    val captureHint: StateFlow<AutoCaptureController.Hint> = _captureHint.asStateFlow()

    /** Yêu cầu lấy nét vào tâm tài liệu (toạ độ chuẩn hoá khung phân tích) khi bắt đầu giữ yên. */
    data class FocusRequest(val point: PointF, val id: Long)
    private val _focusRequest = MutableStateFlow<FocusRequest?>(null)
    val focusRequest: StateFlow<FocusRequest?> = _focusRequest.asStateFlow()

    private val _captureEvent = MutableStateFlow(0)
    val captureEvent: StateFlow<Int> = _captureEvent.asStateFlow()

    private val _errorMessage = MutableStateFlow<String?>(null)
    val errorMessage: StateFlow<String?> = _errorMessage.asStateFlow()

    init {
        viewModelScope.launch(Dispatchers.Default) {
            val openCvOk = (getApplication<Application>() as ScanXApp).isOpenCvReady
            val ok = openCvOk && AiDocumentDetector.ensureLoaded(getApplication())
            _isAiReady.value = ok
            if (!ok) {
                _errorMessage.value = "Không khởi động được AI nhận diện tài liệu — vẫn chụp thủ công được."
            }
        }
    }

    fun clearError() {
        _errorMessage.value = null
    }

    fun attachImageCapture(capture: ImageCapture?) {
        imageCapture = capture
    }

    /** Gọi từ analyzer CameraX (luồng nền). Luôn close() [image] để CameraX gửi khung tiếp theo. */
    fun onFrameAnalyzed(image: ImageProxy) {
        try {
            if (isCapturing || !_isAiReady.value) return
            val frame = ImageProxyUtils.rgbaToUprightBgr(image)
            try {
                val raw = AiDocumentDetector.detect(frame)
                val quad = smoother.update(raw)
                latestQuad = quad
                _detectedQuad.value = quad

                val signature = if (quad != null) AiDocumentDetector.pageSignature(frame, quad) else null
                latestSignature = signature

                if (_captureMode.value == CaptureMode.AUTO) {
                    val now = SystemClock.elapsedRealtime()
                    val decision = autoController.onFrame(quad, signature, now)
                    _autoProgress.value = decision.progress
                    _waitingForNewPage.value = decision.waitingForNewPage
                    _captureHint.value = decision.hint
                    if (decision.holdStarted && quad != null) {
                        val cx = quad.points.sumOf { it.x.toDouble() }.toFloat() / 4f
                        val cy = quad.points.sumOf { it.y.toDouble() }.toFloat() / 4f
                        _focusRequest.value = FocusRequest(PointF(cx, cy), now)
                    }
                    if (decision.shouldCapture) triggerCapture(quad, auto = true, afterRemoval = autoController.lastCaptureAfterRemoval)
                } else {
                    _autoProgress.value = 0f
                    _waitingForNewPage.value = false
                    _captureHint.value = if (quad == null) AutoCaptureController.Hint.NO_DOCUMENT else AutoCaptureController.Hint.NONE
                }
            } finally {
                frame.release()
            }
        } catch (e: Throwable) {
            _errorMessage.value = "Lỗi xử lý khung hình: ${e.message}"
        } finally {
            image.close()
        }
    }

    fun captureManually() {
        if (isCapturing) return
        val quad = latestQuad
        autoController.markCaptured(quad, latestSignature, SystemClock.elapsedRealtime())
        triggerCapture(quad, auto = false, afterRemoval = false)
    }

    private fun triggerCapture(liveQuad: DetectedQuad?, auto: Boolean, afterRemoval: Boolean) {
        val capture = imageCapture
        if (capture == null) {
            _errorMessage.value = "Camera chưa sẵn sàng, thử lại sau."
            return
        }
        isCapturing = true
        _autoProgress.value = 0f
        _captureEvent.value = _captureEvent.value + 1
        capture.takePicture(captureExecutor, object : ImageCapture.OnImageCapturedCallback() {
            override fun onCaptureSuccess(image: ImageProxy) {
                val bitmap = try {
                    ImageProxyUtils.capturedToUprightBitmap(image, CAPTURE_MAX_SIDE)
                } catch (e: Throwable) {
                    _errorMessage.value = "Không đọc được ảnh chụp: ${e.message}"
                    null
                } finally {
                    image.close()
                    isCapturing = false
                    smoother.reset()
                }
                if (bitmap != null) processCaptured(bitmap, liveQuad, auto, afterRemoval)
            }

            override fun onError(exception: ImageCaptureException) {
                isCapturing = false
                _errorMessage.value = "Chụp ảnh thất bại: ${exception.message}"
            }
        })
    }

    private fun processCaptured(bitmap: Bitmap, liveQuad: DetectedQuad?, auto: Boolean, afterRemoval: Boolean) {
        _processingCount.value = _processingCount.value + 1
        viewModelScope.launch(Dispatchers.Default) {
            processMutex.withLock {
                var master: Mat? = null
                try {
                    val refined = if (_isAiReady.value) {
                        val small = ImageProxyUtils.bitmapToSmallBgr(bitmap)
                        try {
                            AiDocumentDetector.detect(small)
                        } finally {
                            small.release()
                        }
                    } else {
                        null
                    }?.takeIf { it.confidence >= REFINE_MIN_CONFIDENCE }
                    // Khung trên luồng xem trước đã đứng yên suốt thời gian giữ nên đáng tin; kết quả AI trên ảnh chụp chỉ
                    // thay thế khi khớp với nó (tránh trường hợp AI nhận nhầm vật khác làm trang giấy).
                    val quad = when {
                        refined != null && liveQuad != null -> if (maxCornerDistance(refined, liveQuad) < 0.06f) refined else liveQuad
                        else -> refined ?: liveQuad
                    }
                    var m = if (quad != null) {
                        PerspectiveTransformer.warp(bitmap, quad.points, PAGE_MAX_SIDE)
                    } else {
                        PerspectiveTransformer.whole(bitmap, PAGE_MAX_SIDE)
                    }
                    bitmap.recycle()
                    master = m

                    val rotation = runCatching { OrientationDetector.detect(recognizer, m) }.getOrDefault(0)
                    if (rotation != 0) {
                        val r = Mat()
                        Core.rotate(m, r, when (rotation) { 90 -> Core.ROTATE_90_CLOCKWISE; 180 -> Core.ROTATE_180; else -> Core.ROTATE_90_COUNTERCLOCKWISE })
                        m.release()
                        m = r
                        master = r
                    }

                    // Kiểm tra trùng lần 2 trên ảnh đã làm phẳng + xoay đúng chiều (chính xác hơn khung xem trước).
                    val gray = Mat()
                    Imgproc.cvtColor(m, gray, Imgproc.COLOR_RGBA2GRAY)
                    val sig = AiDocumentDetector.signatureFromGray(gray)
                    gray.release()
                    val prev = lastPageSignature
                    if (auto && !afterRemoval && prev != null && AiDocumentDetector.pageSimilarity(prev, sig) > DUPLICATE_SIMILARITY) {
                        _errorMessage.value = "Trang trùng với trang vừa chụp — đã bỏ qua"
                        return@withLock
                    }

                    val file = File(sessionDir, "p_${System.currentTimeMillis()}_${captured.size}.jpg")
                    val bgr = Mat()
                    Imgproc.cvtColor(m, bgr, Imgproc.COLOR_RGBA2BGR)
                    val ok = Imgcodecs.imwrite(file.absolutePath, bgr, org.opencv.core.MatOfInt(Imgcodecs.IMWRITE_JPEG_QUALITY, 92))
                    bgr.release()
                    if (!ok) error("không ghi được ảnh trang")
                    val full = Bitmap.createBitmap(m.cols(), m.rows(), Bitmap.Config.ARGB_8888)
                    org.opencv.android.Utils.matToBitmap(m, full)
                    val preview = ScanFilters.renderBitmap(full, PdfExportMode.DEFAULT, PREVIEW_MAX_SIDE)
                    full.recycle()
                    lastPageSignature = sig
                    synchronized(captured) { captured.add(CapturedPage(file, preview)) }
                    _pages.value = _pages.value + preview
                } catch (e: Throwable) {
                    if (!bitmap.isRecycled) bitmap.recycle()
                    _errorMessage.value = "Không xử lý được ảnh vừa chụp: ${e.message}"
                } finally {
                    master?.release()
                    _processingCount.value = (_processingCount.value - 1).coerceAtLeast(0)
                }
            }
        }
    }

    fun removePage(index: Int) {
        synchronized(captured) {
            if (index !in captured.indices) return
            val removed = captured.removeAt(index)
            removed.masterFile.delete()
            _pages.value = captured.map { it.preview }
            removed.preview.recycle()
            if (captured.isEmpty()) lastPageSignature = null
        }
    }

    fun setCaptureMode(mode: CaptureMode) {
        _captureMode.value = mode
        prefs.captureMode = mode
        autoController.reset()
        _autoProgress.value = 0f
        _waitingForNewPage.value = false
    }

    fun toggleFlash() {
        _isFlashOn.value = !_isFlashOn.value
        prefs.flashEnabled = _isFlashOn.value
    }

    /** Kết thúc phiên: trả danh sách trang (ảnh master trên đĩa), phiên sau bắt đầu lại từ đầu. */
    fun takeSessionPages(): List<CapturedPage> {
        val result = synchronized(captured) { captured.toList().also { captured.clear() } }
        _pages.value = emptyList()
        autoController.reset()
        lastPageSignature = null
        return result
    }

    fun discardSession() {
        synchronized(captured) {
            captured.forEach { it.masterFile.delete(); if (!it.preview.isRecycled) it.preview.recycle() }
            captured.clear()
        }
        _pages.value = emptyList()
        autoController.reset()
        smoother.reset()
        lastPageSignature = null
    }

    override fun onCleared() {
        super.onCleared()
        captureExecutor.shutdown()
        recognizer.close()
    }

    companion object {
        /** Mỗi nấc độ nhạy trong Cài đặt = 90 ms giữ yên (mặc định 5 nấc = 0,45 s; khung rất yên chỉ ~0,27 s). */
        const val HOLD_MS_PER_STEP = 90L
        private const val CAPTURE_MAX_SIDE = 3264
        private const val PAGE_MAX_SIDE = 2800
        private const val PREVIEW_MAX_SIDE = 900
        private const val REFINE_MIN_CONFIDENCE = 0.5f
        /** Ảnh trang đã làm phẳng giống trang trước ≥ mức này → coi là chụp trùng. */
        private const val DUPLICATE_SIMILARITY = 0.55f
    }
}
