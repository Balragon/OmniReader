package dev.gold.mdvault.docx

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream
import java.util.concurrent.CancellationException

class DocxXmlSanitizerTest {

    @Test
    fun `rejects doctype declarations from xml parts`() {
        val malicious = """<?xml version="1.0"?>
            <!DOCTYPE lol [<!ENTITY xxe SYSTEM "file:///etc/passwd">]>
            <w:document>&xxe;</w:document>"""
        val error = assertThrows(DocxImportRejectedException::class.java) {
            sanitize("word/document.xml" to malicious.toByteArray())
        }
        assertEquals(DocxImportRejectedException.Reason.DTD_NOT_ALLOWED, error.reason)
    }

    @Test
    fun `normalizes illegal controls before checking for doctype`() {
        val malicious = "<!DOC".toByteArray() + byteArrayOf(0x01) + "TYPE doc><doc/>".toByteArray()

        val error = assertThrows(DocxImportRejectedException::class.java) {
            sanitize("word/document.xml" to malicious)
        }

        assertEquals(DocxImportRejectedException.Reason.DTD_NOT_ALLOWED, error.reason)
    }

    @Test
    fun `strips illegal control bytes but keeps media untouched`() {
        val xml = "<w:document>a".toByteArray() + byteArrayOf(0x08) + "b</w:document>".toByteArray()
        val media = byteArrayOf(0x08) + "binary".toByteArray() + byteArrayOf(0x00) + "data".toByteArray()
        val sanitized = DocxXmlSanitizer.sanitize(
            ByteArrayInputStream(
                zipOfBytes(
                    "word/document.xml" to xml,
                    "word/media/image1.png" to media,
                ),
            ),
        )
        assertTrue(entryBytes(sanitized.bytes, "word/document.xml").toString(Charsets.UTF_8).contains("ab"))
        assertArrayEquals(media, entryBytes(sanitized.bytes, "word/media/image1.png"))
    }

    @Test
    fun `mixed case XML parts receive the same DTD policy`() {
        val error = assertThrows(DocxImportRejectedException::class.java) {
            sanitize("word/document.XML" to "<!DOCTYPE doc><doc/>".toByteArray())
        }
        assertEquals(DocxImportRejectedException.Reason.DTD_NOT_ALLOWED, error.reason)
    }

    @Test
    fun `utf16 XML parts receive the same DTD policy`() {
        val xml = "<?xml version=\"1.0\" encoding=\"UTF-16\"?><!DOCTYPE doc><doc/>"
            .toByteArray(Charsets.UTF_16LE)
        val withBom = byteArrayOf(0xff.toByte(), 0xfe.toByte()) + xml
        val error = assertThrows(DocxImportRejectedException::class.java) {
            sanitize("word/document.xml" to withBom)
        }
        assertEquals(DocxImportRejectedException.Reason.DTD_NOT_ALLOWED, error.reason)

        val noBomWithLeadingWhitespace =
            "  <?xml version=\"1.0\" encoding=\"UTF-16LE\"?><!DOCTYPE doc><doc/>"
                .toByteArray(Charsets.UTF_16LE)
        val noBomError = assertThrows(DocxImportRejectedException::class.java) {
            sanitize("word/document.xml" to noBomWithLeadingWhitespace)
        }
        assertEquals(DocxImportRejectedException.Reason.DTD_NOT_ALLOWED, noBomError.reason)
    }

    @Test
    fun `external non-hyperlink relationships are rejected before conversion`() {
        val relationships = """
            <Relationships>
              <Relationship Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/image" TargetMode="External" Target="file:///tmp/private.png" />
            </Relationships>
        """.trimIndent()
        val error = assertThrows(DocxImportRejectedException::class.java) {
            sanitize("word/_rels/document.xml.RELS" to relationships.toByteArray())
        }
        assertEquals(DocxImportRejectedException.Reason.EXTERNAL_RELATIONSHIP, error.reason)
    }

    @Test
    fun `relationship attributes after a quoted angle bracket cannot bypass validation`() {
        val relationships =
            """<Relationship Type="image" Note=">" TargetMode="External" Target="file:///tmp/private.png" />"""

        val error = assertThrows(DocxImportRejectedException::class.java) {
            sanitize("word/_rels/document.xml.rels" to relationships.toByteArray())
        }

        assertEquals(DocxImportRejectedException.Reason.EXTERNAL_RELATIONSHIP, error.reason)
    }

    @Test
    fun `external hyperlinks remain valid document content`() {
        val relationships = """
            <Relationships>
              <Relationship Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/hyperlink" TargetMode="External" Target="https://example.com" />
            </Relationships>
        """.trimIndent()
        val sanitized = sanitize("word/_rels/document.xml.rels" to relationships.toByteArray())
        assertTrue(
            entryBytes(sanitized.bytes, "word/_rels/document.xml.rels")
                .toString(Charsets.UTF_8)
                .contains("example.com"),
        )
    }

    @Test
    fun `absolute image targets are rejected even without TargetMode`() {
        val variants = listOf(
            """<Relationship Type="image" Target="file:///tmp/private.png" />""",
            """<r:Relationship Type="image" Target="file&#58;///tmp/private.png" />""",
        )
        for (relationship in variants) {
            val error = assertThrows(DocxImportRejectedException::class.java) {
                sanitize("word/_rels/document.xml.rels" to relationship.toByteArray())
            }
            assertEquals(DocxImportRejectedException.Reason.EXTERNAL_RELATIONSHIP, error.reason)
        }
    }

