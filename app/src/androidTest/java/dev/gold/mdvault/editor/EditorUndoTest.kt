package dev.gold.mdvault.editor

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

/**
 * S5 undo 회귀 테스트 — Gboard 예비 판정에서 "undo가 작동하지 않는다"는
 * 보고를 재현/검증한다 (TextFieldState.undoState 경로).
 */
class EditorUndoTest {

    @get:Rule
    val rule = createComposeRule()

    @Test
    fun portRecordsTypingAndUndoReverts() {
        val port = ComposeEditorPort("시작 ")
        rule.setContent { MaterialTheme { MarkdownEditorScreen(port) } }

        rule.onNode(hasSetTextAction()).performTextInput("hello")
        rule.waitForIdle()

        val recorded = port.canUndo
        val textAfterInput = port.text
        assertTrue("입력이 버퍼에 반영되어야 함: $textAfterInput", textAfterInput.contains("hello"))
        assertTrue("undoState가 입력을 기록해야 함 (canUndo)", recorded)

        rule.runOnUiThread { port.undo() }
        rule.waitForIdle()
        assertFalse("undo 후 입력이 사라져야 함: ${port.text}", port.text.contains("hello"))
        assertEquals("시작 ", port.text)
    }

    @Test
    fun undoButtonEnablesAfterTypingAndRevertsOnClick() {
        val port = ComposeEditorPort("시작 ")
        rule.setContent { MaterialTheme { MarkdownEditorScreen(port) } }

        rule.onNode(hasSetTextAction()).performTextInput("world")
        rule.waitForIdle()

        rule.onNodeWithText("Undo").performClick()
        rule.waitForIdle()
        assertFalse(
            "Undo 버튼 클릭으로 입력이 사라져야 함: ${port.text}",
            port.text.contains("world"),
        )
    }
}
