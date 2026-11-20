package io.acanthis.app.core.preferences

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

enum class ReaderTheme {
    LIGHT,
    SEPIA,
    OLED,
    ;

    companion object {
        fun fromStorageName(value: String?): ReaderTheme {
            return entries.firstOrNull { it.name == value } ?: LIGHT
        }
    }
}

data class ReaderSettings(
    val theme: ReaderTheme = DEFAULT_THEME,
    val fontSizeSp: Float = DEFAULT_FONT_SIZE_SP,
    val lineHeightMultiplier: Float = DEFAULT_LINE_HEIGHT_MULTIPLIER,
    val horizontalPaddingDp: Int = DEFAULT_HORIZONTAL_PADDING_DP,
    val firstLineIndent: Boolean = DEFAULT_FIRST_LINE_INDENT,
) {
    companion object {
        val DEFAULT_THEME = ReaderTheme.LIGHT
        const val MIN_FONT_SIZE_SP = 12f
        const val MAX_FONT_SIZE_SP = 36f
        const val DEFAULT_FONT_SIZE_SP = 18f

        const val MIN_LINE_HEIGHT_MULTIPLIER = 1.2f
        const val MAX_LINE_HEIGHT_MULTIPLIER = 2.8f
        const val DEFAULT_LINE_HEIGHT_MULTIPLIER = 1.8f

        const val MIN_HORIZONTAL_PADDING_DP = 8
        const val MAX_HORIZONTAL_PADDING_DP = 64
        const val DEFAULT_HORIZONTAL_PADDING_DP = 20

        const val DEFAULT_FIRST_LINE_INDENT = true
    }
}

fun ReaderSettings.sanitized(): ReaderSettings {
    val safeFontSize = if (fontSizeSp.isNaN()) {
        ReaderSettings.DEFAULT_FONT_SIZE_SP
    } else {
        fontSizeSp.coerceIn(ReaderSettings.MIN_FONT_SIZE_SP, ReaderSettings.MAX_FONT_SIZE_SP)
    }
    val safeLineHeight = if (lineHeightMultiplier.isNaN()) {
        ReaderSettings.DEFAULT_LINE_HEIGHT_MULTIPLIER
    } else {
        lineHeightMultiplier.coerceIn(
            ReaderSettings.MIN_LINE_HEIGHT_MULTIPLIER,
            ReaderSettings.MAX_LINE_HEIGHT_MULTIPLIER,
        )
    }
    val safePadding = horizontalPaddingDp.coerceIn(
        ReaderSettings.MIN_HORIZONTAL_PADDING_DP,
        ReaderSettings.MAX_HORIZONTAL_PADDING_DP,
    )
    return copy(
        fontSizeSp = safeFontSize,
        lineHeightMultiplier = safeLineHeight,
        horizontalPaddingDp = safePadding,
    )
}

class ReaderSettingsStore(
    private val dataStore: DataStore<Preferences>,
) {
    private object Keys {
        val THEME = stringPreferencesKey("reader_theme")
        val FONT_SIZE_SP = floatPreferencesKey("reader_font_size_sp")
        val LINE_HEIGHT_MULTIPLIER = floatPreferencesKey("reader_line_height_multiplier")
        val HORIZONTAL_PADDING_DP = intPreferencesKey("reader_horizontal_padding_dp")
        val FIRST_LINE_INDENT = booleanPreferencesKey("reader_first_line_indent")
    }

    val settingsFlow: Flow<ReaderSettings> = dataStore.data.map(::readFromPreferences)

    suspend fun update(transform: (ReaderSettings) -> ReaderSettings) {
        dataStore.edit { prefs ->
            val current = readFromPreferences(prefs)
            val updated = transform(current).sanitized()
            prefs[Keys.THEME] = updated.theme.name
            prefs[Keys.FONT_SIZE_SP] = updated.fontSizeSp
            prefs[Keys.LINE_HEIGHT_MULTIPLIER] = updated.lineHeightMultiplier
            prefs[Keys.HORIZONTAL_PADDING_DP] = updated.horizontalPaddingDp
            prefs[Keys.FIRST_LINE_INDENT] = updated.firstLineIndent
        }
    }

    private fun readFromPreferences(prefs: Preferences): ReaderSettings {
        return ReaderSettings(
            theme = ReaderTheme.fromStorageName(prefs[Keys.THEME]),
            fontSizeSp = prefs[Keys.FONT_SIZE_SP] ?: ReaderSettings.DEFAULT_FONT_SIZE_SP,
            lineHeightMultiplier = prefs[Keys.LINE_HEIGHT_MULTIPLIER]
                ?: ReaderSettings.DEFAULT_LINE_HEIGHT_MULTIPLIER,
            horizontalPaddingDp = prefs[Keys.HORIZONTAL_PADDING_DP]
                ?: ReaderSettings.DEFAULT_HORIZONTAL_PADDING_DP,
            firstLineIndent = prefs[Keys.FIRST_LINE_INDENT]
                ?: ReaderSettings.DEFAULT_FIRST_LINE_INDENT,
        ).sanitized()
    }
}
