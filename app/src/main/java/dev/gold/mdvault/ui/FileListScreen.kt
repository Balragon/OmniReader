package dev.gold.mdvault.ui

import android.content.ContentResolver
import android.net.Uri
import android.provider.DocumentsContract
import android.provider.OpenableColumns
import android.util.Log
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
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
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.gold.mdvault.document.DocumentKind
import dev.gold.mdvault.document.DocumentTypeDetector
import dev.gold.mdvault.document.DocxToMarkdownImporter
import dev.gold.mdvault.storage.SafDocument
import dev.gold.mdvault.storage.VaultError
import dev.gold.mdvault.storage.VaultRepository
import dev.gold.mdvault.storage.openableSize
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import java.io.FileNotFoundException

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FileListScreen(
    vaultRepository: VaultRepository,
    docxToMarkdownImporter: DocxToMarkdownImporter,
    currentDirectory: String,
    canNavigateUp: Boolean,
    onNavigateUp: () -> Unit,
    onOpenDirectory: (String) -> Unit,
    onOpenFile: (String) -> Unit,
    onEditFile: (String) -> Unit,
    onOpenDocument: (Uri) -> Unit,
    onOpenVaultSetup: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var refreshKey by remember { mutableIntStateOf(0) }
    var entries by remember(currentDirectory, refreshKey) { mutableStateOf<List<SafDocument>?>(null) }
    var error by remember(currentDirectory, refreshKey) { mutableStateOf<VaultErrorUi?>(null) }
    var status by remember { mutableStateOf<String?>(null) }
    var statusRecovery by remember { mutableStateOf<VaultErrorUi?>(null) }
    var pendingDelete by remember { mutableStateOf<DeleteTarget?>(null) }
    var isDeleting by remember { mutableStateOf(false) }
    val listState = rememberLazyListState()

    fun refresh() {
        refreshKey += 1
    }

    fun createNote() {
        scope.launch {
            status = "새 노트 생성 중..."
            statusRecovery = null
            try {
                val path = withContext(Dispatchers.IO) {
                    val existingNames = vaultRepository.list(currentDirectory)
                        .map { it.displayName }
                        .toSet()
                    val fileName = firstFreeNoteName(existingNames)
                    val newPath = joinVaultPath(currentDirectory, fileName)
                    vaultRepository.create(newPath, MARKDOWN_MIME_TYPE) { output ->
                        output.write(ByteArray(0))
                    }
                    newPath
                }
                status = null
                statusRecovery = null
                refresh()
                // 새 노트는 빈 문서 — 읽기 화면(검정 화면)이 아니라 바로 편집기로
                onEditFile(path)
            } catch (e: VaultError) {
                Log.w(TAG, "Failed to create note", e)
                val uiError = e.toVaultErrorUi()
                status = "새 노트 생성 실패: ${uiError.message}"
                statusRecovery = uiError
            } catch (e: Exception) {
                status = "새 노트 생성 실패: ${e.userMessage()}"
                statusRecovery = null
            }
        }
    }

    fun deleteNote(target: DeleteTarget) {
        scope.launch {
            isDeleting = true
            status = "삭제 중..."
            statusRecovery = null
            try {
                withContext(Dispatchers.IO) {
                    vaultRepository.delete(target.path)
                }
                pendingDelete = null
                status = "삭제했습니다: ${target.displayName}"
                statusRecovery = null
                refresh()
            } catch (e: VaultError) {
                Log.w(TAG, "Failed to delete note", e)
                val uiError = e.toVaultErrorUi()
                status = "삭제 실패: ${uiError.message}"
                statusRecovery = uiError
            } catch (e: Exception) {
                status = "삭제 실패: ${e.userMessage()}"
                statusRecovery = null
            } finally {
                isDeleting = false
            }
        }
    }

    val docxPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            status = "DOCX 가져오는 중..."
            statusRecovery = null
            try {
                val result = withContext(Dispatchers.IO) {
                    importDocxIntoVault(
                        contentResolver = context.contentResolver,
                        sourceUri = uri,
                        currentDirectory = currentDirectory,
                        vaultRepository = vaultRepository,
                        docxToMarkdownImporter = docxToMarkdownImporter,
                    )
                }
                status = "가져오기 완료: ${result.markdownPath} (경고 ${result.warningCount}건)"
                statusRecovery = null
                refresh()
            } catch (e: VaultError) {
                Log.w(TAG, "Failed to import DOCX into vault", e)
                val uiError = e.toVaultErrorUi()
                status = "DOCX 가져오기 실패: ${uiError.message}"
                statusRecovery = uiError
            } catch (e: Exception) {
                status = "DOCX 가져오기 실패: ${e.userMessage()}"
                statusRecovery = null
            }
        }
    }

    LaunchedEffect(currentDirectory, refreshKey) {
        entries = null
        error = null
        try {
            entries = withContext(Dispatchers.IO) {
                vaultRepository.list(currentDirectory)
                    .filter { it.isDirectory || it.isViewerSupported() }
                    .sortedWith(
                        compareBy<SafDocument> { !it.isDirectory }
                            .thenBy { it.displayName.lowercase() },
                    )
            }
        } catch (e: VaultError) {
            Log.w(TAG, "Failed to list vault directory", e)
            error = e.toVaultErrorUi()
        } catch (e: Exception) {
            error = VaultErrorUi(e.userMessage())
        }
    }

    BackHandler(enabled = canNavigateUp) {
        onNavigateUp()
    }

    pendingDelete?.let { target ->
        AlertDialog(
            onDismissRequest = { if (!isDeleting) pendingDelete = null },
            title = { Text("노트 삭제") },
            text = { Text("${target.displayName} 파일을 삭제할까요? 이 작업은 되돌릴 수 없습니다.") },
            confirmButton = {
                TextButton(
                    onClick = { deleteNote(target) },
                    enabled = !isDeleting,
                ) {
                    Text("삭제")
                }
            },
            dismissButton = {
                TextButton(
                    onClick = { pendingDelete = null },
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
                        text = if (currentDirectory.isBlank()) "내 폴더" else currentDirectory,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                },
                navigationIcon = {
                    if (canNavigateUp) {
                        TextButton(onClick = onNavigateUp) {
                            Text("뒤로")
                        }
                    }
                },
                actions = {
                    TextButton(onClick = ::createNote) {
                        Text("새 노트")
                    }
                    TextButton(onClick = onOpenVaultSetup) {
                        Text("폴더 변경")
                    }
                    TextButton(
                        onClick = {
                            docxPicker.launch(
                                arrayOf(
                                    "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
                                    "application/octet-stream",
                                ),
                            )
                        },
                    ) {
                        Text("DOCX 가져오기")
                    }
                },
            )
        },
    ) { contentPadding ->
        LazyColumn(
            state = listState,
            modifier = Modifier
                .fillMaxSize()
                .padding(contentPadding),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            status?.let { message ->
                item {
                    Column {
                        Text(
                            text = message,
                            style = MaterialTheme.typography.bodyMedium,
                        )
                        statusRecovery?.let { uiError ->
                            VaultErrorRecoveryButton(
                                error = uiError,
                                onOpenVaultSetup = onOpenVaultSetup,
                                onBackToList = if (canNavigateUp) onNavigateUp else null,
                            )
                        }
                    }
                }
            }
            when {
                error != null -> item {
                    Column {
                        Text(
                            text = "목록을 불러오지 못했습니다: ${error!!.message}",
                            style = MaterialTheme.typography.bodyLarge,
                        )
                        VaultErrorRecoveryButton(
                            error = error!!,
                            onOpenVaultSetup = onOpenVaultSetup,
                            onBackToList = if (canNavigateUp) onNavigateUp else null,
                        )
                    }
                }
                entries == null -> item {
                    Text(
                        text = "목록 불러오는 중...",
                        style = MaterialTheme.typography.bodyLarge,
                    )
                }
                entries!!.isEmpty() -> item {
                    Text(
                        text = "표시할 파일이나 폴더가 없습니다.",
                        style = MaterialTheme.typography.bodyLarge,
                    )
                }
                else -> items(entries!!, key = { it.documentId }) { document ->
                    val path = joinVaultPath(currentDirectory, document.displayName)
                    VaultEntryRow(
                        document = document,
                        onClick = {
                            if (document.isDirectory) {
                                onOpenDirectory(path)
                            } else if (document.kind() == DocumentKind.MARKDOWN) {
                                onOpenFile(path)
                            } else {
                                onOpenDocument(document.uri)
                            }
                        },
                        onDelete = if (!document.isDirectory && document.kind() == DocumentKind.MARKDOWN) {
                            {
                                pendingDelete = DeleteTarget(
                                    path = path,
                                    displayName = document.displayName,
                                )
                            }
                        } else {
                            null
                        },
                    )
                }
            }
        }
    }
}

