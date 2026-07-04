package dev.gold.mdvault.document

import dev.gold.mdvault.docx.AssetResolver
import dev.gold.mdvault.docx.DocxExportEngine
import dev.gold.mdvault.storage.VaultRepository
import kotlinx.coroutines.runBlocking
import java.io.ByteArrayInputStream

/**
 * P1: 노트를 제한된 DOCX로 export해 vault의 같은 폴더에 저장한다.
 * 기존 파일은 절대 덮어쓰지 않는다 — 충돌 시 -2, -3 접미사 (데이터 안전성 규칙).
 */
class VaultDocxExporter(
    private val vaultRepository: VaultRepository,
    private val exportEngine: DocxExportEngine,
) {
    data class ExportedDocx(val relativePath: String, val warningCount: Int)

    suspend fun export(noteRelativePath: String): ExportedDocx {
        val markdown = vaultRepository.read(noteRelativePath) { input ->
            input.readBytes().decodeToString()
        }
        val baseDirectory = noteRelativePath.substringBeforeLast('/', "")
        val baseName = noteRelativePath.substringAfterLast('/').removeSuffix(".md")

        // 이미지 상대경로는 노트가 있는 폴더 기준으로 해석
        val assets = AssetResolver { relativePath ->
            val assetPath = listOf(baseDirectory, relativePath)
                .flatMap { it.split('/') }
                .filter { it.isNotBlank() }
                .joinToString("/")
            runCatching {
                runBlocking {
                    vaultRepository.read(assetPath, trackRecent = false) { it.readBytes() }
                }
            }.getOrNull()?.let(::ByteArrayInputStream)
        }

        val existingNames = vaultRepository.list(baseDirectory).map { it.displayName }.toSet()
        val fileName = firstFreeName(baseName, existingNames)
        val targetPath = listOf(baseDirectory, fileName)
            .filter { it.isNotBlank() }
            .joinToString("/")

        var warningCount = 0
        vaultRepository.create(targetPath, DOCX_MIME_TYPE) { output ->
            warningCount = exportEngine.export(markdown, baseName, assets, output).size
        }
        return ExportedDocx(targetPath, warningCount)
    }

    private fun firstFreeName(baseName: String, existingNames: Set<String>): String {
        val preferred = "$baseName.docx"
        if (preferred !in existingNames) return preferred
        var index = 2
        while (true) {
            val candidate = "$baseName-$index.docx"
            if (candidate !in existingNames) return candidate
            index += 1
        }
    }

    private companion object {
        private const val DOCX_MIME_TYPE =
            "application/vnd.openxmlformats-officedocument.wordprocessingml.document"
    }
}
