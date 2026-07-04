package dev.gold.mdvault.preview

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
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
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.runtime.rememberCoroutineScope
import dev.gold.mdvault.document.VaultDocxExporter
import dev.gold.mdvault.markdown.MarkdownEngine
import dev.gold.mdvault.storage.VaultRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import java.io.ByteArrayInputStream

/**
 * 읽기 화면 — 앱의 핵심 동선. Markdown을 렌더링해 보여주고,
 * 편집은 명시적 "편집" 버튼으로만 진입한다.
 *
 * 보안/오프라인 원칙: JS 비활성, 네트워크 로드 차단(오프라인 동작 우선),
 * 이미지는 vault 안 상대경로만 shouldInterceptRequest로 SAF에서 읽어 공급.
 * 외부 링크는 외부 브라우저로, vault 내부 .md 링크는 reader 내 이동.
 */
@Composable
fun MarkdownReaderScreen(
    vaultRepository: VaultRepository,
    markdownEngine: MarkdownEngine,
    docxExporter: VaultDocxExporter,
    relativePath: String,
    onEdit: () -> Unit,
    onBack: () -> Unit,
    onOpenNote: (String) -> Unit,
) {
    var html by remember(relativePath) { mutableStateOf<String?>(null) }
    var error by remember(relativePath) { mutableStateOf<String?>(null) }
    var notice by remember(relativePath) { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()

    LaunchedEffect(relativePath) {
        withContext(Dispatchers.IO) {
            try {
                val markdown = vaultRepository.read(relativePath) { input ->
                    input.readBytes().decodeToString()
                }
                html = PreviewHtmlBuilder.build(markdownEngine.toHtml(markdown))
            } catch (e: Exception) {
                error = e.message ?: e.javaClass.simpleName
            }
        }
    }

    Column(modifier = Modifier.fillMaxSize()) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            TextButton(onClick = onBack) { Text("← 목록") }
            Text(
                text = relativePath.substringAfterLast('/'),
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.weight(1f).padding(horizontal = 8.dp),
                maxLines = 1,
            )
            TextButton(onClick = {
                notice = "DOCX 내보내는 중…"
                scope.launch(Dispatchers.IO) {
                    notice = try {
                        val result = docxExporter.export(relativePath)
                        "내보내기 완료: ${result.relativePath} (경고 ${result.warningCount}건)"
                    } catch (e: Exception) {
                        "내보내기 실패: ${e.message ?: e.javaClass.simpleName}"
                    }
                }
            }) { Text("DOCX") }
            TextButton(onClick = onEdit) { Text("편집") }
        }
        notice?.let {
            Text(
                text = it,
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 2.dp),
            )
        }
        when {
            error != null -> Text(
                text = "문서를 열 수 없습니다: $error",
                modifier = Modifier.padding(24.dp),
            )
            html == null -> Text(text = "여는 중…", modifier = Modifier.padding(24.dp))
            else -> VaultWebView(
                html = html!!,
                baseDirectory = relativePath.substringBeforeLast('/', ""),
                vaultRepository = vaultRepository,
                onOpenNote = onOpenNote,
            )
        }
    }
}

@Composable
private fun VaultWebView(
    html: String,
    baseDirectory: String,
    vaultRepository: VaultRepository,
    onOpenNote: (String) -> Unit,
) {
    val context = LocalContext.current
    key(html) {
        AndroidView(
            modifier = Modifier.fillMaxSize(),
            factory = { ctx ->
                WebView(ctx).apply {
                    settings.javaScriptEnabled = false
                    settings.blockNetworkLoads = true
                    settings.allowFileAccess = false
                    settings.allowContentAccess = false
                    webViewClient = VaultWebViewClient(vaultRepository, context, onOpenNote)
                    loadDataWithBaseURL(vaultBaseUrl(baseDirectory), html, "text/html", "utf-8", null)
                }
            },
        )
    }
}

private const val VAULT_HOST = "vault.local"

private fun vaultBaseUrl(baseDirectory: String): String {
    val builder = Uri.Builder().scheme("https").authority(VAULT_HOST)
    baseDirectory.split('/').filter { it.isNotBlank() }.forEach { builder.appendPath(it) }
    val url = builder.build().toString()
    return if (url.endsWith("/")) url else "$url/"
}

private class VaultWebViewClient(
    private val vaultRepository: VaultRepository,
    private val context: Context,
    private val onOpenNote: (String) -> Unit,
) : WebViewClient() {

    override fun shouldInterceptRequest(
        view: WebView,
        request: WebResourceRequest,
    ): WebResourceResponse? {
        val url = request.url
        if (url.host != VAULT_HOST) return null
        val vaultPath = url.path.orEmpty().trimStart('/')
        if (vaultPath.isEmpty()) return null
        return try {
            // WebView IO 스레드에서 호출됨 — blocking 안전. 최근 문서 오염 방지.
            val bytes = runBlocking {
                vaultRepository.read(vaultPath, trackRecent = false) { it.readBytes() }
            }
            WebResourceResponse(mimeTypeFor(vaultPath), null, ByteArrayInputStream(bytes))
        } catch (e: Exception) {
            null
        }
    }

    override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
        val url = request.url
        if (url.host == VAULT_HOST) {
            val vaultPath = url.path.orEmpty().trimStart('/')
            if (vaultPath.endsWith(".md", ignoreCase = true)) {
                onOpenNote(vaultPath)
            }
            return true
        }
        if (url.scheme == "http" || url.scheme == "https") {
            runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, url)) }
        }
        return true // reader는 문서 하나만 표시 — WebView 내 탐색 금지
    }

    private fun mimeTypeFor(path: String): String =
        when (path.substringAfterLast('.').lowercase()) {
            "png" -> "image/png"
            "jpg", "jpeg" -> "image/jpeg"
            "gif" -> "image/gif"
            "webp" -> "image/webp"
            "svg" -> "image/svg+xml"
            "md", "txt" -> "text/plain"
            else -> "application/octet-stream"
        }
}
