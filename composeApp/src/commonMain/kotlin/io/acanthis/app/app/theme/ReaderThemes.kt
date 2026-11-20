package io.acanthis.app.app.theme

import androidx.compose.ui.graphics.Color
import io.acanthis.app.core.preferences.ReaderTheme

data class ReaderPalette(
    val background: Color,
    val text: Color,
    val secondaryText: Color,
    val surface: Color,
    val onSurface: Color,
)

private val LightReaderPalette = ReaderPalette(
    background = Color(0xFFFAF8F5),
    text = Color(0xFF222222),
    secondaryText = Color(0xFF66625B),
    surface = Color(0xFFF1EDE6),
    onSurface = Color(0xFF1F1D1A),
)

private val SepiaReaderPalette = ReaderPalette(
    background = Color(0xFFF4ECD8),
    text = Color(0xFF3D2E1E),
    secondaryText = Color(0xFF735C44),
    surface = Color(0xFFE9DEC4),
    onSurface = Color(0xFF352718),
)

private val OledReaderPalette = ReaderPalette(
    background = Color(0xFF000000),
    text = Color(0xFFC8C8C8),
    secondaryText = Color(0xFF8E8E93),
    surface = Color(0xFF141416),
    onSurface = Color(0xFFD8D8DC),
)

fun ReaderTheme.toPalette(): ReaderPalette {
    return when (this) {
        ReaderTheme.LIGHT -> LightReaderPalette
        ReaderTheme.SEPIA -> SepiaReaderPalette
        ReaderTheme.OLED -> OledReaderPalette
    }
}
