package com.scanx.app.ui.screens

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.scanx.app.R
import com.scanx.app.data.CaptureMode

/**
 * Cài đặt quét: chọn chế độ chụp mặc định (Auto/Thủ công) và độ nhạy tự chụp — số khung hình cần
 * đứng yên trước khi hệ thống tự bấm chụp. Giá trị càng thấp thì chụp càng nhanh (đổi lại dễ chụp
 * khi tay chưa hẳn hết rung), đúng với yêu cầu "chụp nhanh hơn, chuẩn hơn" của người dùng.
 */
@Composable
fun ScanningSettingsScreen(
    captureMode: CaptureMode,
    autoCaptureStableFrames: Int,
    flashDefault: Boolean,
    onBack: () -> Unit,
    onCaptureModeChange: (CaptureMode) -> Unit,
    onStableFramesChange: (Int) -> Unit,
    onFlashDefaultChange: (Boolean) -> Unit,
) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.scanning_settings_title)) },
                navigationIcon = {
                    IconButton(onClick = onBack) { Icon(Icons.Filled.ArrowBack, contentDescription = null) }
                }
            )
        }
    ) { padding ->
        Column(modifier = Modifier.padding(padding).padding(horizontal = 20.dp, vertical = 12.dp)) {
            Text(stringResource(R.string.scanning_capture_mode), style = MaterialTheme.typography.titleMedium)
            Row(modifier = Modifier.padding(top = 12.dp, bottom = 20.dp)) {
                FilterChip(
                    selected = captureMode == CaptureMode.AUTO,
                    onClick = { onCaptureModeChange(CaptureMode.AUTO) },
                    label = { Text(stringResource(R.string.scanning_capture_mode_auto)) },
                    modifier = Modifier.padding(end = 8.dp),
                )
                FilterChip(
                    selected = captureMode == CaptureMode.MANUAL,
                    onClick = { onCaptureModeChange(CaptureMode.MANUAL) },
                    label = { Text(stringResource(R.string.scanning_capture_mode_manual)) },
                )
            }

            HorizontalDivider()

            Text(
                stringResource(R.string.scanning_auto_sensitivity),
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.padding(top = 20.dp),
            )
            Text(
                stringResource(R.string.scanning_auto_sensitivity_desc),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 4.dp, bottom = 8.dp),
            )
            Slider(
                value = autoCaptureStableFrames.toFloat(),
                onValueChange = { onStableFramesChange(it.toInt()) },
                valueRange = 3f..15f,
                steps = 11,
                enabled = captureMode == CaptureMode.AUTO,
            )
            Row(modifier = Modifier.fillMaxWidth()) {
                Text(
                    stringResource(R.string.scanning_auto_sensitivity_fast),
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.weight(1f),
                )
                Text(
                    stringResource(R.string.scanning_auto_sensitivity_slow),
                    style = MaterialTheme.typography.bodySmall,
                    textAlign = androidx.compose.ui.text.style.TextAlign.End,
                    modifier = Modifier.weight(1f),
                )
            }

            HorizontalDivider(modifier = Modifier.padding(top = 20.dp))

            Row(
                modifier = Modifier.fillMaxWidth().padding(top = 16.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    stringResource(R.string.scanning_flash_default),
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.weight(1f),
                )
                Switch(checked = flashDefault, onCheckedChange = onFlashDefaultChange)
            }
        }
    }
}
