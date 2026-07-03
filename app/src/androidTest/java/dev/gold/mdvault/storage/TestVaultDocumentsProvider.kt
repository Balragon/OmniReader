package dev.gold.mdvault.storage

import android.content.res.AssetFileDescriptor
import android.database.Cursor
import android.database.MatrixCursor
import android.os.Bundle
import android.os.CancellationSignal
import android.os.ParcelFileDescriptor
import android.provider.DocumentsContract
import android.provider.DocumentsProvider
import java.io.File
import java.io.FileNotFoundException

class TestVaultDocumentsProvider : DocumentsProvider() {
    override fun onCreate(): Boolean = true

    override fun call(method: String, arg: String?, extras: Bundle?): Bundle? {
        if (method == METHOD_RESET) {
            rootDirectory.deleteRecursively()
            rootDirectory.mkdirs()
            return Bundle.EMPTY
        }
        return super.call(method, arg, extras)
    }

    override fun queryRoots(projection: Array<out String>?): Cursor {
        val resolvedProjection = projection ?: DEFAULT_ROOT_PROJECTION
        return MatrixCursor(resolvedProjection).apply {
            newRow().also { row ->
                for (column in resolvedProjection) {
                    row.add(
                        when (column) {
                            DocumentsContract.Root.COLUMN_ROOT_ID -> ROOT_ID
                            DocumentsContract.Root.COLUMN_DOCUMENT_ID -> ROOT_ID
                            DocumentsContract.Root.COLUMN_TITLE -> "Test Vault"
                            DocumentsContract.Root.COLUMN_FLAGS -> DocumentsContract.Root.FLAG_SUPPORTS_CREATE
                            DocumentsContract.Root.COLUMN_MIME_TYPES -> "*/*"
                            DocumentsContract.Root.COLUMN_AVAILABLE_BYTES -> rootDirectory.freeSpace
                            else -> null
                        }
                    )
                }
            }
        }
    }

    override fun queryDocument(documentId: String, projection: Array<out String>?): Cursor =
        MatrixCursor(projection ?: DEFAULT_DOCUMENT_PROJECTION).apply {
            addDocument(fileForDocumentId(documentId), documentId)
        }

    override fun queryChildDocuments(
        parentDocumentId: String,
        projection: Array<out String>?,
        sortOrder: String?,
    ): Cursor {
        val resolvedProjection = projection ?: DEFAULT_DOCUMENT_PROJECTION
        val parent = fileForDocumentId(parentDocumentId)
        return MatrixCursor(resolvedProjection).apply {
            parent.listFiles()
                ?.sortedBy { it.name }
                ?.forEach { child -> addDocument(child, documentIdForFile(child)) }
        }
    }

    override fun createDocument(parentDocumentId: String, mimeType: String, displayName: String): String {
        val parent = fileForDocumentId(parentDocumentId)
        if (!parent.exists() && !parent.mkdirs()) {
            throw FileNotFoundException("Unable to create parent directory: $parentDocumentId")
        }

        val target = uniqueFile(parent, displayName)
        if (mimeType == DocumentsContract.Document.MIME_TYPE_DIR) {
            if (!target.mkdirs()) throw FileNotFoundException("Unable to create directory: $displayName")
        } else if (!target.createNewFile()) {
            throw FileNotFoundException("Unable to create document: $displayName")
        }
        return documentIdForFile(target)
    }

    override fun openDocument(
        documentId: String,
        mode: String,
        signal: CancellationSignal?,
    ): ParcelFileDescriptor =
        ParcelFileDescriptor.open(fileForDocumentId(documentId), ParcelFileDescriptor.parseMode(mode))

    override fun openTypedDocument(
        documentId: String,
        mimeTypeFilter: String,
        opts: Bundle?,
        signal: CancellationSignal?,
    ): AssetFileDescriptor =
        AssetFileDescriptor(openDocument(documentId, "r", signal), 0, AssetFileDescriptor.UNKNOWN_LENGTH)

