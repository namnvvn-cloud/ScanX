package com.scanx.app.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.scanx.app.R
import com.scanx.app.convert.CloudAiClient

/**
 * Cấu hình "AI Cloud": người dùng dán API key Anthropic của chính họ (console.anthropic.com) và chọn
 * model. Key chỉ lưu trong bộ nhớ riêng của app trên máy; ảnh trang chỉ được gửi khi bật AI Cloud
 * cho từng lần xuất Word/Excel/PowerPoint.
 */
@Composable
fun CloudSettingsDialog(
    initialKey: String,
    initialModel: String,
    onDismiss: () -> Unit,
    onSave: (key: String, model: String) -> Unit,
) {
    var key by remember { mutableStateOf(initialKey) }
    var model by remember { mutableStateOf(initialModel) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.settings_cloud_ai)) },
        text = {
            Column {
                Text(stringResource(R.string.cloud_settings_desc), style = MaterialTheme.typography.bodySmall)
                OutlinedTextField(
                    value = key,
                    onValueChange = { key = it.trim() },
                    label = { Text(stringResource(R.string.cloud_settings_key)) },
                    singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                    modifier = Modifier.fillMaxWidth().padding(top = 12.dp),
                )
                Text(stringResource(R.string.cloud_settings_model), style = MaterialTheme.typography.labelLarge, modifier = Modifier.padding(top = 12.dp))
                for ((id, label) in CloudAiClient.MODELS) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.fillMaxWidth().clickable { model = id },
                    ) {
                        RadioButton(selected = model == id, onClick = { model = id })
                        Text(label, style = MaterialTheme.typography.bodyMedium)
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = { onSave(key, model) }) { Text(stringResource(R.string.action_ok)) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) } },
    )
}
