package dev.gold.mdvault.docx

import dev.gold.mdvault.document.ConversionWarning
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.zip.ZipInputStream
import javax.xml.parsers.DocumentBuilderFactory

class SimpleOoxmlDocxExportEngineTest {

    private val engine = SimpleOoxmlDocxExportEngine()
    private val fixtureDir = File("../fixtures/md")
    private val fixtureAssets = AssetResolver { relativePath ->
        File(fixtureDir, relativePath).takeIf { it.isFile }?.inputStream()
    }

    private fun export(
        markdown: String,
        assets: AssetResolver = fixtureAssets,
    ): Pair<Map<String, ByteArray>, List<ConversionWarning>> {
        val output = ByteArrayOutputStream()
        val warnings = engine.export(markdown, "test-document", assets, output)
        val entries = mutableMapOf<String, ByteArray>()
        ZipInputStream(ByteArrayInputStream(output.toByteArray())).use { zip ->
            var entry = zip.nextEntry
            while (entry != null) {
                entries[entry.name] = zip.readBytes()
                entry = zip.nextEntry
            }
        }
        return entries to warnings
    }

    private fun assertWellFormed(entries: Map<String, ByteArray>) {
        val factory = DocumentBuilderFactory.newInstance().apply { isNamespaceAware = true }
        for ((name, bytes) in entries) {
            if (!name.endsWith(".xml") && !name.endsWith(".rels")) continue
            try {
                factory.newDocumentBuilder().parse(ByteArrayInputStream(bytes))
            } catch (e: Exception) {
                throw AssertionError("Malformed XML in $name: ${e.message}\n${bytes.decodeToString()}", e)
            }
        }
    }

    @Test
    fun `every markdown fixture exports to well-formed ooxml`() {
        val fixtures = fixtureDir.listFiles { file ->
            file.isFile && file.extension == "md" && !file.name.endsWith(".EXPECTED.md")
        }.orEmpty().sortedBy { it.name }
        assertEquals("Expected the 10 S0 fixtures", 10, fixtures.size)

        for (fixture in fixtures) {
            val (entries, _) = export(fixture.readText())
            assertWellFormed(entries)
            for (required in listOf(
                "[Content_Types].xml", "_rels/.rels", "word/document.xml",
                "word/styles.xml", "word/numbering.xml", "word/_rels/document.xml.rels",
                "docProps/core.xml",
            )) {
                assertTrue("$required missing for ${fixture.name}", entries.containsKey(required))
            }
        }
    }

    @Test
    fun `illegal control characters are stripped and output stays well-formed`() {
        val markdown = "# 제목제목\n\n본문텍스트 **강조** 끝."
        val (entries, _) = export(markdown)
        assertWellFormed(entries)
        val document = entries.getValue("word/document.xml").decodeToString()
        assertFalse(document.any { it.code in 0x00..0x08 || it.code == 0x0B || it.code == 0x0C })
        assertTrue(document.contains("제목제목"))
    }

    @Test
    fun `bold run segmentation preserves surrounding spaces via xml space preserve`() {
        val (entries, _) = export("word **bold** word")
        val document = entries.getValue("word/document.xml").decodeToString()
        assertTrue(document.contains("<w:t xml:space=\"preserve\">word </w:t>"))
        assertTrue(document.contains("<w:b/>"))
        assertTrue(document.contains("<w:t xml:space=\"preserve\"> word</w:t>"))
        // 모든 w:t는 xml:space="preserve"
        assertEquals(
            Regex("<w:t[ >]").findAll(document).count(),
            Regex("""<w:t xml:space="preserve">""").findAll(document).count(),
        )
    }

