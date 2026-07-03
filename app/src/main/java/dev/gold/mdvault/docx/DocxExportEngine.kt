package dev.gold.mdvault.docx

import dev.gold.mdvault.document.ConversionWarning
import java.io.InputStream
import java.io.OutputStream

/**
 * Markdown 안 상대경로 이미지 참조(media/...)를 바이트로 해석하는 콜백.
 * 찾지 못하면 null — 엔진은 warning을 남기고 alt 텍스트로 대체한다.
 */
fun interface AssetResolver {
    fun open(relativePath: String): InputStream?
}

interface DocxExportEngine {
    /**
     * Markdown을 제한된 DOCX로 export한다. 지원: heading 1-3, 문단,
     * bold/italic, bullet/numbered list(중첩 2단), pipe table, 이미지,
     * 하이퍼링크. 그 외는 ConversionWarning.UnsupportedFeature로 회수하고
     * 가능한 한 plain text로 강등한다.
     */
    fun export(
        markdown: String,
        title: String,
        assets: AssetResolver,
        output: OutputStream,
    ): List<ConversionWarning>
}
