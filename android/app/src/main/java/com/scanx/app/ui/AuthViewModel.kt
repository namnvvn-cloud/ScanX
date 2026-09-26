package com.scanx.app.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.google.android.gms.auth.api.signin.GoogleSignIn
import com.google.android.gms.auth.api.signin.GoogleSignInClient
import com.google.android.gms.auth.api.signin.GoogleSignInOptions
import com.google.android.gms.tasks.Task
import com.google.android.gms.tasks.Tasks
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.GoogleAuthProvider
import com.scanx.app.data.BackendApi
import com.scanx.app.data.DocumentMeta
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.io.File

/**
 * Đăng nhập Firebase Auth (Email/Password + Google) và gọi backend ScanX để sao lưu tài liệu.
 * Đăng nhập là TUỲ CHỌN (quyết định của anh Nam) — app vẫn dùng bình thường ở chế độ local-only nếu
 * không đăng nhập; đây chỉ là tính năng cộng thêm để sao lưu 1 CHIỀU lên cloud (bấm tay, không tự
 * động, không đồng bộ 2 chiều — MVP Phase 1).
 */
class AuthViewModel(application: Application) : AndroidViewModel(application) {

    private val auth = FirebaseAuth.getInstance()

    private val googleSignInClient: GoogleSignInClient by lazy {
        val opts = GoogleSignInOptions.Builder(GoogleSignInOptions.DEFAULT_SIGN_IN)
            .requestIdToken(WEB_CLIENT_ID)
            .requestEmail()
            .build()
        GoogleSignIn.getClient(application, opts)
    }

    private val _currentEmail = MutableStateFlow(auth.currentUser?.email)
    val currentEmail: StateFlow<String?> = _currentEmail.asStateFlow()

    private val _busy = MutableStateFlow(false)
    val busy: StateFlow<Boolean> = _busy.asStateFlow()

    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error.asStateFlow()

    private val _backupStatus = MutableStateFlow<String?>(null)
    val backupStatus: StateFlow<String?> = _backupStatus.asStateFlow()

    fun googleSignInIntent() = googleSignInClient.signInIntent

    fun clearError() {
        _error.value = null
    }

    fun signInWithEmail(email: String, password: String) = runAuthTask {
        auth.signInWithEmailAndPassword(email, password)
    }

    fun registerWithEmail(email: String, password: String) = runAuthTask {
        auth.createUserWithEmailAndPassword(email, password)
    }

    fun signInWithGoogleIdToken(idToken: String) = runAuthTask {
        auth.signInWithCredential(GoogleAuthProvider.getCredential(idToken, null))
    }

    private fun runAuthTask(block: () -> Task<*>) {
        _busy.value = true
        _error.value = null
        viewModelScope.launch(Dispatchers.IO) {
            try {
                Tasks.await(block())
                _currentEmail.value = auth.currentUser?.email
                registerWithBackend()
            } catch (e: Exception) {
                _error.value = e.message ?: "Đăng nhập thất bại"
            } finally {
                _busy.value = false
            }
        }
    }

    /** Gọi POST /auth/login 1 lần sau khi đăng nhập để backend tạo hồ sơ user nếu là lần đầu. */
    private fun registerWithBackend() {
        val token = auth.currentUser?.let { Tasks.await(it.getIdToken(false)) }?.token ?: return
        runCatching { BackendApi(token).login() }
    }

    fun signOut() {
        auth.signOut()
        googleSignInClient.signOut()
        _currentEmail.value = null
        _backupStatus.value = null
        _error.value = null
    }

    /**
     * Sao lưu 1 CHIỀU lên cloud: với mỗi tài liệu local, tạo metadata trên backend rồi PUT file PDF
     * thẳng lên storage qua presigned URL. KHÔNG tải xuống, KHÔNG xoá gì ở máy hay trên cloud.
     */
    fun backupAll(documents: List<DocumentMeta>, getPdfFile: (String) -> File) {
        val user = auth.currentUser ?: return
        if (documents.isEmpty()) return
        _busy.value = true
        _error.value = null
        _backupStatus.value = "Đang sao lưu 0/${documents.size}..."
        viewModelScope.launch(Dispatchers.IO) {
            var ok = 0
            var fail = 0
            try {
                val token = Tasks.await(user.getIdToken(false))?.token
                    ?: throw IllegalStateException("Không lấy được token đăng nhập")
                val api = BackendApi(token)
                api.login() // đảm bảo đã có hồ sơ user trước khi tạo document
                documents.forEachIndexed { index, doc ->
                    _backupStatus.value = "Đang sao lưu ${index + 1}/${documents.size}: ${doc.title}"
                    try {
                        val res = api.createDocument(doc.title, doc.pageCount)
                        val uploadUrl = res.getString("uploadUrl")
                        api.uploadFile(uploadUrl, getPdfFile(doc.id))
                        ok++
                    } catch (e: Exception) {
                        fail++
                    }
                }
                _backupStatus.value = if (fail == 0) {
                    "Đã sao lưu xong $ok tài liệu."
                } else {
                    "Đã sao lưu $ok tài liệu, $fail tài liệu lỗi (thử lại sau)."
                }
            } catch (e: Exception) {
                _error.value = e.message ?: "Sao lưu thất bại"
                _backupStatus.value = null
            } finally {
                _busy.value = false
            }
        }
    }

    companion object {
        /** OAuth Web Client ID (client_type 3 trong google-services.json) — dùng cho requestIdToken()
         *  để Google trả về idToken xác thực được ở Firebase (khác Android client_type 1). */
        const val WEB_CLIENT_ID = "455836379547-ninn40uifpgo9e6ql8s12qosf7j5h3ub.apps.googleusercontent.com"
    }
}
