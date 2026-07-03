package dev.gold.mdvault.markdown

import com.vladsch.flexmark.ast.BulletList
import com.vladsch.flexmark.ast.FencedCodeBlock
import com.vladsch.flexmark.ast.Heading
import com.vladsch.flexmark.ast.Image
import com.vladsch.flexmark.ast.Link
import com.vladsch.flexmark.ast.ListItem
import com.vladsch.flexmark.ast.OrderedList
import com.vladsch.flexmark.ext.gfm.tasklist.TaskListItem
import com.vladsch.flexmark.ext.tables.TableBlock
import com.vladsch.flexmark.ext.tables.TableCell
import com.vladsch.flexmark.ext.tables.TableRow
import com.vladsch.flexmark.util.ast.Node
import dev.gold.mdvault.document.ConversionWarning
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class FlexmarkMarkdownEngineTest {
    private val engine = FlexmarkMarkdownEngine()

    @Test
    fun `markdown fixtures round trip through html preserving AST structure`() {
        val fixtureDir = File("../fixtures/md")
        val fixtures = fixtureDir.listFiles { file ->
            file.isFile && file.extension == "md" && !file.name.endsWith(".EXPECTED.md")
        }.orEmpty().sortedBy { it.name }

        assertTrue("Expected Markdown fixtures in ${fixtureDir.path}", fixtures.isNotEmpty())

        for (fixture in fixtures) {
            val original = fixture.readText()
            val roundTripped = engine.fromHtml(engine.toHtml(original))

            assertEquals(
                "Structural Markdown round trip failed for ${fixture.name}",
                extractStructure(original),
                extractStructure(roundTripped),
            )
        }
    }

    @Test
    fun `extractFrontMatter returns YAML metadata without body content`() {
        val markdown = File("../fixtures/md/yaml-front-matter.md").readText()

        val frontMatter = engine.extractFrontMatter(markdown)

        assertEquals(listOf("회의 기록"), frontMatter["title"])
        assertEquals(listOf("mdvault", "변환"), frontMatter["tags"])
        assertEquals(listOf("2026-07-03"), frontMatter["created"])
        assertFalse(frontMatter.containsKey("본문"))
    }

    @Test
    fun `cleaner drops javascript href and records warning`() {
        val result = JsoupHtmlCleaner().clean(
            """<p><a href="javascript:alert(1)" onclick="alert(2)">link</a></p>""",
        )

        assertFalse(result.html.contains("javascript:", ignoreCase = true))
        assertFalse(result.html.contains("onclick", ignoreCase = true))
        assertTrue(
            result.warnings.contains(
                ConversionWarning.UnsafeLinkDropped("javascript:alert(1)"),
            ),
        )
    }

    private fun extractStructure(markdown: String): MarkdownStructure {
        val ast = engine.parseToAst(markdown)
        return MarkdownStructure(
            headings = ast.descendantsOfType<Heading>().map {
                HeadingShape(level = it.level, text = it.text.toString())
            },
            listItems = ast.descendantsOfType<ListItem>().map {
                ListItemShape(
                    type = when (it.parent) {
                        is OrderedList -> "ordered"
                        is BulletList -> "bullet"
                        else -> "unknown"
                    },
                    depth = it.countAncestorsOfType(ListItem::class.java),
                    checked = (it as? TaskListItem)?.isItemDoneMarker,
                )
            },
            tables = ast.descendantsOfType<TableBlock>().map { table ->
                val rows = table.descendantsOfType<TableRow>()
                    .map { row -> row.childrenOfType<TableCell>() }
                    .filterNot { cells -> cells.all { it.text.toString().trim().isTableSeparatorCell() } }
                TableShape(
                    rows = rows.map { cells ->
                        cells.map { cell -> cell.text.toString().trim() }
                    },
                    alignments = rows.map { cells ->
                        cells.map { cell -> cell.alignment?.name ?: "NONE" }
                    },
                )
            },
            links = ast.descendantsOfType<Link>().map {
                LinkShape(
                    text = it.text.toString(),
                    url = it.url.toString(),
                    title = it.title.toString(),
                    image = false,
                )
            },
            images = ast.descendantsOfType<Image>().map {
                LinkShape(
                    text = it.text.toString(),
                    url = it.url.toString(),
                    title = it.title.toString(),
                    image = true,
                )
            },
            codeFences = ast.descendantsOfType<FencedCodeBlock>().map {
                CodeFenceShape(
                    info = it.info.toString(),
                    content = it.contentChars.toString().trimEnd(),
                )
            },
        )
    }

    private inline fun <reified T : Node> Node.descendantsOfType(): List<T> =
        descendants.filterIsInstance<T>().toList()

    private inline fun <reified T : Node> Node.childrenOfType(): List<T> =
        children.filterIsInstance<T>().toList()

    private fun String.isTableSeparatorCell(): Boolean =
        matches(Regex(""":?-{3,}:?"""))

    private data class MarkdownStructure(
        val headings: List<HeadingShape>,
        val listItems: List<ListItemShape>,
        val tables: List<TableShape>,
        val links: List<LinkShape>,
        val images: List<LinkShape>,
        val codeFences: List<CodeFenceShape>,
    )

    private data class HeadingShape(
        val level: Int,
        val text: String,
    )

    private data class ListItemShape(
        val type: String,
        val depth: Int,
        val checked: Boolean?,
    )

    private data class TableShape(
        val rows: List<List<String>>,
        val alignments: List<List<String>>,
    )

    private data class LinkShape(
        val text: String,
        val url: String,
        val title: String,
        val image: Boolean,
    )

    private data class CodeFenceShape(
        val info: String,
        val content: String,
    )
}
