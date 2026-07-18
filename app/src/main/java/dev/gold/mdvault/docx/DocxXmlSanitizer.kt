package dev.gold.mdvault.docx

import org.xml.sax.Attributes
import org.xml.sax.EntityResolver
import org.xml.sax.InputSource
import org.xml.sax.SAXException
import org.xml.sax.helpers.DefaultHandler
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FilterOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.io.StringReader
import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.Charset
import java.nio.charset.CodingErrorAction
import java.util.Locale
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream
import javax.xml.parsers.ParserConfigurationException
import javax.xml.parsers.SAXParserFactory

/** Applies the shared DOCX policy before Mammoth sees archive or XML content. */
internal object DocxXmlSanitizer {

    data class Sanitized(
        val bytes: ByteArray,
        val strippedCount: Int,
    )

    data class Result(
        val strippedCount: Int,
    )

    /** Convenience wrapper used by focused JVM tests. Production uses [sanitizeTo]. */
    fun sanitize(
        input: InputStream,
        policy: DocxImportPolicy = DocxImportPolicy(),
        checkCancelled: () -> Unit = {},
    ): Sanitized {
        val output = ByteArrayOutputStream()
        val result = sanitizeTo(input, output, policy, checkCancelled = checkCancelled)
        return Sanitized(output.toByteArray(), result.strippedCount)
    }

    /**
     * Sanitizes to a caller-owned stream without keeping the resulting archive in heap.
     * The compressed source is spooled to a bounded temporary file so content types can
     * classify every part independent of ZIP entry order.
     */
    fun sanitizeTo(
        input: InputStream,
        output: OutputStream,
        policy: DocxImportPolicy = DocxImportPolicy(),
        temporaryDirectory: File? = null,
        checkCancelled: () -> Unit = {},
    ): Result {
        if (temporaryDirectory != null && !temporaryDirectory.isDirectory && !temporaryDirectory.mkdirs()) {
            throw DocxImportRejectedException(DocxImportRejectedException.Reason.TEMPORARY_STORAGE_UNAVAILABLE)
        }
        val sourceFile = File.createTempFile(TEMP_FILE_PREFIX, TEMP_FILE_SUFFIX, temporaryDirectory)
        try {
            sourceFile.outputStream().buffered().use { temporaryOutput ->
                LimitedInputStream(
                    input,
                    policy.maxCompressedBytes,
                    DocxImportRejectedException.Reason.COMPRESSED_INPUT_LIMIT,
                ).copyCheckedTo(temporaryOutput, checkCancelled)
            }
            checkCancelled()
            return sanitizeArchive(sourceFile, output, policy, checkCancelled)
        } catch (error: DocxImportRejectedException) {
            throw error
        } catch (error: java.util.concurrent.CancellationException) {
            throw error
        } catch (error: Exception) {
            throw DocxImportRejectedException(
                DocxImportRejectedException.Reason.MALFORMED_ARCHIVE,
                error,
            )
        } finally {
            sourceFile.delete()
        }
    }

    private fun sanitizeArchive(
        sourceFile: File,
        output: OutputStream,
        policy: DocxImportPolicy,
        checkCancelled: () -> Unit,
    ): Result {
        var stripped = 0
        var expandedBytes = 0L
        ZipFile(sourceFile).use { archive ->
            val entries = inspectEntries(archive, policy, checkCancelled)
            val packageTypes = readPackageContentTypes(archive, entries, policy, checkCancelled)
            val limitedOutput = LimitedOutputStream(
                output,
                policy.maxSanitizedArchiveBytes,
                DocxImportRejectedException.Reason.SANITIZED_ARCHIVE_LIMIT,
            )
            ZipOutputStream(NonClosingOutputStream(limitedOutput)).use { zipOut ->
                for (entry in entries) {
                    checkCancelled()
                    zipOut.putNextEntry(ZipEntry(entry.name))
                    if (!entry.isDirectory) {
                        archive.getInputStream(entry).use { entryInput ->
                            if (packageTypes.isXmlPart(entry.name)) {
                                val bytes = entryInput.readBytesLimited(
                                    policy.maxEntryBytes,
                                    DocxImportRejectedException.Reason.ENTRY_SIZE_LIMIT,
                                    checkCancelled,
                                )
                                expandedBytes = accountExpandedBytes(expandedBytes, bytes.size.toLong(), policy)
                                val sanitized = sanitizeXmlPart(
                                    bytes = bytes,
                                    relationshipPart = packageTypes.isRelationshipPart(entry.name),
                                )
                                stripped += sanitized.bytesRemoved
                                zipOut.write(sanitized.bytes)
                            } else {
                                val copied = entryInput.copyEntryTo(
                                    output = zipOut,
                                    maxBytes = policy.maxEntryBytes,
                                    checkCancelled = checkCancelled,
                                )
                                expandedBytes = accountExpandedBytes(expandedBytes, copied, policy)
                            }
                        }
                    }
                    zipOut.closeEntry()
                }
            }
        }
        return Result(stripped)
    }

