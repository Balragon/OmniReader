package dev.gold.mdvault.docx

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

@RunWith(AndroidJUnit4::class)
class DocxXmlSanitizerAndroidTest {
    @Test
    fun AndroidExpatParserAcceptsSafeXmlAndRejectsNormalizedDoctype() {
        val safe = sanitize("word/document.xml", "<document><text>safe</text></document>".toByteArray())
        assertTrue(safe.isNotEmpty())

        val malicious = "<!DOC".toByteArray() + byteArrayOf(0x01) + "TYPE doc><doc/>".toByteArray()
        val error = rejectionFor("word/document.xml", malicious)
        assertEquals(DocxImportRejectedException.Reason.DTD_NOT_ALLOWED, error.reason)
    }

    @Test
    fun AndroidExpatParserReadsRelationshipAttributesStructurally() {
        val relationship =
            """<Relationship Type="image" Note=">" TargetMode="External" Target="file:///tmp/private.png" />"""

        val error = rejectionFor("word/_rels/document.xml.rels", relationship.toByteArray())

        assertEquals(DocxImportRejectedException.Reason.EXTERNAL_RELATIONSHIP, error.reason)
    }

    private fun rejectionFor(name: String, bytes: ByteArray): DocxImportRejectedException = try {
        sanitize(name, bytes)
        throw AssertionError("Expected DOCX rejection")
    } catch (error: DocxImportRejectedException) {
        error
    }

    private fun sanitize(name: String, bytes: ByteArray): ByteArray {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val output = ByteArrayOutputStream()
        DocxXmlSanitizer.sanitizeTo(
            input = ByteArrayInputStream(zipOf(name to bytes)),
            output = output,
            temporaryDirectory = context.cacheDir,
        )
        return output.toByteArray()
    }

    private fun zipOf(vararg entries: Pair<String, ByteArray>): ByteArray {
        val output = ByteArrayOutputStream()
        ZipOutputStream(output).use { zip ->
            for ((name, bytes) in entries) {
                zip.putNextEntry(ZipEntry(name))
                zip.write(bytes)
                zip.closeEntry()
            }
        }
        return output.toByteArray()
    }
}
