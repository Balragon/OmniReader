package dev.gold.mdvault.preview

/**
 * JSON/CSV 원문을 WebView에 안전하게 표시할 HTML 본문으로 바꾼다.
 * 외부 라이브러리나 Android API를 사용하지 않아 순수 JVM 테스트가 가능하다.
 */
internal object StructuredTextHtmlBuilder {

    fun json(source: String, sourceTruncated: Boolean = false): StructuredTextHtml {
        val normalized = source.removePrefix(UTF8_BOM)
        val formatted = if (sourceTruncated) {
            null
        } else {
            try {
                JsonPrettyPrinter(normalized).format()
            } catch (_: IllegalArgumentException) {
                null
            }
        }
        val rendered = preformattedHtml(formatted ?: normalized, "json-document")
        return StructuredTextHtml(
            bodyHtml = rendered.html,
            formatted = formatted != null,
            truncated = sourceTruncated || rendered.truncated,
        )
    }

    fun csv(source: String, sourceTruncated: Boolean = false): StructuredTextHtml {
        val normalized = source.removePrefix(UTF8_BOM)
        val parsed = try {
            CsvParser(normalized, sourceTruncated).parse()
        } catch (_: IllegalArgumentException) {
            null
        }
        if (parsed == null || (sourceTruncated && parsed.rows.isEmpty())) {
            val rendered = preformattedHtml(normalized, "csv-document")
            return StructuredTextHtml(
                bodyHtml = rendered.html,
                formatted = false,
                truncated = sourceTruncated || rendered.truncated,
            )
        }
        val rendered = csvTableHtml(parsed.rows)
        return StructuredTextHtml(
            bodyHtml = rendered.html,
            formatted = true,
            truncated = parsed.truncated || rendered.truncated,
        )
    }

    private fun preformattedHtml(text: String, className: String): RenderedHtml {
        val prefix = "<pre class=\"$className\"><code>"
        val suffix = "</code></pre>"
        val escaped = text.escapeHtmlTextBounded(MAX_STRUCTURED_HTML_CHARS - prefix.length - suffix.length)
        return RenderedHtml(
            html = prefix + escaped.html + suffix,
            truncated = escaped.truncated,
        )
    }

    private fun csvTableHtml(rows: List<List<String>>): RenderedHtml {
        if (rows.isEmpty()) return preformattedHtml("", "csv-document")
        val footer = "</tbody></table></div>"
        val output = StringBuilder(minOf(MAX_STRUCTURED_HTML_CHARS, 4_096))
        output.append("<div class=\"csv-scroll\"><table class=\"csv-table\"><tbody>\n")
        var truncated = false
        rowLoop@ for ((rowIndex, row) in rows.withIndex()) {
            if (output.length >= MAX_STRUCTURED_HTML_CHARS - HTML_CLOSING_RESERVE) {
                truncated = true
                break
            }
            output.append("<tr><th class=\"csv-row-number\" scope=\"row\">")
            output.append(rowIndex + 1)
            output.append("</th>")
            for (cell in row) {
                val available = MAX_STRUCTURED_HTML_CHARS - output.length - HTML_CLOSING_RESERVE - CSV_CELL_TAGS.length
                if (available <= 0) {
                    truncated = true
                    break
                }
                val escaped = cell.escapeHtmlTextBounded(available)
                output.append("<td>")
                output.append(escaped.html)
                output.append("</td>")
                if (escaped.truncated) {
                    truncated = true
                    break
                }
            }
            output.append("</tr>\n")
            if (truncated) break@rowLoop
        }
        output.append(footer)
        return RenderedHtml(output.toString(), truncated)
    }
}

internal data class StructuredTextHtml(
    val bodyHtml: String,
    val formatted: Boolean,
    val truncated: Boolean = false,
)

private data class RenderedHtml(
    val html: String,
    val truncated: Boolean,
)

private data class EscapedHtml(
    val html: String,
    val truncated: Boolean,
)

