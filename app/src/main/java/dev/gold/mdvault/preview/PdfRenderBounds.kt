package dev.gold.mdvault.preview

internal data class PdfBitmapSize(
    val width: Int,
    val height: Int,
)

internal fun calculatePdfBitmapSize(
    pageWidth: Int,
    pageHeight: Int,
    targetWidth: Int,
    maxDimension: Int = PDF_MAX_BITMAP_DIMENSION,
    maxPixels: Long = PDF_MAX_BITMAP_PIXELS,
): PdfBitmapSize? {
    if (pageWidth <= 0 || pageHeight <= 0 || targetWidth <= 0) return null
    if (targetWidth > maxDimension) return null

    val height = pageHeight.toDouble() * targetWidth.toDouble() / pageWidth.toDouble()
    if (!height.isFinite() || height < 1.0 || height > maxDimension.toDouble()) return null
    val roundedHeight = height.toInt().coerceAtLeast(1)
    val pixels = targetWidth.toLong() * roundedHeight.toLong()
    if (pixels > maxPixels) return null
    return PdfBitmapSize(targetWidth, roundedHeight)
}

internal const val PDF_MAX_BITMAP_DIMENSION = 8_192
internal const val PDF_MAX_BITMAP_PIXELS = 16L * 1024L * 1024L
