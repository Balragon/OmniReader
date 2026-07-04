package dev.gold.mdvault.preview

import android.app.Activity
import android.content.ContentResolver
import android.content.Context
import android.content.ContextWrapper
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color as AndroidColor
import android.net.Uri
import android.os.RemoteException
import android.provider.OpenableColumns
import android.util.Log
import android.webkit.WebView
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.rememberTransformableState
import androidx.compose.foundation.gestures.transformable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import dev.gold.mdvault.document.DocumentKind
import dev.gold.mdvault.document.DocumentTypeDetector
import dev.gold.mdvault.document.DocxToMarkdownImporter
import dev.gold.mdvault.markdown.MarkdownEngine
import dev.gold.mdvault.storage.BoundedTextRead
import dev.gold.mdvault.storage.RecentFilesRepository
import dev.gold.mdvault.storage.VaultError
import dev.gold.mdvault.storage.openableSize
import dev.gold.mdvault.storage.readTextBounded
import dev.gold.mdvault.ui.VaultErrorRecoveryButton
import dev.gold.mdvault.ui.VaultErrorUi
import dev.gold.mdvault.ui.toVaultErrorUi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileNotFoundException
import java.security.MessageDigest
import kotlin.math.roundToInt

/**
 * 단일 파일 뷰어 — 앱의 핵심 동선. "내 파일" 등에서 md/txt/docx/html/pdf/이미지를
 * 탭하면 이 화면이 바로 열린다 (볼트 설정 불필요).
 *
 * - md/txt: 즉시 렌더링 (원본은 읽기만)
 * - docx: 캐시에 즉석 변환 후 렌더링, "MD 저장"으로 원할 때만 저장
 * - html: JS 비활성 WebView (오프라인 — 원격 리소스 로드 안 함)
 * - 이미지: 네이티브 몰입형 화면맞춤 표시 (핀치 줌)
 * - pdf: PdfRenderer 페이지 뷰
 */
@Composable
fun SingleDocumentViewerScreen(
    uri: Uri,
    markdownEngine: MarkdownEngine,
    docxImporter: DocxToMarkdownImporter,
    recentFiles: RecentFilesRepository,
    onBack: () -> Unit,
    onOpenVaultSetup: (() -> Unit)? = null,
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
                notice = loaded.notice
                runCatching { recentFiles.record(uri, name, kind.name) }
                loaded.state
            } catch (e: VaultError) {
                Log.w(TAG, "Failed to open document", e)
                ViewerState.Error(e.toVaultErrorUi())
            } catch (e: SecurityException) {
                Log.w(TAG, "Permission lost while opening document", e)
                ViewerState.Error(VaultError.PermissionLost().toVaultErrorUi())
            } catch (e: FileNotFoundException) {
                Log.w(TAG, "Document missing while opening document", e)
                ViewerState.Error(VaultError.DocumentMissing(displayName).toVaultErrorUi())
            } catch (e: RemoteException) {
                Log.w(TAG, "Provider unavailable while opening document", e)
                ViewerState.Error(VaultError.ProviderUnavailable().toVaultErrorUi())
            } catch (e: IllegalStateException) {
                Log.w(TAG, "Provider unavailable while opening document", e)
                ViewerState.Error(VaultError.ProviderUnavailable().toVaultErrorUi())
            } catch (e: Exception) {
                ViewerState.Error(VaultErrorUi(e.message ?: e.javaClass.simpleName))
            }
        }
    }

    val currentState = state
    if (currentState is ViewerState.Image) {
        MediaViewerScaffold(displayName = displayName, onBack = onBack) {
            FullscreenImageContent(
                uri = currentState.uri,
                displayName = displayName,
                onBack = onBack,
                onOpenVaultSetup = onOpenVaultSetup,
            )
        }
        return
    }
    if (currentState is ViewerState.Pdf) {
        MediaViewerScaffold(displayName = displayName, onBack = onBack) {
            PdfPagesView(
                uri = uri,
                modifier = Modifier.fillMaxSize(),
                onBack = onBack,
                onOpenVaultSetup = onOpenVaultSetup,
            )
        }
        return
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
        when (currentState) {
            ViewerState.Loading -> Text("여는 중…", modifier = Modifier.padding(24.dp))
            is ViewerState.Error -> Column(modifier = Modifier.padding(24.dp)) {
                Text(text = "문서를 열 수 없습니다: ${currentState.error.message}")
                VaultErrorRecoveryButton(
                    error = currentState.error,
                    onOpenVaultSetup = onOpenVaultSetup,
                    onBackToList = onBack,
                )
            }
            is ViewerState.Image -> Unit
            is ViewerState.Pdf -> Unit
            is ViewerState.Web -> DocumentWebViewer(
                state = currentState,
                modifier = Modifier.fillMaxSize(),
            )
        }
    }
}

