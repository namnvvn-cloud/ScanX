package com.scanx.app.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.google.android.gms.tasks.Tasks
import com.google.firebase.auth.FirebaseAuth
import com.scanx.app.data.BackendApi
import com.scanx.app.data.BusinessFeature
import com.scanx.app.data.Entitlements
import com.scanx.app.data.EntitlementsStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/** Kết quả kiểm tra quyền trước khi chạy 1 tính năng Business. */
sealed interface FeatureAccess {
    /** Có gói Business còn hạn. */
    data object Business : FeatureAccess
    /** Chưa có Business nhưng còn lượt dùng thử — [remainingAfter] = số lượt còn lại sau lần này. */
    data class Trial(val remainingAfter: Int) : FeatureAccess
    /** Bị khoá — hiện hộp thoại Business cho tính năng [feature]. */
    data class Locked(val feature: BusinessFeature) : FeatureAccess
}

/**
 * Trạng thái gói Business của tài khoản đang đăng nhập (mục 5+6 Phase 2). Nguồn: GET /users/me — backend
 * đã tính sẵn còn hạn/hết hạn. Lưu lại để dùng offline; chưa đăng nhập = Free. Tự làm mới khi đổi tài
 * khoản và khi app quay lại foreground (người dùng vừa mua gói trên web xong quay về app).
 */
class EntitlementsViewModel(application: Application) : AndroidViewModel(application) {

    private val auth = FirebaseAuth.getInstance()
    private val store = EntitlementsStore(application)

    private val _state = MutableStateFlow(initialState())
    val state: StateFlow<Entitlements> = _state.asStateFlow()

    private val _refreshing = MutableStateFlow(false)
    val refreshing: StateFlow<Boolean> = _refreshing.asStateFlow()

    private val _refreshError = MutableStateFlow<String?>(null)
    val refreshError: StateFlow<String?> = _refreshError.asStateFlow()

    private var lastRefreshAt = 0L
    private var lastUid: String? = auth.currentUser?.uid

    private val authListener = FirebaseAuth.AuthStateListener { fa ->
        val uid = fa.currentUser?.uid
        if (uid != lastUid) {
            lastUid = uid
            if (uid == null) {
                store.clearAccount()
                _state.value = Entitlements.free(null)
            } else {
                _state.value = store.cached(fa.currentUser?.email) ?: Entitlements.free(fa.currentUser?.email)
                refresh(force = true)
            }
        }
    }

    init {
        auth.addAuthStateListener(authListener)
        refresh(force = true)
    }

    override fun onCleared() {
        auth.removeAuthStateListener(authListener)
    }

    private fun initialState(): Entitlements {
        val email = auth.currentUser?.email
        return store.cached(email) ?: Entitlements.free(email)
    }

    /** Gọi khi app quay lại foreground — tối đa 1 lần / 30 s để không gọi server liên tục. */
    fun refreshIfStale() {
        if (System.currentTimeMillis() - lastRefreshAt > 30_000) refresh(force = false)
    }

    fun refresh(force: Boolean = true) {
        val user = auth.currentUser ?: run { _state.value = Entitlements.free(null); return }
        if (_refreshing.value && !force) return
        lastRefreshAt = System.currentTimeMillis()
        _refreshing.value = true
        _refreshError.value = null
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val token = Tasks.await(user.getIdToken(false))?.token
                    ?: throw IllegalStateException("Không lấy được token đăng nhập")
                val me = BackendApi(token).me()
                val email = user.email ?: me.optString("email")
                store.save(email, me)
                _state.value = Entitlements.fromUserJson(email, me)
            } catch (e: Exception) {
                // Giữ trạng thái đã lưu (offline / server đang "ngủ") — chỉ báo lỗi khi người dùng tự bấm làm mới.
                _refreshError.value = e.message ?: "Không tải được trạng thái gói"
            } finally {
                _refreshing.value = false
            }
        }
    }

    fun trialsRemaining(f: BusinessFeature): Int =
        (_state.value.trialLimit(f) - store.trialsUsed(f)).coerceAtLeast(0)

    /**
     * Kiểm tra (và nếu dùng thử thì TRỪ lượt) cho 1 thao tác cần các tính năng [features]. Chỉ trừ lượt khi
     * TẤT CẢ tính năng đều được phép — tránh mất lượt khi thao tác vẫn bị chặn.
     */
    fun tryUse(features: List<BusinessFeature>): FeatureAccess {
        if (features.isEmpty()) return FeatureAccess.Business
        val ent = _state.value
        val missing = features.filterNot { ent.hasFeature(it) }
        if (missing.isEmpty()) return FeatureAccess.Business
        missing.firstOrNull { trialsRemaining(it) <= 0 }?.let { return FeatureAccess.Locked(it) }
        missing.forEach { store.consumeTrial(it) }
        return FeatureAccess.Trial(missing.minOf { trialsRemaining(it) })
    }
}
