package dev.gold.mdvault.storage

import android.content.ContentResolver
import android.database.Cursor
import android.net.Uri
import android.os.RemoteException
import android.provider.DocumentsContract
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.FileNotFoundException
import java.io.InputStream
import java.io.OutputStream

data class SafDocument(
    val uri: Uri,
    val documentId: String,
    val displayName: String,
    val mimeType: String,
    val lastModified: Long?,
    val size: Long?,
    val isVirtual: Boolean,
) {
    val isDirectory: Boolean = mimeType == DocumentsContract.Document.MIME_TYPE_DIR
}

class SafDocumentRepository(
    private val contentResolver: ContentResolver,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) {
    suspend fun list(treeUri: Uri, parentUri: Uri = rootDocumentUri(treeUri)): List<SafDocument> =
        withContext(ioDispatcher) {
            mapProviderFailures {
                val parentDocumentId = DocumentsContract.getDocumentId(parentUri)
                val childrenUri = DocumentsContract.buildChildDocumentsUriUsingTree(treeUri, parentDocumentId)
                val documents = mutableListOf<SafDocument>()
                queryCursor(treeUri, childrenUri, parentDocumentId).use { cursor ->
                    while (cursor.moveToNext()) {
                        documents += cursor.toSafDocument(treeUri)
                    }
                }
                documents
            }
        }

    suspend fun metadata(treeUri: Uri, documentUri: Uri): SafDocument =
        withContext(ioDispatcher) {
            mapProviderFailures {
                queryDocument(treeUri, documentUri, documentUri.toString())
                    ?: throw missingDocument(treeUri, documentUri.toString())
            }
        }

    suspend fun findChild(treeUri: Uri, parentUri: Uri, displayName: String): SafDocument? =
        list(treeUri, parentUri).firstOrNull { it.displayName == displayName }

    suspend fun resolve(treeUri: Uri, relativePath: String): SafDocument? =
        withContext(ioDispatcher) {
            val segments = normalizeRelativePath(relativePath)
            mapProviderFailures {
                if (segments.isEmpty()) {
                    return@mapProviderFailures queryDocument(treeUri, rootDocumentUri(treeUri), "vault root")
                }

                var current = queryDocument(treeUri, rootDocumentUri(treeUri), "vault root") ?: return@mapProviderFailures null
                for (segment in segments) {
                    val childrenUri = DocumentsContract.buildChildDocumentsUriUsingTree(treeUri, current.documentId)
                    var matched: SafDocument? = null
                    queryCursor(treeUri, childrenUri, segment).use { cursor ->
                        while (cursor.moveToNext()) {
                            val child = cursor.toSafDocument(treeUri)
                            if (child.displayName == segment) {
                                matched = child
                                return@use
                            }
                        }
                    }
                    current = matched ?: return@mapProviderFailures null
                }
                current
            }
        }

    suspend fun <T> read(
        treeUri: Uri,
        documentUri: Uri,
        typedMimeType: String? = null,
        reader: (InputStream) -> T,
    ): T =
        withContext(ioDispatcher) {
            mapProviderFailures {
                val document = queryDocument(treeUri, documentUri, documentUri.toString())
                    ?: throw missingDocument(treeUri, documentUri.toString())
                if (document.isVirtual) {
                    val mimeType = typedMimeType ?: document.mimeType.takeUnless { it.isBlank() } ?: "*/*"
                    val descriptor = contentResolver.openTypedAssetFileDescriptor(documentUri, mimeType, null)
                        ?: throw missingDocument(treeUri, document.displayName)
                    descriptor.use { asset ->
                        asset.createInputStream().use(reader)
                    }
                } else {
                    val input = contentResolver.openInputStream(documentUri)
                        ?: throw missingDocument(treeUri, document.displayName)
                    input.use(reader)
                }
            }
        }

    suspend fun <T> write(
        documentUri: Uri,
        mode: String = "wt",
        writer: (OutputStream) -> T,
    ): T =
        withContext(ioDispatcher) {
            mapProviderFailures {
                val output = contentResolver.openOutputStream(documentUri, mode)
                    ?: throw VaultError.DocumentMissing(documentUri.toString())
                output.use(writer)
            }
        }

    suspend fun create(
        treeUri: Uri,
        parentUri: Uri,
        mimeType: String,
        displayName: String,
    ): SafDocument =
        withContext(ioDispatcher) {
            mapProviderFailures {
                val uri = DocumentsContract.createDocument(contentResolver, parentUri, mimeType, displayName)
                    ?: throw missingDocument(treeUri, displayName)
                queryDocument(treeUri, uri, displayName) ?: throw missingDocument(treeUri, displayName)
            }
        }

    suspend fun delete(documentUri: Uri) {
        withContext(ioDispatcher) {
            mapProviderFailures {
                val deleted = DocumentsContract.deleteDocument(contentResolver, documentUri)
                if (!deleted) {
                    throw VaultError.DocumentMissing(documentUri.toString())
                }
            }
        }
    }

    fun rootDocumentUri(treeUri: Uri): Uri =
        DocumentsContract.buildDocumentUriUsingTree(treeUri, DocumentsContract.getTreeDocumentId(treeUri))

    private fun queryDocument(treeUri: Uri, documentUri: Uri, missingName: String): SafDocument? {
        queryCursor(treeUri, documentUri, missingName).use { cursor ->
            if (cursor.moveToFirst()) {
                return cursor.toSafDocument(treeUri)
            }
        }
        return null
    }

    private fun queryCursor(treeUri: Uri, uri: Uri, missingName: String): Cursor =
        contentResolver.query(uri, DOCUMENT_PROJECTION, null, null, null)
            ?: throw missingDocument(treeUri, missingName)

    private fun missingDocument(treeUri: Uri, name: String): VaultError =
        if (hasPersistedReadPermission(treeUri)) {
            VaultError.DocumentMissing(name)
        } else {
            VaultError.PermissionLost()
        }

    private fun hasPersistedReadPermission(treeUri: Uri): Boolean =
        contentResolver.persistedUriPermissions.any { permission ->
            permission.uri == treeUri && permission.isReadPermission
        }

    private inline fun <T> mapProviderFailures(block: () -> T): T =
        try {
            block()
        } catch (error: VaultError) {
            throw error
        } catch (error: SecurityException) {
            throw VaultError.PermissionLost()
        } catch (error: FileNotFoundException) {
            throw VaultError.DocumentMissing(error.message.orEmpty())
        } catch (error: RemoteException) {
            throw VaultError.ProviderUnavailable()
        } catch (error: CancellationException) {
            throw error
        } catch (error: IllegalStateException) {
            throw VaultError.ProviderUnavailable()
        } catch (error: java.io.IOException) {
            throw VaultError.Unknown(error)
        } catch (error: RuntimeException) {
            throw VaultError.Unknown(error)
        }

    private fun android.database.Cursor.toSafDocument(treeUri: Uri): SafDocument {
        val documentId = getString(getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_DOCUMENT_ID))
        val mimeType = getString(getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_MIME_TYPE)).orEmpty()
        val flags = getOptionalInt(DocumentsContract.Document.COLUMN_FLAGS) ?: 0
        return SafDocument(
            uri = DocumentsContract.buildDocumentUriUsingTree(treeUri, documentId),
            documentId = documentId,
            displayName = getString(getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_DISPLAY_NAME)).orEmpty(),
            mimeType = mimeType,
            lastModified = getOptionalLong(DocumentsContract.Document.COLUMN_LAST_MODIFIED),
            size = getOptionalLong(DocumentsContract.Document.COLUMN_SIZE),
            isVirtual = flags and DocumentsContract.Document.FLAG_VIRTUAL_DOCUMENT != 0,
        )
    }

    private fun android.database.Cursor.getOptionalLong(columnName: String): Long? {
        val index = getColumnIndex(columnName)
        return if (index >= 0 && !isNull(index)) getLong(index) else null
    }

    private fun android.database.Cursor.getOptionalInt(columnName: String): Int? {
        val index = getColumnIndex(columnName)
        return if (index >= 0 && !isNull(index)) getInt(index) else null
    }

    private fun normalizeRelativePath(relativePath: String): List<String> =
        relativePath
            .split('/')
            .filter { it.isNotBlank() }
            .also { segments ->
                require(segments.none { it == "." || it == ".." }) {
                    "Vault-relative paths must not contain '.' or '..': $relativePath"
                }
            }

    private companion object {
        private val DOCUMENT_PROJECTION = arrayOf(
            DocumentsContract.Document.COLUMN_DOCUMENT_ID,
            DocumentsContract.Document.COLUMN_DISPLAY_NAME,
            DocumentsContract.Document.COLUMN_MIME_TYPE,
            DocumentsContract.Document.COLUMN_LAST_MODIFIED,
            DocumentsContract.Document.COLUMN_SIZE,
            DocumentsContract.Document.COLUMN_FLAGS,
        )
    }
}