    private fun inspectEntries(
        archive: ZipFile,
        policy: DocxImportPolicy,
        checkCancelled: () -> Unit,
    ): List<ZipEntry> {
        val entries = mutableListOf<ZipEntry>()
        val partNames = mutableSetOf<String>()
        val iterator = archive.entries()
        while (iterator.hasMoreElements()) {
            checkCancelled()
            val entry = iterator.nextElement()
            if (entries.size >= policy.maxEntryCount) {
                throw DocxImportRejectedException(DocxImportRejectedException.Reason.ENTRY_COUNT_LIMIT)
            }
            validatePartName(entry.name, partNames)
            entries += entry
        }
        return entries
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

    private fun readPackageContentTypes(
        archive: ZipFile,
        entries: List<ZipEntry>,
        policy: DocxImportPolicy,
        checkCancelled: () -> Unit,
    ): PackageContentTypes {
        val contentTypesEntry = entries.firstOrNull {
            it.name.equals(CONTENT_TYPES_PART, ignoreCase = true)
        } ?: return PackageContentTypes.EMPTY
        val bytes = archive.getInputStream(contentTypesEntry).use {
            it.readBytesLimited(
                policy.maxEntryBytes,
                DocxImportRejectedException.Reason.ENTRY_SIZE_LIMIT,
                checkCancelled,
            )
        }
        val sanitized = sanitizeXmlPart(bytes, relationshipPart = false)
        val handler = ContentTypesHandler()
        parseXml(sanitized.text, handler)
        return handler.result()
    }

    private data class PackageContentTypes(
        val defaults: Map<String, String>,
        val overrides: Map<String, String>,
    ) {
        fun isXmlPart(name: String): Boolean =
            name.hasXmlSuffix() || contentTypeFor(name).isXmlContentType()

        fun isRelationshipPart(name: String): Boolean =
            name.hasRelationshipSuffix() || contentTypeFor(name).isRelationshipContentType()

        private fun contentTypeFor(name: String): String? {
            val normalized = "/${name.trimStart('/')}".lowercase(Locale.ROOT)
            return overrides[normalized]
                ?: name.substringAfterLast('.', "").lowercase(Locale.ROOT).takeIf { it.isNotEmpty() }
                    ?.let(defaults::get)
        }

        companion object {
            val EMPTY = PackageContentTypes(emptyMap(), emptyMap())
        }
    }

    private class ContentTypesHandler : DefaultHandler() {
        private val defaults = mutableMapOf<String, String>()
        private val overrides = mutableMapOf<String, String>()

        override fun startElement(uri: String?, localName: String?, qName: String?, attributes: Attributes) {
            when ((localName?.takeIf { it.isNotEmpty() } ?: qName.orEmpty()).substringAfter(':')) {
                "Default" -> {
                    val extension = attributes.valueOf("Extension").lowercase(Locale.ROOT)
                    val contentType = attributes.valueOf("ContentType").lowercase(Locale.ROOT)
                    if (extension.isBlank() || contentType.isBlank() || defaults.put(extension, contentType) != null) {
                        throw MalformedPackageXmlException()
                    }
                }
                "Override" -> {
                    val partName = attributes.valueOf("PartName").lowercase(Locale.ROOT)
                    val contentType = attributes.valueOf("ContentType").lowercase(Locale.ROOT)
                    if (!partName.startsWith('/') || contentType.isBlank() || overrides.put(partName, contentType) != null) {
                        throw MalformedPackageXmlException()
                    }
                }
            }
        }

        fun result(): PackageContentTypes = PackageContentTypes(defaults.toMap(), overrides.toMap())
    }

    private data class SanitizedPart(
        val bytes: ByteArray,
        val text: String,
        val bytesRemoved: Int,
    )

    private fun sanitizeXmlPart(
        bytes: ByteArray,
        relationshipPart: Boolean,
    ): SanitizedPart {
        val decoded = decodeXml(bytes)
        val normalizedText = decoded.text.filterIllegalXmlCharacters()
        if (normalizedText.contains("<!DOCTYPE", ignoreCase = true)) {
            throw DocxImportRejectedException(DocxImportRejectedException.Reason.DTD_NOT_ALLOWED)
        }
        validateDeclaredEncoding(normalizedText, decoded.charset)
        val cleaned = normalizedText.toByteArray(decoded.charset)
        val handler = if (relationshipPart) RelationshipHandler() else DefaultHandler()
        try {
            parseXml(normalizedText, handler)
        } catch (error: UnsafeRelationshipException) {
            throw DocxImportRejectedException(
                DocxImportRejectedException.Reason.EXTERNAL_RELATIONSHIP,
                error,
            )
        }
        return SanitizedPart(
            bytes = cleaned,
            text = normalizedText,
            bytesRemoved = (bytes.size - cleaned.size).coerceAtLeast(0),
        )
    }

    private class RelationshipHandler : DefaultHandler() {
        override fun startElement(uri: String?, localName: String?, qName: String?, attributes: Attributes) {
            val elementName = (localName?.takeIf { it.isNotEmpty() } ?: qName.orEmpty()).substringAfter(':')
            if (!elementName.equals("Relationship", ignoreCase = true)) return
            val type = attributes.valueOf("Type")
            if (type.endsWith("/hyperlink", ignoreCase = true)) return
            val targetMode = attributes.valueOf("TargetMode")
            val target = attributes.valueOf("Target").trim()
            if (
                targetMode.equals("External", ignoreCase = true) ||
                target.startsWith("//") ||
                ABSOLUTE_URI_SCHEME.containsMatchIn(target) ||
                target.contains('&')
            ) {
                throw UnsafeRelationshipException()
            }
        }
    }

    private fun Attributes.valueOf(name: String): String {
        for (index in 0 until length) {
            val attributeName = (getLocalName(index).takeIf { it.isNotEmpty() } ?: getQName(index)).substringAfter(':')
            if (attributeName.equals(name, ignoreCase = true)) return getValue(index).orEmpty()
        }
        return ""
    }

    private data class DecodedXml(
        val text: String,
        val charset: Charset,
    )

    private fun decodeXml(bytes: ByteArray): DecodedXml {
        if (bytes.hasPrefix(0x00, 0x00, 0xFE, 0xFF) || bytes.hasPrefix(0xFF, 0xFE, 0x00, 0x00)) {
            throw DocxImportRejectedException(DocxImportRejectedException.Reason.UNSUPPORTED_XML_ENCODING)
        }
        val charset = when {
            bytes.hasPrefix(0xFE, 0xFF) ||
                bytes.hasPrefix(0x00, 0x3C, 0x00, 0x3F) ||
                bytes.looksLikeUtf16BigEndian() -> Charsets.UTF_16BE
            bytes.hasPrefix(0xFF, 0xFE) ||
                bytes.hasPrefix(0x3C, 0x00, 0x3F, 0x00) ||
                bytes.looksLikeUtf16LittleEndian() -> Charsets.UTF_16LE
            else -> Charsets.UTF_8
        }
        val text = try {
            charset.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(bytes))
                .toString()
        } catch (error: CharacterCodingException) {
            throw DocxImportRejectedException(
                DocxImportRejectedException.Reason.UNSUPPORTED_XML_ENCODING,
                error,
            )
        }
        return DecodedXml(text, charset)
    }

