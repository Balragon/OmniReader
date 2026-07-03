package dev.gold.mdvault.editor

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.ui.text.TextRange

/**
 * Compose TextFieldState 기반 EditorPort 구현.
 * undo/redo는 자체 구현하지 않고 TextFieldState.undoState를 사용한다
 * (experimental API — Foundation 버전은 compose BOM으로 version catalog에 고정).
 */
@OptIn(ExperimentalFoundationApi::class)
class ComposeEditorPort(initialText: String = "") : EditorPort {

    val textFieldState = TextFieldState(initialText)
    val scrollState = ScrollState(0)

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
        get() = textFieldState.undoState.canUndo

    override val canRedo: Boolean
        get() = textFieldState.undoState.canRedo

    override fun undo() {
        if (canUndo) textFieldState.undoState.undo()
    }

    override fun redo() {
        if (canRedo) textFieldState.undoState.redo()
    }

    override val scrollPositionPx: Int
        get() = scrollState.value

    override suspend fun scrollTo(positionPx: Int) {
        scrollState.scrollTo(positionPx)
    }
}
