package com.example.pixelmaster.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

// 修图软件统一使用深色界面，保证观片环境一致
private val EditorScheme = darkColorScheme(
    primary = CakePink,
    onPrimary = Color(0xFF3D0A22),
    secondary = Cream,
    onSecondary = Color(0xFF3D2E0A),
    background = EditorBackground,
    onBackground = OnEditorSurface,
    surface = EditorSurface,
    onSurface = OnEditorSurface,
    surfaceVariant = EditorSurfaceVariant,
    onSurfaceVariant = OnEditorSurfaceVariant,
    outline = CakePinkDim
)

@Composable
fun PixelMasterTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = EditorScheme,
        typography = Typography,
        content = content
    )
}
