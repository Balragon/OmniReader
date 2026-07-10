package dev.gold.mdvault.document

import org.junit.Assert.assertEquals
import org.junit.Test

class DocumentTypeDetectorTest {

    @Test
    fun `extension wins over generic mime`() {
        // 파일 관리자가 .md를 text/plain이나 octet-stream으로 보내는 케이스
        assertEquals(DocumentKind.MARKDOWN, DocumentTypeDetector.detect("노트.md", "text/plain"))
        assertEquals(DocumentKind.MARKDOWN, DocumentTypeDetector.detect("a.markdown", "application/octet-stream"))
        assertEquals(DocumentKind.JSON, DocumentTypeDetector.detect("data.json", "application/octet-stream"))
        assertEquals(DocumentKind.CSV, DocumentTypeDetector.detect("table.csv", "text/plain"))
        assertEquals(DocumentKind.DOCX, DocumentTypeDetector.detect("보고서.docx", "application/octet-stream"))
    }

    @Test
    fun `mime fallback when extension unknown`() {
        assertEquals(DocumentKind.PDF, DocumentTypeDetector.detect("붙임1", "application/pdf"))
        assertEquals(DocumentKind.IMAGE, DocumentTypeDetector.detect(null, "image/png"))
        assertEquals(DocumentKind.HTML, DocumentTypeDetector.detect(null, "text/html"))
        assertEquals(DocumentKind.JSON, DocumentTypeDetector.detect(null, "application/json; charset=utf-8"))
        assertEquals(DocumentKind.JSON, DocumentTypeDetector.detect(null, "application/ld+json"))
        assertEquals(DocumentKind.CSV, DocumentTypeDetector.detect(null, "text/csv"))
        assertEquals(DocumentKind.CSV, DocumentTypeDetector.detect(null, "text/comma-separated-values"))
        assertEquals(DocumentKind.PLAIN_TEXT, DocumentTypeDetector.detect("README", "text/x-readme"))
    }

    @Test
    fun `all target extensions detected`() {
        assertEquals(DocumentKind.PDF, DocumentTypeDetector.detect("문서.pdf", null))
        assertEquals(DocumentKind.HTML, DocumentTypeDetector.detect("페이지.html", null))
        assertEquals(DocumentKind.IMAGE, DocumentTypeDetector.detect("사진.jpg", null))
        assertEquals(DocumentKind.IMAGE, DocumentTypeDetector.detect("스크린샷.png", null))
        assertEquals(DocumentKind.PLAIN_TEXT, DocumentTypeDetector.detect("메모.txt", null))
        assertEquals(DocumentKind.UNSUPPORTED, DocumentTypeDetector.detect("sheet.xls", "application/vnd.ms-excel"))
        assertEquals(DocumentKind.UNSUPPORTED, DocumentTypeDetector.detect("압축.zip", "application/zip"))
    }
}
