package dev.gold.mdvault.docx

/**
 * PNG IHDR / JPEG SOF 마커 파싱으로 픽셀 크기를 추출한다.
 * BitmapFactory 금지 (docx/는 순수 JVM — CLAUDE.md 규칙 3).
 */
object ImageDimensionReader {

    data class Dimensions(val widthPx: Int, val heightPx: Int) {
        val widthEmu: Long get() = widthPx * EMU_PER_PIXEL
        val heightEmu: Long get() = heightPx * EMU_PER_PIXEL
    }

    /** 96dpi 기준 1px = 9525 EMU */
    const val EMU_PER_PIXEL = 9525L

    fun read(bytes: ByteArray): Dimensions? = readPng(bytes) ?: readJpeg(bytes)

    private fun readPng(bytes: ByteArray): Dimensions? {
        val signature = byteArrayOf(
            0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A,
        )
        if (bytes.size < 24) return null
        if (!bytes.copyOfRange(0, 8).contentEquals(signature)) return null
        // 첫 청크는 IHDR: length(4) type(4)=IHDR width(4) height(4)
        if (bytes[12].toInt() != 'I'.code || bytes[13].toInt() != 'H'.code ||
            bytes[14].toInt() != 'D'.code || bytes[15].toInt() != 'R'.code
        ) {
            return null
        }
        val width = bytes.readIntBE(16)
        val height = bytes.readIntBE(20)
        return if (width > 0 && height > 0) Dimensions(width, height) else null
    }

    private fun readJpeg(bytes: ByteArray): Dimensions? {
        if (bytes.size < 4) return null
        if (bytes.u(0) != 0xFF || bytes.u(1) != 0xD8) return null
        var index = 2
        while (index + 9 < bytes.size) {
            if (bytes.u(index) != 0xFF) return null
            var marker = bytes.u(index + 1)
            // 패딩된 0xFF 스킵
            while (marker == 0xFF && index + 2 < bytes.size) {
                index++
                marker = bytes.u(index + 1)
            }
            when {
                marker == 0x01 || marker in 0xD0..0xD9 -> index += 2 // standalone
                isSofMarker(marker) -> {
                    val height = (bytes.u(index + 5) shl 8) or bytes.u(index + 6)
                    val width = (bytes.u(index + 7) shl 8) or bytes.u(index + 8)
                    return if (width > 0 && height > 0) Dimensions(width, height) else null
                }
                else -> {
                    val length = (bytes.u(index + 2) shl 8) or bytes.u(index + 3)
                    if (length < 2) return null
                    index += 2 + length
                }
            }
        }
        return null
    }

    private fun isSofMarker(marker: Int): Boolean =
        marker in 0xC0..0xCF && marker != 0xC4 && marker != 0xC8 && marker != 0xCC

    private fun ByteArray.u(index: Int): Int = this[index].toInt() and 0xFF

    private fun ByteArray.readIntBE(offset: Int): Int =
        (u(offset) shl 24) or (u(offset + 1) shl 16) or (u(offset + 2) shl 8) or u(offset + 3)
}