@Composable
private fun MediaViewerScaffold(
    displayName: String,
    onBack: () -> Unit,
    content: @Composable () -> Unit,
) {
    val context = LocalContext.current
    val view = LocalView.current
    val window = remember(context, view) { context.findActivity()?.window }
    var chromeVisible by remember { mutableStateOf(true) }
    BackHandler(onBack = onBack)

    DisposableEffect(window, view) {
        var previousStatusBarColor: Int? = null
        var previousNavigationBarColor: Int? = null
        var previousLightStatusBars: Boolean? = null
        var previousLightNavigationBars: Boolean? = null
        if (window != null) {
            previousStatusBarColor = window.statusBarColor
            previousNavigationBarColor = window.navigationBarColor
            WindowCompat.setDecorFitsSystemWindows(window, false)
            WindowCompat.getInsetsController(window, view).apply {
                previousLightStatusBars = isAppearanceLightStatusBars
                previousLightNavigationBars = isAppearanceLightNavigationBars
                isAppearanceLightStatusBars = false
                isAppearanceLightNavigationBars = false
                systemBarsBehavior =
                    WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
                show(WindowInsetsCompat.Type.systemBars())
            }
            window.statusBarColor = AndroidColor.TRANSPARENT
            window.navigationBarColor = AndroidColor.TRANSPARENT
        }
        onDispose {
            if (window != null) {
                WindowCompat.getInsetsController(window, view).apply {
                    show(WindowInsetsCompat.Type.systemBars())
                    previousLightStatusBars?.let { isAppearanceLightStatusBars = it }
                    previousLightNavigationBars?.let { isAppearanceLightNavigationBars = it }
                }
                previousStatusBarColor?.let { window.statusBarColor = it }
                previousNavigationBarColor?.let { window.navigationBarColor = it }
                WindowCompat.setDecorFitsSystemWindows(window, true)
            }
        }
    }

    LaunchedEffect(chromeVisible, window, view) {
        if (window != null) {
            val controller = WindowCompat.getInsetsController(window, view)
            if (chromeVisible) {
                controller.show(WindowInsetsCompat.Type.systemBars())
            } else {
                controller.hide(WindowInsetsCompat.Type.systemBars())
            }
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black)
            .pointerInput(Unit) {
                detectTapGestures(onTap = { chromeVisible = !chromeVisible })
            },
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Black),
        ) {
            content()
        }

        AnimatedVisibility(
            visible = chromeVisible,
            enter = fadeIn() + slideInVertically(initialOffsetY = { -it / 2 }),
            exit = fadeOut() + slideOutVertically(targetOffsetY = { -it / 2 }),
            modifier = Modifier.align(Alignment.TopCenter),
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(Color.Black.copy(alpha = 0.86f))
                    .statusBarsPadding()
                    .padding(horizontal = 8.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                TextButton(onClick = onBack) {
                    Text("←", color = Color.White)
                }
                Text(
                    text = displayName,
                    color = Color.White,
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.weight(1f).padding(horizontal = 8.dp),
                    maxLines = 1,
                )
            }
        }
    }
}

