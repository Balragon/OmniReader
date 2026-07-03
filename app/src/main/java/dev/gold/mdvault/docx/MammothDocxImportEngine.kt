package dev.gold.mdvault.docx

import dev.gold.mdvault.document.ConversionWarning
import org.zwobble.mammoth.DocumentConverter
import java.io.ByteArrayInputStream
import java.io.InputStream
import java.security.MessageDigest

class MammothDocxImportEngine : DocxImportEngine {

    override fun importDocx(input: InputStream, imageSink: ImageSink): HtmlImportResult {
        val assets = mutableListOf<ExtractedAsset>()
        var sequence = 0

        val converter = DocumentConverter().imageConverter { image ->
            val bytes = image.inputStream.use { it.readBytes() }
            sequence += 1
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

        val sanitized = DocxXmlSanitizer.sanitize(input)
        val result = converter.convertToHtml(ByteArrayInputStream(sanitized.bytes))

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
}
