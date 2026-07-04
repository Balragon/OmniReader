package dev.gold.mdvault.preview

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.util.Log
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
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
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
import dev.gold.mdvault.document.VaultDocxExporter
import dev.gold.mdvault.markdown.MarkdownEngine
import dev.gold.mdvault.settings.ReaderSettingsRepository
import dev.gold.mdvault.storage.BoundedTextRead
import dev.gold.mdvault.storage.VaultError
import dev.gold.mdvault.storage.VaultRepository
import dev.gold.mdvault.storage.readTextBounded
import dev.gold.mdvault.storage.vaultDocumentSize
import dev.gold.mdvault.ui.VaultErrorRecoveryButton
import dev.gold.mdvault.ui.VaultErrorUi
import dev.gold.mdvault.ui.toVaultErrorUi
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import java.io.ByteArrayInputStream
import kotlin.math.roundToInt

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
    readerSettingsRepository: ReaderSettingsRepository,
    relativePath: String,
    onEdit: () -> Unit,
    onBack: () -> Unit,
    onOpenNote: (String) -> Unit,
    onOpenVaultSetup: (() -> Unit)? = null,
) {
    var html by remember(relativePath) { mutableStateOf<String?>(null) }
    var error by remember(relativePath) { mutableStateOf<VaultErrorUi?>(null) }
    var notice by remember(relativePath) { mutableStateOf<String?>(null) }
    var noticeRecovery by remember(relativePath) { mutableStateOf<VaultErrorUi?>(null) }
    val scope = rememberCoroutineScope()
    val fontScalePercent by readerSettingsRepository.fontScalePercent.collectAsState(initial = 100)

    LaunchedEffect(relativePath) {
        withContext(Dispatchers.IO) {
            error = null
            noticeRecovery = null
            try {
                val markdown = vaultRepository.readMarkdownPreview(relativePath)
                notice = markdown.truncationNotice()
                html = if (markdown.text.isBlank()) {
                    PreviewHtmlBuilder.build(
                        "<p style=\"opacity:0.6\">빈 문서입니다 — 오른쪽 위 \"편집\"을 눌러 작성하세요.</p>",
                    )
                } else {
                    PreviewHtmlBuilder.build(markdownEngine.toHtml(markdown.text))
                }
            } catch (e: VaultError) {
                Log.w(TAG, "Failed to read markdown preview", e)
                error = e.toVaultErrorUi()
            } catch (e: Exception) {
                error = VaultErrorUi(e.message ?: e.javaClass.simpleName)
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
                scope.launch {
                    readerSettingsRepository.setFontScalePercent(
                        nextReaderFontScalePercent(fontScalePercent),
                    )
                }
            }) { Text("Aa") }
            TextButton(onClick = {
                notice = "DOCX 내보내는 중…"
                noticeRecovery = null
                scope.launch(Dispatchers.IO) {
                    notice = try {
                        val result = docxExporter.export(relativePath)
                        "내보내기 완료: ${result.relativePath} (경고 ${result.warningCount}건)"
                    } catch (e: VaultError) {
                        Log.w(TAG, "Failed to export DOCX", e)
                        val uiError = e.toVaultErrorUi()
                        noticeRecovery = uiError
                        "내보내기 실패: ${uiError.message}"
                    } catch (e: Exception) {
                        "내보내기 실패: ${e.message ?: e.javaClass.simpleName}"
                    }
                }
            }) { Text("DOCX") }
            TextButton(onClick = onEdit) { Text("편집") }
        }
        notice?.let {
            Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 2.dp)) {
                Text(
                    text = it,
                    style = MaterialTheme.typography.bodySmall,
                )
                noticeRecovery?.let { uiError ->
                    VaultErrorRecoveryButton(
                        error = uiError,
                        onOpenVaultSetup = onOpenVaultSetup,
                        onBackToList = onBack,
                    )
                }
            }
        }
        when {
            error != null -> Column(modifier = Modifier.padding(24.dp)) {
                Text(text = "문서를 열 수 없습니다: ${error!!.message}")
                VaultErrorRecoveryButton(
                    error = error!!,
                    onOpenVaultSetup = onOpenVaultSetup,
                    onBackToList = onBack,
                )
            }
            html == null -> Text(text = "여는 중…", modifier = Modifier.padding(24.dp))
            else -> VaultWebView(
                html = html!!,
                baseDirectory = relativePath.substringBeforeLast('/', ""),
                vaultRepository = vaultRepository,
                readerSettingsRepository = readerSettingsRepository,
                documentKey = relativePath,
                fontScalePercent = fontScalePercent,
                onOpenNote = onOpenNote,
            )
        }
    }
}

