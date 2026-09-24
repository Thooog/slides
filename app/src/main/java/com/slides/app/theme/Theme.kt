package com.slides.app.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

@Composable
fun SlidesTheme(content: @Composable () -> Unit) {
    val scheme = lightColorScheme(
        primary = SlidesAccent,
        onPrimary = SlidesOnAccent,
        background = Color.White,
        surface = Color.White,
        onBackground = SlidesTextPrimary,
        onSurface = SlidesTextPrimary,
        surfaceVariant = Color(0xFFF0F1F2),
        onSurfaceVariant = SlidesTextSecondary,
    )
    MaterialTheme(colorScheme = scheme, content = content)
}