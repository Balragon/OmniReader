package dev.gold.mdvault.docx

import dev.gold.mdvault.document.ConversionWarning
import org.zwobble.mammoth.DocumentConverter
import java.io.ByteArrayInputStream
import java.io.InputStream
import java.security.MessageDigest

class MammothDocxImportEngine(
    private val policy: DocxImportPolicy = DocxImportPolicy(),
) : DocxImportEngine {

    override fun importDocx(input: InputStream, imageSink: ImageSink): HtmlImportResult {
        val assets = mutableListOf<ExtractedAsset>()
        var sequence = 0
        var totalAssetBytes = 0L
        var pendingRejection: DocxImportRejectedException? = null

        fun reject(reason: DocxImportRejectedException.Reason): Nothing {
            val error = DocxImportRejectedException(reason)
            pendingRejection = error
            throw error
        }

        val converter = DocumentConverter().imageConverter { image ->
            pendingRejection?.let { throw it }
            sequence += 1
            if (sequence > policy.maxAssetCount) {
                reject(DocxImportRejectedException.Reason.ASSET_COUNT_LIMIT)
            }
            val bytes = try {
                image.inputStream.use {
                    it.readBytesLimited(
                        policy.maxAssetBytes,
                        DocxImportRejectedException.Reason.ASSET_SIZE_LIMIT,
                    )
                }
            } catch (error: DocxImportRejectedException) {
                pendingRejection = error
                throw error
            }
            totalAssetBytes += bytes.size
            if (totalAssetBytes > policy.maxTotalAssetBytes) {
                reject(DocxImportRejectedException.Reason.TOTAL_ASSET_SIZE_LIMIT)
            }
            val relativePath = buildString {
                append("media/")
                append(sha256Prefix(bytes))
                append('-')
                append(sequence.toString().padStart(3, '0'))
                append('.')
                append(extensionFor(image.contentType))
            }
            imageSink.store(relativePath, image.contentType, bytes)
            assets += ExtractedAsset(relativePath, image.contentType, bytes.size.toLong())

            val attributes = mutableMapOf("src" to relativePath)
            image.altText.orElse(null)?.takeIf { it.isNotBlank() }?.let { attributes["alt"] = it }
            attributes
        }

        val sanitized = DocxXmlSanitizer.sanitize(input, policy)
        val result = try {
            converter.convertToHtml(ByteArrayInputStream(sanitized.bytes))
        } catch (error: RuntimeException) {
            pendingRejection?.let { throw it }
            throw error
        }
        pendingRejection?.let { throw it }
        policy.requireConversionSize(result.value)

        val warnings = buildList {
            if (sanitized.strippedCount > 0) {
                add(ConversionWarning.IllegalXmlCharactersStripped(sanitized.strippedCount))
            }
            result.warnings.mapTo(this) { ConversionWarning.UnsupportedFeature(it) }
        }
        return HtmlImportResult(
            html = result.value,
            warnings = warnings,
            extractedAssets = assets,
        )
    }

    private fun sha256Prefix(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256")
            .digest(bytes)
            .joinToString("") { "%02x".format(it) }
            .take(HASH_PREFIX_LENGTH)

    private fun extensionFor(contentType: String): String =
        when (contentType.substringBefore(';').trim().lowercase()) {
            "image/png" -> "png"
            "image/jpeg", "image/jpg" -> "jpg"
            "image/gif" -> "gif"
            "image/bmp" -> "bmp"
            "image/webp" -> "webp"
            "image/tiff" -> "tiff"
            else -> "bin"
        }

    private companion object {
        private const val HASH_PREFIX_LENGTH = 12
    }

    private fun DocxImportPolicy.requireConversionSize(value: String) {
        if (value.length > maxConversionCharacters) {
            throw DocxImportRejectedException(
                DocxImportRejectedException.Reason.CONVERSION_OUTPUT_LIMIT,
            )
        }
    }
}