private suspend fun VaultRepository.readMarkdownPreview(relativePath: String): BoundedTextRead {
    val size = vaultDocumentSize(relativePath)
    return read(relativePath) { input ->
        input.readTextBounded(TEXT_PREVIEW_MAX_BYTES, size)
    }
}

private fun BoundedTextRead.truncationNotice(): String? =
    if (truncated) LARGE_TEXT_NOTICE else null

@Composable
private fun VaultWebView(
    html: String,
    baseDirectory: String,
    vaultRepository: VaultRepository,
    readerSettingsRepository: ReaderSettingsRepository,
    documentKey: String,
    fontScalePercent: Int,
    onOpenNote: (String) -> Unit,
) {
    val context = LocalContext.current
    var webView by remember(documentKey, html) { mutableStateOf<WebView?>(null) }
    var pageFinished by remember(documentKey, html) { mutableStateOf(false) }
    var restored by remember(documentKey, html) { mutableStateOf(false) }
    var restoreRatio by remember(documentKey, html) { mutableStateOf<Float?>(null) }

    LaunchedEffect(readerSettingsRepository, documentKey, html) {
        restoreRatio = withContext(Dispatchers.IO) {
            readerSettingsRepository.readingPosition(documentKey).webReadingRatioOrNull()
        }
    }

    LaunchedEffect(pageFinished, restoreRatio, webView) {
        val view = webView
        val ratio = restoreRatio
        if (!restored && pageFinished && view != null && ratio != null) {
            restored = true
            if (ratio >= WEB_RESTORE_MIN_RATIO) {
                view.post {
                    view.scrollTo(0, (webContentHeightPx(view) * ratio).roundToInt())
                }
            }
        }
    }

    DisposableEffect(documentKey, html) {
        onDispose {
            webView?.let { view ->
                saveWebReadingPositionAsync(readerSettingsRepository, documentKey, view)
            }
        }
    }

    key(documentKey, html) {
        AndroidView(
            modifier = Modifier.fillMaxSize(),
            factory = { ctx ->
                WebView(ctx).apply {
                    settings.javaScriptEnabled = false
                    settings.blockNetworkLoads = true
                    settings.allowFileAccess = false
                    settings.allowContentAccess = false
                    settings.textZoom = fontScalePercent
                    webViewClient = DocumentWebViewClient(
                        context = context,
                        onOpenNote = onOpenNote,
                        loadAsset = { path ->
                            runCatching {
                                // WebView IO 스레드에서 호출됨 — blocking 안전. 최근 문서 오염 방지.
                                runBlocking {
                                    vaultRepository.read(path, trackRecent = false) { it.readBytes() }
                                }
                            }.getOrNull()
                        },
                        onPageFinished = { view ->
                            webView = view
                            pageFinished = true
                        },
                    )
                    webView = this
                    loadDataWithBaseURL(vaultBaseUrl(baseDirectory), html, "text/html", "utf-8", null)
                }
            },
            update = { view ->
                webView = view
                if (view.settings.textZoom != fontScalePercent) {
                    view.settings.textZoom = fontScalePercent
                }
            },
        )
    }
}

internal const val VAULT_HOST = "vault.local"

internal fun vaultBaseUrl(baseDirectory: String): String {
    val builder = Uri.Builder().scheme("https").authority(VAULT_HOST)
    baseDirectory.split('/').filter { it.isNotBlank() }.forEach { builder.appendPath(it) }
    val url = builder.build().toString()
    return if (url.endsWith("/")) url else "$url/"
}

