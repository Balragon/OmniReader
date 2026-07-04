package dev.gold.mdvault.storage

import android.content.Context
import android.net.Uri
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.recentFilesDataStore by preferencesDataStore(name = "recent_files")

/**
 * 뷰어로 연 단일 파일(URI) 최근 목록. vault와 무관 — "내 파일"에서 연 문서,
 * 공유로 받은 문서 등. 재열기 실패(권한 소멸)는 호출자가 remove로 정리한다.
 */
class RecentFilesRepository(private val context: Context) {

    data class RecentFile(
        val uri: String,
        val displayName: String,
        val kind: String,
        val openedAtMillis: Long,
    )

    val recentFiles: Flow<List<RecentFile>> = context.recentFilesDataStore.data.map { preferences ->
        decode(preferences[RECENT_FILES].orEmpty())
    }

    suspend fun record(uri: Uri, displayName: String, kind: String) {
        val entry = RecentFile(uri.toString(), displayName, kind, System.currentTimeMillis())
        context.recentFilesDataStore.edit { preferences ->
            val updated = listOf(entry)
                .plus(decode(preferences[RECENT_FILES].orEmpty()).filterNot { it.uri == entry.uri })
                .take(MAX_ENTRIES)
            preferences[RECENT_FILES] = encode(updated)
        }
    }

    suspend fun remove(uriString: String) {
        context.recentFilesDataStore.edit { preferences ->
            val updated = decode(preferences[RECENT_FILES].orEmpty()).filterNot { it.uri == uriString }
            preferences[RECENT_FILES] = encode(updated)
        }
    }

    private fun encode(entries: List<RecentFile>): String =
        entries.joinToString("\n") { entry ->
            listOf(
                Uri.encode(entry.uri),
                Uri.encode(entry.displayName),
                Uri.encode(entry.kind),
                entry.openedAtMillis.toString(),
            ).joinToString("|")
        }

    private fun decode(encoded: String): List<RecentFile> =
        encoded.lineSequence()
            .filter { it.isNotBlank() }
            .mapNotNull { line ->
                val fields = line.split('|')
                if (fields.size != 4) return@mapNotNull null
                RecentFile(
                    uri = Uri.decode(fields[0]),
                    displayName = Uri.decode(fields[1]),
                    kind = Uri.decode(fields[2]),
                    openedAtMillis = fields[3].toLongOrNull() ?: 0L,
                )
            }
            .toList()

    private companion object {
        private val RECENT_FILES = stringPreferencesKey("recent_files")
        private const val MAX_ENTRIES = 30
    }
}
