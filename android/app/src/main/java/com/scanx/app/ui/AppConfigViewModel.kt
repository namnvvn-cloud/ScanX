package com.scanx.app.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.scanx.app.BuildConfig
import com.scanx.app.data.AppConfigRepository
import com.scanx.app.data.ProductInfo
import com.scanx.app.data.UpdateInfo
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * Kiểm tra phiên bản mới (nhắc / ÉP cập nhật) + nội dung "Thông tin sản phẩm". Chính sách do backend
 * quyết định (Web Admin → Ứng dụng): bản mới nhất tự lấy từ GitHub Releases do CI build, "ép cập nhật"
 * bật/tắt được. Kiểm tra lúc mở app và mỗi lần quay lại app (tối đa 1 lần / 10 phút).
 */
class AppConfigViewModel(application: Application) : AndroidViewModel(application) {

    private val repo = AppConfigRepository(application)

    private val _update = MutableStateFlow<UpdateInfo?>(null)
    /** Khác null = cần hiện hộp thoại cập nhật (bắt buộc hoặc nhắc). */
    val update: StateFlow<UpdateInfo?> = _update.asStateFlow()

    private val _productInfo = MutableStateFlow(repo.cachedProductInfo())
    val productInfo: StateFlow<ProductInfo> = _productInfo.asStateFlow()

    private val _checking = MutableStateFlow(false)
    val checking: StateFlow<Boolean> = _checking.asStateFlow()

    /** Kết quả lần bấm "Kiểm tra cập nhật" thủ công (hiện ở màn Thông tin sản phẩm). */
    private val _manualResult = MutableStateFlow<String?>(null)
    val manualResult: StateFlow<String?> = _manualResult.asStateFlow()

    private var lastCheck = 0L

    init {
        check(manual = false)
    }

    fun checkIfStale() {
        // Đang bị ép cập nhật thì kiểm tra lại mỗi lần quay về app (có thể vừa cài xong bản mới).
        val interval = if (_update.value?.forceUpdate == true) 0L else 10 * 60_000L
        if (System.currentTimeMillis() - lastCheck > interval) check(manual = false)
    }

    fun check(manual: Boolean) {
        if (_checking.value) return
        lastCheck = System.currentTimeMillis()
        _checking.value = true
        if (manual) _manualResult.value = null
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val (info, product) = repo.fetch(BuildConfig.VERSION_CODE)
                _productInfo.value = product
                val snoozed = !info.forceUpdate && repo.snoozedUntil(info.latestVersionCode) > System.currentTimeMillis()
                _update.value = when {
                    info.forceUpdate -> info
                    info.updateAvailable && (manual || !snoozed) -> info
                    else -> null
                }
                if (manual) {
                    _manualResult.value = if (info.updateAvailable) {
                        "Có bản mới ${info.latestVersionName}"
                    } else {
                        "Bạn đang dùng bản mới nhất (${BuildConfig.VERSION_NAME})"
                    }
                }
            } catch (e: Exception) {
                // Mất mạng / server đang khởi động: không chặn người dùng.
                if (manual) _manualResult.value = "Không kiểm tra được: ${e.message ?: "lỗi mạng"}"
            } finally {
                _checking.value = false
            }
        }
    }

    /** "Để sau" — chỉ dùng được khi không bắt buộc. */
    fun snooze() {
        val info = _update.value ?: return
        if (info.forceUpdate) return
        repo.snooze(info.latestVersionCode)
        _update.value = null
    }
}
