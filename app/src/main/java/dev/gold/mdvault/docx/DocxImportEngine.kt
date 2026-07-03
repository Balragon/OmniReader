package dev.gold.mdvault.docx

import dev.gold.mdvault.document.ConversionWarning
import java.io.InputStream

/**
 * 이미지를 수신 즉시 파일로 저장하는 콜백. 엔진은 이미지 바이트를 메모리에
 * 누적하지 않고 곧바로 sink로 넘긴다. relativePath는 엔진이 결정하며
 * (sha256 앞 12자리 + 순번 + 확장자) HTML의 <img src>에 그대로 삽입된다.
 */
fun interface ImageSink {
    fun store(relativePath: String, contentType: String, bytes: ByteArray)
}

data class ExtractedAsset(
    val relativePath: String,
    val contentType: String,
    val sizeBytes: Long,
)

data class HtmlImportResult(
    val html: String,
    val warnings: List<ConversionWarning>,
    val extractedAssets: List<ExtractedAsset>,
)

interface DocxImportEngine {
    fun importDocx(input: InputStream, imageSink: ImageSink): HtmlImportResult
}
