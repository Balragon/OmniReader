package dev.gold.mdvault.storage

import android.content.ContentResolver
import android.net.Uri
import android.provider.DocumentsContract
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
            val parentDocumentId = DocumentsContract.getDocumentId(parentUri)
            val childrenUri = DocumentsContract.buildChildDocumentsUriUsingTree(treeUri, parentDocumentId)
            val documents = mutableListOf<SafDocument>()
            contentResolver.query(childrenUri, DOCUMENT_PROJECTION, null, null, null)?.use { cursor ->
                while (cursor.moveToNext()) {
                    documents += cursor.toSafDocument(treeUri)
                }
            }
            documents
        }

    suspend fun metadata(treeUri: Uri, documentUri: Uri): SafDocument =
        withContext(ioDispatcher) {
            queryDocument(treeUri, documentUri)
                ?: throw FileNotFoundException("Document not found: $documentUri")
        }

    suspend fun findChild(treeUri: Uri, parentUri: Uri, displayName: String): SafDocument? =
        list(treeUri, parentUri).firstOrNull { it.displayName == displayName }

    suspend fun resolve(treeUri: Uri, relativePath: String): SafDocument? =
        withContext(ioDispatcher) {
            val segments = normalizeRelativePath(relativePath)
            if (segments.isEmpty()) {
                return@withContext queryDocument(treeUri, rootDocumentUri(treeUri))
            }

            var current = queryDocument(treeUri, rootDocumentUri(treeUri)) ?: return@withContext null
            for (segment in segments) {
                val childrenUri = DocumentsContract.buildChildDocumentsUriUsingTree(treeUri, current.documentId)
                var matched: SafDocument? = null
                contentResolver.query(childrenUri, DOCUMENT_PROJECTION, null, null, null)?.use { cursor ->
                    while (cursor.moveToNext()) {
                        val child = cursor.toSafDocument(treeUri)
                        if (child.displayName == segment) {
                            matched = child
                            return@use
                        }
                    }
                }
                current = matched ?: return@withContext null
            }
            current
        }

    suspend fun <T> read(
        treeUri: Uri,
        documentUri: Uri,
        typedMimeType: String? = null,
        reader: (InputStream) -> T,
    ): T =
        withContext(ioDispatcher) {
            val document = queryDocument(treeUri, documentUri)
                ?: throw FileNotFoundException("Document not found: $documentUri")
            if (document.isVirtual) {
                val mimeType = typedMimeType ?: document.mimeType.takeUnless { it.isBlank() } ?: "*/*"
                val descriptor = contentResolver.openTypedAssetFileDescriptor(documentUri, mimeType, null)
                    ?: throw FileNotFoundException("Unable to open virtual document: $documentUri")
                descriptor.use { asset ->
                    asset.createInputStream().use(reader)
                }
            } else {
                val input = contentResolver.openInputStream(documentUri)
                    ?: throw FileNotFoundException("Unable to open document: $documentUri")
                input.use(reader)
            }
        }

    suspend fun <T> write(
        documentUri: Uri,
        mode: String = "wt",
        writer: (OutputStream) -> T,
    ): T =
        withContext(ioDispatcher) {
            val output = contentResolver.openOutputStream(documentUri, mode)
                ?: throw FileNotFoundException("Unable to open document for writing: $documentUri")
            output.use(writer)
        }

    suspend fun create(
        treeUri: Uri,
        parentUri: Uri,
        mimeType: String,
        displayName: String,
    ): SafDocument =
        withContext(ioDispatcher) {
            val uri = DocumentsContract.createDocument(contentResolver, parentUri, mimeType, displayName)
                ?: throw FileNotFoundException("Unable to create $displayName under $parentUri")
            queryDocument(treeUri, uri) ?: throw FileNotFoundException("Created document not found: $uri")
        }

    fun rootDocumentUri(treeUri: Uri): Uri =
        DocumentsContract.buildDocumentUriUsingTree(treeUri, DocumentsContract.getTreeDocumentId(treeUri))

    private fun queryDocument(treeUri: Uri, documentUri: Uri): SafDocument? {
        contentResolver.query(documentUri, DOCUMENT_PROJECTION, null, null, null)?.use { cursor ->
            if (cursor.moveToFirst()) {
                return cursor.toSafDocument(treeUri)
            }
        }
        return null
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
