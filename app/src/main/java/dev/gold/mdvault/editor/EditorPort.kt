package dev.gold.mdvault.editor

/**
 * 에디터 구현 교체 지점 (S5 spike).
 *
 * 기본 구현은 Compose TextFieldState 기반(ComposeEditorPort). 실기기 한글 IME
 * 판정(spike/S5-REPORT.md)에서 조합 깨짐이 발견되면 AppCompatEditText +
 * AndroidView 구현으로 이 인터페이스 뒤에서만 교체한다 — 화면 코드는 불변.
 */
interface EditorPort {
    /** 전체 버퍼 접근 */
    var text: String

    /** 현재 선택 범위 (커서는 start == end) */
    val selectionStart: Int
    val selectionEnd: Int
    fun select(start: Int, end: Int)

    val canUndo: Boolean
    val canRedo: Boolean
    fun undo()
    fun redo()

    /** 스크롤 위치(px) 보존/복원 */
    val scrollPositionPx: Int
    suspend fun scrollTo(positionPx: Int)
}