@Composable
private fun VaultEntryRow(
    document: SafDocument,
    onClick: () -> Unit,
    onDelete: (() -> Unit)?,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 4.dp, vertical = 12.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = document.label(),
            style = MaterialTheme.typography.labelLarge,
        )
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = document.displayName,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                style = MaterialTheme.typography.bodyLarge,
            )
            if (!document.isDirectory && document.size != null) {
                Text(
                    text = "${document.size} bytes",
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }
        if (onDelete != null) {
            TextButton(onClick = onDelete) {
                Text("삭제")
            }
        }
    }
}

private fun SafDocument.kind(): DocumentKind =
    DocumentTypeDetector.detect(displayName, mimeType)

private fun SafDocument.isViewerSupported(): Boolean =
    kind() != DocumentKind.UNSUPPORTED

private fun SafDocument.label(): String =
    if (isDirectory) {
        "폴더"
    } else {
        when (kind()) {
            DocumentKind.MARKDOWN -> "MD"
            DocumentKind.PLAIN_TEXT -> "TXT"
            DocumentKind.DOCX -> "DOCX"
            DocumentKind.HTML -> "HTML"
            DocumentKind.PDF -> "PDF"
            DocumentKind.IMAGE -> "이미지"
            DocumentKind.UNSUPPORTED -> "파일"
        }
    }

