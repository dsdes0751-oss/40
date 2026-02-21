package com.tuna.proj_01

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

private val Context.textViewerDataStore: DataStore<Preferences> by preferencesDataStore(name = "text_viewer_settings")

enum class TextViewerTheme {
    LIGHT,
    DARK,
    SEPIA
}

enum class TextViewerReadingMode {
    PAGED,
    VERTICAL,
    HORIZONTAL
}

enum class TextViewerOrientationLock {
    SYSTEM,
    PORTRAIT,
    LANDSCAPE
}

data class TextViewerSettings(
    val fontSizeSp: Float = 18f,
    val lineHeightMult: Float = 1.45f,
    val letterSpacingEm: Float = 0.0f,
    val paragraphSpacingDp: Float = 10f,
    val marginLeftDp: Int = 18,
    val marginRightDp: Int = 18,
    val marginTopDp: Int = 18,
    val marginBottomDp: Int = 18,
    val theme: TextViewerTheme = TextViewerTheme.DARK,
    val brightness: Float = 0.85f,
    val blueLightPercent: Int = 0,
    val immersiveMode: Boolean = false,
    val readingMode: TextViewerReadingMode = TextViewerReadingMode.PAGED,
    val volumeKeyPaging: Boolean = false,
    val orientationLock: TextViewerOrientationLock = TextViewerOrientationLock.SYSTEM
)

data class TextViewerBookPosition(
    val chunkIndex: Int = 0,
    val percent: Int = 0
)

object TextViewerSettingsStore {
    private val KEY_FONT_SIZE = floatPreferencesKey("tv_font_size_sp")
    private val KEY_LINE_HEIGHT = floatPreferencesKey("tv_line_height_mult")
    private val KEY_LETTER_SPACING = floatPreferencesKey("tv_letter_spacing_em")
    private val KEY_PARAGRAPH_SPACING = floatPreferencesKey("tv_paragraph_spacing_dp")
    private val KEY_MARGIN_LEFT = intPreferencesKey("tv_margin_left_dp")
    private val KEY_MARGIN_RIGHT = intPreferencesKey("tv_margin_right_dp")
    private val KEY_MARGIN_TOP = intPreferencesKey("tv_margin_top_dp")
    private val KEY_MARGIN_BOTTOM = intPreferencesKey("tv_margin_bottom_dp")
    private val KEY_THEME = intPreferencesKey("tv_theme")
    private val KEY_BRIGHTNESS = floatPreferencesKey("tv_brightness")
    private val KEY_BLUE_LIGHT = intPreferencesKey("tv_blue_light_percent")
    private val KEY_IMMERSIVE = booleanPreferencesKey("tv_immersive")
    private val KEY_READING_MODE = intPreferencesKey("tv_reading_mode")
    private val KEY_VOLUME_PAGING = booleanPreferencesKey("tv_volume_paging")
    private val KEY_ORIENTATION = intPreferencesKey("tv_orientation")

    private fun chunkKey(bookId: String) = intPreferencesKey("tv_pos_chunk_${sanitize(bookId)}")
    private fun percentKey(bookId: String) = intPreferencesKey("tv_pos_percent_${sanitize(bookId)}")

    fun settingsFlow(context: Context): Flow<TextViewerSettings> {
        return context.textViewerDataStore.data.map { pref ->
            TextViewerSettings(
                fontSizeSp = pref[KEY_FONT_SIZE] ?: 18f,
                lineHeightMult = pref[KEY_LINE_HEIGHT] ?: 1.45f,
                letterSpacingEm = pref[KEY_LETTER_SPACING] ?: 0.0f,
                paragraphSpacingDp = pref[KEY_PARAGRAPH_SPACING] ?: 10f,
                marginLeftDp = pref[KEY_MARGIN_LEFT] ?: 18,
                marginRightDp = pref[KEY_MARGIN_RIGHT] ?: 18,
                marginTopDp = pref[KEY_MARGIN_TOP] ?: 18,
                marginBottomDp = pref[KEY_MARGIN_BOTTOM] ?: 18,
                theme = TextViewerTheme.entries.getOrElse(pref[KEY_THEME] ?: TextViewerTheme.DARK.ordinal) { TextViewerTheme.DARK },
                brightness = pref[KEY_BRIGHTNESS] ?: 0.85f,
                blueLightPercent = pref[KEY_BLUE_LIGHT] ?: 0,
                immersiveMode = pref[KEY_IMMERSIVE] ?: false,
                readingMode = TextViewerReadingMode.entries.getOrElse(
                    pref[KEY_READING_MODE] ?: TextViewerReadingMode.PAGED.ordinal
                ) { TextViewerReadingMode.PAGED },
                volumeKeyPaging = pref[KEY_VOLUME_PAGING] ?: false,
                orientationLock = TextViewerOrientationLock.entries.getOrElse(
                    pref[KEY_ORIENTATION] ?: TextViewerOrientationLock.SYSTEM.ordinal
                ) { TextViewerOrientationLock.SYSTEM }
            )
        }
    }