    private fun validateDeclaredEncoding(text: String, charset: Charset) {
        val declaration = XML_DECLARATION.find(text.take(XML_DECLARATION_SCAN_CHARS))?.value ?: return
        val declared = XML_DECLARATION_ENCODING.find(declaration)
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
            throw DocxImportRejectedException(DocxImportRejectedException.Reason.UNSUPPORTED_XML_ENCODING)
        }
    }

    private fun parseXml(text: String, handler: DefaultHandler) {
        try {
            val parserFactory = SAXParserFactory.newInstance().apply {
                isNamespaceAware = false
                setFeature(EXTERNAL_GENERAL_ENTITIES_FEATURE, false)
            }
            val reader = parserFactory.newSAXParser().xmlReader.apply {
                setFeature(EXTERNAL_GENERAL_ENTITIES_FEATURE, false)
                entityResolver = EntityResolver { _, _ ->
                    throw SAXException("External XML entities are not allowed")
                }
                contentHandler = handler
            }
            reader.parse(InputSource(StringReader(text)))
        } catch (error: UnsafeRelationshipException) {
            throw error
        } catch (error: MalformedPackageXmlException) {
            throw DocxImportRejectedException(DocxImportRejectedException.Reason.MALFORMED_XML, error)
        } catch (error: ParserConfigurationException) {
            throw DocxImportRejectedException(DocxImportRejectedException.Reason.XML_PARSER_UNAVAILABLE, error)
        } catch (error: SAXException) {
            if (error is org.xml.sax.SAXNotRecognizedException || error is org.xml.sax.SAXNotSupportedException) {
                throw DocxImportRejectedException(DocxImportRejectedException.Reason.XML_PARSER_UNAVAILABLE, error)
            }
            throw DocxImportRejectedException(DocxImportRejectedException.Reason.MALFORMED_XML, error)
        } catch (error: java.io.IOException) {
            throw DocxImportRejectedException(DocxImportRejectedException.Reason.MALFORMED_XML, error)
        }
    }

    private fun String.filterIllegalXmlCharacters(): String {
        if (none { it.isIllegalXmlCharacter() }) return this
        return buildString(length) {
            for (character in this@filterIllegalXmlCharacters) {
                if (!character.isIllegalXmlCharacter()) append(character)
            }
        }
    }

    private fun Char.isIllegalXmlCharacter(): Boolean =
        code < 0x20 && this != '\t' && this != '\n' && this != '\r'

    private fun ByteArray.hasPrefix(vararg values: Int): Boolean =
        size >= values.size && values.indices.all { index -> (this[index].toInt() and 0xff) == values[index] }

    private fun ByteArray.looksLikeUtf16LittleEndian(): Boolean = hasUtf16NullPattern(nullParity = 1)

    private fun ByteArray.looksLikeUtf16BigEndian(): Boolean = hasUtf16NullPattern(nullParity = 0)

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

    private fun String.hasXmlSuffix(): Boolean {
        val lowercase = lowercase(Locale.ROOT)
        return lowercase.endsWith(".xml") || lowercase.endsWith(".rels")
    }

    private fun String.hasRelationshipSuffix(): Boolean = lowercase(Locale.ROOT).endsWith(".rels")

    private fun String?.isXmlContentType(): Boolean {
        val normalized = this?.substringBefore(';')?.trim()?.lowercase(Locale.ROOT) ?: return false
        return normalized == "application/xml" || normalized == "text/xml" || normalized.endsWith("+xml")
    }

    private fun String?.isRelationshipContentType(): Boolean =
        this?.substringBefore(';')?.trim()?.equals(RELATIONSHIPS_CONTENT_TYPE, ignoreCase = true) == true

    private fun accountExpandedBytes(current: Long, additional: Long, policy: DocxImportPolicy): Long {
        if (additional > policy.maxExpandedBytes - current) {
            throw DocxImportRejectedException(DocxImportRejectedException.Reason.EXPANDED_SIZE_LIMIT)
        }
        return current + additional
    }

    private fun InputStream.copyCheckedTo(output: OutputStream, checkCancelled: () -> Unit) {
        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
        while (true) {
            checkCancelled()
            val read = read(buffer)
            if (read < 0) return
            if (read > 0) output.write(buffer, 0, read)
        }
    }

    private fun InputStream.copyEntryTo(
        output: OutputStream,
        maxBytes: Long,
        checkCancelled: () -> Unit,
    ): Long {
        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
        var total = 0L
        while (true) {
            checkCancelled()
            val read = read(buffer)
            if (read < 0) return total
            if (read == 0) continue
            if (read > maxBytes - total) {
                throw DocxImportRejectedException(DocxImportRejectedException.Reason.ENTRY_SIZE_LIMIT)
            }
            output.write(buffer, 0, read)
            total += read
        }
    }

    private class UnsafeRelationshipException : SAXException()
    private class MalformedPackageXmlException : SAXException()
    private class NonClosingOutputStream(output: OutputStream) : FilterOutputStream(output) {
        override fun close() {
            flush()
        }
    }

    private val ABSOLUTE_URI_SCHEME = Regex("""^[A-Za-z][A-Za-z0-9+.-]*:""")
    private val XML_DECLARATION = Regex("""^\uFEFF?<\?xml\s+.*?\?>""", RegexOption.DOT_MATCHES_ALL)
    private val XML_DECLARATION_ENCODING = Regex(
        """\bencoding\s*=\s*["']\s*([^"'\s]+)""",
        setOf(RegexOption.IGNORE_CASE),
    )
    private const val XML_DECLARATION_SCAN_CHARS = 256
    private const val XML_ENCODING_SAMPLE_BYTES = 128
    private const val UTF16_MIN_NULLS = 4
    private const val CONTENT_TYPES_PART = "[Content_Types].xml"
    private const val RELATIONSHIPS_CONTENT_TYPE =
        "application/vnd.openxmlformats-package.relationships+xml"
    private const val EXTERNAL_GENERAL_ENTITIES_FEATURE =
        "http://xml.org/sax/features/external-general-entities"
    private const val TEMP_FILE_PREFIX = "omnireader-docx-source-"
    private const val TEMP_FILE_SUFFIX = ".zip"
}
