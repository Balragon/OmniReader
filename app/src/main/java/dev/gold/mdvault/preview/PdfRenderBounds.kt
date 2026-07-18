package dev.gold.mdvault.preview

import kotlin.math.floor
import kotlin.math.sqrt

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

    val aspectRatio = pageHeight.toDouble() / pageWidth.toDouble()
    if (!aspectRatio.isFinite() || aspectRatio <= 0.0) return null
    val widthForDimension = floor(maxDimension.toDouble() / aspectRatio).toInt()
    val widthForPixels = floor(sqrt(maxPixels.toDouble() / aspectRatio)).toInt()
    val boundedWidth = minOf(targetWidth, maxDimension, widthForDimension, widthForPixels)
    if (boundedWidth <= 0) return null
    val height = aspectRatio * boundedWidth.toDouble()
    if (!height.isFinite() || height < 1.0 || height > maxDimension.toDouble()) return null
    val roundedHeight = height.toInt().coerceAtLeast(1)
    val pixels = boundedWidth.toLong() * roundedHeight.toLong()
    if (pixels > maxPixels) return null
    return PdfBitmapSize(boundedWidth, roundedHeight)
}

internal fun pdfMaxBitmapPixels(memoryClassMb: Int): Long {
    if (memoryClassMb <= 0) return PDF_MIN_BITMAP_PIXELS
    val heapBytes = memoryClassMb.toLong() * 1024L * 1024L
    return (heapBytes / PDF_BITMAP_BYTES_PER_PIXEL / PDF_HEAP_FRACTION_DENOMINATOR)
        .coerceIn(PDF_MIN_BITMAP_PIXELS, PDF_MAX_BITMAP_PIXELS)
}

internal const val PDF_MAX_BITMAP_DIMENSION = 8_192
internal const val PDF_MAX_BITMAP_PIXELS = 6L * 1024L * 1024L
private const val PDF_MIN_BITMAP_PIXELS = 2L * 1024L * 1024L
private const val PDF_BITMAP_BYTES_PER_PIXEL = 4L
private const val PDF_HEAP_FRACTION_DENOMINATOR = 8L
