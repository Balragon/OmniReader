package dev.gold.mdvault.preview

import android.content.ContentResolver
import android.net.Uri
import android.provider.OpenableColumns
import android.webkit.WebView
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import dev.gold.mdvault.document.DocumentKind
import dev.gold.mdvault.document.DocumentTypeDetector
import dev.gold.mdvault.document.DocxToMarkdownImporter
import dev.gold.mdvault.markdown.MarkdownEngine
import dev.gold.mdvault.storage.RecentFilesRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileNotFoundException
import java.security.MessageDigest

/**
 * 단일 파일 뷰어 — 앱의 핵심 동선. "내 파일" 등에서 md/txt/docx/html/pdf/이미지를
 * 탭하면 이 화면이 바로 열린다 (볼트 설정 불필요).
 *
 * - md/txt: 즉시 렌더링 (원본은 읽기만)
 * - docx: 캐시에 즉석 변환 후 렌더링, "MD 저장"으로 원할 때만 저장
 * - html: JS 비활성 WebView (오프라인 — 원격 리소스 로드 안 함)
 * - 이미지: WebView 표시 (핀치 줌)
 * - pdf: PdfRenderer 페이지 뷰
 */
@Composable
fun SingleDocumentViewerScreen(
    uri: Uri,
    markdownEngine: MarkdownEngine,
    docxImporter: DocxToMarkdownImporter,
    recentFiles: RecentFilesRepository,
    onBack: () -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var state by remember(uri) { mutableStateOf<ViewerState>(ViewerState.Loading) }
    var displayName by remember(uri) { mutableStateOf("문서") }
    var notice by remember(uri) { mutableStateOf<String?>(null) }

    LaunchedEffect(uri) {
        withContext(Dispatchers.IO) {
            state = try {
                val resolver = context.contentResolver
                val name = resolver.displayNameOf(uri) ?: uri.lastPathSegment ?: "문서"
                displayName = name
                val kind = DocumentTypeDetector.detect(name, resolver.getType(uri))
                val loaded = loadDocument(kind, name, uri, resolver, markdownEngine, docxImporter, context.cacheDir)
                runCatching { recentFiles.record(uri, name, kind.name) }
                loaded
            } catch (e: Exception) {
                ViewerState.Error(e.message ?: e.javaClass.simpleName)
            }
        }
    }

    Column(modifier = Modifier.fillMaxSize()) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            TextButton(onClick = onBack) { Text("←") }
            Text(
                text = displayName,
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.weight(1f).padding(horizontal = 8.dp),
                maxLines = 1,
            )
            val docxState = state as? ViewerState.Web
            if (docxState?.savableMarkdown != null) {
                val saveLauncher = rememberLauncherForActivityResult(
                    ActivityResultContracts.CreateDocument("text/markdown"),
                ) { target ->
                    if (target != null) {
                        scope.launch(Dispatchers.IO) {
                            notice = try {
                                context.contentResolver.openOutputStream(target, "wt")!!.use { output ->
                                    output.write(docxState.savableMarkdown.toByteArray(Charsets.UTF_8))
                                }
                                "MD 저장 완료 (이미지는 별도 저장되지 않음)"
                            } catch (e: Exception) {
                                "저장 실패: ${e.message}"
                            }
                        }
                    }
                }
                TextButton(onClick = {
                    saveLauncher.launch(displayName.substringBeforeLast('.') + ".md")
                }) { Text("MD 저장") }
            }
        }
        notice?.let {
            Text(
                text = it,
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 2.dp),
            )
        }
        when (val current = state) {
            ViewerState.Loading -> Text("여는 중…", modifier = Modifier.padding(24.dp))
            is ViewerState.Error -> Text(
                text = "문서를 열 수 없습니다: ${current.message}",
                modifier = Modifier.padding(24.dp),
            )
            is ViewerState.Pdf -> PdfPagesView(uri, modifier = Modifier.fillMaxSize())
            is ViewerState.Web -> key(current.html) {
                AndroidView(
                    modifier = Modifier.fillMaxSize(),
                    factory = { ctx ->
                        WebView(ctx).apply {
                            settings.javaScriptEnabled = false
                            settings.blockNetworkLoads = true
                            settings.allowFileAccess = false
                            settings.allowContentAccess = false
                            if (current.enableZoom) {
                                settings.setSupportZoom(true)
                                settings.builtInZoomControls = true
                                settings.displayZoomControls = false
                            }
                            webViewClient = DocumentWebViewClient(
                                context = context,
                                loadAsset = current.loadAsset,
                            )
                            loadDataWithBaseURL(vaultBaseUrl(""), current.html, "text/html", "utf-8", null)
                        }
                    },
                )
            }
        }
    }
}

