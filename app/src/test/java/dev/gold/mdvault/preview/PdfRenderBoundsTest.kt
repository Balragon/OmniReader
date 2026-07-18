package dev.gold.mdvault.preview

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PdfRenderBoundsTest {

    @Test
    fun `normal page geometry produces a bounded bitmap`() {
        assertEquals(PdfBitmapSize(1_440, 2_035), calculatePdfBitmapSize(612, 865, 1_440))
    }

    @Test
    fun `extreme or invalid geometry is rejected before allocation`() {
        assertNull(calculatePdfBitmapSize(1, 100_000, 1_440))
        assertNull(calculatePdfBitmapSize(0, 100, 1_440))
        assertNull(calculatePdfBitmapSize(100, 100, 9_000))
        assertNull(calculatePdfBitmapSize(1, Int.MAX_VALUE, 1_440))
    }

    @Test
    fun `bitmap budget is capped and adapts down for a small heap`() {
        val smallHeap = pdfMaxBitmapPixels(64)
        val largeHeap = pdfMaxBitmapPixels(512)

        assertTrue(smallHeap < largeHeap)
        assertEquals(PDF_MAX_BITMAP_PIXELS, largeHeap)
        val downscaled = calculatePdfBitmapSize(1_440, 8_000, 1_440, maxPixels = smallHeap)
        assertNotNull(downscaled)
        assertTrue(downscaled!!.width < 1_440)
        assertTrue(downscaled.width.toLong() * downscaled.height <= smallHeap)
    }
}
