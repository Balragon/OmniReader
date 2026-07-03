package dev.gold.mdvault.docx

import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

/**
 * DOCX(zip)의 XML 파트에서 XML 1.0 불법 제어문자(0x00-0x08, 0x0B, 0x0C,
 * 0x0E-0x1F)를 제거한다. LLM/도구가 생성한 DOCX에 이런 바이트가 들어오면
 * Mammoth의 SAX 파서가 RuntimeException으로 죽기 때문에 import 앞단에서 거른다.
 *
 * UTF-8에서 이 값들은 항상 단일 바이트(연속 바이트는 0x80 이상)이므로
 * 바이트 단위 필터링이 안전하다. 결과는 메모리 버퍼로 반환한다(개인 vault
 * 규모 문서 전제 — 초대형 파일 스트리밍은 필요해지면 캐시 파일로 전환).
 */
internal object DocxXmlSanitizer {

    data class Sanitized(val bytes: ByteArray, val strippedCount: Int)

    fun sanitize(input: InputStream): Sanitized {
        val output = ByteArrayOutputStream()
        var stripped = 0
        ZipInputStream(input).use { zipIn ->
            ZipOutputStream(output).use { zipOut ->
                var entry = zipIn.nextEntry
                while (entry != null) {
                    val bytes = zipIn.readBytes()
                    val cleaned = if (entry.name.isXmlPart()) {
                        filterIllegalXmlBytes(bytes).also { stripped += bytes.size - it.size }
                    } else {
                        bytes
                    }
                    zipOut.putNextEntry(ZipEntry(entry.name))
                    zipOut.write(cleaned)
                    zipOut.closeEntry()
                    entry = zipIn.nextEntry
                }
            }
        }
        return Sanitized(output.toByteArray(), stripped)
    }

    private fun String.isXmlPart(): Boolean =
        endsWith(".xml") || endsWith(".rels")

    private fun filterIllegalXmlBytes(bytes: ByteArray): ByteArray {
        if (bytes.none { it.isIllegalXmlByte() }) return bytes
        val result = ByteArrayOutputStream(bytes.size)
        for (byte in bytes) {
            if (!byte.isIllegalXmlByte()) result.write(byte.toInt())
        }
        return result.toByteArray()
    }

    private fun Byte.isIllegalXmlByte(): Boolean {
        val value = toInt() and 0xff
        return value < 0x20 && value != 0x09 && value != 0x0A && value != 0x0D
    }
}
