package dev.gold.mdvault.docx

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
import java.io.File

class ImageDimensionReaderTest {

    @Test
    fun `reads png dimensions from fixture`() {
        val bytes = File("../fixtures/md/images/relative-sample.png").readBytes()
        val dimensions = ImageDimensionReader.read(bytes)
        assertNotNull(dimensions)
        assertEquals(dimensions!!.widthPx * ImageDimensionReader.EMU_PER_PIXEL, dimensions.widthEmu)
    }

    @Test
    fun `reads jpeg dimensions from docx fixture media`() {
        // images.docx 안의 JPEG를 꺼내 SOF 파싱 검증
        val docx = File("../fixtures/docx/images.docx")
        var jpegBytes: ByteArray? = null
        java.util.zip.ZipInputStream(docx.inputStream()).use { zip ->
            var entry = zip.nextEntry
            while (entry != null) {
                if (entry.name.startsWith("word/media/") &&
                    (entry.name.endsWith(".jpg") || entry.name.endsWith(".jpeg"))
                ) {
                    jpegBytes = zip.readBytes()
                    break
                }
                entry = zip.nextEntry
            }
        }
        assertNotNull("images.docx에 JPEG가 있어야 함", jpegBytes)
        val dimensions = ImageDimensionReader.read(jpegBytes!!)
        assertNotNull("JPEG SOF 파싱 실패", dimensions)
    }

    @Test
    fun `rejects non-image bytes`() {
        assertNull(ImageDimensionReader.read("not an image at all".toByteArray()))
        assertNull(ImageDimensionReader.read(ByteArray(0)))
    }
}
