package dev.gold.mdvault

import dev.gold.mdvault.document.DocxToMarkdownImporter
import dev.gold.mdvault.docx.DocxExportEngine
import dev.gold.mdvault.docx.DocxImportEngine
import dev.gold.mdvault.docx.MammothDocxImportEngine
import dev.gold.mdvault.docx.SimpleOoxmlDocxExportEngine
import dev.gold.mdvault.markdown.FlexmarkMarkdownEngine
import dev.gold.mdvault.markdown.JsoupHtmlCleaner
import dev.gold.mdvault.markdown.MarkdownEngine

/**
 * 수동 DI 컨테이너. Hilt 금지 (CLAUDE.md 아키텍처 규칙).
 */
class AppContainer {
    val htmlCleaner: JsoupHtmlCleaner = JsoupHtmlCleaner()
    val markdownEngine: MarkdownEngine = FlexmarkMarkdownEngine(htmlCleaner)
    val docxImportEngine: DocxImportEngine = MammothDocxImportEngine()
    val docxExportEngine: DocxExportEngine = SimpleOoxmlDocxExportEngine()
    val docxToMarkdownImporter: DocxToMarkdownImporter = DocxToMarkdownImporter(
        docxImportEngine = docxImportEngine,
        htmlCleaner = htmlCleaner,
        markdownEngine = markdownEngine,
    )
}