    @Test
    fun `malformed XML is rejected instead of reaching Mammoth`() {
        val error = assertThrows(DocxImportRejectedException::class.java) {
            sanitize("word/document.xml" to "<document><unclosed></document>".toByteArray())
        }

        assertEquals("MALFORMED_XML", error.reason.name)
    }

    @Test
    fun `only an XML declaration controls the declared encoding`() {
        val validUtf8 = """<document encoding="windows-1252"><text>safe</text></document>"""

        val sanitized = sanitize("word/document.xml" to validUtf8.toByteArray())

        assertTrue(
            entryBytes(sanitized.bytes, "word/document.xml")
                .toString(Charsets.UTF_8)
                .contains("windows-1252"),
        )
    }

    @Test
    fun `strips illegal UTF16 control code points before XML parsing`() {
        val source = "<?xml version=\"1.0\" encoding=\"UTF-16LE\"?><document>a\u0001b</document>"
            .toByteArray(Charsets.UTF_16LE)

        val sanitized = sanitize("word/document.xml" to source)
        val decoded = entryBytes(sanitized.bytes, "word/document.xml").toString(Charsets.UTF_16LE)

        assertEquals("<?xml version=\"1.0\" encoding=\"UTF-16LE\"?><document>ab</document>", decoded)
        assertEquals(2, sanitized.strippedCount)
    }

    @Test
    fun `content types classify nonstandard XML part names`() {
        val contentTypes = """
            <Types xmlns="http://schemas.openxmlformats.org/package/2006/content-types">
              <Override PartName="/word/custom.payload" ContentType="application/vnd.example+xml" />
            </Types>
        """.trimIndent()
        val archive = zipOfBytes(
            "word/custom.payload" to "<!DOCTYPE doc><doc/>".toByteArray(),
            "[Content_Types].xml" to contentTypes.toByteArray(),
        )

        val error = assertThrows(DocxImportRejectedException::class.java) {
            DocxXmlSanitizer.sanitize(ByteArrayInputStream(archive))
        }

        assertEquals(DocxImportRejectedException.Reason.DTD_NOT_ALLOWED, error.reason)
    }

    @Test
    fun `entry count and expanded bytes are bounded`() {
        val twoEntries = zipOfBytes(
            "a.xml" to "<a/>".toByteArray(),
            "b.xml" to "<b/>".toByteArray(),
        )
        val entryError = assertThrows(DocxImportRejectedException::class.java) {
            DocxXmlSanitizer.sanitize(
                ByteArrayInputStream(twoEntries),
                DocxImportPolicy(maxEntryCount = 1),
            )
        }
        assertEquals(DocxImportRejectedException.Reason.ENTRY_COUNT_LIMIT, entryError.reason)

        val oversizedEntry = zipOfBytes("large.bin" to ByteArray(32) { 'a'.code.toByte() })
        val sizeError = assertThrows(DocxImportRejectedException::class.java) {
            DocxXmlSanitizer.sanitize(
                ByteArrayInputStream(oversizedEntry),
                DocxImportPolicy(maxEntryBytes = 16, maxExpandedBytes = 16),
            )
        }
        assertEquals(DocxImportRejectedException.Reason.ENTRY_SIZE_LIMIT, sizeError.reason)

        val aggregate = zipOfBytes(
            "a.bin" to ByteArray(24),
            "b.bin" to ByteArray(24),
        )
        val aggregateError = assertThrows(DocxImportRejectedException::class.java) {
            DocxXmlSanitizer.sanitize(
                ByteArrayInputStream(aggregate),
                DocxImportPolicy(maxEntryBytes = 32, maxExpandedBytes = 40),
            )
        }
        assertEquals(DocxImportRejectedException.Reason.EXPANDED_SIZE_LIMIT, aggregateError.reason)
    }

    @Test
    fun `actual compressed bytes are bounded independently of metadata`() {
        val archive = zipOfBytes("document.xml" to "<document/>".toByteArray())
        val error = assertThrows(DocxImportRejectedException::class.java) {
            DocxXmlSanitizer.sanitize(
                ByteArrayInputStream(archive),
                DocxImportPolicy(maxCompressedBytes = 8),
            )
        }
        assertEquals(DocxImportRejectedException.Reason.COMPRESSED_INPUT_LIMIT, error.reason)
    }

    @Test
    fun `cancellation is observed inside archive processing and is not wrapped`() {
        val archive = zipOfBytes(
            "a.xml" to "<a/>".toByteArray(),
            "b.xml" to "<b/>".toByteArray(),
        )
        var checks = 0

        assertThrows(CancellationException::class.java) {
            DocxXmlSanitizer.sanitize(ByteArrayInputStream(archive)) {
                checks += 1
                if (checks >= 4) throw CancellationException("cancelled")
            }
        }
        assertTrue(checks >= 4)
    }

    private fun sanitize(entry: Pair<String, ByteArray>): DocxXmlSanitizer.Sanitized =
        DocxXmlSanitizer.sanitize(ByteArrayInputStream(zipOfBytes(entry)))

    private fun zipOfBytes(vararg entries: Pair<String, ByteArray>): ByteArray {
        val out = ByteArrayOutputStream()
        ZipOutputStream(out).use { zip ->
            for ((name, content) in entries) {
                zip.putNextEntry(ZipEntry(name))
                zip.write(content)
                zip.closeEntry()
            }
        }
        return out.toByteArray()
    }

    private fun entryBytes(zipBytes: ByteArray, name: String): ByteArray {
        ZipInputStream(ByteArrayInputStream(zipBytes)).use { zip ->
            var entry = zip.nextEntry
            while (entry != null) {
                if (entry.name == name) return zip.readBytes()
                entry = zip.nextEntry
            }
        }
        throw AssertionError("entry not found: $name")
    }
}
