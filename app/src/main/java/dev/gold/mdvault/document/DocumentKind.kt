package dev.gold.mdvault.document

/**
 * 뷰어가 지원하는 문서 종류. 판별은 확장자 우선, MIME 보조
 * (파일 관리자들이 .md를 text/plain이나 octet-stream으로 보내는 일이 흔함).
 */
enum class DocumentKind {
    MARKDOWN,
    PLAIN_TEXT,
    JSON,
    CSV,
    DOCX,
    HTML,
    PDF,
    IMAGE,
    UNSUPPORTED,
}

object DocumentTypeDetector {

    fun detect(displayName: String?, mimeType: String?): DocumentKind {
        val extension = displayName?.substringAfterLast('.', "")?.lowercase().orEmpty()
        when (extension) {
            "md", "markdown" -> return DocumentKind.MARKDOWN
            "json" -> return DocumentKind.JSON
            "csv" -> return DocumentKind.CSV
            "docx" -> return DocumentKind.DOCX
            "html", "htm" -> return DocumentKind.HTML
            "pdf" -> return DocumentKind.PDF
            "png", "jpg", "jpeg", "webp", "gif", "bmp" -> return DocumentKind.IMAGE
            "txt", "log" -> return DocumentKind.PLAIN_TEXT
        }
        val normalizedMimeType = mimeType?.substringBefore(';')?.trim()?.lowercase().orEmpty()
        return when (normalizedMimeType) {
            "text/markdown" -> DocumentKind.MARKDOWN
            "application/json", "text/json" -> DocumentKind.JSON
            "text/csv", "text/comma-separated-values", "application/csv" -> DocumentKind.CSV
            "application/vnd.openxmlformats-officedocument.wordprocessingml.document" -> DocumentKind.DOCX
            "text/html" -> DocumentKind.HTML
            "application/pdf" -> DocumentKind.PDF
            "text/plain" -> DocumentKind.PLAIN_TEXT
            else -> when {
                normalizedMimeType.endsWith("+json") -> DocumentKind.JSON
                normalizedMimeType.startsWith("image/") -> DocumentKind.IMAGE
                normalizedMimeType.startsWith("text/") -> DocumentKind.PLAIN_TEXT
                else -> DocumentKind.UNSUPPORTED
            }
        }
    }
}
