package com.scanx.app.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.unit.dp
import com.scanx.app.R
import com.scanx.app.convert.ExportFormat
import com.scanx.app.convert.TranslationChoice

/**
 * Hộp thoại "Dịch sang tiếng Việt": chọn máy dịch trong 3 lựa chọn — Gemini (miễn phí, ưu tiên mặc
 * định khi đã có key), Claude (trả phí, chính xác nhất), ML Kit (offline). Tuỳ chọn đọc chữ bằng AI
 * Cloud (Claude) trước khi dịch, định dạng bản dịch (Word giữ bố cục / TXT).
 * [showShare] = hiện 2 nút Chia sẻ / Lưu (màn tài liệu); false = 1 nút Dịch (công cụ dịch file import).
 */
@Composable
fun TranslateDialog(
    cloudConfigured: Boolean,
    geminiConfigured: Boolean,
    showShare: Boolean,
    onDismiss: () -> Unit,
    onOpenCloudSettings: () -> Unit,
    onOpenGeminiSettings: () -> Unit,
    onConfirm: (engine: TranslationChoice, cloudOcr: Boolean, output: ExportFormat, share: Boolean) -> Unit,
) {
    var engine by remember {
        mutableStateOf(
            when {
                geminiConfigured -> TranslationChoice.GEMINI
                cloudConfigured -> TranslationChoice.CLAUDE
                else -> TranslationChoice.MLKIT
            },
        )
    }
    var cloudOcr by remember { mutableStateOf(false) }
    var output by remember { mutableStateOf(ExportFormat.DOCX) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.translate_title)) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                Text(stringResource(R.string.translate_desc), style = MaterialTheme.typography.bodySmall)
                Text(stringResource(R.string.translate_engine), style = MaterialTheme.typography.labelLarge, modifier = Modifier.padding(top = 12.dp))
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.fillMaxWidth().clickable { if (geminiConfigured) engine = TranslationChoice.GEMINI else onOpenGeminiSettings() },
                ) {
                    RadioButton(selected = engine == TranslationChoice.GEMINI, onClick = { if (geminiConfigured) engine = TranslationChoice.GEMINI else onOpenGeminiSettings() })
                    Text(
                        stringResource(if (geminiConfigured) R.string.translate_engine_gemini else R.string.translate_engine_gemini_off),
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.fillMaxWidth().clickable { if (cloudConfigured) engine = TranslationChoice.CLAUDE else onOpenCloudSettings() },
                ) {
                    RadioButton(selected = engine == TranslationChoice.CLAUDE, enabled = cloudConfigured, onClick = { engine = TranslationChoice.CLAUDE })
                    Text(
                        stringResource(if (cloudConfigured) R.string.translate_engine_claude else R.string.translate_engine_claude_off),
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().clickable { engine = TranslationChoice.MLKIT }) {
                    RadioButton(selected = engine == TranslationChoice.MLKIT, onClick = { engine = TranslationChoice.MLKIT })
                    Text(stringResource(R.string.translate_engine_mlkit), style = MaterialTheme.typography.bodyMedium)
                }
                if (cloudConfigured) {
                    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().clickable { cloudOcr = !cloudOcr }) {
                        Checkbox(checked = cloudOcr, onCheckedChange = { cloudOcr = it })
                        Text(stringResource(R.string.translate_cloud_ocr), style = MaterialTheme.typography.bodyMedium)
                    }
                }
                Text(stringResource(R.string.translate_output), style = MaterialTheme.typography.labelLarge, modifier = Modifier.padding(top = 12.dp))
                for ((fmt, label) in listOf(ExportFormat.DOCX to R.string.translate_output_docx, ExportFormat.TXT to R.string.translate_output_txt)) {
                    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().clickable { output = fmt }) {
                        RadioButton(selected = output == fmt, onClick = { output = fmt })
                        Text(stringResource(label), style = MaterialTheme.typography.bodyMedium)
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { onConfirm(engine, cloudOcr && cloudConfigured, output, false) }) {
                Text(stringResource(if (showShare) R.string.export_save else R.string.translate_action))
            }
        },
        dismissButton = {
            if (showShare) {
                TextButton(onClick = { onConfirm(engine, cloudOcr && cloudConfigured, output, true) }) {
                    Text(stringResource(R.string.action_share))
                }
            } else {
                TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) }
            }
        },
    )
}
