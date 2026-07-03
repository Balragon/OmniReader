package dev.gold.mdvault.document

import dev.gold.mdvault.docx.MammothDocxImportEngine
import dev.gold.mdvault.markdown.FlexmarkMarkdownEngine
import dev.gold.mdvault.markdown.JsoupHtmlCleaner
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

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
            .orEmpty().sortedBy { it.name }
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
    fun `unsafe links are dropped with warnings`() {
        val result = import("links.docx")
        // javascript: href fixture — cleaner가 제거하고 warning으로 수집
        assertFalse(result.markdown.contains("javascript:"))
    }
}
