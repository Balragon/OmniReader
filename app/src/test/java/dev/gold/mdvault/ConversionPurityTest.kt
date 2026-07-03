package dev.gold.mdvault

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * CLAUDE.md 규칙 3 강제: markdown/, docx/ 패키지는 순수 JVM이어야 한다.
 * android.*, androidx.* import가 발견되면 빌드를 실패시킨다.
 * 이 테스트를 약화하거나 삭제하지 않는다.
 */
class ConversionPurityTest {

    private val forbidden = Regex("""^import\s+(android|androidx)\.""")
    private val pureDirs = listOf(
        "src/main/java/dev/gold/mdvault/markdown",
        "src/main/java/dev/gold/mdvault/docx",
    )

    @Test
    fun `markdown and docx packages must not import Android APIs`() {
        val violations = mutableListOf<String>()
        for (dir in pureDirs) {
            val root = File(dir)
            if (!root.exists()) continue
            root.walkTopDown()
                .filter { it.isFile && (it.extension == "kt" || it.extension == "java") }
                .forEach { file ->
                    file.readLines().forEachIndexed { index, line ->
                        if (forbidden.containsMatchIn(line.trim())) {
                            violations += "${file.path}:${index + 1}: ${line.trim()}"
                        }
                    }
                }
        }
        assertTrue(
            "변환 파이프라인 순수성 위반 (CLAUDE.md 규칙 3):\n" + violations.joinToString("\n"),
            violations.isEmpty()
        )
    }
}