private class CsvParser(
    private val source: String,
    private val sourceTruncated: Boolean,
) {
    private var index = 0

    fun parse(): CsvParseResult {
        if (source.isEmpty()) return CsvParseResult(emptyList(), truncated = false)
        val rows = mutableListOf<List<String>>()
        var cellCount = 0

        while (index < source.length) {
            if (rows.size >= MAX_CSV_ROWS) {
                return CsvParseResult(rows, truncated = true)
            }
            val row = mutableListOf<String>()
            while (true) {
                if (cellCount >= MAX_CSV_CELLS || row.size >= MAX_CSV_COLUMNS) {
                    if (row.isNotEmpty()) rows += row
                    return CsvParseResult(rows, truncated = true)
                }
                val field = try {
                    parseField()
                } catch (_: TruncatedCsvFieldException) {
                    return CsvParseResult(rows, truncated = true)
                }
                row += field
                cellCount += 1

                when {
                    index >= source.length -> {
                        if (sourceTruncated) return CsvParseResult(rows, truncated = true)
                        rows += row
                        return CsvParseResult(rows, truncated = false)
                    }
                    source[index] == ',' -> index += 1
                    source[index] == '\r' || source[index] == '\n' -> {
                        consumeLineBreak()
                        rows += row
                        break
                    }
                    else -> throw IllegalArgumentException("Unexpected character after CSV field")
                }
            }
        }
        return CsvParseResult(rows, truncated = sourceTruncated)
    }

    private fun parseField(): String =
        if (index < source.length && source[index] == '"') parseQuotedField() else parsePlainField()

    private fun parseQuotedField(): String {
        index += 1
        val value = StringBuilder()
        while (index < source.length) {
            when (val current = source[index]) {
                '"' -> {
                    if (index + 1 < source.length && source[index + 1] == '"') {
                        value.append('"')
                        index += 2
                    } else {
                        index += 1
                        return value.toString()
                    }
                }
                '\r', '\n' -> {
                    consumeLineBreak()
                    value.append('\n')
                }
                else -> {
                    value.append(current)
                    index += 1
                }
            }
        }
        if (sourceTruncated) throw TruncatedCsvFieldException()
        throw IllegalArgumentException("Unterminated quoted CSV field")
    }

    private fun parsePlainField(): String {
        val start = index
        while (index < source.length && source[index] != ',' && source[index] != '\r' && source[index] != '\n') {
            require(source[index] != '"') { "Quote in unquoted CSV field" }
            index += 1
        }
        return source.substring(start, index)
    }

    private fun consumeLineBreak() {
        if (source[index] == '\r' && index + 1 < source.length && source[index + 1] == '\n') {
            index += 2
        } else {
            index += 1
        }
    }
}

private class TruncatedCsvFieldException : RuntimeException()

private data class CsvParseResult(
    val rows: List<List<String>>,
    val truncated: Boolean,
)

private class JsonPrettyPrinter(private val source: String) {
    private val output = StringBuilder(minOf(source.length + 256, MAX_JSON_OUTPUT_CHARS))
    private var index = 0

    fun format(): String {
        skipWhitespace()
        parseValue(depth = 0)
        skipWhitespace()
        require(index == source.length) { "Unexpected content after JSON value" }
        return output.toString()
    }

    private fun parseValue(depth: Int) {
        require(depth <= MAX_JSON_DEPTH) { "JSON nesting is too deep" }
        skipWhitespace()
        require(index < source.length) { "Missing JSON value" }
        when (source[index]) {
            '{' -> parseObject(depth)
            '[' -> parseArray(depth)
            '"' -> append(readString())
            't' -> readLiteral("true")
            'f' -> readLiteral("false")
            'n' -> readLiteral("null")
            '-', in '0'..'9' -> readNumber()
            else -> throw IllegalArgumentException("Invalid JSON value")
        }
    }

    private fun parseObject(depth: Int) {
        index += 1
        append('{')
        skipWhitespace()
        if (consume('}')) {
            append('}')
            return
        }
        append('\n')
        while (true) {
            skipWhitespace()
            require(index < source.length && source[index] == '"') { "JSON object key must be a string" }
            appendIndent(depth + 1)
            append(readString())
            skipWhitespace()
            require(consume(':')) { "Missing colon after JSON object key" }
            append(": ")
            parseValue(depth + 1)
            skipWhitespace()
            when {
                consume(',') -> append(",\n")
                consume('}') -> {
                    append('\n')
                    appendIndent(depth)
                    append('}')
                    return
                }
                else -> throw IllegalArgumentException("Missing comma or closing brace")
            }
        }
    }