private sealed interface ViewerState {
    data object Loading : ViewerState
    data class Error(val message: String) : ViewerState
    data object Pdf : ViewerState
    data class Web(
        val html: String,
        val loadAsset: (String) -> ByteArray?,
        val savableMarkdown: String? = null,
        val enableZoom: Boolean = false,
    ) : ViewerState
}

private fun loadDocument(
    kind: DocumentKind,
    displayName: String,
    uri: Uri,
    resolver: ContentResolver,
    markdownEngine: MarkdownEngine,
    docxImporter: DocxToMarkdownImporter,
    cacheDir: File,
): ViewerState = when (kind) {
    DocumentKind.PDF -> ViewerState.Pdf

    DocumentKind.MARKDOWN -> {
        val markdown = resolver.readText(uri)
        // 단일 문서 권한이라 옆의 상대경로 이미지는 접근 불가 — placeholder로 남는다
        ViewerState.Web(PreviewHtmlBuilder.build(markdownEngine.toHtml(markdown)), { null })
    }

    DocumentKind.PLAIN_TEXT -> {
        val text = resolver.readText(uri)
        ViewerState.Web(PreviewHtmlBuilder.build("<pre>${text.escapeHtml()}</pre>"), { null })
    }

    DocumentKind.HTML -> {
        // JS 비활성 + 네트워크 차단 상태로 원본 그대로 표시 (스타일 보존)
        ViewerState.Web(resolver.readText(uri), { null })
    }

    DocumentKind.IMAGE -> {
        val extension = displayName.substringAfterLast('.', "png").lowercase()
        val assetName = "image.$extension"
        ViewerState.Web(
            // 갤러리처럼 화면 안에 전체가 들어오게(contain) — 핀치 줌으로 확대.
            // vh/flex는 WebView wide-viewport에서 높이가 0으로 계산될 수 있어
            // position:fixed + object-fit:contain 사용.
            html = "<html><head>" +
                "<meta name=\"viewport\" content=\"width=device-width, initial-scale=1\">" +
                "</head><body style=\"margin:0;background:#111\">" +
                "<img src=\"$assetName\" " +
                "style=\"position:fixed;top:0;left:0;width:100%;height:100%;object-fit:contain\">" +
                "</body></html>",
            loadAsset = { key ->
                if (key == assetName) {
                    runCatching {
                        resolver.openInputStream(uri)?.use { it.readBytes() }
                    }.getOrNull()
                } else {
                    null
                }
            },
            enableZoom = true,
        )
    }

    DocumentKind.DOCX -> {
        val digest = MessageDigest.getInstance("SHA-256")
            .digest(uri.toString().toByteArray())
            .joinToString("") { "%02x".format(it) }
            .take(12)
        val assetRoot = File(cacheDir, "opened/$digest").apply { mkdirs() }
        val input = resolver.openInputStream(uri) ?: throw FileNotFoundException("$uri")
        val imported = input.use { stream ->
            docxImporter.import(stream) { relativePath, _, bytes ->
                val target = File(assetRoot, relativePath)
                target.parentFile?.mkdirs()
                target.writeBytes(bytes)
            }
        }
        ViewerState.Web(
            html = PreviewHtmlBuilder.build(markdownEngine.toHtml(imported.markdown)),
            loadAsset = { relativePath ->
                File(assetRoot, relativePath).takeIf { it.isFile }?.readBytes()
            },
            savableMarkdown = imported.markdown,
        )
    }

    DocumentKind.UNSUPPORTED ->
        ViewerState.Error("지원하지 않는 형식입니다: $displayName")
}

private fun ContentResolver.displayNameOf(uri: Uri): String? {
    query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
        if (cursor.moveToFirst()) {
            val index = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
            if (index >= 0 && !cursor.isNull(index)) return cursor.getString(index)
        }
    }
    return null
}

private fun ContentResolver.readText(uri: Uri): String =
    openInputStream(uri)?.use { it.readBytes().decodeToString() }
        ?: throw FileNotFoundException("$uri")

private fun String.escapeHtml(): String =
    replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