private const val TEXT_PREVIEW_MAX_BYTES = 4 * 1024 * 1024
private const val LARGE_TEXT_NOTICE = "파일이 너무 커서 앞부분만 표시합니다"
private const val TAG = "MarkdownReaderScreen"

/**
 * 문서 WebView 공통 클라이언트. vault.local 요청은 loadAsset으로 해석
 * (vault, 캐시 폴더, 단일 URI 등 소스는 호출자가 주입). 외부 링크는 외부
 * 브라우저로, 내부 .md 링크는 onOpenNote로 위임.
 */
internal class DocumentWebViewClient(
    private val context: Context,
    private val loadAsset: (String) -> ByteArray?,
    private val onOpenNote: ((String) -> Unit)? = null,
    private val onPageFinished: ((WebView) -> Unit)? = null,
) : WebViewClient() {

    override fun shouldInterceptRequest(
        view: WebView,
        request: WebResourceRequest,
    ): WebResourceResponse? {
        val url = request.url
        if (url.host != VAULT_HOST) return null
        val assetPath = url.path.orEmpty().trimStart('/')
        if (assetPath.isEmpty()) return null
        val bytes = loadAsset(assetPath) ?: return null
        return WebResourceResponse(mimeTypeFor(assetPath), null, ByteArrayInputStream(bytes))
    }

    override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
        val url = request.url
        if (url.host == VAULT_HOST) {
            val assetPath = url.path.orEmpty().trimStart('/')
            if (assetPath.endsWith(".md", ignoreCase = true)) {
                onOpenNote?.invoke(assetPath)
            }
            return true
        }
        if (url.scheme == "http" || url.scheme == "https") {
            runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, url)) }
        }
        return true // 뷰어는 문서 하나만 표시 — WebView 내 탐색 금지
    }

    override fun onPageFinished(view: WebView, url: String?) {
        super.onPageFinished(view, url)
        onPageFinished?.invoke(view)
    }

    private fun mimeTypeFor(path: String): String =
        when (path.substringAfterLast('.').lowercase()) {
            "png" -> "image/png"
            "jpg", "jpeg" -> "image/jpeg"
            "gif" -> "image/gif"
            "webp" -> "image/webp"
            "bmp" -> "image/bmp"
            "svg" -> "image/svg+xml"
            "md", "txt" -> "text/plain"
            else -> "application/octet-stream"
        }
}

internal fun nextReaderFontScalePercent(current: Int): Int {
    val currentIndex = READER_FONT_SCALE_STEPS.indexOf(current)
    if (currentIndex >= 0) {
        return READER_FONT_SCALE_STEPS[(currentIndex + 1) % READER_FONT_SCALE_STEPS.size]
    }
    return READER_FONT_SCALE_STEPS.firstOrNull { it > current } ?: READER_FONT_SCALE_STEPS.first()
}

internal fun String?.webReadingRatioOrNull(): Float? =
    this?.takeIf { it.startsWith("web:") }
        ?.substringAfter("web:")
        ?.toFloatOrNull()
        ?.takeIf { it.isFinite() }
        ?.coerceIn(0f, 1f)

internal fun saveWebReadingPositionAsync(
    readerSettingsRepository: ReaderSettingsRepository,
    documentKey: String,
    webView: WebView,
) {
    val ratio = (webView.scrollY.toFloat() / webContentHeightPx(webView).toFloat())
        .coerceIn(0f, 1f)
    CoroutineScope(Dispatchers.IO).launch {
        runCatching {
            readerSettingsRepository.saveReadingPosition(documentKey, "web:$ratio")
        }
    }
}

internal fun webContentHeightPx(webView: WebView): Int =
    (webView.contentHeight * webView.resources.displayMetrics.density)
        .roundToInt()
        .coerceAtLeast(1)

internal const val WEB_RESTORE_MIN_RATIO = 0.01f
private val READER_FONT_SCALE_STEPS = listOf(85, 100, 115, 130, 150)
