package dev.gold.mdvault.storage

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.DocumentsContract
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import java.io.InputStream
import java.io.OutputStream

private val Context.vaultDataStore by preferencesDataStore(name = "vault_repository")

class VaultRepository(
    private val context: Context,
    private val safRepository: SafDocumentRepository = SafDocumentRepository(context.contentResolver),
) {
    val vaultTreeUri: Flow<Uri?> = context.vaultDataStore.data.map { preferences ->
        preferences[VAULT_TREE_URI]?.let(Uri::parse)
    }

    val recentDocuments: Flow<List<String>> = context.vaultDataStore.data.map { preferences ->
        decodeRecent(preferences[RECENT_DOCUMENTS].orEmpty())
    }

    suspend fun currentVaultTreeUri(): Uri? = vaultTreeUri.first()

    suspend fun setVaultTreeUri(
        treeUri: Uri,
        grantFlags: Int = Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION,
    ) {
        require(DocumentsContract.isTreeUri(treeUri)) { "Vault URI must be a SAF tree URI: $treeUri" }

        val persistableFlags = grantFlags and PERSISTABLE_PERMISSION_FLAGS
        require(persistableFlags != 0) { "Vault URI needs at least read or write permission" }

        val previous = currentVaultTreeUri()
        context.contentResolver.takePersistableUriPermission(treeUri, persistableFlags)
        if (previous != null && previous != treeUri) {
            releasePersistedPermission(previous)
        }
        context.vaultDataStore.edit { preferences ->
            preferences[VAULT_TREE_URI] = treeUri.toString()
        }
    }

    suspend fun clearVault() {
        currentVaultTreeUri()?.let(::releasePersistedPermission)
        context.vaultDataStore.edit { preferences ->
            preferences.remove(VAULT_TREE_URI)
            preferences.remove(RECENT_DOCUMENTS)
        }
    }

    suspend fun list(relativeDirectory: String = ""): List<SafDocument> {
        val treeUri = requireVaultTreeUri()
        val directory = safRepository.resolve(treeUri, relativeDirectory)
            ?: throw IllegalArgumentException("Vault directory not found: $relativeDirectory")
        require(directory.isDirectory) { "Vault path is not a directory: $relativeDirectory" }
        return safRepository.list(treeUri, directory.uri)
    }

    suspend fun create(
        relativePath: String,
        mimeType: String,
        writer: ((OutputStream) -> Unit)? = null,
    ): SafDocument {
        val treeUri = requireVaultTreeUri()
        val path = VaultRelativePath.parse(relativePath)
        val parent = safRepository.resolve(treeUri, path.parentPath)
            ?: throw IllegalArgumentException("Parent directory not found: ${path.parentPath}")
        require(parent.isDirectory) { "Parent path is not a directory: ${path.parentPath}" }

        val document = safRepository.create(treeUri, parent.uri, mimeType, path.fileName)
        if (writer != null) {
            safRepository.write(document.uri, writer = writer)
        }
        recordRecentDocument(path.value)
        return document
    }

    /**
     * trackRecent=false는 부수 읽기(reader의 이미지 인터셉트, export의 asset
     * 해석)용 — 최근 문서 목록을 오염시키지 않는다.
     */
    suspend fun <T> read(
        relativePath: String,
        trackRecent: Boolean = true,
        reader: (InputStream) -> T,
    ): T {
        val treeUri = requireVaultTreeUri()
        val path = VaultRelativePath.parse(relativePath)
        val document = safRepository.resolve(treeUri, path.value)
            ?: throw IllegalArgumentException("Vault document not found: ${path.value}")
        require(!document.isDirectory) { "Vault path is a directory: ${path.value}" }
        return safRepository.read(treeUri, document.uri, document.mimeType, reader).also {
            if (trackRecent) recordRecentDocument(path.value)
        }
    }

    suspend fun <T> write(relativePath: String, writer: (OutputStream) -> T): T {
        val treeUri = requireVaultTreeUri()
        val path = VaultRelativePath.parse(relativePath)
        val document = safRepository.resolve(treeUri, path.value)
            ?: throw IllegalArgumentException("Vault document not found: ${path.value}")
        require(!document.isDirectory) { "Vault path is a directory: ${path.value}" }
        return safRepository.write(document.uri, writer = writer).also {
            recordRecentDocument(path.value)
        }
    }

    suspend fun recordRecentDocument(relativePath: String) {
        val path = VaultRelativePath.parse(relativePath).value
        context.vaultDataStore.edit { preferences ->
            val updated = listOf(path)
                .plus(decodeRecent(preferences[RECENT_DOCUMENTS].orEmpty()).filterNot { it == path })
                .take(MAX_RECENT_DOCUMENTS)
            preferences[RECENT_DOCUMENTS] = encodeRecent(updated)
        }
    }

    private suspend fun requireVaultTreeUri(): Uri =
        currentVaultTreeUri() ?: throw IllegalStateException("Vault tree URI is not configured")

    private fun releasePersistedPermission(treeUri: Uri) {
        val persistedPermission = context.contentResolver.persistedUriPermissions
            .firstOrNull { it.uri == treeUri }
            ?: return

        var flags = 0
        if (persistedPermission.isReadPermission) flags = flags or Intent.FLAG_GRANT_READ_URI_PERMISSION
        if (persistedPermission.isWritePermission) flags = flags or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
        if (flags != 0) {
            context.contentResolver.releasePersistableUriPermission(treeUri, flags)
        }
    }

    private data class VaultRelativePath(val value: String) {
        val fileName: String = value.substringAfterLast('/')
        val parentPath: String = value.substringBeforeLast('/', missingDelimiterValue = "")

        companion object {
            fun parse(relativePath: String): VaultRelativePath {
                val segments = relativePath
                    .split('/')
                    .filter { it.isNotBlank() }
                require(segments.isNotEmpty()) { "Vault-relative path must not be empty" }
                require(segments.none { it == "." || it == ".." }) {
                    "Vault-relative paths must not contain '.' or '..': $relativePath"
                }
                return VaultRelativePath(segments.joinToString("/"))
            }
        }
    }

    private companion object {
        private val VAULT_TREE_URI = stringPreferencesKey("vault_tree_uri")
        private val RECENT_DOCUMENTS = stringPreferencesKey("recent_documents")
        private const val MAX_RECENT_DOCUMENTS = 50
        private const val PERSISTABLE_PERMISSION_FLAGS =
            Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION

        private fun encodeRecent(paths: List<String>): String =
            paths.joinToString("\n") { Uri.encode(it) }

        private fun decodeRecent(encoded: String): List<String> =
            encoded.lineSequence()
                .filter { it.isNotBlank() }
                .map { Uri.decode(it) }
                .toList()
    }
}
