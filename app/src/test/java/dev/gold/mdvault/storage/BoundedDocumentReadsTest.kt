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
        assertTrue(read.text.startsWith("가나다라마바"))
    }
}