private data class ImportResult(
    val markdownPath: String,
    val warningCount: Int,
)

private data class DeleteTarget(
    val path: String,
    val displayName: String,
)

private data class MediaDirectory(
    val existingNames: MutableSet<String>,
)

private suspend fun importDocxIntoVault(
    contentResolver: ContentResolver,
    sourceUri: Uri,
    currentDirectory: String,
    vaultRepository: VaultRepository,
    docxToMarkdownImporter: DocxToMarkdownImporter,
): ImportResult {
    val displayName = contentResolver.displayName(sourceUri)
    val size = contentResolver.openableSize(sourceUri)
    if (size != null && size > DOCX_IMPORT_MAX_BYTES) {
        throw IllegalArgumentException("DOCX 파일이 너무 커서 변환할 수 없습니다 (50MB 초과)")
    }
    val markdownName = "${displayName.docxBaseName().sanitizeVaultFileName()}.md"
    val mediaDirectory = ensureMediaDirectory(vaultRepository, currentDirectory)
    val input = contentResolver.openInputStream(sourceUri)
        ?: throw FileNotFoundException(sourceUri.toString())

    val imported = input.use { stream ->
        docxToMarkdownImporter.import(stream) { relativePath, contentType, bytes ->
            val assetPath = joinVaultPath(currentDirectory, relativePath)
            val assetName = relativePath.substringAfterLast('/')
            runBlocking(Dispatchers.IO) {
                if (assetName in mediaDirectory.existingNames) {
                    vaultRepository.write(assetPath) { output ->
                        output.write(bytes)
                    }
                } else {
                    vaultRepository.create(assetPath, contentType.ifBlank { BINARY_MIME_TYPE }) { output ->
                        output.write(bytes)
                    }
                    mediaDirectory.existingNames += assetName
                }
            }
        }
    }

    // 기존 노트를 절대 덮어쓰지 않는다 (데이터 안전성 최우선) — 충돌 시 -2, -3 접미사
    val existingNames = vaultRepository.list(currentDirectory)
        .map { it.displayName }
        .toSet()
    val finalName = firstFreeImportName(markdownName, existingNames)
    val finalPath = joinVaultPath(currentDirectory, finalName)
    vaultRepository.create(finalPath, MARKDOWN_MIME_TYPE) { output ->
        output.write(imported.markdown.toByteArray(Charsets.UTF_8))
    }

    return ImportResult(
        markdownPath = finalPath,
        warningCount = imported.warnings.size,
    )
}