@Composable
private fun FullscreenImageContent(
    uri: Uri,
    displayName: String,
    onBack: () -> Unit,
    onOpenVaultSetup: (() -> Unit)?,
) {
    val context = LocalContext.current
    val density = LocalDensity.current
    var image by remember(uri) { mutableStateOf<ImageBitmap?>(null) }
    var error by remember(uri) { mutableStateOf<VaultErrorUi?>(null) }
    var scale by remember(uri) { mutableStateOf(1f) }
    var offsetX by remember(uri) { mutableStateOf(0f) }
    var offsetY by remember(uri) { mutableStateOf(0f) }
    val transformableState = rememberTransformableState { zoomChange, panChange, _ ->
        val nextScale = (scale * zoomChange).coerceIn(1f, 6f)
        if (nextScale == 1f) {
            offsetX = 0f
            offsetY = 0f
        } else {
            offsetX += panChange.x
            offsetY += panChange.y
        }
        scale = nextScale
    }

    BoxWithConstraints(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black)
            .transformable(transformableState),
    ) {
        val targetWidthPx = with(density) { maxWidth.toPx().roundToInt() }
            .coerceAtLeast(1) * IMAGE_SCREEN_MULTIPLIER
        val targetHeightPx = with(density) { maxHeight.toPx().roundToInt() }
            .coerceAtLeast(1) * IMAGE_SCREEN_MULTIPLIER

        LaunchedEffect(uri, targetWidthPx, targetHeightPx) {
            image = null
            error = null
            try {
                image = withContext(Dispatchers.IO) {
                    decodeSampledBitmap(context.contentResolver, uri, targetWidthPx, targetHeightPx)
                        .asImageBitmap()
                }
            } catch (e: SecurityException) {
                Log.w(TAG, "Permission lost while opening image", e)
                error = VaultError.PermissionLost().toVaultErrorUi()
            } catch (e: FileNotFoundException) {
                Log.w(TAG, "Image document missing", e)
                error = VaultError.DocumentMissing(displayName).toVaultErrorUi()
            } catch (e: RemoteException) {
                Log.w(TAG, "Provider unavailable while opening image", e)
                error = VaultError.ProviderUnavailable().toVaultErrorUi()
            } catch (e: IllegalStateException) {
                Log.w(TAG, "Provider unavailable while opening image", e)
                error = VaultError.ProviderUnavailable().toVaultErrorUi()
            } catch (e: Exception) {
                error = VaultErrorUi(e.message ?: e.javaClass.simpleName)
            }
        }

        when {
            error != null -> Column(
                modifier = Modifier
                    .align(Alignment.Center)
                    .padding(24.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text(
                    text = "이미지를 열 수 없습니다: ${error!!.message}",
                    color = Color.White,
                )
                VaultErrorRecoveryButton(
                    error = error!!,
                    onOpenVaultSetup = onOpenVaultSetup,
                    onBackToList = onBack,
                )
            }
            image == null -> Text(
                text = "여는 중…",
                color = Color.White,
                modifier = Modifier
                    .align(Alignment.Center)
                    .padding(24.dp),
            )
            else -> Image(
                bitmap = image!!,
                contentDescription = displayName,
                contentScale = ContentScale.Fit,
                modifier = Modifier
                    .fillMaxSize()
                    .graphicsLayer(
                        scaleX = scale,
                        scaleY = scale,
                        translationX = offsetX,
                        translationY = offsetY,
                    ),
            )
        }
    }
}

@Composable
private fun DocumentWebViewer(
    state: ViewerState.Web,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    key(state.html) {
        AndroidView(
            modifier = modifier,
            factory = { ctx ->
                WebView(ctx).apply {
                    settings.javaScriptEnabled = false
                    settings.blockNetworkLoads = true
                    settings.allowFileAccess = false
                    settings.allowContentAccess = false
                    if (state.enableZoom) {
                        settings.useWideViewPort = true
                        settings.loadWithOverviewMode = true
                        settings.setSupportZoom(true)
                        settings.builtInZoomControls = true
                        settings.displayZoomControls = false
                    }
                    webViewClient = DocumentWebViewClient(
                        context = context,
                        loadAsset = state.loadAsset,
                    )
                    loadDataWithBaseURL(vaultBaseUrl(""), state.html, "text/html", "utf-8", null)
                }
            },
        )
    }
}

private sealed interface ViewerState {
    data object Loading : ViewerState
    data class Error(val error: VaultErrorUi) : ViewerState
    data class Image(val uri: Uri) : ViewerState
    data object Pdf : ViewerState
    data class Web(
        val html: String,
        val loadAsset: (String) -> ByteArray?,
        val savableMarkdown: String? = null,
        val enableZoom: Boolean = false,
    ) : ViewerState
}

private data class LoadedViewerDocument(
    val state: ViewerState,
    val notice: String? = null,
)

