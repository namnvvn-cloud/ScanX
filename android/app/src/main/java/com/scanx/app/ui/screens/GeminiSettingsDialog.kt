package com.scanx.app.ui.screens

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.scanx.app.R
import com.scanx.app.convert.GeminiAiClient

/**
 * Cấu hình máy dịch Gemini (MIỄN PHÍ): người dùng dán API key lấy tại aistudio.google.com/apikey
 * (không cần thẻ). Chỉ dùng cho tính năng dịch chữ, không liên quan tính năng đọc chữ viết tay
 * "AI Cloud" (vẫn dùng Claude riêng). Google đổi tên model khá thường xuyên nên ô model để gõ tự do,
 * kèm 2 gợi ý bấm nhanh.
 */
@Composable
fun GeminiSettingsDialog(
    initialKey: String,
    initialModel: String,
    onDismiss: () -> Unit,
    onSave: (key: String, model: String) -> Unit,
) {
    var key by remember { mutableStateOf(initialKey) }
    var model by remember { mutableStateOf(initialModel) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.settings_gemini)) },
        text = {
            Column {
                Text(stringResource(R.string.gemini_settings_desc), style = MaterialTheme.typography.bodySmall)
                OutlinedTextField(
                    value = key,
                    onValueChange = { key = it.trim() },
                    label = { Text(stringResource(R.string.gemini_settings_key)) },
                    singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                    modifier = Modifier.fillMaxWidth().padding(top = 12.dp),
                )
                OutlinedTextField(
                    value = model,
                    onValueChange = { model = it.trim() },
                    label = { Text(stringResource(R.string.gemini_settings_model)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth().padding(top = 12.dp),
                )
                Row(modifier = Modifier.fillMaxWidth().padding(top = 4.dp)) {
                    for ((id, label) in GeminiAiClient.SUGGESTED_MODELS) {
                        TextButton(onClick = { model = id }) { Text(label, style = MaterialTheme.typography.labelSmall) }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = { onSave(key, model.ifBlank { GeminiAiClient.DEFAULT_MODEL }) }) { Text(stringResource(R.string.action_ok)) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) } },
    )
}
