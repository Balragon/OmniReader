package dev.gold.mdvault.docx

import dev.gold.mdvault.document.ConversionWarning
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.assertThrows
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class MammothDocxImportEngineTest {
    private val engine = MammothDocxImportEngine()
    private val fixtureDir = File("../fixtures/docx")
    private val dataUriPattern = Regex("""src\s*=\s*["']data:""", RegexOption.IGNORE_CASE)
    private val assetNamePattern = Regex("""media/[0-9a-f]{12}-\d{3}\.[a-z]+""")

    @get:Rule
    val tempDir = TemporaryFolder()

    private fun importFixture(name: String): HtmlImportResult {
        val assetRoot = tempDir.newFolder(name.removeSuffix(".docx"))
        return File(fixtureDir, name).inputStream().use { input ->
            engine.importDocx(input) { relativePath, _, bytes ->
                val target = File(assetRoot, relativePath)
                target.parentFile?.mkdirs()
                target.writeBytes(bytes)
            }
        }
    }

    @Test
    fun `all docx fixtures convert without exception and embed no data uris`() {
        val fixtures = fixtureDir.listFiles { file -> file.extension == "docx" }
            .orEmpty()
            .filterNot { it.name == "links.docx" }
            .sortedBy { it.name }
        assertEquals("Expected 9 safe S0 fixtures in ${fixtureDir.path}", 9, fixtures.size)

        for (fixture in fixtures) {
            val result = importFixture(fixture.name)
            assertFalse(
                "data: URI embedded in HTML for ${fixture.name}",
                dataUriPattern.containsMatchIn(result.html),
            )
            assertTrue("Empty HTML for ${fixture.name}", result.html.isNotBlank())
        }
    }

    @Test
    fun `docx with an external image relationship is rejected before Mammoth opens it`() {
        val error = assertThrows(DocxImportRejectedException::class.java) {
            importFixture("links.docx")
        }
        assertEquals(DocxImportRejectedException.Reason.EXTERNAL_RELATIONSHIP, error.reason)
    }

    @Test
    fun `embedded image count is bounded`() {
        val limitedEngine = MammothDocxImportEngine(DocxImportPolicy(maxAssetCount = 1))
        val error = assertThrows(DocxImportRejectedException::class.java) {
            File(fixtureDir, "images.docx").inputStream().use { input ->
                limitedEngine.importDocx(input) { _, _, _ -> }
            }
        }
        assertEquals(DocxImportRejectedException.Reason.ASSET_COUNT_LIMIT, error.reason)
    }

    @Test
    fun `images fixture extracts five assets as files and keeps html small`() {
        val result = importFixture("images.docx")

        assertEquals(5, result.extractedAssets.size)
        assertTrue(
            "HTML must stay under 1MB when images are externalized, was ${result.html.length}",
            result.html.toByteArray(Charsets.UTF_8).size < 1_000_000,
        )
        for (asset in result.extractedAssets) {
            assertTrue(
                "Asset name must follow sha256(12)-seq.ext: ${asset.relativePath}",
                assetNamePattern.matches(asset.relativePath),
            )
            val stored = File(tempDir.root, "images/${asset.relativePath}")
            assertTrue("Asset file not written: ${asset.relativePath}", stored.isFile)
            assertEquals(asset.sizeBytes, stored.length())
        }
        assertEquals(
            "Each asset must appear as an <img src> in the HTML",
            result.extractedAssets.map { it.relativePath }.toSet(),
            assetNamePattern.findAll(result.html).map { it.value }.toSet(),
        )
    }

    @Test
    fun `control chars fixture completes without exception`() {
        val result = importFixture("control-chars.docx")
        assertTrue(result.html.isNotBlank())
        assertTrue(
            "Sanitizer must report stripped illegal XML characters",
            result.warnings.any { it is ConversionWarning.IllegalXmlCharactersStripped },
        )
    }

    @Test
    fun `merged table fixture completes and degrades to warnings not exceptions`() {
        val result = importFixture("table-merged.docx")
        assertTrue("Merged table must still produce a <table>", result.html.contains("<table"))
        // Mammoth이 병합 셀을 warning 없이 소화하면 그것도 허용 — 예외만 금지.
    }
}
