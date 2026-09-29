package com.scanx.app

import android.app.Application
import android.util.Log
import com.google.android.gms.common.moduleinstall.ModuleInstall
import com.google.android.gms.common.moduleinstall.ModuleInstallRequest
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.chinese.ChineseTextRecognizerOptions
import com.google.mlkit.vision.text.japanese.JapaneseTextRecognizerOptions
import com.google.mlkit.vision.text.korean.KoreanTextRecognizerOptions
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import org.opencv.android.OpenCVLoader

/**
 * Application class cho ScanX.
 *
 * Khởi tạo OpenCV native lib ngay khi app mở (bắt buộc phải gọi trước khi dùng bất kỳ class nào
 * trong org.opencv.*, ví dụ Imgproc). Từ OpenCV 4.9.0, bản Android publish thẳng lên Maven Central
 * đã đóng gói sẵn thư viện native trong AAR nên chỉ cần gọi initLocal() (đồng bộ, không cần app
 * "OpenCV Manager" cài riêng như bản cũ).
 */
class ScanXApp : Application() {

    var isOpenCvReady: Boolean = false
        private set

    override fun onCreate() {
        super.onCreate()
        isOpenCvReady = try {
            OpenCVLoader.initLocal()
        } catch (e: Throwable) {
            Log.e(TAG, "Khởi tạo OpenCV thất bại", e)
            false
        }
        if (!isOpenCvReady) {
            Log.e(TAG, "OpenCV initLocal() trả về false — tính năng tự nhận diện biên tài liệu sẽ không hoạt động.")
        }
        preloadMlKitModules()
    }

    /**
     * Model OCR (Latin/Trung/Nhật/Hàn) nằm trong Google Play services, không nhúng trong APK. Cài từ Google
     * Play thì Play tự tải theo meta-data trong manifest; cài APK ngoài → yêu cầu tải ngay khi mở app để
     * lần OCR đầu tiên không phải chờ. Máy không có Play services: bỏ qua (OCR báo lỗi như cũ).
     */
    private fun preloadMlKitModules() {
        runCatching {
            val clients = listOf(
                TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS),
                TextRecognition.getClient(ChineseTextRecognizerOptions.Builder().build()),
                TextRecognition.getClient(JapaneseTextRecognizerOptions.Builder().build()),
                TextRecognition.getClient(KoreanTextRecognizerOptions.Builder().build()),
            )
            val request = ModuleInstallRequest.newBuilder().apply { clients.forEach { addApi(it) } }.build()
            ModuleInstall.getClient(this).installModules(request)
                .addOnFailureListener { Log.w(TAG, "Không tải trước được model OCR: ${it.message}") }
                .addOnCompleteListener { clients.forEach { c -> runCatching { c.close() } } }
        }.onFailure { Log.w(TAG, "Bỏ qua tải trước model OCR", it) }
    }

    companion object {
        private const val TAG = "ScanXApp"
    }
}
