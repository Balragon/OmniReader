package dev.gold.mdvault.docx

/**
 * 순수 Kotlin XML writer (외부 의존성 0개).
 *
 * - text()/attribute 값은 XML escape(&, <, >, ", ')를 항상 적용
 * - XML 1.0 불법 제어문자(0x00-0x08, 0x0B, 0x0C, 0x0E-0x1F)는 strip
 * - <w:t>에 xml:space="preserve"를 붙이는 것은 호출자(export engine) 책임
 */
class OoxmlWriter(private val out: Appendable) {

    fun declaration() {
        out.append("<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>\r\n")
    }

    fun element(
        name: String,
        vararg attributes: Pair<String, String>,
        body: (OoxmlWriter.() -> Unit)? = null,
    ) {
        out.append('<').append(name)
        for ((key, value) in attributes) {
            out.append(' ').append(key).append("=\"")
            appendEscaped(value)
            out.append('"')
        }
        if (body == null) {
            out.append("/>")
        } else {
            out.append('>')
            body(this)
            out.append("</").append(name).append('>')
        }
    }

    fun text(value: String) {
        appendEscaped(value)
    }

    fun raw(xml: String) {
        out.append(xml)
    }

    private fun appendEscaped(value: String) {
        for (char in value) {
            if (char.isIllegalXmlChar()) continue
            when (char) {
                '&' -> out.append("&amp;")
                '<' -> out.append("&lt;")
                '>' -> out.append("&gt;")
                '"' -> out.append("&quot;")
                '\'' -> out.append("&apos;")
                else -> out.append(char)
            }
        }
    }

    private fun Char.isIllegalXmlChar(): Boolean {
        val code = code
        return code < 0x20 && code != 0x09 && code != 0x0A && code != 0x0D
    }
}
