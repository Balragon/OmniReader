package dev.gold.mdvault.markdown

import com.vladsch.flexmark.util.ast.Node

interface MarkdownEngine {
    fun toHtml(markdown: String): String
    fun fromHtml(html: String): String
    fun fromSanitizedHtml(html: String): String = fromHtml(html)
    fun parseToAst(markdown: String): Node
    fun extractFrontMatter(markdown: String): Map<String, List<String>>
}