    suspend fun updateSettings(context: Context, update: (TextViewerSettings) -> TextViewerSettings) {
        context.textViewerDataStore.edit { pref ->
            val current = TextViewerSettings(
                fontSizeSp = pref[KEY_FONT_SIZE] ?: 18f,
                lineHeightMult = pref[KEY_LINE_HEIGHT] ?: 1.45f,
                letterSpacingEm = pref[KEY_LETTER_SPACING] ?: 0.0f,
                paragraphSpacingDp = pref[KEY_PARAGRAPH_SPACING] ?: 10f,
                marginLeftDp = pref[KEY_MARGIN_LEFT] ?: 18,
                marginRightDp = pref[KEY_MARGIN_RIGHT] ?: 18,
                marginTopDp = pref[KEY_MARGIN_TOP] ?: 18,
                marginBottomDp = pref[KEY_MARGIN_BOTTOM] ?: 18,
                theme = TextViewerTheme.entries.getOrElse(pref[KEY_THEME] ?: TextViewerTheme.DARK.ordinal) { TextViewerTheme.DARK },
                brightness = pref[KEY_BRIGHTNESS] ?: 0.85f,
                blueLightPercent = pref[KEY_BLUE_LIGHT] ?: 0,
                immersiveMode = pref[KEY_IMMERSIVE] ?: false,
                readingMode = TextViewerReadingMode.entries.getOrElse(
                    pref[KEY_READING_MODE] ?: TextViewerReadingMode.PAGED.ordinal
                ) { TextViewerReadingMode.PAGED },
                volumeKeyPaging = pref[KEY_VOLUME_PAGING] ?: false,
                orientationLock = TextViewerOrientationLock.entries.getOrElse(
                    pref[KEY_ORIENTATION] ?: TextViewerOrientationLock.SYSTEM.ordinal
                ) { TextViewerOrientationLock.SYSTEM }
            )
            val next = update(current)

            pref[KEY_FONT_SIZE] = next.fontSizeSp
            pref[KEY_LINE_HEIGHT] = next.lineHeightMult
            pref[KEY_LETTER_SPACING] = next.letterSpacingEm
            pref[KEY_PARAGRAPH_SPACING] = next.paragraphSpacingDp
            pref[KEY_MARGIN_LEFT] = next.marginLeftDp
            pref[KEY_MARGIN_RIGHT] = next.marginRightDp
            pref[KEY_MARGIN_TOP] = next.marginTopDp
            pref[KEY_MARGIN_BOTTOM] = next.marginBottomDp
            pref[KEY_THEME] = next.theme.ordinal
            pref[KEY_BRIGHTNESS] = next.brightness
            pref[KEY_BLUE_LIGHT] = next.blueLightPercent
            pref[KEY_IMMERSIVE] = next.immersiveMode
            pref[KEY_READING_MODE] = next.readingMode.ordinal
            pref[KEY_VOLUME_PAGING] = next.volumeKeyPaging
            pref[KEY_ORIENTATION] = next.orientationLock.ordinal
        }
    }

    suspend fun saveBookPosition(context: Context, bookId: String, position: TextViewerBookPosition) {
        context.textViewerDataStore.edit { pref ->
            pref[chunkKey(bookId)] = position.chunkIndex.coerceAtLeast(0)
            pref[percentKey(bookId)] = position.percent.coerceIn(0, 100)
        }
    }

    suspend fun loadBookPosition(context: Context, bookId: String): TextViewerBookPosition {
        val pref = context.textViewerDataStore.data.first()
        return TextViewerBookPosition(
            chunkIndex = pref[chunkKey(bookId)] ?: 0,
            percent = pref[percentKey(bookId)] ?: 0
        )
    }

    suspend fun clearBookPosition(context: Context, bookId: String) {
        context.textViewerDataStore.edit { pref ->
            pref[chunkKey(bookId)] = 0
            pref[percentKey(bookId)] = 0
        }
    }

    suspend fun clearAllBookPositions(context: Context) {
        context.textViewerDataStore.edit { pref ->
            val allKeys = pref.asMap().keys
            allKeys.filter { key ->
                val name = key.name
                name.startsWith("tv_pos_chunk_") || name.startsWith("tv_pos_percent_")
            }.forEach { key ->
                @Suppress("UNCHECKED_CAST")
                pref[key as Preferences.Key<Int>] = 0
            }
        }
    }

    private fun sanitize(bookId: String): String {
        return bookId.replace(Regex("[^a-zA-Z0-9_]"), "_")
    }
}
