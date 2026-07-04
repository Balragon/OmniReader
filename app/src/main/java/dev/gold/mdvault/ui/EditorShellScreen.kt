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
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.gold.mdvault.editor.ComposeEditorPort
import dev.gold.mdvault.editor.MarkdownEditorScreen
import dev.gold.mdvault.storage.VaultError
import dev.gold.mdvault.storage.VaultRepository
import dev.gold.mdvault.storage.readTextBounded
import dev.gold.mdvault.storage.vaultDocumentSize
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.security.MessageDigest

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EditorShellScreen(
    vaultRepository: VaultRepository,
    relativePath: String,
    onBack: () -> Unit,
    onDeleted: () -> Unit,
    onOpenVaultSetup: (() -> Unit)? = null,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val draftFile = remember(context.cacheDir, relativePath) {
        editorDraftFile(context.cacheDir, relativePath)
    }
    var editorPort by remember(relativePath) { mutableStateOf<ComposeEditorPort?>(null) }
    var savedVaultText by remember(relativePath) { mutableStateOf<String?>(null) }
    var status by remember(relativePath) { mutableStateOf<String?>(null) }
    var statusRecovery by remember(relativePath) { mutableStateOf<VaultErrorUi?>(null) }
    var isSaving by remember(relativePath) { mutableStateOf(false) }
    var isDeleting by remember(relativePath) { mutableStateOf(false) }
    var showDeleteDialog by remember(relativePath) { mutableStateOf(false) }
    var draftPrompt by remember(relativePath) { mutableStateOf<DraftPrompt?>(null) }
    val currentSavedVaultText by rememberUpdatedState(savedVaultText)

    fun save(afterSave: (() -> Unit)? = null) {
        val port = editorPort ?: run {
            afterSave?.invoke()
            return
        }
        val textToSave = port.text
        scope.launch {
            isSaving = true
            status = "저장 중..."
            statusRecovery = null
            try {
                withContext(Dispatchers.IO) {
                    vaultRepository.write(relativePath) { output ->
                        output.write(textToSave.toByteArray(Charsets.UTF_8))
                    }
                    draftFile.delete()
                }
                savedVaultText = textToSave
                status = "저장됨"
                statusRecovery = null
                afterSave?.invoke()
            } catch (e: VaultError) {
                Log.w(TAG, "Failed to save vault document", e)
                val uiError = e.toVaultErrorUi()
                status = "저장 실패: ${uiError.message}"
                statusRecovery = uiError
            } catch (e: Exception) {
                status = "저장 실패: ${e.message ?: e.javaClass.simpleName}"
                statusRecovery = null
            } finally {
                isSaving = false
            }
        }
    }

    fun deleteNote() {
        scope.launch {
            isDeleting = true
            status = "삭제 중..."
            statusRecovery = null
            try {
                withContext(Dispatchers.IO) {
                    vaultRepository.delete(relativePath)
                    draftFile.delete()
                }
                status = null
                statusRecovery = null
                showDeleteDialog = false
                onDeleted()
            } catch (e: VaultError) {
                Log.w(TAG, "Failed to delete vault document", e)
                val uiError = e.toVaultErrorUi()
                status = "삭제 실패: ${uiError.message}"
                statusRecovery = uiError
            } catch (e: Exception) {
                status = "삭제 실패: ${e.message ?: e.javaClass.simpleName}"
                statusRecovery = null
            } finally {
                isDeleting = false
            }
        }
    }

    LaunchedEffect(relativePath) {
        editorPort = null
        savedVaultText = null
        draftPrompt = null
        status = "불러오는 중..."
        statusRecovery = null
        try {
            val loaded = withContext(Dispatchers.IO) {
                loadEditorState(vaultRepository, relativePath, draftFile)
            }
            savedVaultText = loaded.vaultText
            editorPort = ComposeEditorPort(loaded.vaultText)
            draftPrompt = loaded.draftText?.let(::DraftPrompt)
            status = null
            statusRecovery = null
        } catch (e: VaultError) {
            Log.w(TAG, "Failed to load editor state", e)
            val uiError = e.toVaultErrorUi()
            status = "파일을 불러오지 못했습니다: ${uiError.message}"
            statusRecovery = uiError
        } catch (e: Exception) {
            status = "파일을 불러오지 못했습니다: ${e.message ?: e.javaClass.simpleName}"
            statusRecovery = null
        }
    }

    LaunchedEffect(editorPort, draftFile) {
        val port = editorPort ?: return@LaunchedEffect
        port.autosaveDraftAfterIdle(
            draftFile = draftFile,
            shouldKeepDraft = { text -> text != currentSavedVaultText },
            onError = { e ->
                if (e is VaultError) {
                    Log.w(TAG, "Failed to autosave editor draft", e)
                    val uiError = e.toVaultErrorUi()
                    status = "임시 저장 실패: ${uiError.message}"
                    statusRecovery = uiError
                } else {
                    status = "임시 저장 실패: ${e.message ?: e.javaClass.simpleName}"
                    statusRecovery = null
                }
            },
        )
    }

    BackHandler(enabled = !isSaving && !isDeleting) {
        save(onBack)
    }

    draftPrompt?.let { prompt ->
        AlertDialog(
            onDismissRequest = {},
            title = { Text("임시 저장본 복원") },
            text = { Text("저장되지 않은 임시 저장본이 있습니다. 복원할까요?") },
            confirmButton = {
                TextButton(
                    onClick = {
                        editorPort = ComposeEditorPort(prompt.text)
                        draftPrompt = null
                        status = "임시 저장본을 복원했습니다"
                    },
                ) {
                    Text("임시 저장본 복원")
                }
            },
            dismissButton = {
                TextButton(
                    onClick = {
                        scope.launch {
                            withContext(Dispatchers.IO) {
                                draftFile.delete()
                            }
                            draftPrompt = null
                            status = null
                        }
                    },
                ) {
                    Text("무시")
                }
            },
        )
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
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(contentPadding)
                    .padding(24.dp),
            ) {
                Text(
                    text = status ?: "불러오는 중...",
                    style = MaterialTheme.typography.bodyLarge,
                )
                statusRecovery?.let { uiError ->
                    VaultErrorRecoveryButton(
                        error = uiError,
                        onOpenVaultSetup = onOpenVaultSetup,
                        onBackToList = onDeleted,
                    )
                }
            }
        } else {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(contentPadding),
            ) {
                status?.let { message ->
                    Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
                        Text(
                            text = message,
                            style = MaterialTheme.typography.bodyMedium,
                        )
                        statusRecovery?.let { uiError ->
                            VaultErrorRecoveryButton(
                                error = uiError,
                                onOpenVaultSetup = onOpenVaultSetup,
                                onBackToList = onDeleted,
                            )
                        }
                    }
                }
                MarkdownEditorScreen(
                    port = port,
                    modifier = Modifier.weight(1f),
                )
            }
        }
    }
}

