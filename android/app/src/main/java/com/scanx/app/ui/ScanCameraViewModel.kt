package com.scanx.app.ui

import android.app.Application
import android.graphics.Bitmap
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
import com.scanx.app.scan.DetectedQuad
import com.scanx.app.scan.ImageProxyUtils
import com.scanx.app.scan.PerspectiveTransformer
import com.scanx.app.scan.QuadSmoother
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.util.concurrent.Executors

/**
 * Điều phối 1 phiên quét:
 *  - Luồng phân tích (ImageAnalysis RGBA 640×480): AI DocAligner phát hiện 4 góc → làm mượt →
 *    máy trạng thái tự chụp theo thời gian giữ yên + nhận biết lật trang.
 *  - Khi chụp: ImageCapture lấy ảnh độ phân giải cao (CAPTURE_MODE_MINIMIZE_LATENCY), chạy AI lại
 *    trên chính ảnh chụp để tinh chỉnh góc chính xác, làm phẳng + tăng nét ở luồng nền. Luồng phân
 *    tích được mở lại ngay khi có ảnh (không chờ xử lý xong) → lật trang liên tục vẫn bắt kịp.
 */
class ScanCameraViewModel(application: Application) : AndroidViewModel(application) {

    private val prefs = AppPreferences(application)
    private val smoother = QuadSmoother()
    private val autoController = AutoCaptureController(holdMillis = prefs.autoCaptureStableFrames * HOLD_MS_PER_STEP)
    private val captureExecutor = Executors.newSingleThreadExecutor()

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

    private val _pages = MutableStateFlow<List<Bitmap>>(emptyList())
    val pages: StateFlow<List<Bitmap>> = _pages.asStateFlow()

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
                    val decision = autoController.onFrame(quad, signature, SystemClock.elapsedRealtime())
                    _autoProgress.value = decision.progress
                    _waitingForNewPage.value = decision.waitingForNewPage
                    if (decision.shouldCapture) triggerCapture(quad)
                } else {
                    _autoProgress.value = 0f
                    _waitingForNewPage.value = false
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
        triggerCapture(quad)
    }

    private fun triggerCapture(liveQuad: DetectedQuad?) {
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
                if (bitmap != null) processCaptured(bitmap, liveQuad)
            }

            override fun onError(exception: ImageCaptureException) {
                isCapturing = false
                _errorMessage.value = "Chụp ảnh thất bại: ${exception.message}"
            }
        })
    }

    private fun processCaptured(bitmap: Bitmap, liveQuad: DetectedQuad?) {
        _processingCount.value = _processingCount.value + 1
        viewModelScope.launch(Dispatchers.Default) {
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
                }
                val quad = refined?.takeIf { it.confidence >= REFINE_MIN_CONFIDENCE } ?: liveQuad
                val warped = if (quad != null) {
                    PerspectiveTransformer.warpAndEnhance(bitmap, quad.points)
                } else {
                    PerspectiveTransformer.enhanceOnly(bitmap)
                }
                bitmap.recycle()
                val page = limitSize(warped, PAGE_MAX_SIDE)
                _pages.value = _pages.value + page
            } catch (e: Throwable) {
                _errorMessage.value = "Không xử lý được ảnh vừa chụp: ${e.message}"
            } finally {
                _processingCount.value = (_processingCount.value - 1).coerceAtLeast(0)
            }
        }
    }

    private fun limitSize(src: Bitmap, maxSide: Int): Bitmap {
        val scale = maxSide.toFloat() / maxOf(src.width, src.height)
        if (scale >= 1f) return src
        val out = Bitmap.createScaledBitmap(
            src, (src.width * scale).toInt().coerceAtLeast(1), (src.height * scale).toInt().coerceAtLeast(1), true,
        )
        if (out !== src) src.recycle()
        return out
    }

    fun removePage(index: Int) {
        val current = _pages.value.toMutableList()
        if (index in current.indices) {
            val removed = current.removeAt(index)
            _pages.value = current
            removed.recycle()
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

    /** Kết thúc phiên: trả danh sách trang, phiên sau bắt đầu lại từ đầu. */
    fun takeSessionPages(): List<Bitmap> {
        val result = _pages.value
        _pages.value = emptyList()
        autoController.reset()
        return result
    }

    fun discardSession() {
        _pages.value.forEach { if (!it.isRecycled) it.recycle() }
        _pages.value = emptyList()
        autoController.reset()
        smoother.reset()
    }

    override fun onCleared() {
        super.onCleared()
        captureExecutor.shutdown()
        _pages.value.forEach { if (!it.isRecycled) it.recycle() }
    }

    companion object {
        /** Mỗi nấc độ nhạy trong Cài đặt = 40 ms giữ yên (mặc định 6 nấc = 0,24 s; ảnh rất nét chỉ ~0,15 s). */
        const val HOLD_MS_PER_STEP = 40L
        private const val CAPTURE_MAX_SIDE = 2400
        private const val PAGE_MAX_SIDE = 2000
        private const val REFINE_MIN_CONFIDENCE = 0.5f
    }
}