    private fun MatrixCursor.addDocument(file: File, documentId: String) {
        newRow().also { row ->
            val mimeType = if (file.isDirectory) {
                DocumentsContract.Document.MIME_TYPE_DIR
            } else {
                "text/markdown"
            }
            for (column in columnNames) {
                row.add(
                    when (column) {
                        DocumentsContract.Document.COLUMN_DOCUMENT_ID -> documentId
                        DocumentsContract.Document.COLUMN_DISPLAY_NAME -> file.name
                        DocumentsContract.Document.COLUMN_MIME_TYPE -> mimeType
                        DocumentsContract.Document.COLUMN_LAST_MODIFIED -> file.lastModified()
                        DocumentsContract.Document.COLUMN_SIZE -> if (file.isFile) file.length() else null
                        DocumentsContract.Document.COLUMN_FLAGS -> flagsFor(file)
                        else -> null
                    }
                )
            }
        }
    }

    private fun flagsFor(file: File): Int {
        var flags = DocumentsContract.Document.FLAG_SUPPORTS_WRITE or
            DocumentsContract.Document.FLAG_SUPPORTS_DELETE
        if (file.isDirectory) {
            flags = flags or DocumentsContract.Document.FLAG_DIR_SUPPORTS_CREATE
        }
        return flags
    }

    private val rootDirectory: File
        get() = File(requireNotNull(context).filesDir, "saf-test-vault").apply { mkdirs() }

    private fun fileForDocumentId(documentId: String): File {
        val file = if (documentId == ROOT_ID) {
            rootDirectory
        } else {
            File(rootDirectory, documentId)
        }
        val rootPath = rootDirectory.canonicalPath
        val filePath = file.canonicalPath
        if (filePath != rootPath && !filePath.startsWith("$rootPath/")) {
            throw FileNotFoundException("Document outside test root: $documentId")
        }
        return file
    }

    private fun documentIdForFile(file: File): String =
        if (file.canonicalFile == rootDirectory.canonicalFile) {
            ROOT_ID
        } else {
            file.relativeTo(rootDirectory).path
        }

    private fun uniqueFile(parent: File, displayName: String): File {
        var target = File(parent, displayName)
        if (!target.exists()) return target

        val base = displayName.substringBeforeLast('.', displayName)
        val extension = displayName.substringAfterLast('.', missingDelimiterValue = "")
        var index = 1
        while (target.exists()) {
            val suffix = " ($index)"
            target = if (extension.isBlank()) {
                File(parent, base + suffix)
            } else {
                File(parent, "$base$suffix.$extension")
            }
            index += 1
        }
        return target
    }

    companion object {
        const val AUTHORITY = "dev.gold.mdvault.test.documents"
        const val ROOT_ID = "root"
        const val METHOD_RESET = "reset"

        private val DEFAULT_ROOT_PROJECTION = arrayOf(
            DocumentsContract.Root.COLUMN_ROOT_ID,
            DocumentsContract.Root.COLUMN_DOCUMENT_ID,
            DocumentsContract.Root.COLUMN_TITLE,
            DocumentsContract.Root.COLUMN_FLAGS,
            DocumentsContract.Root.COLUMN_MIME_TYPES,
            DocumentsContract.Root.COLUMN_AVAILABLE_BYTES,
        )

        private val DEFAULT_DOCUMENT_PROJECTION = arrayOf(
            DocumentsContract.Document.COLUMN_DOCUMENT_ID,
            DocumentsContract.Document.COLUMN_DISPLAY_NAME,
            DocumentsContract.Document.COLUMN_MIME_TYPE,
            DocumentsContract.Document.COLUMN_LAST_MODIFIED,
            DocumentsContract.Document.COLUMN_SIZE,
            DocumentsContract.Document.COLUMN_FLAGS,
        )
    }
}
