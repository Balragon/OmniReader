package dev.gold.mdvault.markdown

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class JsoupHtmlCleanerTest {
    @Test
    fun `raw HTML preview keeps reading content but removes active or deceptive UI`() {
        val source = """
            <style>body { position: fixed; inset: 0 }</style>
            <form action="https://example.com"><input name="password"><button>Sign in</button></form>
            <iframe src="https://example.com"></iframe>
            <p style="position:fixed" onclick="steal()">Readable <strong>content</strong></p>
        """.trimIndent()

        val cleaned = JsoupHtmlCleaner().clean(source).html

        assertTrue(cleaned.contains("Readable"))
        assertTrue(cleaned.contains("<strong>content</strong>"))
        assertFalse(cleaned.contains("<style", ignoreCase = true))
        assertFalse(cleaned.contains("<form", ignoreCase = true))
        assertFalse(cleaned.contains("<iframe", ignoreCase = true))
        assertFalse(cleaned.contains("position", ignoreCase = true))
        assertFalse(cleaned.contains("onclick", ignoreCase = true))
    }
}
