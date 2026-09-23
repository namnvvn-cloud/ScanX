package com.scanx.app.ui

import android.app.Application
import android.graphics.Bitmap
import android.graphics.BitmapFactory
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
import com.scanx.app.data.PageFilter
import com.scanx.app.scan.AiDocumentDetector
import com.scanx.app.scan.AutoCaptureController
import com.scanx.app.scan.CapturedPage
import com.scanx.app.scan.DetectedQuad
import com.scanx.app.scan.ImageProxyUtils
import com.scanx.app.scan.PageRectifier
import com.scanx.app.scan.PerspectiveTransformer
import com.scanx.app.scan.QuadSmoother
import com.scanx.app.scan.ScanFilters
import com.scanx.app.scan.maxCornerDistance
import com.scanx.app.data.PdfExportMode
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.opencv.android.Utils
import org.opencv.core.Core
import org.opencv.core.Mat
import org.opencv.imgcodecs.Imgcodecs
import org.opencv.imgproc.Imgproc
import java.io.File
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.concurrent.Executors

/**
 * Điều phối 1 phiên quét:
 *  - Luồng phân tích (ImageAnalysis RGBA 640×480): AI DocAligner phát hiện 4 góc → làm mượt →
 *    [AutoCaptureController] (giữ yên 0,45 s — rất yên thì 0,27 s, đủ nét, đủ 4 góc, chỉ chụp TRANG MỚI).
 *  - Khi chụp: ảnh ~8 MP → AI chạy lại trên ảnh chụp → tinh chỉnh góc dưới-pixel → làm phẳng đúng
 *    tỉ lệ giấy thật → nắn dòng chữ ([PageRectifier]) → kiểm tra trùng trang lần 2 trên ảnh đã nắn →
 *    lưu master màu ra đĩa, hiển thị bản đen trắng (chế độ mặc định). Xử lý nối tiếp theo thứ tự chụp.
 */
class ScanCameraViewModel(application: Application) : AndroidViewModel(application) {

    private val prefs = AppPreferences(application)
    private val smoother = QuadSmoother()
    private val autoController = AutoCaptureController(holdMillis = prefs.autoCaptureStableFrames * HOLD_MS_PER_STEP)
    private val captureExecutor = Executors.newSingleThreadExecutor()
    private val processMutex = Mutex()
    private val sessionDir: File get() = File(getApplication<Application>().cacheDir, "scan_session").apply { mkdirs() }
    private val captured = ArrayList<CapturedPage>()
    @Volatile private var lastPageSignature: FloatArray? = null

    @Volatile private var imageCapture: ImageCapture? = null
    @Volatile private var isCapturing = false
    @Volatile private var latestQuad: DetectedQuad? = null
    @Volatile private var latestSignature: FloatArray? = null

    /** Tiêu cự thật ÷ đường chéo cảm biến (không đơn vị) — đọc 1 lần khi mở camera (bản 0.7), nhân
     *  với đường chéo ảnh chụp (px) ra tiêu cự pixel cho [PageGeometry]. Null = dùng ước lượng cũ. */
    @Volatile private var focalToSensorDiagRatio: Double? = null

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

    /**
     * Tiêu cự thật + kích thước cảm biến (mm) đọc từ CameraCharacteristics (bản 0.7) — quy về tỉ lệ
     * không đơn vị (tiêu cự ÷ đường chéo cảm biến) để dùng được với ảnh chụp ở bất kỳ độ phân giải
     * nào ([PerspectiveTransformer.warp] nhân lại với đường chéo ảnh thật). Không gọi được (máy cũ/
     * thiếu thông tin) → giữ null, [PageGeometry] tự quay về ước lượng như bản trước.
     */
    fun setCameraIntrinsics(focalLengthMm: Float, sensorWidthMm: Float, sensorHeightMm: Float) {
        val sensorDiagMm = kotlin.math.hypot(sensorWidthMm.toDouble(), sensorHeightMm.toDouble())
        if (focalLengthMm > 0f && sensorDiagMm > 0.0) {
            focalToSensorDiagRatio = focalLengthMm / sensorDiagMm
        }
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
                            // Bản 0.8: Net + khoá riêng với luồng xem trước ([ScanCameraViewModel.onFrameAnalyzed])
                            // để không làm khựng khung hình trang tiếp theo trong lúc trang này còn đang xử lý.
                            AiDocumentDetector.detectRefine(small)
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
                        val focalPx = focalToSensorDiagRatio?.let { it * kotlin.math.hypot(bitmap.width.toDouble(), bitmap.height.toDouble()) }
                        PerspectiveTransformer.warp(bitmap, quad.points, PAGE_MAX_SIDE, focalPx)
                    } else {
                        PerspectiveTransformer.whole(bitmap, PAGE_MAX_SIDE)
                    }
                    bitmap.recycle()
                    master = m