    private fun parseArray(depth: Int) {
        index += 1
        append('[')
        skipWhitespace()
        if (consume(']')) {
            append(']')
            return
        }
        append('\n')
        while (true) {
            appendIndent(depth + 1)
            parseValue(depth + 1)
            skipWhitespace()
            when {
                consume(',') -> append(",\n")
                consume(']') -> {
                    append('\n')
                    appendIndent(depth)
                    append(']')
                    return
                }
                else -> throw IllegalArgumentException("Missing comma or closing bracket")
            }
        }
    }

    private fun readString(): String {
        val start = index
        require(source[index] == '"')
        index += 1
        while (index < source.length) {
            when (val current = source[index++]) {
                '"' -> return source.substring(start, index)
                '\\' -> {
                    require(index < source.length) { "Incomplete JSON escape" }
                    when (source[index++]) {
                        '"', '\\', '/', 'b', 'f', 'n', 'r', 't' -> Unit
                        'u' -> repeat(4) {
                            require(index < source.length && source[index].isJsonHexDigit()) {
                                "Invalid JSON unicode escape"
                            }
                            index += 1
                        }
                        else -> throw IllegalArgumentException("Invalid JSON escape")
                    }
                }
                else -> require(current.code >= 0x20) { "Control character in JSON string" }
            }
        }
        throw IllegalArgumentException("Unterminated JSON string")
    }

    private fun readLiteral(literal: String) {
        require(source.startsWith(literal, index)) { "Invalid JSON literal" }
        index += literal.length
        append(literal)
    }

    private fun readNumber() {
        val start = index
        if (source[index] == '-') index += 1
        require(index < source.length) { "Incomplete JSON number" }
        if (source[index] == '0') {
            index += 1
        } else {
            require(source[index] in '1'..'9') { "Invalid JSON number" }
            while (index < source.length && source[index] in '0'..'9') index += 1
        }
        if (index < source.length && source[index] == '.') {
            index += 1
            require(index < source.length && source[index] in '0'..'9') { "Invalid JSON fraction" }
            while (index < source.length && source[index] in '0'..'9') index += 1
        }
        if (index < source.length && (source[index] == 'e' || source[index] == 'E')) {
            index += 1
            if (index < source.length && (source[index] == '+' || source[index] == '-')) index += 1
            require(index < source.length && source[index] in '0'..'9') { "Invalid JSON exponent" }
            while (index < source.length && source[index] in '0'..'9') index += 1
        }
        append(source.substring(start, index))
    }

    private fun skipWhitespace() {
        while (index < source.length && source[index] in JSON_WHITESPACE) index += 1
    }

    private fun consume(expected: Char): Boolean {
        if (index >= source.length || source[index] != expected) return false
        index += 1
        return true
    }

    private fun appendIndent(depth: Int) = repeat(depth) { append("  ") }

    private fun append(value: Char) {
        require(output.length + 1 <= MAX_JSON_OUTPUT_CHARS) { "Formatted JSON is too large" }
        output.append(value)
    }

    private fun append(value: String) {
        require(output.length + value.length <= MAX_JSON_OUTPUT_CHARS) { "Formatted JSON is too large" }
        output.append(value)
    }
}

private fun String.escapeHtmlTextBounded(maxChars: Int): EscapedHtml {
    require(maxChars >= 0)
    val output = StringBuilder(minOf(length, maxChars))
    for (character in this@escapeHtmlTextBounded) {
        val escaped = when (character) {
            '&' -> "&amp;"
            '<' -> "&lt;"
            '>' -> "&gt;"
            else -> null
        }
        val addedLength = escaped?.length ?: 1
        if (output.length + addedLength > maxChars) return EscapedHtml(output.toString(), truncated = true)
        if (escaped != null) output.append(escaped) else output.append(character)
    }
    return EscapedHtml(output.toString(), truncated = false)
}

private fun Char.isJsonHexDigit(): Boolean =
    this in '0'..'9' || this in 'a'..'f' || this in 'A'..'F'

private const val UTF8_BOM = "\uFEFF"
private const val MAX_JSON_DEPTH = 128
private const val MAX_JSON_OUTPUT_CHARS = 8 * 1024 * 1024
private const val MAX_STRUCTURED_HTML_CHARS = 8 * 1024 * 1024
private const val MAX_CSV_ROWS = 2_000
private const val MAX_CSV_CELLS = 20_000
private const val MAX_CSV_COLUMNS = 200
private const val HTML_CLOSING_RESERVE = 128
private const val CSV_CELL_TAGS = "<td></td>"
private val JSON_WHITESPACE = charArrayOf(' ', '\t', '\r', '\n')
