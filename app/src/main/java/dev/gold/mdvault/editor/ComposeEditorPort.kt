package dev.gold.mdvault.editor

import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.text.TextRange
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.drop

/**
 * Compose TextFieldState 기반 EditorPort 구현.
 *
 * undo/redo는 자체 debounce 스냅샷 스택이다. TextFieldState.undoState는
 * 한글 IME 조합의 자모 단계(ㅅ→서→설)를 전부 개별 항목으로 기록해
 * (merge는 연속 "삽입"에만 적용, 조합 업데이트는 "치환"이라 merge 불가)
 * 한글 입력에서 사실상 무용하다 — EditorImeCompositionUndoTest로 확인.
 * 대신 타이핑이 [RECORD_DEBOUNCE_MS] 멈출 때마다 스냅샷을 기록한다
 * (일반 에디터의 단어/버스트 단위 undo와 동일한 UX).
 */
class ComposeEditorPort(initialText: String = "") : EditorPort {

    val textFieldState = TextFieldState(initialText)
    val scrollState = ScrollState(0)

    private data class EditorSnapshot(
        val text: String,
        val selectionStart: Int,
        val selectionEnd: Int,
    )

    private val undoStack = mutableStateListOf<EditorSnapshot>()
    private val redoStack = mutableStateListOf<EditorSnapshot>()
    private var lastRecorded by mutableStateOf(EditorSnapshot(initialText, 0, 0))

    /**
     * 편집 화면이 살아있는 동안 호출해 두는 변경 추적 루프
     * (MarkdownEditorScreen의 LaunchedEffect에서 수집).
     */
    @OptIn(FlowPreview::class)
    suspend fun trackChangesForUndo() {
        snapshotFlow { textFieldState.text.toString() }
            .drop(1) // 초기값은 기록하지 않는다
            .debounce(RECORD_DEBOUNCE_MS)
            .collect { recordPendingChange() }
    }

    private fun currentSnapshot() = EditorSnapshot(
        text = textFieldState.text.toString(),
        selectionStart = textFieldState.selection.start,
        selectionEnd = textFieldState.selection.end,
    )

    private fun recordPendingChange() {
        val current = currentSnapshot()
        if (current.text == lastRecorded.text) return
        undoStack.add(lastRecorded)
        if (undoStack.size > UNDO_CAPACITY) undoStack.removeAt(0)
        redoStack.clear()
        lastRecorded = current
    }

    override var text: String
        get() = textFieldState.text.toString()
        set(value) {
            textFieldState.edit { replace(0, length, value) }
        }

    override val selectionStart: Int
        get() = textFieldState.selection.start

    override val selectionEnd: Int
        get() = textFieldState.selection.end

    override fun select(start: Int, end: Int) {
        textFieldState.edit {
            selection = TextRange(
                start.coerceIn(0, length),
                end.coerceIn(0, length),
            )
        }
    }

    override val canUndo: Boolean
        get() = undoStack.isNotEmpty() ||
            !textFieldState.text.contentEquals(lastRecorded.text)

    override val canRedo: Boolean
        get() = redoStack.isNotEmpty()

    override fun undo() {
        recordPendingChange() // debounce가 아직 안 돈 최신 입력을 먼저 확정
        val target = undoStack.removeLastOrNull() ?: return
        redoStack.add(currentSnapshot())
        restore(target)
    }

    override fun redo() {
        val target = redoStack.removeLastOrNull() ?: return
        undoStack.add(currentSnapshot())
        restore(target)
    }

    private fun restore(snapshot: EditorSnapshot) {
        textFieldState.edit {
            replace(0, length, snapshot.text)
            selection = TextRange(
                snapshot.selectionStart.coerceIn(0, length),
                snapshot.selectionEnd.coerceIn(0, length),
            )
        }
        lastRecorded = snapshot
    }

    override val scrollPositionPx: Int
        get() = scrollState.value

    override suspend fun scrollTo(positionPx: Int) {
        scrollState.scrollTo(positionPx)
    }

    private companion object {
        private const val RECORD_DEBOUNCE_MS = 350L
        private const val UNDO_CAPACITY = 100
    }
}
