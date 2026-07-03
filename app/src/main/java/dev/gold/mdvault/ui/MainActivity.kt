package dev.gold.mdvault.ui

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import dev.gold.mdvault.AppContainer
import dev.gold.mdvault.editor.ComposeEditorPort
import dev.gold.mdvault.editor.MarkdownEditorScreen
import dev.gold.mdvault.editor.s5KoreanSample
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.io.File
import java.io.FileNotFoundException

class MainActivity : ComponentActivity() {
    private val container = AppContainer()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    SpikeHome(container)
                }
            }
        }
    }
}

/** spike 하네스 홈: S1 import 측정 / S5 에디터 판정 전환. */
@Composable
private fun SpikeHome(container: AppContainer) {
    var screen by remember { mutableStateOf("s1") }
    val editorPort = remember { ComposeEditorPort(s5KoreanSample()) }

    Column(modifier = Modifier.fillMaxSize()) {
        Row(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Button(onClick = { screen = "s1" }) { Text("S1 Import") }
            Button(onClick = { screen = "s5" }) { Text("S5 Editor") }
        }
        when (screen) {
            "s1" -> S1SpikeScreen(container)
            else -> MarkdownEditorScreen(editorPort)
        }
    }
}

/**
 * S1 spike 하네스: 실기기 release APK에서 DOCX import 시간을 측정한다
 * (spike/S1-REPORT.md 참조). 정식 UI는 S1/S3 게이트 통과 후 별도 구현.
 */
@Composable
private fun S1SpikeScreen(container: AppContainer) {
    var status by remember { mutableStateOf("mdvault — S1 spike harness") }
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        status = "importing…"
        scope.launch(Dispatchers.IO) {
            val startedAt = System.currentTimeMillis()
            status = try {
                val assetRoot = File(context.cacheDir, "s1-import").apply { mkdirs() }
                val input = context.contentResolver.openInputStream(uri)
                    ?: throw FileNotFoundException("$uri")
                val result = input.use { stream ->
                    container.docxImportEngine.importDocx(stream) { relativePath, _, bytes ->
                        val target = File(assetRoot, relativePath)
                        target.parentFile?.mkdirs()
                        target.writeBytes(bytes)
                    }
                }
                val elapsedMs = System.currentTimeMillis() - startedAt
                "OK ${elapsedMs}ms — html ${result.html.length}자, " +
                    "assets ${result.extractedAssets.size}개, warnings ${result.warnings.size}건"
            } catch (e: Exception) {
                "FAILED ${e.javaClass.simpleName}: ${e.message}"
            }
        }
    }

    Column(
        modifier = Modifier.fillMaxSize().padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text(text = status, style = MaterialTheme.typography.bodyLarge)
        Button(onClick = {
            picker.launch(
                arrayOf(
                    "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
                    "application/octet-stream",
                ),
            )
        }) {
            Text("Import DOCX")
        }
    }
}
