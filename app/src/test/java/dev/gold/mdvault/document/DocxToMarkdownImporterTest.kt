package dev.gold.mdvault.document

import dev.gold.mdvault.docx.MammothDocxImportEngine
import dev.gold.mdvault.docx.DocxImportRejectedException
import dev.gold.mdvault.docx.DocxImportEngine
import dev.gold.mdvault.docx.DocxImportPolicy
import dev.gold.mdvault.docx.HtmlImportResult
import dev.gold.mdvault.markdown.FlexmarkMarkdownEngine
import dev.gold.mdvault.markdown.JsoupHtmlCleaner
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test
import java.io.File
import java.io.ByteArrayInputStream

class DocxToMarkdownImporterTest {

    private val importer = DocxToMarkdownImporter(
        docxImportEngine = MammothDocxImportEngine(),
        htmlCleaner = JsoupHtmlCleaner(),
        markdownEngine = FlexmarkMarkdownEngine(),
    )
    private val fixtureDir = File("../fixtures/docx")

    private fun import(name: String) =
        File(fixtureDir, name).inputStream().use { input ->
            importer.import(input) { _, _, _ -> }
        }

    @Test
    fun `every docx fixture imports to non-blank markdown`() {
        val fixtures = fixtureDir.listFiles { file -> file.extension == "docx" }
            .orEmpty().filterNot { it.name == "links.docx" }.sortedBy { it.name }
        assertTrue(fixtures.isNotEmpty())
        for (fixture in fixtures) {
            val result = import(fixture.name)
            assertTrue("Blank markdown for ${fixture.name}", result.markdown.isNotBlank())
            assertFalse("data: URI in markdown for ${fixture.name}", result.markdown.contains("](data:"))
        }
    }

    @Test
    fun `korean document produces korean headings in markdown`() {
        val result = import("simple-korean.docx")
        assertTrue("heading marker missing:\n${result.markdown}", result.markdown.contains("# "))
    }

    @Test
    fun `images document references extracted asset paths in markdown`() {
        val result = import("images.docx")
        assertTrue(result.assets.size == 5)
        assertTrue(
            "markdown must reference media/ paths:\n${result.markdown.take(500)}",
            result.markdown.contains("media/"),
        )
    }

    @Test
    fun `external image relationships are rejected before conversion`() {
        val error = assertThrows(DocxImportRejectedException::class.java) {
            import("links.docx")
        }
        assertEquals(DocxImportRejectedException.Reason.EXTERNAL_RELATIONSHIP, error.reason)
    }

    @Test
    fun `conversion output is bounded before DOM expansion`() {
        val limitedImporter = DocxToMarkdownImporter(
            docxImportEngine = object : DocxImportEngine {
                override fun importDocx(
                    input: java.io.InputStream,
                    imageSink: dev.gold.mdvault.docx.ImageSink,
                ): HtmlImportResult = HtmlImportResult(
                    html = "<p>${"x".repeat(32)}</p>",
                    warnings = emptyList(),
                    extractedAssets = emptyList(),
                )
            },
            htmlCleaner = JsoupHtmlCleaner(),
            markdownEngine = FlexmarkMarkdownEngine(),
            policy = DocxImportPolicy(maxConversionCharacters = 16),
        )

        val error = assertThrows(DocxImportRejectedException::class.java) {
            limitedImporter.import(ByteArrayInputStream(byteArrayOf())) { _, _, _ -> }
        }
        assertEquals(DocxImportRejectedException.Reason.CONVERSION_OUTPUT_LIMIT, error.reason)
    }
}
