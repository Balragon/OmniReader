package dev.gold.mdvault

import dev.gold.mdvault.document.DocxToMarkdownImporter
import dev.gold.mdvault.docx.DocxImportEngine
import dev.gold.mdvault.docx.MammothDocxImportEngine
import dev.gold.mdvault.markdown.FlexmarkMarkdownEngine
import dev.gold.mdvault.markdown.JsoupHtmlCleaner
import dev.gold.mdvault.markdown.MarkdownEngine
import dev.gold.mdvault.settings.ReaderSettingsRepository
import dev.gold.mdvault.storage.RecentFilesRepository

/**
 * 수동 DI 컨테이너. Hilt 금지 (CLAUDE.md 아키텍처 규칙).
 */
class AppContainer(context: android.content.Context) {
    private val applicationContext = context.applicationContext

    val recentFilesRepository: RecentFilesRepository = RecentFilesRepository(applicationContext)
    val readerSettingsRepository: ReaderSettingsRepository = ReaderSettingsRepository(applicationContext)
    val htmlCleaner: JsoupHtmlCleaner = JsoupHtmlCleaner()
    val markdownEngine: MarkdownEngine = FlexmarkMarkdownEngine(htmlCleaner)
    val docxImportEngine: DocxImportEngine = MammothDocxImportEngine()
    val docxToMarkdownImporter: DocxToMarkdownImporter = DocxToMarkdownImporter(
        docxImportEngine = docxImportEngine,
        htmlCleaner = htmlCleaner,
        markdownEngine = markdownEngine,
    )
}
