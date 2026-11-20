package io.acanthis.app.core.preferences

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ReaderSettingsTest {

    @Test
    fun defaultReaderSettingsMatchSpec() {
        val defaults = ReaderSettings()
        assertEquals(ReaderTheme.LIGHT, defaults.theme)
        assertEquals(18f, defaults.fontSizeSp)
        assertEquals(1.8f, defaults.lineHeightMultiplier)
        assertEquals(20, defaults.horizontalPaddingDp)
        assertTrue(defaults.firstLineIndent)
    }

    @Test
    fun sanitizedClampsOutOfRangeValuesAndHandlesNaN() {
        val belowMin = ReaderSettings(
            theme = ReaderTheme.SEPIA,
            fontSizeSp = 6f,
            lineHeightMultiplier = 0.5f,
            horizontalPaddingDp = 0,
            firstLineIndent = false,
        ).sanitized()

        assertEquals(ReaderTheme.SEPIA, belowMin.theme)
        assertEquals(12f, belowMin.fontSizeSp)
        assertEquals(1.2f, belowMin.lineHeightMultiplier)
        assertEquals(8, belowMin.horizontalPaddingDp)
        assertFalse(belowMin.firstLineIndent)

        val aboveMax = ReaderSettings(
            theme = ReaderTheme.OLED,
            fontSizeSp = 72f,
            lineHeightMultiplier = 4.5f,
            horizontalPaddingDp = 128,
            firstLineIndent = true,
        ).sanitized()

        assertEquals(ReaderTheme.OLED, aboveMax.theme)
        assertEquals(36f, aboveMax.fontSizeSp)
        assertEquals(2.8f, aboveMax.lineHeightMultiplier)
        assertEquals(64, aboveMax.horizontalPaddingDp)
        assertTrue(aboveMax.firstLineIndent)

        val nanValues = ReaderSettings(
            fontSizeSp = Float.NaN,
            lineHeightMultiplier = Float.NaN,
        ).sanitized()

        assertEquals(18f, nanValues.fontSizeSp)
        assertEquals(1.8f, nanValues.lineHeightMultiplier)
    }

    @Test
    fun sanitizedPreservesValidValues() {
        val valid = ReaderSettings(
            theme = ReaderTheme.OLED,
            fontSizeSp = 22f,
            lineHeightMultiplier = 2.1f,
            horizontalPaddingDp = 32,
            firstLineIndent = false,
        )
        assertEquals(valid, valid.sanitized())
    }

    @Test
    fun readerThemeFromStorageNameFallsBackToLight() {
        assertEquals(ReaderTheme.LIGHT, ReaderTheme.fromStorageName("LIGHT"))
        assertEquals(ReaderTheme.SEPIA, ReaderTheme.fromStorageName("SEPIA"))
        assertEquals(ReaderTheme.OLED, ReaderTheme.fromStorageName("OLED"))
        assertEquals(ReaderTheme.LIGHT, ReaderTheme.fromStorageName("UNKNOWN"))
        assertEquals(ReaderTheme.LIGHT, ReaderTheme.fromStorageName(null))
    }

    @Test
    fun shouldResetSyncCursorComparesNormalizedServerUrls() {
        assertFalse(shouldResetSyncCursor("http://a:5301/v1", "http://a:5301/"))
        assertFalse(shouldResetSyncCursor("  http://a:5301/v1/  ", "http://a:5301"))
        assertFalse(shouldResetSyncCursor("", "   "))

        assertTrue(shouldResetSyncCursor("http://a:5301", "http://b:5301"))
        assertTrue(shouldResetSyncCursor("http://a:5301/v1", "https://a:5301/v1"))
        assertTrue(shouldResetSyncCursor("http://a:5301", "http://a:5302"))
        assertTrue(shouldResetSyncCursor("http://a:5301", ""))
        assertTrue(shouldResetSyncCursor("", "http://a:5301"))
    }
}
