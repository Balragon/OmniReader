package dev.gold.mdvault.preview

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import dev.gold.mdvault.settings.ReaderSettingsRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.io.InputStream
import kotlin.math.roundToInt

internal const val VAULT_HOST = "vault.local"

internal fun vaultBaseUrl(baseDirectory: String): String {
    val builder = Uri.Builder().scheme("https").authority(VAULT_HOST)
    baseDirectory.split('/').filter { it.isNotBlank() }.forEach { builder.appendPath(it) }
    val url = builder.build().toString()
    return if (url.endsWith("/")) url else "$url/"
}

/**
 * 문서 WebView 공통 클라이언트. vault.local 요청은 loadAsset으로 해석
 * (캐시 폴더, 단일 URI 등 소스는 호출자가 주입). 외부 링크는 외부
 * 브라우저로 보내고, WebView 안 탐색은 막는다.
 */
internal class DocumentWebViewClient(
    private val context: Context,
    private val loadAsset: (String) -> InputStream?,
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
        val stream = loadAsset(assetPath) ?: return null
        return WebResourceResponse(mimeTypeFor(assetPath), null, stream)
    }

    override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
        val url = request.url
        if (url.host == VAULT_HOST) {
            return true
        }
        if (
            shouldLaunchExternalNavigation(
                scheme = url.scheme,
                isForMainFrame = request.isForMainFrame,
                hasGesture = request.hasGesture(),
            )
        ) {
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

internal fun shouldLaunchExternalNavigation(
    scheme: String?,
    isForMainFrame: Boolean,
    hasGesture: Boolean,
): Boolean =
    isForMainFrame && hasGesture &&
        (scheme.equals("http", ignoreCase = true) || scheme.equals("https", ignoreCase = true))

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
