package dev.gold.mdvault.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.gold.mdvault.editor.ComposeEditorPort
import dev.gold.mdvault.editor.MarkdownEditorScreen
import dev.gold.mdvault.storage.VaultRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EditorShellScreen(
    vaultRepository: VaultRepository,
    relativePath: String,
    onBack: () -> Unit,
    onDeleted: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val scope = rememberCoroutineScope()
    var editorPort by remember(relativePath) { mutableStateOf<ComposeEditorPort?>(null) }
    var status by remember(relativePath) { mutableStateOf<String?>(null) }
    var isSaving by remember(relativePath) { mutableStateOf(false) }
    var isDeleting by remember(relativePath) { mutableStateOf(false) }
    var showDeleteDialog by remember(relativePath) { mutableStateOf(false) }

    fun save(afterSave: (() -> Unit)? = null) {
        val port = editorPort ?: run {
            afterSave?.invoke()
            return
        }
        scope.launch {
            isSaving = true
            status = "저장 중..."
            try {
                withContext(Dispatchers.IO) {
                    vaultRepository.write(relativePath) { output ->
                        output.write(port.text.toByteArray(Charsets.UTF_8))
                    }
                }
                status = "저장됨"
                afterSave?.invoke()
            } catch (e: Exception) {
                status = "저장 실패: ${e.message ?: e.javaClass.simpleName}"
            } finally {
                isSaving = false
            }
        }
    }

    fun deleteNote() {
        scope.launch {
            isDeleting = true
            status = "삭제 중..."
            try {
                withContext(Dispatchers.IO) {
                    vaultRepository.delete(relativePath)
                }
                status = null
                showDeleteDialog = false
                onDeleted()
            } catch (e: Exception) {
                status = "삭제 실패: ${e.message ?: e.javaClass.simpleName}"
            } finally {
                isDeleting = false
            }
        }
    }

    LaunchedEffect(relativePath) {
        editorPort = null
        status = "불러오는 중..."
        try {
            val text = withContext(Dispatchers.IO) {
                vaultRepository.read(relativePath) { input ->
                    String(input.readBytes(), Charsets.UTF_8)
                }
            }
            editorPort = ComposeEditorPort(text)
            status = null
        } catch (e: Exception) {
            status = "파일을 불러오지 못했습니다: ${e.message ?: e.javaClass.simpleName}"
        }
    }

    BackHandler(enabled = !isSaving && !isDeleting) {
        save(onBack)
    }

    if (showDeleteDialog) {
        AlertDialog(
            onDismissRequest = { if (!isDeleting) showDeleteDialog = false },
            title = { Text("노트 삭제") },
            text = {
                Text("${relativePath.substringAfterLast('/')} 파일을 삭제할까요? 이 작업은 되돌릴 수 없습니다.")
            },
            confirmButton = {
                TextButton(
                    onClick = ::deleteNote,
                    enabled = !isDeleting,
                ) {
                    Text("삭제")
                }
            },
            dismissButton = {
                TextButton(
                    onClick = { showDeleteDialog = false },
                    enabled = !isDeleting,
                ) {
                    Text("취소")
                }
            },
        )
    }

    Scaffold(
        modifier = modifier,
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        text = relativePath,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                },
                navigationIcon = {
                    TextButton(
                        onClick = { save(onBack) },
                        enabled = !isSaving && !isDeleting,
                    ) {
                        Text("뒤로")
                    }
                },
                actions = {
                    TextButton(
                        onClick = { showDeleteDialog = true },
                        enabled = editorPort != null && !isSaving && !isDeleting,
                    ) {
                        Text("삭제")
                    }
                    TextButton(
                        onClick = { save() },
                        enabled = editorPort != null && !isSaving && !isDeleting,
                    ) {
                        Text("저장")
                    }
                },
            )
        },
    ) { contentPadding ->
        val port = editorPort
        if (port == null) {
            Text(
                text = status ?: "불러오는 중...",
                modifier = Modifier
                    .fillMaxSize()
                    .padding(contentPadding)
                    .padding(24.dp),
                style = MaterialTheme.typography.bodyLarge,
            )
        } else {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(contentPadding),
            ) {
                status?.let { message ->
                    Text(
                        text = message,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
                MarkdownEditorScreen(
                    port = port,
                    modifier = Modifier.weight(1f),
                )
            }
        }
    }
}