private data class EditorLoadState(
    val vaultText: String,
    val draftText: String?,
)

private data class DraftPrompt(
    val text: String,
)

private suspend fun loadEditorState(
    vaultRepository: VaultRepository,
    relativePath: String,
    draftFile: File,
): EditorLoadState {
    val size = vaultRepository.vaultDocumentSize(relativePath)
    if (size != null && size > EDITOR_MAX_BYTES) {
        throw IllegalArgumentException(EDITOR_TOO_LARGE_MESSAGE)
    }
    val vaultRead = vaultRepository.read(relativePath) { input ->
        input.readTextBounded(EDITOR_MAX_BYTES, size)
    }
    if (vaultRead.truncated) {
        throw IllegalArgumentException(EDITOR_TOO_LARGE_MESSAGE)
    }
    val vaultText = vaultRead.text
    val vaultLastModified = vaultFileLastModified(vaultRepository, relativePath)
    val draftText = readRestorableDraft(draftFile, vaultText, vaultLastModified)
    return EditorLoadState(vaultText = vaultText, draftText = draftText)
}

private suspend fun vaultFileLastModified(
    vaultRepository: VaultRepository,
    relativePath: String,
): Long? {
    val parentPath = relativePath.substringBeforeLast('/', missingDelimiterValue = "")
    val fileName = relativePath.substringAfterLast('/')
    return vaultRepository.list(parentPath)
        .firstOrNull { it.displayName == fileName }
        ?.lastModified
}

private fun readRestorableDraft(
    draftFile: File,
    vaultText: String,
    vaultLastModified: Long?,
): String? {
    if (!draftFile.isFile) return null
    if (draftFile.length() > EDITOR_MAX_BYTES) return null
    val draftIsNewer = vaultLastModified == null ||
        vaultLastModified <= 0L ||
        draftFile.lastModified() > vaultLastModified
    if (!draftIsNewer) return null
    val draftText = draftFile.readText(Charsets.UTF_8)
    return draftText.takeIf { it != vaultText }
}

private fun editorDraftFile(cacheDir: File, relativePath: String): File =
    File(File(cacheDir, "drafts"), "${relativePath.sha12()}.md")

private fun String.sha12(): String {
    val digest = MessageDigest.getInstance("SHA-256").digest(toByteArray(Charsets.UTF_8))
    return digest.take(6).joinToString("") { byte -> "%02x".format(byte.toInt() and 0xff) }
}

private const val EDITOR_MAX_BYTES = 2 * 1024 * 1024
private const val EDITOR_TOO_LARGE_MESSAGE = "파일이 너무 커서 편집할 수 없습니다 (2MB 초과)"
private const val TAG = "EditorShellScreen"