private suspend fun ensureMediaDirectory(
    vaultRepository: VaultRepository,
    currentDirectory: String,
): MediaDirectory {
    val mediaPath = joinVaultPath(currentDirectory, MEDIA_DIRECTORY_NAME)
    val media = vaultRepository.list(currentDirectory)
        .firstOrNull { it.displayName == MEDIA_DIRECTORY_NAME }
    if (media == null) {
        vaultRepository.create(mediaPath, DocumentsContract.Document.MIME_TYPE_DIR)
    } else {
        require(media.isDirectory) { "media 항목이 폴더가 아닙니다: $mediaPath" }
    }
    val existingNames = vaultRepository.list(mediaPath)
        .map { it.displayName }
        .toMutableSet()
    return MediaDirectory(existingNames)
}

private fun firstFreeImportName(preferredName: String, existingNames: Set<String>): String {
    if (preferredName !in existingNames) return preferredName
    val base = preferredName.removeSuffix(".md")
    var index = 2
    while (true) {
        val candidate = "$base-$index.md"
        if (candidate !in existingNames) return candidate
        index += 1
    }
}

private fun firstFreeNoteName(existingNames: Set<String>): String {
    var index = 1
    while (true) {
        val candidate = "note-$index.md"
        if (candidate !in existingNames) return candidate
        index += 1
    }
}

private fun ContentResolver.displayName(uri: Uri): String {
    query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
        if (cursor.moveToFirst()) {
            val index = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
            if (index >= 0 && !cursor.isNull(index)) {
                return cursor.getString(index)
            }
        }
    }
    return uri.lastPathSegment?.substringAfterLast('/') ?: "imported-docx"
}

private fun String.docxBaseName(): String =
    if (endsWith(".docx", ignoreCase = true)) {
        dropLast(".docx".length)
    } else {
        substringBeforeLast('.', missingDelimiterValue = this)
    }.ifBlank { "imported-docx" }

private fun String.sanitizeVaultFileName(): String =
    replace('/', '_')
        .replace('\\', '_')
        .trim()
        .ifBlank { "imported-docx" }

internal fun joinVaultPath(parent: String, child: String): String =
    listOf(parent, child)
        .flatMap { it.split('/') }
        .filter { it.isNotBlank() }
        .joinToString("/")

private fun Exception.userMessage(): String =
    if (this is VaultError) {
        toVaultErrorUi().message
    } else {
        message ?: javaClass.simpleName
    }

private const val TAG = "FileListScreen"
private const val MEDIA_DIRECTORY_NAME = "media"
private const val MARKDOWN_MIME_TYPE = "text/markdown"
private const val BINARY_MIME_TYPE = "application/octet-stream"
private const val DOCX_IMPORT_MAX_BYTES = 50L * 1024L * 1024L
