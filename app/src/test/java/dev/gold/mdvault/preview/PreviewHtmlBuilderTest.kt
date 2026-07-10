package dev.gold.mdvault.preview

import dev.gold.mdvault.markdown.FlexmarkMarkdownEngine
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class PreviewHtmlBuilderTest {

    @Test
    fun `wraps body with viewport, korean word-break and dark mode support`() {
        val html = PreviewHtmlBuilder.build("<p>본문</p>")
        assertTrue(html.contains("<p>본문</p>"))
        assertTrue(html.contains("viewport"))
        assertTrue(html.contains("keep-all"))
        assertTrue(html.contains("prefers-color-scheme: dark"))
        assertTrue(html.contains(".csv-scroll"))
        assertTrue(html.contains(".csv-row-number"))
        assertFalse("reader shell must not contain script tags", html.contains("<script"))
    }

    @Test
    fun `renders every markdown fixture through the engine without script injection`() {
        val engine = FlexmarkMarkdownEngine()
        val fixtures = File("../fixtures/md").listFiles { f ->
            f.isFile && f.extension == "md" && !f.name.endsWith(".EXPECTED.md")
        }.orEmpty()
        assertTrue(fixtures.isNotEmpty())
        for (fixture in fixtures) {
            val html = PreviewHtmlBuilder.build(engine.toHtml(fixture.readText()))
            assertFalse("script leaked for ${fixture.name}", html.contains("<script"))
            assertTrue(html.startsWith("<!DOCTYPE html>"))
        }
    }
}
