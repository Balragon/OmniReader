package dev.gold.mdvault.editor

import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputConnection
import androidx.activity.ComponentActivity
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.performClick
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

/**
 * 한글 IME 조합(composition) 경로의 undo 검증 — Gboard 예비 판정에서
 * "undo가 작동하지 않는다"는 보고의 결정적 재현. 실제 IME 대신
 * AndroidComposeView의 InputConnection을 직접 잡아 setComposingText를 주입한다
 * (StatelessInputConnection — 실제 IME와 동일 코드 경로).
 */
class EditorImeCompositionUndoTest {

    @get:Rule
    val rule = createAndroidComposeRule<ComponentActivity>()

    private fun focusedInputConnection(): InputConnection {
        var connection: InputConnection? = null
        rule.runOnUiThread {
            val focused = rule.activity.window.decorView.findFocus()
                ?: error("포커스된 뷰 없음")
            connection = focused.onCreateInputConnection(EditorInfo())
        }
        return connection ?: error("InputConnection 생성 실패 — 텍스트 세션 비활성")
    }

    private fun InputConnection.composeStep(text: String) {
        beginBatchEdit()
        setComposingText(text, 1)
        endBatchEdit()
    }

    private fun setUpEditor(): ComposeEditorPort {
        val port = ComposeEditorPort("시작 ")
        rule.setContent { MaterialTheme { MarkdownEditorScreen(port) } }
        rule.onNode(hasSetTextAction()).performClick()
        rule.waitForIdle()
        return port
    }

    @Test
    fun undoAfterFinishedComposition() {
        val port = setUpEditor()
        val ic = focusedInputConnection()

        rule.runOnUiThread {
            ic.composeStep("ㅅ")
            ic.composeStep("서")
            ic.composeStep("설")
            ic.finishComposingText()
        }
        rule.waitForIdle()
        assertTrue("조합 결과가 버퍼에 있어야 함: ${port.text}", port.text.contains("설"))
        assertTrue("확정된 조합 후 canUndo=true여야 함", port.canUndo)

        rule.runOnUiThread { port.undo() }
        rule.waitForIdle()
        assertEquals("확정된 조합의 undo는 입력을 되돌려야 함", "시작 ", port.text)
    }

    @Test
    fun undoWhileCompositionActive() {
        val port = setUpEditor()
        val ic = focusedInputConnection()

        rule.runOnUiThread {
            ic.composeStep("ㅎ")
            ic.composeStep("하")
            ic.composeStep("한")
        }
        rule.waitForIdle()
        assertTrue("조합 중 텍스트가 버퍼에 있어야 함: ${port.text}", port.text.contains("한"))

        val canUndoWhileComposing = port.canUndo
        rule.runOnUiThread { port.undo() }
        rule.waitForIdle()

        assertTrue(
            "조합 중 undo 진단 — canUndo=$canUndoWhileComposing, undo 후 텍스트='${port.text}'",
            port.text == "시작 ",
        )
    }

    @Test
    fun repeatedUndoAfterMultipleSyllables() {
        val port = setUpEditor()
        val ic = focusedInputConnection()

        rule.runOnUiThread {
            // "안녕" 두 글자를 조합으로 입력 후 스페이스로 확정 — 실제 IME 패턴
            ic.composeStep("ㅇ"); ic.composeStep("아"); ic.composeStep("안")
            ic.composeStep("안ㄴ"); ic.composeStep("안녀"); ic.composeStep("안녕")
            ic.finishComposingText()
            ic.commitText(" ", 1)
        }
        rule.waitForIdle()
        assertTrue(port.text.contains("안녕 "))

        rule.runOnUiThread {
            while (port.canUndo) port.undo()
        }
        rule.waitForIdle()
        assertEquals("undo 연타로 원문 복귀해야 함", "시작 ", port.text)
    }
}
