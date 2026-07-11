package dev.gold.mdvault.docx

import java.io.ByteArrayOutputStream
import java.io.FilterInputStream
import java.io.FilterOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream

/** Shared resource and capability policy for untrusted DOCX imports. */
data class DocxImportPolicy(
    val maxCompressedBytes: Long = 50L * 1024L * 1024L,
    val maxEntryCount: Int = 4_096,
    val maxEntryBytes: Long = 24L * 1024L * 1024L,
    val maxExpandedBytes: Long = 96L * 1024L * 1024L,
    val maxSanitizedArchiveBytes: Long = 64L * 1024L * 1024L,
    val maxAssetCount: Int = 256,
    val maxAssetBytes: Long = 8L * 1024L * 1024L,
    val maxTotalAssetBytes: Long = 32L * 1024L * 1024L,
    val maxConversionCharacters: Int = 8 * 1024 * 1024,
) {
    init {
        require(maxCompressedBytes > 0)
        require(maxEntryCount > 0)
        require(maxEntryBytes > 0)
        require(maxExpandedBytes >= maxEntryBytes)
        require(maxSanitizedArchiveBytes > 0)
        require(maxAssetCount > 0)
        require(maxAssetBytes > 0)
        require(maxTotalAssetBytes >= maxAssetBytes)
        require(maxConversionCharacters > 0)
    }
}

class DocxImportRejectedException(
    val reason: Reason,
) : IOException(reason.message) {
    enum class Reason(val message: String) {
        COMPRESSED_INPUT_LIMIT("The DOCX exceeds the compressed input limit"),
        ENTRY_COUNT_LIMIT("The DOCX contains too many archive entries"),
        ENTRY_SIZE_LIMIT("A DOCX archive entry is too large"),
        EXPANDED_SIZE_LIMIT("The DOCX expands beyond the safe import limit"),
        SANITIZED_ARCHIVE_LIMIT("The sanitized DOCX exceeds the safe import limit"),
        DUPLICATE_ENTRY("The DOCX contains duplicate archive entries"),
        UNSAFE_PART_NAME("The DOCX contains an unsafe archive path"),
        UNSUPPORTED_XML_ENCODING("The DOCX contains an unsupported XML encoding"),
        DTD_NOT_ALLOWED("DOCX XML declarations that can load entities are not allowed"),
        EXTERNAL_RELATIONSHIP("External DOCX relationships are not allowed"),
        ASSET_COUNT_LIMIT("The DOCX contains too many embedded images"),
        ASSET_SIZE_LIMIT("An embedded DOCX image is too large"),
        TOTAL_ASSET_SIZE_LIMIT("The DOCX contains too much embedded image data"),
        CONVERSION_OUTPUT_LIMIT("The converted DOCX content is too large"),
    }
}

internal class LimitedInputStream(
    input: InputStream,
    private val limit: Long,
    private val reason: DocxImportRejectedException.Reason,
) : FilterInputStream(input) {
    private var count = 0L

    override fun read(): Int {
        val value = super.read()
        if (value >= 0) account(1)
        return value
    }

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
        if (length == 0) return 0
        val allowed = (limit - count + 1L).coerceAtMost(length.toLong()).toInt()
        if (allowed <= 0) throw DocxImportRejectedException(reason)
        val read = super.read(buffer, offset, allowed)
        if (read > 0) account(read.toLong())
        return read
    }

    private fun account(bytes: Long) {
        count += bytes
        if (count > limit) throw DocxImportRejectedException(reason)
    }
}

internal class LimitedOutputStream(
    output: OutputStream,
    private val limit: Long,
    private val reason: DocxImportRejectedException.Reason,
) : FilterOutputStream(output) {
    private var count = 0L

    override fun write(value: Int) {
        account(1)
        out.write(value)
    }

    override fun write(buffer: ByteArray, offset: Int, length: Int) {
        account(length.toLong())
        out.write(buffer, offset, length)
    }

    private fun account(bytes: Long) {
        if (bytes > limit - count) throw DocxImportRejectedException(reason)
        count += bytes
    }
}

internal fun InputStream.readBytesLimited(
    limit: Long,
    reason: DocxImportRejectedException.Reason,
): ByteArray {
    val initialSize = limit.coerceAtMost(DEFAULT_BUFFER_SIZE.toLong()).toInt()
    val output = ByteArrayOutputStream(initialSize)
    val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
    var total = 0L
    while (true) {
        val read = read(buffer)
        if (read < 0) break
        if (read == 0) continue
        total += read
        if (total > limit) throw DocxImportRejectedException(reason)
        output.write(buffer, 0, read)
    }
    return output.toByteArray()
}
