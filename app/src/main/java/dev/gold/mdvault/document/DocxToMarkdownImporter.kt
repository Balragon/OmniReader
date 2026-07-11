package dev.gold.mdvault.document

import dev.gold.mdvault.docx.DocxImportEngine
import dev.gold.mdvault.docx.DocxImportPolicy
import dev.gold.mdvault.docx.DocxImportRejectedException
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
    private val policy: DocxImportPolicy = DocxImportPolicy(),
) {
    data class ImportedDocument(
        val markdown: String,
        val warnings: List<ConversionWarning>,
        val assets: List<ExtractedAsset>,
    )

    fun import(input: InputStream, imageSink: ImageSink): ImportedDocument {
        val htmlResult = docxImportEngine.importDocx(input, imageSink)
        requireWithinConversionLimit(htmlResult.html)
        val cleaned = htmlCleaner.clean(htmlResult.html)
        requireWithinConversionLimit(cleaned.html)
        val markdown = markdownEngine.fromHtml(cleaned.html)
        requireWithinConversionLimit(markdown)
        return ImportedDocument(
            markdown = markdown,
            warnings = htmlResult.warnings + cleaned.warnings,
            assets = htmlResult.extractedAssets,
        )
    }

    private fun requireWithinConversionLimit(value: String) {
        if (value.length > policy.maxConversionCharacters) {
            throw DocxImportRejectedException(
                DocxImportRejectedException.Reason.CONVERSION_OUTPUT_LIMIT,
            )
        }
    }
}
