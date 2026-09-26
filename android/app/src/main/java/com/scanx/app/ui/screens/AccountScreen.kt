package com.scanx.app.ui.screens

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.google.android.gms.auth.api.signin.GoogleSignIn
import com.google.android.gms.common.api.ApiException
import com.scanx.app.data.DocumentMeta
import com.scanx.app.ui.AuthViewModel
import java.io.File

/**
 * Màn "Tài khoản & Sao lưu": đăng nhập TUỲ CHỌN (Email/Password + Google) để dùng tính năng
 * "Sao lưu lên đám mây" — 1 CHIỀU, bấm tay (quyết định MVP đã chốt với anh Nam, không tự động,
 * không đồng bộ 2 chiều). Không đăng nhập vẫn dùng app quét/xuất file bình thường như trước.
 */
@Composable
fun AccountScreen(
    viewModel: AuthViewModel,
    documents: List<DocumentMeta>,
    getPdfFile: (String) -> File,
    onBack: () -> Unit,
) {
    val email by viewModel.currentEmail.collectAsStateWithLifecycle()
    val busy by viewModel.busy.collectAsStateWithLifecycle()
    val error by viewModel.error.collectAsStateWithLifecycle()
    val backupStatus by viewModel.backupStatus.collectAsStateWithLifecycle()

    val googleLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        val task = GoogleSignIn.getSignedInAccountFromIntent(result.data)
        try {
            val account = task.getResult(ApiException::class.java)
            account.idToken?.let { viewModel.signInWithGoogleIdToken(it) }
        } catch (e: ApiException) {
            // Người dùng bấm huỷ hoặc lỗi Google Sign-In — không cần báo lỗi kỹ thuật khó hiểu.
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Tài khoản & Sao lưu") },
                navigationIcon = {
                    IconButton(onClick = onBack) { Icon(Icons.Filled.ArrowBack, contentDescription = null) }
                },
            )
        }
    ) { padding ->
        Column(modifier = Modifier.fillMaxSize().padding(padding).padding(20.dp)) {
            if (email == null) {
                LoginForm(
                    busy = busy,
                    error = error,
                    onLogin = viewModel::signInWithEmail,
                    onRegister = viewModel::registerWithEmail,
                    onGoogle = { googleLauncher.launch(viewModel.googleSignInIntent()) },
                    onDismissError = viewModel::clearError,
                )
            } else {
                Text("Đã đăng nhập: $email", style = MaterialTheme.typography.titleMedium)
                Spacer(Modifier.height(8.dp))
                Text("Tài liệu trên máy: ${documents.size}", style = MaterialTheme.typography.bodyMedium)
                Spacer(Modifier.height(20.dp))
                Button(
                    onClick = { viewModel.backupAll(documents, getPdfFile) },
                    enabled = !busy && documents.isNotEmpty(),
                    modifier = Modifier.fillMaxWidth(),
                ) { Text("Sao lưu lên đám mây") }
                Text(
                    "Sao lưu 1 chiều: đẩy tài liệu từ máy lên cloud, không tự động, không đồng bộ 2 chiều. " +
                        "Server free tier có thể chờ ~1 phút nếu lâu chưa dùng.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 8.dp),
                )
                if (busy) {
                    Spacer(Modifier.height(16.dp))
                    LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                }
                backupStatus?.let {
                    Spacer(Modifier.height(12.dp))
                    Text(it, style = MaterialTheme.typography.bodyMedium)
                }
                error?.let {
                    Spacer(Modifier.height(12.dp))
                    Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                }
                Spacer(Modifier.weight(1f))
                OutlinedButton(onClick = { viewModel.signOut() }, modifier = Modifier.fillMaxWidth()) {
                    Text("Đăng xuất")
                }
            }
        }
    }
}

@Composable
private fun LoginForm(
    busy: Boolean,
    error: String?,
    onLogin: (String, String) -> Unit,
    onRegister: (String, String) -> Unit,
    onGoogle: () -> Unit,
    onDismissError: () -> Unit,
) {
    var email by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }

    Text(
        "Đăng nhập để sao lưu tài liệu lên đám mây (tuỳ chọn — không đăng nhập vẫn dùng app bình thường).",
        style = MaterialTheme.typography.bodyMedium,
    )
    Spacer(Modifier.height(16.dp))
    OutlinedTextField(
        value = email,
        onValueChange = { email = it.trim(); onDismissError() },
        label = { Text("Email") },
        singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email),
        modifier = Modifier.fillMaxWidth(),
    )
    Spacer(Modifier.height(8.dp))
    OutlinedTextField(
        value = password,
        onValueChange = { password = it; onDismissError() },
        label = { Text("Mật khẩu (tối thiểu 6 ký tự)") },
        singleLine = true,
        visualTransformation = PasswordVisualTransformation(),
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
        modifier = Modifier.fillMaxWidth(),
    )
    error?.let {
        Spacer(Modifier.height(8.dp))
        Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
    }
    Spacer(Modifier.height(16.dp))
    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Button(
            onClick = { onLogin(email, password) },
            enabled = !busy && email.isNotBlank() && password.length >= 6,
            modifier = Modifier.fillMaxWidth().weight(1f),
        ) { Text("Đăng nhập") }
        OutlinedButton(
            onClick = { onRegister(email, password) },
            enabled = !busy && email.isNotBlank() && password.length >= 6,
            modifier = Modifier.fillMaxWidth().weight(1f),
        ) { Text("Đăng ký") }
    }
    Spacer(Modifier.height(12.dp))
    OutlinedButton(onClick = onGoogle, enabled = !busy, modifier = Modifier.fillMaxWidth()) {
        Text("Đăng nhập bằng Google")
    }
    if (busy) {
        Spacer(Modifier.height(16.dp))
        LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
    }
}
