package dev.gold.mdvault.editor

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.input.TextFieldLineLimits
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * S5 spike: plain Markdown 편집 화면. 서식 렌더링 없음 — 순수 텍스트 버퍼.
 * 실기기 한글 IME 판정 절차는 spike/S5-REPORT.md 참조.
 */
@Composable
fun MarkdownEditorScreen(
    port: ComposeEditorPort,
    modifier: Modifier = Modifier,
) {
    LaunchedEffect(port) { port.trackChangesForUndo() }
    Column(modifier = modifier.fillMaxSize()) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp),
            horizontalArrangement = Arrangement.End,
        ) {
            TextButton(onClick = port::undo, enabled = port.canUndo) { Text("Undo") }
            TextButton(onClick = port::redo, enabled = port.canRedo) { Text("Redo") }
        }
        BasicTextField(
            state = port.textFieldState,
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 8.dp),
            textStyle = MaterialTheme.typography.bodyLarge.copy(
                fontSize = 15.sp,
                lineHeight = 24.sp,
            ),
            lineLimits = TextFieldLineLimits.MultiLine(),
            scrollState = port.scrollState,
        )
    }
}
