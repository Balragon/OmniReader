package dev.gold.mdvault.editor

/**
 * S5 실기기 판정용 약 50KB 한글 샘플 (fixtures/md/korean.md는 기기에서
 * 접근 불가하므로 코드로 생성).
 */
fun s5KoreanSample(): String = buildString {
    append("# 한글 입력 안정성 테스트 문서\n\n")
    append("이 문서는 Samsung Keyboard와 Gboard로 문서 중간 삽입, 삭제, ")
    append("조합 중 커서 이동, undo 반복을 검증하기 위한 것입니다.\n\n")
    var index = 1
    while (length < 50_000) {
        append("## 절 $index\n\n")
        append("마크다운 볼트는 개인 지식 관리를 위한 도구입니다. ")
        append("한글 조합 입력은 자모가 결합되는 과정에서 IME 상태가 민감하게 관리되어야 하며, ")
        append("특히 받침이 다음 글자의 초성으로 넘어가는 도깨비불 현상과 ")
        append("조합 중 커서 이동이 겹칠 때 버퍼 불일치가 발생하기 쉽습니다. ")
        append("**굵은 강조**와 *기울임*, `코드 조각`, [링크](https://example.com/$index)도 ")
        append("섞여 있어 마크다운 문법과 한글이 함께 있는 실제 조건을 재현합니다.\n\n")
        append("- 목록 항목 하나 ($index)\n")
        append("- 목록 항목 둘: 가나다라마바사 아자차카타파하\n\n")
        index++
    }
}
