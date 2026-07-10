package dev.gold.mdvault.storage

import android.content.ContentResolver
import android.net.Uri
import android.provider.OpenableColumns
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.nio.charset.Charset

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

internal fun InputStream.readTextBounded(
    maxBytes: Int,
    knownSize: Long?,
    fallbackCharset: Charset? = null,
): BoundedTextRead {
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
    val truncated = sizeSaysTruncated || streamSaysTruncated
    val charset = if (fallbackCharset != null && !bytes.isValidUtf8(allowIncompleteTail = truncated)) {
        fallbackCharset
    } else {
        Charsets.UTF_8
    }
    return BoundedTextRead(text = String(bytes, charset), truncated = truncated)
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

private fun ByteArray.isValidUtf8(allowIncompleteTail: Boolean): Boolean {
    var index = 0
    while (index < size) {
        val first = this[index].toInt() and 0xFF
        val continuationCount: Int
        val secondRange: IntRange
        when {
            first <= 0x7F -> {
                index += 1
                continue
            }
            first in 0xC2..0xDF -> {
                continuationCount = 1
                secondRange = 0x80..0xBF
            }
            first == 0xE0 -> {
                continuationCount = 2
                secondRange = 0xA0..0xBF
            }
            first in 0xE1..0xEC || first in 0xEE..0xEF -> {
                continuationCount = 2
                secondRange = 0x80..0xBF
            }
            first == 0xED -> {
                continuationCount = 2
                secondRange = 0x80..0x9F
            }
            first == 0xF0 -> {
                continuationCount = 3
                secondRange = 0x90..0xBF
            }
            first in 0xF1..0xF3 -> {
                continuationCount = 3
                secondRange = 0x80..0xBF
            }
            first == 0xF4 -> {
                continuationCount = 3
                secondRange = 0x80..0x8F
            }
            else -> return false
        }

        if (index + continuationCount >= size) return allowIncompleteTail
        val second = this[index + 1].toInt() and 0xFF
        if (second !in secondRange) return false
        for (offset in 2..continuationCount) {
            val next = this[index + offset].toInt() and 0xFF
            if (next !in 0x80..0xBF) return false
        }
        index += continuationCount + 1
    }
    return true
}
