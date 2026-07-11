package dev.gold.mdvault.docx

import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.nio.charset.Charset
import java.util.Locale
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

/** Applies the shared DOCX policy before Mammoth sees archive or XML content. */
internal object DocxXmlSanitizer {

    data class Sanitized(
        val bytes: ByteArray,
        val strippedCount: Int,
    )

    fun sanitize(
        input: InputStream,
        policy: DocxImportPolicy = DocxImportPolicy(),
    ): Sanitized {
        val output = ByteArrayOutputStream()
        val limitedInput = LimitedInputStream(
            input,
            policy.maxCompressedBytes,
            DocxImportRejectedException.Reason.COMPRESSED_INPUT_LIMIT,
        )
        val limitedOutput = LimitedOutputStream(
            output,
            policy.maxSanitizedArchiveBytes,
            DocxImportRejectedException.Reason.SANITIZED_ARCHIVE_LIMIT,
        )
        var stripped = 0
        var entryCount = 0
        var expandedBytes = 0L
        val partNames = mutableSetOf<String>()

        ZipInputStream(limitedInput).use { zipIn ->
            ZipOutputStream(limitedOutput).use { zipOut ->
                var entry = zipIn.nextEntry
                while (entry != null) {
                    entryCount += 1
                    if (entryCount > policy.maxEntryCount) {
                        throw DocxImportRejectedException(
                            DocxImportRejectedException.Reason.ENTRY_COUNT_LIMIT,
                        )
                    }
                    validatePartName(entry.name, partNames)
                    val bytes = zipIn.readBytesLimited(
                        policy.maxEntryBytes,
                        DocxImportRejectedException.Reason.ENTRY_SIZE_LIMIT,
                    )
                    expandedBytes += bytes.size
                    if (expandedBytes > policy.maxExpandedBytes) {
                        throw DocxImportRejectedException(
                            DocxImportRejectedException.Reason.EXPANDED_SIZE_LIMIT,
                        )
                    }
                    val cleaned = if (entry.name.isXmlPart()) {
                        val sanitizedPart = sanitizeXmlPart(entry.name, bytes)
                        stripped += sanitizedPart.bytesRemoved
                        sanitizedPart.bytes
                    } else {
                        bytes
                    }
                    zipOut.putNextEntry(ZipEntry(entry.name))
                    zipOut.write(cleaned)
                    zipOut.closeEntry()
                    zipIn.closeEntry()
                    entry = zipIn.nextEntry
                }
            }
        }
        return Sanitized(output.toByteArray(), stripped)
    }

    private fun validatePartName(name: String, partNames: MutableSet<String>) {
        val slashNormalized = name.replace('\\', '/')
        val normalized = slashNormalized.removeSuffix("/")
        val segments = normalized.split('/')
        if (
            name.isBlank() ||
            name.startsWith('/') ||
            name.contains('\\') ||
            slashNormalized.contains("//") ||
            segments.any { it.isBlank() || it == "." || it == ".." }
        ) {
            throw DocxImportRejectedException(DocxImportRejectedException.Reason.UNSAFE_PART_NAME)
        }
        if (!partNames.add(normalized.lowercase(Locale.ROOT))) {
            throw DocxImportRejectedException(DocxImportRejectedException.Reason.DUPLICATE_ENTRY)
        }
    }

    private data class SanitizedPart(
        val bytes: ByteArray,
        val bytesRemoved: Int,
    )

    private fun sanitizeXmlPart(name: String, bytes: ByteArray): SanitizedPart {
        val decoded = decodeXml(bytes)
        if (decoded.text.contains("<!DOCTYPE", ignoreCase = true)) {
            throw DocxImportRejectedException(DocxImportRejectedException.Reason.DTD_NOT_ALLOWED)
        }
        if (
            name.isRelationshipPart() &&
            RELATIONSHIP_TAG.findAll(decoded.text).any { it.value.isUnsafeExternalRelationshipTag() }
        ) {
            throw DocxImportRejectedException(
                DocxImportRejectedException.Reason.EXTERNAL_RELATIONSHIP,
            )
        }
        val cleaned = if (decoded.canFilterAsciiControlBytes) {
            filterIllegalXmlBytes(bytes)
        } else {
            bytes
        }
        return SanitizedPart(
            bytes = cleaned,
            bytesRemoved = (bytes.size - cleaned.size).coerceAtLeast(0),
        )
    }

    private data class DecodedXml(
        val text: String,
        val canFilterAsciiControlBytes: Boolean,
    )

    private fun decodeXml(bytes: ByteArray): DecodedXml {
        if (bytes.hasPrefix(0x00, 0x00, 0xFE, 0xFF) || bytes.hasPrefix(0xFF, 0xFE, 0x00, 0x00)) {
            throw DocxImportRejectedException(
                DocxImportRejectedException.Reason.UNSUPPORTED_XML_ENCODING,
            )
        }
        val (charset, filterControls) = when {
            bytes.hasPrefix(0xFE, 0xFF) ||
                bytes.hasPrefix(0x00, 0x3C, 0x00, 0x3F) ||
                bytes.looksLikeUtf16BigEndian() ->
                Charsets.UTF_16BE to false
            bytes.hasPrefix(0xFF, 0xFE) ||
                bytes.hasPrefix(0x3C, 0x00, 0x3F, 0x00) ||
                bytes.looksLikeUtf16LittleEndian() ->
                Charsets.UTF_16LE to false
            else -> Charsets.UTF_8 to true
        }
        val text = bytes.toString(charset)
        validateDeclaredEncoding(text, charset)
        return DecodedXml(text, filterControls)
    }

