package dev.gold.mdvault.ui

import android.net.Uri
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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
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
import dev.gold.mdvault.preview.MarkdownReaderScreen
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import java.io.File
import java.io.FileNotFoundException

class MainActivity : ComponentActivity() {
    private val container: AppContainer by lazy(LazyThreadSafetyMode.NONE) {
        AppContainer(applicationContext)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    MdvaultApp(container)
                }
            }
        }
    }
}

private enum class Screen {
    VaultSetup,
    Home,
    Reader,
    Editor,
    Spike,
}

private sealed interface VaultState {
    data object Loading : VaultState
    data class Ready(val treeUri: Uri?) : VaultState
}

@Composable
private fun MdvaultApp(container: AppContainer) {
    val vaultState by container.vaultRepository.vaultTreeUri
        .map<Uri?, VaultState> { VaultState.Ready(it) }
        .collectAsState(initial = VaultState.Loading)
    var screen by remember { mutableStateOf<Screen?>(null) }
    var directoryBackStack by remember { mutableStateOf(listOf("")) }
    var editorPath by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(vaultState) {
        val ready = vaultState as? VaultState.Ready ?: return@LaunchedEffect
        if (screen == null) {
            screen = if (ready.treeUri == null) Screen.VaultSetup else Screen.Home
        }
        if (ready.treeUri == null) {
            screen = Screen.VaultSetup
            directoryBackStack = listOf("")
            editorPath = null
        }
    }

    when (val state = vaultState) {
        VaultState.Loading -> LoadingScreen()
        is VaultState.Ready -> when (screen ?: if (state.treeUri == null) Screen.VaultSetup else Screen.Home) {
            Screen.VaultSetup -> VaultSetupScreen(
                vaultRepository = container.vaultRepository,
                vaultTreeUri = state.treeUri,
                onVaultSelected = {
                    directoryBackStack = listOf("")
                    editorPath = null
                    screen = Screen.Home
                },
            )
            Screen.Home -> HomeScreen(
                vaultTreeUri = state.treeUri,
                container = container,
                currentDirectory = directoryBackStack.last(),
                canNavigateUp = directoryBackStack.size > 1,
                onNavigateUp = {
                    if (directoryBackStack.size > 1) {
                        directoryBackStack = directoryBackStack.dropLast(1)
                    }
                },
                onOpenDirectory = { path ->
                    directoryBackStack = directoryBackStack + path
                },
                onOpenFile = { path ->
                    editorPath = path
                    screen = Screen.Reader
                },
                onOpenVaultSetup = { screen = Screen.VaultSetup },
                onOpenSpike = { screen = Screen.Spike },
            )
            Screen.Reader -> {
                val path = editorPath
                if (path == null) {
                    LoadingScreen()
                } else {
                    MarkdownReaderScreen(
                        vaultRepository = container.vaultRepository,
                        markdownEngine = container.markdownEngine,
                        docxExporter = container.vaultDocxExporter,
                        relativePath = path,
                        onEdit = { screen = Screen.Editor },
                        onBack = { screen = Screen.Home },
                        onOpenNote = { notePath -> editorPath = notePath },
                    )
                }
            }
            Screen.Editor -> {
                val path = editorPath
                if (path == null) {
                    LoadingScreen()
                } else {
                    EditorShellScreen(
                        vaultRepository = container.vaultRepository,
                        relativePath = path,
                        onBack = { screen = Screen.Reader },
                    )
                }
            }
            Screen.Spike -> SpikeHome(
                container = container,
                onBackToHome = { screen = Screen.Home },
            )
        }
    }
}

@Composable
private fun LoadingScreen() {
    Column(
        modifier = Modifier.fillMaxSize().padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text(
            text = "볼트 상태 확인 중…",
            style = MaterialTheme.typography.bodyLarge,
        )
    }
}

@Composable
private fun HomeScreen(
    vaultTreeUri: Uri?,
    container: AppContainer,
    currentDirectory: String,
    canNavigateUp: Boolean,
    onNavigateUp: () -> Unit,
    onOpenDirectory: (String) -> Unit,
    onOpenFile: (String) -> Unit,
    onOpenVaultSetup: () -> Unit,
    onOpenSpike: () -> Unit,
) {
    if (vaultTreeUri == null) {
        LoadingScreen()
        return
    }
    FileListScreen(
        vaultRepository = container.vaultRepository,
        docxToMarkdownImporter = container.docxToMarkdownImporter,
        currentDirectory = currentDirectory,
        canNavigateUp = canNavigateUp,
        onNavigateUp = onNavigateUp,
        onOpenDirectory = onOpenDirectory,
        onOpenFile = onOpenFile,
        onOpenVaultSetup = onOpenVaultSetup,
        onOpenSpike = onOpenSpike,
    )
}

/** spike 하네스 홈: S1 import 측정 / S5 에디터 판정 전환. */
@Composable
private fun SpikeHome(
    container: AppContainer,
    onBackToHome: () -> Unit,
) {
    var screen by remember { mutableStateOf("s1") }
    val editorPort = remember { ComposeEditorPort(s5KoreanSample()) }

    Column(modifier = Modifier.fillMaxSize()) {
        Row(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Button(onClick = onBackToHome) { Text("Home") }
            Button(onClick = { screen = "s1" }) { Text("S1") }
            Button(onClick = { screen = "s5" }) { Text("S5") }
            Button(onClick = { screen = "s4" }) { Text("S4") }
        }
        when (screen) {
            "s1" -> S1SpikeScreen(container)
            "s4" -> S4PerfScreen(container)
            else -> MarkdownEditorScreen(editorPort)
        }
    }
}

/**
 * S4 spike: 실제 vault의 SAF 목록 조회 시간 측정 (목표 500ms 이하).
 * 측정 대상 폴더 perf/에 파일 200개를 만들어 두고 실행한다
 * (instrumentation 자체 provider는 API 35에서 직접 접근이 차단되어 이 방식 사용).
 */
@Composable
private fun S4PerfScreen(container: AppContainer) {
    var status by remember { mutableStateOf("vault의 perf/ 폴더 목록 조회 5회를 측정합니다") }
    val scope = rememberCoroutineScope()

    Column(
        modifier = Modifier.fillMaxSize().padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text(text = status, style = MaterialTheme.typography.bodyLarge)
        Button(onClick = {
            status = "측정 중…"
            scope.launch(Dispatchers.IO) {
                status = try {
                    val timings = (1..5).map {
                        val startedAt = System.currentTimeMillis()
                        val count = container.vaultRepository.list("perf").size
                        val elapsed = System.currentTimeMillis() - startedAt
                        elapsed to count
                    }
                    val counts = timings.map { it.second }.distinct()
                    "S4 결과 (${counts}개 항목): " +
                        timings.joinToString(", ") { "${it.first}ms" }
                } catch (e: Exception) {
                    "S4 측정 실패: ${e.message ?: e.javaClass.simpleName}"
                }
            }
        }) {
            Text("S4 목록 측정")
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
