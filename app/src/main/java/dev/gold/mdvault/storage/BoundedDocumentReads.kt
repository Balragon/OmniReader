package dev.gold.mdvault.storage

import android.content.ContentResolver
import android.net.Uri
import android.provider.OpenableColumns
import java.io.ByteArrayOutputStream
import java.io.InputStream

internal data class BoundedTextRead(
    val text: String,
    val truncated: Boolean,
)

internal fun ContentResolver.openableSize(uri: Uri): Long? {
    runCatching {
        query(uri, arrayOf(OpenableColumns.SIZE), null, null, null)?.use { cursor ->
            if (cursor.moveToFirst()) {
                val index = cursor.getColumnIndex(OpenableColumns.SIZE)
                if (index >= 0 && !cursor.isNull(index)) {
                    val size = cursor.getLong(index)
                    if (size >= 0L) return size
                }
            }
        }
    }
    return runCatching {
        openAssetFileDescriptor(uri, "r")?.use { descriptor ->
            descriptor.length.takeIf { it >= 0L }
        }
    }.getOrNull()
}

internal fun InputStream.readTextBounded(maxBytes: Int, knownSize: Long?): BoundedTextRead {
    require(maxBytes > 0) { "maxBytes must be positive" }
    val sizeSaysTruncated = knownSize != null && knownSize > maxBytes
    var streamSaysTruncated = false
    val bytes = if (sizeSaysTruncated) {
        readAtMostBytes(maxBytes)
    } else {
        val sampled = readAtMostBytes(maxBytes + 1)
        streamSaysTruncated = sampled.size > maxBytes
        if (streamSaysTruncated) sampled.copyOf(maxBytes) else sampled
    }
    return BoundedTextRead(
        text = String(bytes, Charsets.UTF_8),
        truncated = sizeSaysTruncated || streamSaysTruncated,
    )
}

internal fun InputStream.readAtMostBytes(maxBytes: Int): ByteArray {
    require(maxBytes >= 0) { "maxBytes must not be negative" }
    val output = ByteArrayOutputStream(minOf(maxBytes, DEFAULT_BUFFER_SIZE))
    val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
    var remaining = maxBytes
    while (remaining > 0) {
        val read = read(buffer, 0, minOf(buffer.size, remaining))
        if (read == -1) break
        output.write(buffer, 0, read)
        remaining -= read
    }
    return output.toByteArray()
}

internal suspend fun VaultRepository.vaultDocumentSize(relativePath: String): Long? {
    val parentPath = relativePath.substringBeforeLast('/', missingDelimiterValue = "")
    val fileName = relativePath.substringAfterLast('/')
    return list(parentPath)
        .firstOrNull { !it.isDirectory && it.displayName == fileName }
        ?.size
}
