package dev.gold.mdvault.settings

import android.content.Context
import android.net.Uri
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

private val Context.readerSettingsDataStore by preferencesDataStore(name = "reader_settings")

class ReaderSettingsRepository(private val context: Context) {

    val fontScalePercent: Flow<Int> = context.readerSettingsDataStore.data.map { preferences ->
        (preferences[FONT_SCALE_PERCENT] ?: DEFAULT_FONT_SCALE_PERCENT).coerceIn(
            MIN_FONT_SCALE_PERCENT,
            MAX_FONT_SCALE_PERCENT,
        )
    }

    suspend fun setFontScalePercent(value: Int) {
        context.readerSettingsDataStore.edit { preferences ->
            preferences[FONT_SCALE_PERCENT] = value.coerceIn(MIN_FONT_SCALE_PERCENT, MAX_FONT_SCALE_PERCENT)
        }
    }

    suspend fun readingPosition(key: String): String? {
        if (key.isBlank()) return null
        return decodePositions(
            context.readerSettingsDataStore.data.first()[READING_POSITIONS].orEmpty(),
        ).firstOrNull { it.key == key }?.payload
    }

    suspend fun saveReadingPosition(key: String, payload: String) {
        if (key.isBlank()) return
        context.readerSettingsDataStore.edit { preferences ->
            val updated = listOf(ReadingPosition(key, payload))
                .plus(decodePositions(preferences[READING_POSITIONS].orEmpty()).filterNot { it.key == key })
                .take(MAX_READING_POSITIONS)
            preferences[READING_POSITIONS] = encodePositions(updated)
        }
    }

    private fun encodePositions(positions: List<ReadingPosition>): String =
        positions.joinToString("\n") { position ->
            listOf(
                Uri.encode(position.key),
                Uri.encode(position.payload),
            ).joinToString("|")
        }

    private fun decodePositions(encoded: String): List<ReadingPosition> =
        encoded.lineSequence()
            .filter { it.isNotBlank() }
            .mapNotNull { line ->
                val fields = line.split('|', limit = 2)
                if (fields.size != 2) return@mapNotNull null
                ReadingPosition(
                    key = runCatching { Uri.decode(fields[0]) }.getOrNull() ?: return@mapNotNull null,
                    payload = runCatching { Uri.decode(fields[1]) }.getOrNull() ?: return@mapNotNull null,
                )
            }
            .toList()

    private data class ReadingPosition(
        val key: String,
        val payload: String,
    )

    private companion object {
        private val FONT_SCALE_PERCENT = intPreferencesKey("font_scale_percent")
        private val READING_POSITIONS = stringPreferencesKey("reading_positions")
        private const val DEFAULT_FONT_SCALE_PERCENT = 100
        private const val MIN_FONT_SCALE_PERCENT = 85
        private const val MAX_FONT_SCALE_PERCENT = 150
        private const val MAX_READING_POSITIONS = 100
    }
}
