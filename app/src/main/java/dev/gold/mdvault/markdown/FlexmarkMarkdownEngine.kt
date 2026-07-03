package dev.gold.mdvault.markdown

import com.vladsch.flexmark.ext.gfm.tasklist.TaskListExtension
import com.vladsch.flexmark.ext.tables.TablesExtension
import com.vladsch.flexmark.ext.yaml.front.matter.AbstractYamlFrontMatterVisitor
import com.vladsch.flexmark.ext.yaml.front.matter.YamlFrontMatterExtension
import com.vladsch.flexmark.html.HtmlRenderer
import com.vladsch.flexmark.html2md.converter.FlexmarkHtmlConverter
import com.vladsch.flexmark.parser.Parser
import com.vladsch.flexmark.util.ast.Node
import com.vladsch.flexmark.util.data.MutableDataSet

class FlexmarkMarkdownEngine(
    private val htmlCleaner: JsoupHtmlCleaner = JsoupHtmlCleaner(),
) : MarkdownEngine {
    private val options = MutableDataSet().set(
        Parser.EXTENSIONS,
        listOf(
            TablesExtension.create(),
            TaskListExtension.create(),
            YamlFrontMatterExtension.create(),
        ),
    )

    private val parser = Parser.builder(options).build()
    private val renderer = HtmlRenderer.builder(options).build()
    private val htmlConverter = FlexmarkHtmlConverter.builder(options).build()

    override fun toHtml(markdown: String): String = renderer.render(parseToAst(markdown))

    override fun fromHtml(html: String): String {
        val cleaned = htmlCleaner.clean(html).html
        return htmlConverter.convert(cleaned).trimEnd() + "\n"
    }

    override fun parseToAst(markdown: String): Node = parser.parse(markdown)

    override fun extractFrontMatter(markdown: String): Map<String, List<String>> {
        val visitor = AbstractYamlFrontMatterVisitor()
        visitor.visit(parseToAst(markdown))
        return visitor.data.mapValues { (_, values) -> values.map { it.unquoteYamlScalar() } }
    }

    private fun String.unquoteYamlScalar(): String {
        if (length < 2) return this
        val first = first()
        val last = last()
        return if ((first == '"' && last == '"') || (first == '\'' && last == '\'')) {
            substring(1, lastIndex)
        } else {
            this
        }
    }
}