                    // Nắn lần 2 theo dòng chữ: bù nghiêng còn sót, kéo dòng chữ về ngang, cắt viền tối.
                    val rect = PageRectifier.rectify(m)
                    if (rect !== m) {
                        m.release()
                        m = rect
                        master = rect
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

    /** Đọc ảnh master đầy đủ độ phân giải của 1 trang đã chụp — dùng mở màn "Chỉnh sửa trang" (bản
     *  0.8). Chạy trên luồng nền (IO), gọi từ coroutine trong Compose. */
    suspend fun getCapturedMaster(index: Int): Bitmap? = withContext(Dispatchers.IO) {
        val file = synchronized(captured) { captured.getOrNull(index)?.masterFile } ?: return@withContext null
        runCatching { BitmapFactory.decodeFile(file.absolutePath) }.getOrNull()
    }

    fun getCapturedPageFilter(index: Int): PageFilter? = synchronized(captured) { captured.getOrNull(index)?.pageFilter }

    /** Đặt bộ lọc riêng cho 1 trang TRƯỚC khi lưu tài liệu (tab "Bộ lọc") — chỉ đổi metadata, cập
     *  nhật luôn ảnh xem trước theo đúng bộ lọc vừa chọn (không đổi ảnh master trên đĩa). */
    fun setCapturedPageFilter(index: Int, filter: PageFilter?) {
        viewModelScope.launch(Dispatchers.Default) {
            val page = synchronized(captured) { captured.getOrNull(index) } ?: return@launch
            val master = runCatching { BitmapFactory.decodeFile(page.masterFile.absolutePath) }.getOrNull() ?: return@launch
            val newPreview = try {
                if (filter != null) ScanFilters.renderPageFilter(master, filter, PREVIEW_MAX_SIDE)
                else ScanFilters.renderBitmap(master, PdfExportMode.DEFAULT, PREVIEW_MAX_SIDE)
            } finally {
                master.recycle()
            }
            synchronized(captured) {
                if (index !in captured.indices || captured[index] !== page) { newPreview.recycle(); return@launch }
                captured[index] = page.copy(preview = newPreview, pageFilter = filter)
                page.preview.recycle()
            }
            _pages.value = synchronized(captured) { captured.map { it.preview } }
        }
    }

    /**
     * Ghi đè ảnh master của 1 trang TRƯỚC khi lưu tài liệu (bản 0.8, tab "Cắt và xoay"/"Làm sạch") —
     * [newMaster] đã là kết quả cuối (đã cắt/xoay/vá). Cập nhật cả ảnh xem trước theo bộ lọc đang
     * chọn của trang đó (nếu có).
     */
    fun updateCapturedPageMaster(index: Int, newMaster: Bitmap) {
        commitCapturedPageEdit(index, newMaster, getCapturedPageFilter(index))
    }

    /**
     * Áp kết quả màn "Chỉnh sửa trang" (bản 0.8) cho 1 trang TRƯỚC khi lưu tài liệu — gộp ảnh master
     * mới (nếu có) VÀ bộ lọc riêng trang vào MỘT thao tác, tránh 2 coroutine ghi đè chồng chéo lên
     * nhau khi người dùng đổi cả ảnh lẫn bộ lọc trong cùng 1 lần chỉnh sửa. [newMaster] null = ảnh
     * không đổi (chỉ đổi bộ lọc) — khi có giá trị, hàm này nhận quyền sở hữu và sẽ recycle nó.
     */
    fun commitCapturedPageEdit(index: Int, newMaster: Bitmap?, filter: PageFilter?) {
        viewModelScope.launch(Dispatchers.Default) {
            val page = synchronized(captured) { captured.getOrNull(index) }
            if (page == null) { newMaster?.recycle(); return@launch }
            try {
                if (newMaster != null) {
                    val rgba = Mat()
                    val bgr = Mat()
                    Utils.bitmapToMat(newMaster, rgba)
                    Imgproc.cvtColor(rgba, bgr, Imgproc.COLOR_RGBA2BGR)
                    rgba.release()
                    Imgcodecs.imwrite(page.masterFile.absolutePath, bgr, org.opencv.core.MatOfInt(Imgcodecs.IMWRITE_JPEG_QUALITY, 92))
                    bgr.release()
                }
                val source = newMaster ?: BitmapFactory.decodeFile(page.masterFile.absolutePath) ?: return@launch
                val newPreview = if (filter != null) ScanFilters.renderPageFilter(source, filter, PREVIEW_MAX_SIDE)
                else ScanFilters.renderBitmap(source, PdfExportMode.DEFAULT, PREVIEW_MAX_SIDE)
                if (source !== newMaster) source.recycle()
                synchronized(captured) {
                    if (index !in captured.indices || captured[index] !== page) { newPreview.recycle(); return@launch }
                    captured[index] = page.copy(preview = newPreview, pageFilter = filter)
                    page.preview.recycle()
                }
                _pages.value = synchronized(captured) { captured.map { it.preview } }
            } finally {
                newMaster?.recycle()
            }
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
    }

    companion object {
        /** Mỗi nấc độ nhạy trong Cài đặt = 90 ms giữ yên (mặc định 5 nấc = 0,45 s; khung rất yên chỉ ~0,27 s). */
        const val HOLD_MS_PER_STEP = 90L
        // Bản 0.7: nâng cùng tỉ lệ ~1,27× với độ phân giải chụp mới (4160×3120, xem ScanCameraScreen)
        // — output DPI tăng từ ~157 lên ~199, ngang Scanner Pro. Bộ lọc (ScanFilters) chạy ở bước xuất
        // file, không phải trong vòng lặp tự chụp, nên không ảnh hưởng tốc độ căn/tự chụp thời gian thực.
        private const val CAPTURE_MAX_SIDE = 4160
        private const val PAGE_MAX_SIDE = 3548
        private const val PREVIEW_MAX_SIDE = 900
        private const val REFINE_MIN_CONFIDENCE = 0.5f
        /** Ảnh trang đã làm phẳng giống trang trước ≥ mức này → coi là chụp trùng. */
        private const val DUPLICATE_SIMILARITY = 0.55f
    }
}