private fun loadDocument(
    kind: DocumentKind,
    displayName: String,
    uri: Uri,
    resolver: ContentResolver,
    markdownEngine: MarkdownEngine,
    docxImporter: DocxToMarkdownImporter,
    cacheDir: File,
): LoadedViewerDocument = when (kind) {
    DocumentKind.PDF -> LoadedViewerDocument(ViewerState.Pdf)

    DocumentKind.MARKDOWN -> {
        val markdown = resolver.readTextPreview(uri)
        // 단일 문서 권한이라 옆의 상대경로 이미지는 접근 불가 — placeholder로 남는다
        LoadedViewerDocument(
            state = ViewerState.Web(PreviewHtmlBuilder.build(markdownEngine.toHtml(markdown.text)), { null }),
            notice = markdown.truncationNotice(),
        )
    }

    DocumentKind.PLAIN_TEXT -> {
        val text = resolver.readTextPreview(uri)
        LoadedViewerDocument(
            state = ViewerState.Web(PreviewHtmlBuilder.build("<pre>${text.text.escapeHtml()}</pre>"), { null }),
            notice = text.truncationNotice(),
        )
    }

    DocumentKind.HTML -> {
        // JS 비활성 + 네트워크 차단 상태로 원본 그대로 표시 (스타일 보존)
        val html = resolver.readTextPreview(uri)
        LoadedViewerDocument(
            state = ViewerState.Web(html.text, { null }),
            notice = html.truncationNotice(),
        )
    }

    DocumentKind.IMAGE -> {
        LoadedViewerDocument(ViewerState.Image(uri))
    }

    DocumentKind.DOCX -> {
        val size = resolver.openableSize(uri)
        if (size != null && size > DOCX_IMPORT_MAX_BYTES) {
            LoadedViewerDocument(
                ViewerState.Error(VaultErrorUi("DOCX 파일이 너무 커서 변환할 수 없습니다 (50MB 초과)")),
            )
        } else {
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
            LoadedViewerDocument(
                ViewerState.Web(
                    html = PreviewHtmlBuilder.build(markdownEngine.toHtml(imported.markdown)),
                    loadAsset = { relativePath ->
                        File(assetRoot, relativePath).takeIf { it.isFile }?.readBytes()
                    },
                    savableMarkdown = imported.markdown,
                ),
            )
        }
    }

    DocumentKind.UNSUPPORTED ->
        LoadedViewerDocument(ViewerState.Error(VaultErrorUi("지원하지 않는 형식입니다: $displayName")))
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

private fun ContentResolver.readTextPreview(uri: Uri): BoundedTextRead =
    openInputStream(uri)?.use { it.readTextBounded(TEXT_PREVIEW_MAX_BYTES, openableSize(uri)) }
        ?: throw FileNotFoundException("$uri")

private fun BoundedTextRead.truncationNotice(): String? =
    if (truncated) LARGE_TEXT_NOTICE else null

private fun decodeSampledBitmap(
    resolver: ContentResolver,
    uri: Uri,
    targetWidthPx: Int,
    targetHeightPx: Int,
): Bitmap {
    // 주의: inJustDecodeBounds 모드의 decodeStream은 성공해도 null을 반환하므로
    // use{}의 반환값에 elvis를 걸면 안 된다 (스트림 null 체크와 분리할 것).
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    val boundsStream = resolver.openInputStream(uri) ?: throw FileNotFoundException("$uri")
    boundsStream.use { input ->
        BitmapFactory.decodeStream(input, null, bounds)
    }
    if (bounds.outWidth <= 0 || bounds.outHeight <= 0) {
        throw IllegalArgumentException("이미지 정보를 읽을 수 없습니다")
    }

    val options = BitmapFactory.Options().apply {
        inSampleSize = calculateInSampleSize(bounds.outWidth, bounds.outHeight, targetWidthPx, targetHeightPx)
        inPreferredConfig = Bitmap.Config.ARGB_8888
    }
    val decodeInput = resolver.openInputStream(uri) ?: throw FileNotFoundException("$uri")
    return decodeInput.use { input ->
        BitmapFactory.decodeStream(input, null, options)
    } ?: throw IllegalArgumentException("이미지를 디코딩할 수 없습니다")
}

private fun calculateInSampleSize(
    width: Int,
    height: Int,
    targetWidth: Int,
    targetHeight: Int,
): Int {
    var sampleSize = 1
    while (width / sampleSize > targetWidth || height / sampleSize > targetHeight) {
        sampleSize *= 2
    }
    return sampleSize
}

private fun String.escapeHtml(): String =
    replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")

private tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}

private const val TEXT_PREVIEW_MAX_BYTES = 4 * 1024 * 1024
private const val DOCX_IMPORT_MAX_BYTES = 50L * 1024L * 1024L
private const val IMAGE_SCREEN_MULTIPLIER = 2
private const val LARGE_TEXT_NOTICE = "파일이 너무 커서 앞부분만 표시합니다"
private const val TAG = "SingleDocumentViewer"
