package dev.gold.mdvault

import dev.gold.mdvault.docx.DocxImportEngine
import dev.gold.mdvault.docx.MammothDocxImportEngine
import dev.gold.mdvault.markdown.FlexmarkMarkdownEngine
import dev.gold.mdvault.markdown.MarkdownEngine

/**
 * 수동 DI 컨테이너. Hilt 금지 (CLAUDE.md 아키텍처 규칙).
 */
class AppContainer {
    val markdownEngine: MarkdownEngine = FlexmarkMarkdownEngine()
    val docxImportEngine: DocxImportEngine = MammothDocxImportEngine()
}
