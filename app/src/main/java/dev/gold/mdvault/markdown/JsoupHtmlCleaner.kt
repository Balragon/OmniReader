package dev.gold.mdvault.markdown

import dev.gold.mdvault.document.ConversionWarning
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.safety.Cleaner
import org.jsoup.safety.Safelist

data class CleanedHtml(
    val html: String,
    val warnings: List<ConversionWarning>,
)

class JsoupHtmlCleaner(
    private val safelist: Safelist = defaultSafelist(),
) {
    fun clean(html: String): CleanedHtml {
        val document = Jsoup.parseBodyFragment(html)
        val warnings = stripKnownUnsafeAttributes(document)
        val imageSources = document.body().select("img").map { element ->
            element.attr("src").takeIf { it.isSafeImageSource() }
        }
        val cleaned = Cleaner(safelist).clean(document)
        cleaned.body().select("img").forEachIndexed { index, element ->
            imageSources.getOrNull(index)?.let { element.attr("src", it) }
        }
        return CleanedHtml(
            html = cleaned.body().html(),
            warnings = warnings,
        )
    }

    private fun stripKnownUnsafeAttributes(document: Document): List<ConversionWarning> {
        val warnings = mutableListOf<ConversionWarning>()
        for (element in document.allElements) {
            for (attribute in element.attributes().asList()) {
                val key = attribute.key
                val value = attribute.value
                if (key.equals("href", ignoreCase = true) && value.isUnsafeJavascriptHref()) {
                    element.removeAttr(key)
                    warnings += ConversionWarning.UnsafeLinkDropped(value)
                    continue
                }
                if (key.startsWith("on", ignoreCase = true)) {
                    element.removeAttr(key)
                    warnings += ConversionWarning.UnsupportedFeature("event-handler:$key")
                }
            }
        }
        return warnings
    }

    private fun String.isUnsafeJavascriptHref(): Boolean {
        val normalized = trimStart()
            .filterNot { it.isWhitespace() || it.code <= CONTROL_CHARACTER_MAX }
            .lowercase()
        return normalized.startsWith("javascript:")
    }

    private fun String.isSafeImageSource(): Boolean {
        val normalized = trim()
        if (normalized.isEmpty() || normalized.isUnsafeJavascriptHref()) return false
        val schemeSeparator = normalized.indexOf(':')
        val pathSeparator = normalized.indexOfAny(charArrayOf('/', '?', '#'))
        if (schemeSeparator == -1 || (pathSeparator != -1 && pathSeparator < schemeSeparator)) {
            return true
        }
        val scheme = normalized.substring(0, schemeSeparator).lowercase()
        return scheme == "http" || scheme == "https"
    }

    companion object {
        private const val CONTROL_CHARACTER_MAX = 0x1f

        fun defaultSafelist(): Safelist =
            Safelist.relaxed()
                .preserveRelativeLinks(true)
                .addTags("img")
                .addAttributes("img", "src", "alt", "title")
                .addAttributes("code", "class")
                .addAttributes("th", "align")
                .addAttributes("td", "align")
                .addTags("input")
                .addAttributes("input", "type", "checked", "disabled")
    }
}
