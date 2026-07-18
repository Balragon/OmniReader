package dev.gold.mdvault.storage

import android.content.ContentResolver
import android.net.Uri
import android.provider.OpenableColumns
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.Charset
import java.nio.charset.CodingErrorAction
import java.nio.charset.StandardCharsets

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
    val detected = bytes.detectTextEncoding()
    var payload = bytes.copyOfRange(detected.bomBytes, bytes.size)
    val utf8Candidate = if (truncated) payload.dropIncompleteTail(Charsets.UTF_8) else payload
    val charset = detected.charset
        ?: if (utf8Candidate.isValidUtf8()) Charsets.UTF_8 else fallbackCharset ?: Charsets.UTF_8
    if (truncated) payload = payload.dropIncompleteTail(charset)
    val text = payload.decodeStrict(charset)
        ?: fallbackCharset?.takeIf { it != charset }?.let(payload::decodeStrict)
        ?: String(payload, charset)
    return BoundedTextRead(text = text, truncated = truncated)
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

private data class DetectedEncoding(
    val charset: Charset?,
    val bomBytes: Int = 0,
)

private fun ByteArray.detectTextEncoding(): DetectedEncoding = when {
    startsWithBytes(0xEF, 0xBB, 0xBF) -> DetectedEncoding(Charsets.UTF_8, 3)
    startsWithBytes(0xFF, 0xFE) -> DetectedEncoding(Charsets.UTF_16LE, 2)
    startsWithBytes(0xFE, 0xFF) -> DetectedEncoding(Charsets.UTF_16BE, 2)
    else -> DetectedEncoding(declaredCharset())
}

private fun ByteArray.declaredCharset(): Charset? {
    val prefix = copyOfRange(0, size.coerceAtMost(CHARSET_DECLARATION_SCAN_BYTES))
        .toString(StandardCharsets.ISO_8859_1)
    val declaration = XML_DECLARATION.find(prefix)?.value
    val name = declaration?.let { XML_ENCODING.find(it)?.groupValues?.get(1) }
        ?: HTML_META_CHARSET.find(prefix)?.groupValues?.get(1)
        ?: HTML_META_CONTENT_TYPE.find(prefix)?.groupValues?.get(1)
    return name?.trim()?.takeIf { it.isNotEmpty() }?.let {
        runCatching { Charset.forName(it) }.getOrNull()
    }
}

private fun ByteArray.dropIncompleteTail(charset: Charset): ByteArray {
    if (charset == Charsets.UTF_8) {
        val completeSize = completeUtf8PrefixSize()
        return if (completeSize == size) this else copyOf(completeSize)
    }
    if (charset == Charsets.UTF_16LE || charset == Charsets.UTF_16BE ||
        charset.name().equals("UTF-16", ignoreCase = true)
    ) {
        var completeSize = size - (size % 2)
        if (completeSize >= 2) {
            val lastUnit = if (charset == Charsets.UTF_16LE) {
                (this[completeSize - 2].toInt() and 0xff) or ((this[completeSize - 1].toInt() and 0xff) shl 8)
            } else {
                ((this[completeSize - 2].toInt() and 0xff) shl 8) or (this[completeSize - 1].toInt() and 0xff)
            }
            if (lastUnit in 0xD800..0xDBFF) completeSize -= 2
        }
        return if (completeSize == size) this else copyOf(completeSize)
    }
    return this
}

private fun ByteArray.completeUtf8PrefixSize(): Int {
    if (isEmpty()) return 0
    var leadIndex = lastIndex
    var continuationBytes = 0
    while (
        leadIndex >= 0 &&
        (this[leadIndex].toInt() and 0xC0) == 0x80 &&
        continuationBytes < MAX_UTF8_CONTINUATION_BYTES
    ) {
        leadIndex -= 1
        continuationBytes += 1
    }
    if (leadIndex < 0 || (this[leadIndex].toInt() and 0xC0) == 0x80) return size
    val lead = this[leadIndex].toInt() and 0xff
    val expected = when (lead) {
        in 0x00..0x7f -> 1
        in 0xc2..0xdf -> 2
        in 0xe0..0xef -> 3
        in 0xf0..0xf4 -> 4
        else -> return size
    }
    return if (size - leadIndex < expected) leadIndex else size
}

private fun ByteArray.decodeStrict(charset: Charset): String? = try {
    charset.newDecoder()
        .onMalformedInput(CodingErrorAction.REPORT)
        .onUnmappableCharacter(CodingErrorAction.REPORT)
        .decode(ByteBuffer.wrap(this))
        .toString()
} catch (_: CharacterCodingException) {
    null
}

private fun ByteArray.startsWithBytes(vararg expected: Int): Boolean =
    size >= expected.size && expected.indices.all { index -> (this[index].toInt() and 0xff) == expected[index] }

private fun ByteArray.isValidUtf8(): Boolean {
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

        if (index + continuationCount >= size) return false
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

private const val CHARSET_DECLARATION_SCAN_BYTES = 1_024
private const val MAX_UTF8_CONTINUATION_BYTES = 3
private val XML_DECLARATION = Regex("""^\uFEFF?<\?xml\s+.*?\?>""", RegexOption.DOT_MATCHES_ALL)
private val XML_ENCODING = Regex(
    """\bencoding\s*=\s*["']\s*([^"'\s]+)""",
    RegexOption.IGNORE_CASE,
)
private val HTML_META_CHARSET = Regex(
    """<meta\b[^>]*\bcharset\s*=\s*["']?\s*([^\s"'/>;]+)""",
    RegexOption.IGNORE_CASE,
)
private val HTML_META_CONTENT_TYPE = Regex(
    """<meta\b[^>]*\bcontent\s*=\s*["'][^"']*\bcharset\s*=\s*([^\s"';>]+)""",
    RegexOption.IGNORE_CASE,
)
