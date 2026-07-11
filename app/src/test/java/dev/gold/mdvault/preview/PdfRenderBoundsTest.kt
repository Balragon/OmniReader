package dev.gold.mdvault.preview

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
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
}
