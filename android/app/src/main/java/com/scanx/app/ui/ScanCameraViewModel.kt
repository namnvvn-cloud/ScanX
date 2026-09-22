package com.scanx.app.ui

import android.app.Application
import android.graphics.Bitmap
import androidx.camera.core.ImageProxy
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.scanx.app.ScanXApp
import com.scanx.app.data.AppPreferences
import com.scanx.app.data.CaptureMode
import com.scanx.app.scan.CaptureStabilityTracker
import com.scanx.app.scan.DetectedQuad
import com.scanx.app.scan.DocumentContourDetector
import com.scanx.app.scan.ImageProxyUtils
import com.scanx.app.scan.PerspectiveTransformer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * Quản lý 1 phiên quét bằng camera tự viết (CameraX + OpenCV): nhận từng khung hình phân tích,
 * phát hiện 4 góc tài liệu, tự động chụp khi khung ổn định (chế độ Auto) hoặc chờ người dùng bấm
 * (chế độ Manual), làm phẳng + tăng cường ảnh ngay sau khi chụp, giữ danh sách trang trong phiên.
 *
 * Việc binding CameraX Preview/ImageAnalysis nằm ở Composable (cần LifecycleOwner) — ViewModel chỉ
 * xử lý dữ liệu khung hình do Composable đưa vào qua [onFrameAnalyzed].
 */
class ScanCameraViewModel(application: Application) : AndroidViewModel(application) {

    private val prefs = AppPreferences(application)
    private val isOpenCvReady get() = (getApplication<Application>() as ScanXApp).isOpenCvReady

    private val stabilityTracker = CaptureStabilityTracker(
        requiredStableFrames = prefs.autoCaptureStableFrames,
    )

    private val frameLock = Any()
    @Volatile private var latestFrame: Bitmap? = null
    @Volatile private var latestQuad: DetectedQuad? = null
    @Volatile private var isCapturing = false

    private val _captureMode = MutableStateFlow(prefs.captureMode)
    val captureMode: StateFlow<CaptureMode> = _captureMode.asStateFlow()

    private val _isFlashOn = MutableStateFlow(prefs.flashEnabled)
    val isFlashOn: StateFlow<Boolean> = _isFlashOn.asStateFlow()

    private val _detectedQuad = MutableStateFlow<DetectedQuad?>(null)
    val detectedQuad: StateFlow<DetectedQuad?> = _detectedQuad.asStateFlow()

    private val _pages = MutableStateFlow<List<Bitmap>>(emptyList())
    val pages: StateFlow<List<Bitmap>> = _pages.asStateFlow()

    /** Tăng dần mỗi lần vừa chụp xong 1 trang — UI quan sát để phát hiệu ứng nháy/âm thanh chụp. */
    private val _captureEvent = MutableStateFlow(0)
    val captureEvent: StateFlow<Int> = _captureEvent.asStateFlow()

    private val _errorMessage = MutableStateFlow<String?>(null)
    val errorMessage: StateFlow<String?> = _errorMessage.asStateFlow()

    fun clearError() {
        _errorMessage.value = null
    }

    /**
     * Gọi từ analyzer của CameraX (chạy trên executor nền, KHÔNG phải main thread) cho mỗi khung
     * hình. Bắt buộc phải đóng [image] sau khi xử lý xong (CameraX chỉ gửi khung tiếp theo sau khi
     * ImageProxy hiện tại được close()).
     */
    fun onFrameAnalyzed(image: ImageProxy) {
        try {
            if (isCapturing) return
            val bitmap = ImageProxyUtils.toUprightBitmap(image)
            val quad = if (isOpenCvReady) DocumentContourDetector.detect(bitmap) else null

            synchronized(frameLock) {
                latestFrame = bitmap
                latestQuad = quad
            }
            _detectedQuad.value = quad

            if (_captureMode.value == CaptureMode.AUTO && stabilityTracker.onFrame(quad)) {
                performCapture(bitmap, quad)
            }
        } catch (e: Exception) {
            _errorMessage.value = "Lỗi xử lý khung hình camera: ${e.message}"
        } finally {
            image.close()
        }
    }

    /** Chụp thủ công: dùng luôn khung hình mới nhất đã phân tích, không cần chụp ảnh riêng nên không có độ trễ. */
    fun captureManually() {
        if (isCapturing) return
        val (frame, quad) = synchronized(frameLock) { latestFrame to latestQuad }
        if (frame == null) {
            _errorMessage.value = "Chưa nhận được hình ảnh từ camera, thử lại sau."
            return
        }
        performCapture(frame, quad)
    }

    private fun performCapture(frame: Bitmap, quad: DetectedQuad?) {
        isCapturing = true
        viewModelScope.launch(Dispatchers.Default) {
            try {
                val processed = if (quad != null) {
                    PerspectiveTransformer.warpAndEnhance(frame, quad.points)
                } else {
                    PerspectiveTransformer.enhanceOnly(frame)
                }
                _pages.value = _pages.value + processed
                _captureEvent.value = _captureEvent.value + 1
                stabilityTracker.reset()
            } catch (e: Exception) {
                _errorMessage.value = "Không xử lý được ảnh vừa chụp: ${e.message}"
            } finally {
                isCapturing = false
            }
        }
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
        stabilityTracker.reset()
    }

    fun toggleFlash() {
        _isFlashOn.value = !_isFlashOn.value
        prefs.flashEnabled = _isFlashOn.value
    }

    /** Kết thúc phiên quét: trả lại danh sách trang đã chụp, phiên tiếp theo bắt đầu từ danh sách rỗng. */
    fun takeSessionPages(): List<Bitmap> {
        val result = _pages.value
        _pages.value = emptyList()
        return result
    }

    fun discardSession() {
        _pages.value.forEach { it.recycle() }
        _pages.value = emptyList()
        stabilityTracker.reset()
    }

    override fun onCleared() {
        super.onCleared()
        synchronized(frameLock) { latestFrame }?.let { if (!it.isRecycled) it.recycle() }
        _pages.value.forEach { if (!it.isRecycled) it.recycle() }
    }
}
