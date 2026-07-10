package dev.gold.mdvault.preview

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class StructuredTextHtmlBuilderTest {

    @Test
    fun `formats nested json without changing token text`() {
        val preview = StructuredTextHtmlBuilder.json(
            """{"big":12345678901234567890,"items":[1,true,null],"empty":{}}""",
        )

        assertTrue(preview.formatted)
        assertTrue(preview.bodyHtml.contains("\n  \"big\": 12345678901234567890,"))
        assertTrue(preview.bodyHtml.contains("\n  \"items\": [\n    1,"))
        assertTrue(preview.bodyHtml.contains("\n  \"empty\": {}\n"))
    }

    @Test
    fun `json strips bom and escapes html content`() {
        val preview = StructuredTextHtmlBuilder.json("\uFEFF{\"value\":\"<script>&\"}")

        assertTrue(preview.formatted)
        assertFalse(preview.bodyHtml.contains("<script>"))
        assertTrue(preview.bodyHtml.contains("&lt;script&gt;&amp;"))
        assertFalse(preview.bodyHtml.contains("\uFEFF"))
    }

    @Test
    fun `invalid or truncated json falls back to escaped raw text`() {
        val invalid = StructuredTextHtmlBuilder.json("{\"value\":01}")
        val unicodeDigit = StructuredTextHtmlBuilder.json("{\"value\":1٢}")
        val truncated = StructuredTextHtmlBuilder.json("{\"value\":1}", sourceTruncated = true)

        assertFalse(invalid.formatted)
        assertTrue(invalid.bodyHtml.contains("{\"value\":01}"))
        assertFalse(unicodeDigit.formatted)
        assertFalse(truncated.formatted)
        assertTrue(truncated.truncated)
        assertTrue(truncated.bodyHtml.contains("{\"value\":1}"))
    }

    @Test
    fun `csv renders quoted commas multiline cells escaped quotes and empty cells`() {
        val preview = StructuredTextHtmlBuilder.csv(
            "\uFEFFname,note,empty\r\n" +
                "Alice,\"a,b\",\r\n" +
                "Bob,\"line 1\r\nline 2\",\"say \"\"hi\"\"\"",
        )

        assertTrue(preview.formatted)
        assertFalse(preview.truncated)
        assertFalse(preview.bodyHtml.contains("\uFEFF"))
        assertTrue(preview.bodyHtml.contains("<td>a,b</td>"))
        assertTrue(preview.bodyHtml.contains("<td></td>"))
        assertTrue(preview.bodyHtml.contains("<td>line 1\nline 2</td>"))
        assertTrue(preview.bodyHtml.contains("<td>say \"hi\"</td>"))
        assertTrue(preview.bodyHtml.contains(">3</th>"))
    }

    @Test
    fun `csv escapes html and malformed quoting falls back to raw text`() {
        val safe = StructuredTextHtmlBuilder.csv("value\n<script>&")
        val malformed = StructuredTextHtmlBuilder.csv("name,note\nAlice,\"unfinished")
        val quoteInPlainField = StructuredTextHtmlBuilder.csv("name,note\nAlice,a\"b")
        val contentAfterQuote = StructuredTextHtmlBuilder.csv("name,note\nAlice,\"ok\"  ")

        assertTrue(safe.formatted)
        assertFalse(safe.bodyHtml.contains("<script>"))
        assertTrue(safe.bodyHtml.contains("&lt;script&gt;&amp;"))
        assertFalse(malformed.formatted)
        assertTrue(malformed.bodyHtml.contains("Alice,\"unfinished"))
        assertFalse(quoteInPlainField.formatted)
        assertFalse(contentAfterQuote.formatted)
    }

    @Test
    fun `csv drops incomplete trailing row when source was truncated`() {
        val preview = StructuredTextHtmlBuilder.csv(
            "name,value\ncomplete,1\nincomplete,2",
            sourceTruncated = true,
        )

        assertTrue(preview.formatted)
        assertTrue(preview.truncated)
        assertTrue(preview.bodyHtml.contains("complete"))
        assertFalse(preview.bodyHtml.contains("incomplete"))
    }

    @Test
    fun `csv limits rows and columns to protect the webview`() {
        val tooManyRows = StructuredTextHtmlBuilder.csv((1..2_001).joinToString("\n"))
        val tooManyColumns = StructuredTextHtmlBuilder.csv((1..201).joinToString(","))

        assertTrue(tooManyRows.formatted)
        assertTrue(tooManyRows.truncated)
        assertTrue(tooManyRows.bodyHtml.contains(">2000</th>"))
        assertFalse(tooManyRows.bodyHtml.contains(">2001</th>"))
        assertTrue(tooManyColumns.formatted)
        assertTrue(tooManyColumns.truncated)
        assertFalse(tooManyColumns.bodyHtml.contains("<td>201</td>"))
    }

    @Test
    fun `structured html output stays bounded after escaping`() {
        val preview = StructuredTextHtmlBuilder.json("\"" + "&".repeat(1_700_000) + "\"")

        assertTrue(preview.formatted)
        assertTrue(preview.truncated)
        assertTrue(preview.bodyHtml.length <= 8 * 1024 * 1024)
    }
}
