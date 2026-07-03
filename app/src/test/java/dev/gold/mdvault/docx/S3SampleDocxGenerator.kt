package dev.gold.mdvault.docx

import org.junit.Test
import java.io.File

/**
 * S3 수동/외부 검증용 샘플 DOCX 생성기 (spike/S3-MANUAL-VERIFICATION.md 참조).
 * 테스트 실행 시 build/outputs/spike/s3-sample.docx 를 갱신한다.
 * spike 종료 후 제거 가능.
 */
class S3SampleDocxGenerator {

    @Test
    fun `generate sample docx for external word processor verification`() {
        val markdown = """
            # mdvault S3 샘플 문서

            ## 서식 검증

            이 문단에는 **굵은 텍스트**와 *기울임 텍스트*, 그리고 ***둘 다***가 있습니다.
            word **bold** word 연쇄 패턴의 공백 보존을 확인하세요.

            ## 목록

            - 불릿 하나
            - 불릿 둘
                - 중첩 불릿
            - 불릿 셋

            1. 순번 하나
            2. 순번 둘
                1. 중첩 순번
            3. 순번 셋

            ## 표

            | 항목 | 설명 | 값 |
            |------|------|---:|
            | 가나다 | 한글 셀 | 100 |
            | ABC | 영문 셀 | 200 |

            ## 링크와 이미지

            [mdvault 저장소](https://github.com/Balragon/mdvault) 링크입니다.

            ![샘플 이미지](images/relative-sample.png)

            끝 문단입니다.
        """.trimIndent()

        val outputDir = File("build/outputs/spike").apply { mkdirs() }
        val target = File(outputDir, "s3-sample.docx")
        val assets = AssetResolver { path ->
            File("../fixtures/md", path).takeIf { it.isFile }?.inputStream()
        }
        target.outputStream().use { output ->
            SimpleOoxmlDocxExportEngine().export(markdown, "mdvault S3 sample", assets, output)
        }
    }
}
