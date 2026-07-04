package dev.gold.mdvault.document

/**
 * 뷰어가 지원하는 문서 종류. 판별은 확장자 우선, MIME 보조
 * (파일 관리자들이 .md를 text/plain이나 octet-stream으로 보내는 일이 흔함).
 */
enum class DocumentKind {
    MARKDOWN,
    PLAIN_TEXT,
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
            "docx" -> return DocumentKind.DOCX
            "html", "htm" -> return DocumentKind.HTML
            "pdf" -> return DocumentKind.PDF
            "png", "jpg", "jpeg", "webp", "gif", "bmp" -> return DocumentKind.IMAGE
            "txt", "log" -> return DocumentKind.PLAIN_TEXT
        }
        return when (mimeType?.substringBefore(';')?.trim()?.lowercase()) {
            "text/markdown" -> DocumentKind.MARKDOWN
            "application/vnd.openxmlformats-officedocument.wordprocessingml.document" -> DocumentKind.DOCX
            "text/html" -> DocumentKind.HTML
            "application/pdf" -> DocumentKind.PDF
            "text/plain" -> DocumentKind.PLAIN_TEXT
            else -> when {
                mimeType?.startsWith("image/") == true -> DocumentKind.IMAGE
                mimeType?.startsWith("text/") == true -> DocumentKind.PLAIN_TEXT
                else -> DocumentKind.UNSUPPORTED
            }
        }
    }
}
