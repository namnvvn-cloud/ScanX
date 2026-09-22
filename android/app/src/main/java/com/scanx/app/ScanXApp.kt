package com.scanx.app

import android.app.Application
import android.util.Log
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
    }

    companion object {
        private const val TAG = "ScanXApp"
    }
}
