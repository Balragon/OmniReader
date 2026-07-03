package dev.gold.mdvault.document

import dev.gold.mdvault.docx.DocxImportEngine
import dev.gold.mdvault.docx.ExtractedAsset
import dev.gold.mdvault.docx.ImageSink
import dev.gold.mdvault.markdown.JsoupHtmlCleaner
import dev.gold.mdvault.markdown.MarkdownEngine
import java.io.InputStream

/**
 * P0-3 import 파이프라인: DOCX → (Mammoth) HTML → (jsoup) 정화 → (flexmark) Markdown.
 * 원본 DOCX는 읽기만 한다 — 절대 덮어쓰지 않는다 (CLAUDE.md 프로젝트 정의).
 */
class DocxToMarkdownImporter(
    private val docxImportEngine: DocxImportEngine,
    private val htmlCleaner: JsoupHtmlCleaner,
    private val markdownEngine: MarkdownEngine,
) {
    data class ImportedDocument(
        val markdown: String,
        val warnings: List<ConversionWarning>,
        val assets: List<ExtractedAsset>,
    )

    fun import(input: InputStream, imageSink: ImageSink): ImportedDocument {
        val htmlResult = docxImportEngine.importDocx(input, imageSink)
        val cleaned = htmlCleaner.clean(htmlResult.html)
        val markdown = markdownEngine.fromHtml(cleaned.html)
        return ImportedDocument(
            markdown = markdown,
            warnings = htmlResult.warnings + cleaned.warnings,
            assets = htmlResult.extractedAssets,
        )
    }
}
