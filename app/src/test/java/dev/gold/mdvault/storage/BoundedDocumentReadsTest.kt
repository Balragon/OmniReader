package dev.gold.mdvault.storage

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.nio.charset.Charset

class BoundedDocumentReadsTest {

    @Test
    fun `keeps valid utf8 when a legacy fallback is available`() {
        val source = "이름,값\n홍길동,1"
        val read = ByteArrayInputStream(source.toByteArray()).readTextBounded(
            maxBytes = 1_024,
            knownSize = null,
            fallbackCharset = Charset.forName("MS949"),
        )

        assertEquals(source, read.text)
        assertFalse(read.truncated)
    }

    @Test
    fun `decodes Korean Excel csv with ms949 fallback`() {
        val charset = Charset.forName("MS949")
        val source = "이름,값\n홍길동,1"
        val read = ByteArrayInputStream(source.toByteArray(charset)).readTextBounded(
            maxBytes = 1_024,
            knownSize = null,
            fallbackCharset = charset,
        )

        assertEquals(source, read.text)
        assertFalse(read.truncated)
    }

    @Test
    fun `truncated utf8 tail does not switch the whole preview to ms949`() {
        val source = "가나다라마바사"
        val bytes = source.toByteArray()
        val read = ByteArrayInputStream(bytes).readTextBounded(
            maxBytes = bytes.size - 1,
            knownSize = bytes.size.toLong(),
            fallbackCharset = Charset.forName("MS949"),
        )

        assertTrue(read.truncated)
        assertEquals("가나다라마바", read.text)
        assertFalse(read.text.contains('\uFFFD'))
    }

    @Test
    fun `detects and strips UTF8 and UTF16 byte order marks`() {
        val utf8 = byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte()) + "본문".toByteArray()
        val utf16 = byteArrayOf(0xFF.toByte(), 0xFE.toByte()) + "본문".toByteArray(Charsets.UTF_16LE)

        assertEquals("본문", ByteArrayInputStream(utf8).readTextBounded(100, null).text)
        assertEquals("본문", ByteArrayInputStream(utf16).readTextBounded(100, null).text)
    }

    @Test
    fun `uses a bounded HTML charset declaration`() {
        val charset = Charset.forName("windows-1252")
        val source = "<meta charset=windows-1252><p>caf\u00e9</p>"

        val read = ByteArrayInputStream(source.toByteArray(charset)).readTextBounded(1_024, null)

        assertEquals(source, read.text)
    }

    @Test
    fun `drops every incomplete UTF8 tail without a replacement character`() {
        val source = "A\uD83D\uDE00".toByteArray()
        for (removedBytes in 1..3) {
            val read = ByteArrayInputStream(source).readTextBounded(
                maxBytes = source.size - removedBytes,
                knownSize = source.size.toLong(),
            )
            assertEquals("A", read.text)
            assertFalse(read.text.contains('\uFFFD'))
        }
    }
}
