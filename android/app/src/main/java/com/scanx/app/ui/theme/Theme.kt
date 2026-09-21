package com.scanx.app.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

// Bảng màu ScanX — xanh dương làm thương hiệu chính, hài hoà cả light/dark, đồng nhất với res/values/colors.xml
private val ScanXPrimary = Color(0xFF2F6FED)
private val ScanXPrimaryDark = Color(0xFF1B4FC4)
private val ScanXBackgroundLight = Color(0xFFF7F8FA)
private val ScanXBackgroundDark = Color(0xFF101317)
private val ScanXSurfaceLight = Color(0xFFFFFFFF)
private val ScanXSurfaceDark = Color(0xFF1B1F24)

private val LightColors = lightColorScheme(
    primary = ScanXPrimary,
    onPrimary = Color.White,
    background = ScanXBackgroundLight,
    surface = ScanXSurfaceLight,
)

private val DarkColors = darkColorScheme(
    primary = ScanXPrimaryDark,
    onPrimary = Color.White,
    background = ScanXBackgroundDark,
    surface = ScanXSurfaceDark,
)

@Composable
fun ScanXTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit
) {
    val colors = if (darkTheme) DarkColors else LightColors
    MaterialTheme(
        colorScheme = colors,
        typography = ScanXTypography,
        content = content
    )
}