    private fun validateDeclaredEncoding(text: String, charset: Charset) {
        val declared = XML_ENCODING.find(text.take(XML_DECLARATION_SCAN_CHARS))
            ?.groupValues
            ?.get(1)
            ?.lowercase(Locale.ROOT)
            ?: return
        val allowed = when (charset) {
            Charsets.UTF_16LE -> setOf("utf-16", "utf-16le")
            Charsets.UTF_16BE -> setOf("utf-16", "utf-16be")
            else -> setOf("utf-8", "utf8", "us-ascii", "ascii")
        }
        if (declared !in allowed) {
            throw DocxImportRejectedException(
                DocxImportRejectedException.Reason.UNSUPPORTED_XML_ENCODING,
            )
        }
    }

    private fun ByteArray.hasPrefix(vararg values: Int): Boolean =
        size >= values.size && values.indices.all { index -> (this[index].toInt() and 0xff) == values[index] }

    private fun ByteArray.looksLikeUtf16LittleEndian(): Boolean =
        hasUtf16NullPattern(nullParity = 1)

    private fun ByteArray.looksLikeUtf16BigEndian(): Boolean =
        hasUtf16NullPattern(nullParity = 0)

    private fun ByteArray.hasUtf16NullPattern(nullParity: Int): Boolean {
        val sampleSize = size.coerceAtMost(XML_ENCODING_SAMPLE_BYTES)
        var expectedNulls = 0
        var oppositeNulls = 0
        for (index in 0 until sampleSize) {
            if (this[index].toInt() != 0) continue
            if (index % 2 == nullParity) expectedNulls += 1 else oppositeNulls += 1
        }
        return expectedNulls >= UTF16_MIN_NULLS && expectedNulls > oppositeNulls * 2
    }

    private fun String.isXmlPart(): Boolean {
        val lowercase = lowercase(Locale.ROOT)
        return lowercase.endsWith(".xml") || lowercase.endsWith(".rels")
    }

    private fun String.isRelationshipPart(): Boolean =
        lowercase(Locale.ROOT).endsWith(".rels")

    private fun String.isUnsafeExternalRelationshipTag(): Boolean {
        val type = RELATIONSHIP_TYPE.find(this)?.groupValues?.get(1).orEmpty()
        if (type.endsWith("/hyperlink", ignoreCase = true)) return false
        if (EXTERNAL_RELATIONSHIP_ATTRIBUTE.containsMatchIn(this)) return true
        val target = RELATIONSHIP_TARGET.find(this)?.groupValues?.get(1)?.trim().orEmpty()
        return target.startsWith("//") ||
            ABSOLUTE_URI_SCHEME.containsMatchIn(target) ||
            target.contains('&')
    }

    private fun filterIllegalXmlBytes(bytes: ByteArray): ByteArray {
        if (bytes.none { it.isIllegalXmlByte() }) return bytes
        val result = ByteArrayOutputStream(bytes.size)
        for (byte in bytes) {
            if (!byte.isIllegalXmlByte()) result.write(byte.toInt())
        }
        return result.toByteArray()
    }

    private fun Byte.isIllegalXmlByte(): Boolean {
        val value = toInt() and 0xff
        return value < 0x20 && value != 0x09 && value != 0x0A && value != 0x0D
    }

    private val EXTERNAL_RELATIONSHIP_ATTRIBUTE = Regex(
        """\bTargetMode\s*=""",
        setOf(RegexOption.IGNORE_CASE),
    )
    private val RELATIONSHIP_TAG = Regex(
        """<\s*(?:[A-Za-z_][A-Za-z0-9_.-]*:)?Relationship\b[^>]*>""",
        setOf(RegexOption.IGNORE_CASE),
    )
    private val RELATIONSHIP_TYPE = Regex(
        """\bType\s*=\s*["']\s*([^"']+)""",
        setOf(RegexOption.IGNORE_CASE),
    )
    private val RELATIONSHIP_TARGET = Regex(
        """\bTarget\s*=\s*["']\s*([^"']+)""",
        setOf(RegexOption.IGNORE_CASE),
    )
    private val ABSOLUTE_URI_SCHEME = Regex("""^[A-Za-z][A-Za-z0-9+.-]*:""")
    private val XML_ENCODING = Regex(
        """\bencoding\s*=\s*["']\s*([^"'\s]+)""",
        setOf(RegexOption.IGNORE_CASE),
    )
    private const val XML_DECLARATION_SCAN_CHARS = 256
    private const val XML_ENCODING_SAMPLE_BYTES = 128
    private const val UTF16_MIN_NULLS = 4
}