    @Test
    fun `relative image is embedded with px to emu dimensions`() {
        val markdown = File(fixtureDir, "image-relative.md").readText()
        val (entries, warnings) = export(markdown)
        assertWellFormed(entries)

        val mediaEntries = entries.keys.filter { it.startsWith("word/media/") }
        assertEquals("이미지 1개가 media로 embed되어야 함: $warnings", 1, mediaEntries.size)

        val document = entries.getValue("word/document.xml").decodeToString()
        val extent = Regex("""<wp:extent cx="(\d+)" cy="(\d+)"/>""").find(document)
        assertNotNull("wp:extent missing", extent)
        val (cx, cy) = extent!!.destructured
        assertTrue(cx.toLong() > 0 && cy.toLong() > 0)
        assertTrue(cx.toLong() <= SimpleOoxmlDocxExportEngine.MAX_IMAGE_WIDTH_EMU)

        val rels = entries.getValue("word/_rels/document.xml.rels").decodeToString()
        assertTrue(rels.contains("media/image1"))
    }

    @Test
    fun `image wider than body is scaled down preserving aspect ratio`() {
        // 2000x500 fake PNG — 헤더만 유효하면 dimension reader가 읽는다
        val fakePng = ByteArray(64).also { bytes ->
            byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A).copyInto(bytes)
            bytes[12] = 'I'.code.toByte(); bytes[13] = 'H'.code.toByte()
            bytes[14] = 'D'.code.toByte(); bytes[15] = 'R'.code.toByte()
            bytes[16] = 0; bytes[17] = 0; bytes[18] = 0x07; bytes[19] = 0xD0.toByte() // 2000
            bytes[20] = 0; bytes[21] = 0; bytes[22] = 0x01; bytes[23] = 0xF4.toByte() // 500
        }
        val (entries, _) = export(
            "![wide](media/wide.png)",
            assets = { path -> if (path == "media/wide.png") ByteArrayInputStream(fakePng) else null },
        )
        val document = entries.getValue("word/document.xml").decodeToString()
        val extent = Regex("""<wp:extent cx="(\d+)" cy="(\d+)"/>""").find(document)!!
        val cx = extent.groupValues[1].toLong()
        val cy = extent.groupValues[2].toLong()
        assertEquals(SimpleOoxmlDocxExportEngine.MAX_IMAGE_WIDTH_EMU, cx)
        assertEquals(cx / 4, cy) // 2000:500 = 4:1 비율 유지
    }

    @Test
    fun `unsupported features degrade with warnings not exceptions`() {
        val markdown = File(fixtureDir, "code-fences.md").readText()
        val (entries, warnings) = export(markdown)
        assertWellFormed(entries)
        assertTrue(warnings.any { it is ConversionWarning.UnsupportedFeature })
    }

    @Test
    fun `exported docx reimports through mammoth preserving structure`() {
        val markdown = """
            # 제목 하나

            ## 제목 둘

            문단 **굵게** 그리고 *기울임* 텍스트.

            - 항목 하나
            - 항목 둘
                - 중첩 항목

            1. 첫째
            2. 둘째

            | 이름 | 값 |
            |------|----|
            | 가   | 1  |
            | 나   | 2  |

            [링크](https://example.com)
        """.trimIndent()

        val output = ByteArrayOutputStream()
        engine.export(markdown, "roundtrip", fixtureAssets, output)

        val imported = MammothDocxImportEngine().importDocx(
            ByteArrayInputStream(output.toByteArray()),
        ) { _, _, _ -> }
        val html = imported.html

        assertTrue("h1 lost: $html", html.contains("<h1>제목 하나</h1>"))
        assertTrue("h2 lost: $html", html.contains("<h2>제목 둘</h2>"))
        assertTrue("bold lost: $html", html.contains("<strong>굵게</strong>"))
        assertTrue("italic lost: $html", html.contains("<em>기울임</em>"))
        assertTrue("bullet list lost: $html", html.contains("<ul>"))
        assertTrue("ordered list lost: $html", html.contains("<ol>"))
        assertTrue("table lost: $html", html.contains("<table>"))
        assertTrue("hyperlink lost: $html", html.contains("href=\"https://example.com\""))
    }
}
