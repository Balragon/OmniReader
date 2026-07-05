package dev.gold.mdvault.preview

import android.app.Activity
import android.content.ContentResolver
import android.content.Context
import android.content.ContextWrapper
import android.database.Cursor
import android.graphics.Bitmap
import android.graphics.Color as AndroidColor
import android.graphics.ImageDecoder
import android.graphics.drawable.AnimatedImageDrawable
import android.net.Uri
import android.os.RemoteException
import android.provider.DocumentsContract
import android.provider.OpenableColumns
import android.util.Log
import android.webkit.WebView
import android.widget.ImageView
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
import androidx.compose.runtime.collectAsState
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
import dev.gold.mdvault.settings.ReaderSettingsRepository
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
import java.io.IOException
import java.security.MessageDigest
import kotlin.math.roundToInt

/**
 * 단일 파일 뷰어 — 앱의 핵심 동선. "내 파일" 등에서 md/txt/docx/html/pdf/이미지를
 * 탭하면 이 화면이 바로 열린다 (볼트 설정 불필요).
 *
 * - md/txt: 즉시 렌더링 (원본은 읽기만)
 * - docx: 캐시에 즉석 변환 후 렌더링
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
    readerSettingsRepository: ReaderSettingsRepository,
    onBack: () -> Unit,
    onOpenVaultSetup: (() -> Unit)? = null,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val fontScalePercent by readerSettingsRepository.fontScalePercent.collectAsState(initial = 100)
    var state by remember(uri) { mutableStateOf<ViewerState>(ViewerState.Loading) }
    var displayName by remember(uri) { mutableStateOf("문서") }
    var notice by remember(uri) { mutableStateOf<String?>(null) }
    // 읽기 위치 키. URI는 같은 파일이라도 오픈마다 바뀔 수 있어(Downloads provider가
    // raw:↔msf: 문서 ID를 오감) 파일명+크기로 식별한다.
    var documentKey by remember(uri) { mutableStateOf(uri.toString()) }

    LaunchedEffect(uri) {
        withContext(Dispatchers.IO) {
            state = try {
                val resolver = context.contentResolver
                val name = resolver.displayNameOf(uri) ?: uri.lastPathSegment ?: "문서"
                displayName = name
                documentKey = "doc:$name:${resolver.openableSize(uri) ?: -1}"
                val kind = DocumentTypeDetector.detect(name, resolver.getType(uri))
                val loaded = loadDocument(kind, name, uri, resolver, markdownEngine, docxImporter, context.cacheDir)
                notice = loaded.notice
                // 다시 열 수 있는(영구 권한을 가진) 문서만 최근 목록에 남긴다.
                // 파일 앱 탭(VIEW)의 일시적 권한은 재실행 후 소멸하므로 기록하지 않아
                // "권한 만료" 죽은 항목이 쌓이지 않는다.
                if (resolver.hasPersistedReadPermission(uri)) {
                    runCatching { recentFiles.record(uri, name, kind.name) }
                }
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
                readerSettingsRepository = readerSettingsRepository,
                documentKey = documentKey,
                onBack = onBack,
                onOpenVaultSetup = onOpenVaultSetup,
            )
        }
        return
    }

    // 텍스트 문서(md/txt/html/docx)도 이미지/PDF처럼 시스템 뒤로가기를 가로채
    // 상단 ← 와 동일하게 동작시킨다 (미가로채면 앱이 그대로 종료됨).
    BackHandler(onBack = onBack)

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
            val savableMarkdown = docxState?.savableMarkdown
            if (docxState != null) {
                TextButton(onClick = {
                    scope.launch {
                        readerSettingsRepository.setFontScalePercent(
                            nextReaderFontScalePercent(fontScalePercent),
                        )
                    }
                }) { Text("Aa") }
            }
            if (docxState != null && savableMarkdown != null) {
                val markdownSaveLauncher = rememberLauncherForActivityResult(
                    ActivityResultContracts.CreateDocument("text/markdown"),
                ) { target ->
                    if (target != null) {
                        scope.launch(Dispatchers.IO) {
                            notice = try {
                                context.contentResolver.openOutputStream(target, "wt")!!.use { output ->
                                    output.write(savableMarkdown.toByteArray(Charsets.UTF_8))
                                }
                                "MD 저장 완료 (이미지는 별도 저장되지 않음)"
                            } catch (e: Exception) {
                                "저장 실패: ${e.message}"
                            }
                        }
                    }
                }
                val packageSaveLauncher = rememberLauncherForActivityResult(
                    ActivityResultContracts.OpenDocumentTree(),
                ) { targetTree ->
                    if (targetTree != null) {
                        scope.launch(Dispatchers.IO) {
                            notice = try {
                                val result = saveMarkdownWithAssets(
                                    resolver = context.contentResolver,
                                    targetTree = targetTree,
                                    baseName = displayName.exportBaseName(),
                                    markdown = savableMarkdown,
                                    assetRoot = docxState.assetRoot,
                                    assetRelativePaths = docxState.assetRelativePaths,
                                )
                                if (result.savedAssets == result.totalAssets) {
                                    "MD 저장 완료 (이미지 ${result.savedAssets}개 포함)"
                                } else {
                                    "MD 저장 완료 (이미지 ${result.savedAssets}/${result.totalAssets}개 포함)"
                                }
                            } catch (e: Exception) {
                                "저장 실패: ${e.message ?: e.javaClass.simpleName}"
                            }
                        }
                    }
                }
                TextButton(onClick = {
                    if (docxState.assetRelativePaths.isEmpty()) {
                        markdownSaveLauncher.launch(displayName.exportBaseName() + ".md")
                    } else {
                        packageSaveLauncher.launch(null)
                    }
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
                documentKey = documentKey,
                readerSettingsRepository = readerSettingsRepository,
                fontScalePercent = fontScalePercent,
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
    var image by remember(uri) { mutableStateOf<FullscreenImage?>(null) }
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
                    decodeFullscreenImage(
                        resolver = context.contentResolver,
                        uri = uri,
                        displayName = displayName,
                        targetWidthPx = targetWidthPx,
                        targetHeightPx = targetHeightPx,
                    )
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
            } catch (e: ImageDecoder.DecodeException) {
                Log.w(TAG, "Failed to decode image", e)
                error = VaultErrorUi("이미지를 디코딩할 수 없습니다")
            } catch (e: Exception) {
                error = VaultErrorUi(e.message ?: e.javaClass.simpleName)
            }
        }

        val currentImage = image
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
            currentImage == null -> Text(
                text = "여는 중…",
                color = Color.White,
                modifier = Modifier
                    .align(Alignment.Center)
                    .padding(24.dp),
            )
            currentImage is FullscreenImage.Static -> Image(
                bitmap = currentImage.bitmap,
                contentDescription = displayName,
                contentScale = ContentScale.Fit,
                modifier = fullscreenImageModifier(scale, offsetX, offsetY),
            )
            currentImage is FullscreenImage.Animated -> AnimatedFullscreenImage(
                image = currentImage,
                displayName = displayName,
                modifier = fullscreenImageModifier(scale, offsetX, offsetY),
            )
        }
    }
}

@Composable
private fun AnimatedFullscreenImage(
    image: FullscreenImage.Animated,
    displayName: String,
    modifier: Modifier = Modifier,
) {
    DisposableEffect(image.drawable) {
        image.drawable.start()
        onDispose { image.drawable.stop() }
    }

    AndroidView(
        modifier = modifier,
        factory = { ctx ->
            ImageView(ctx).apply {
                setBackgroundColor(AndroidColor.BLACK)
                scaleType = ImageView.ScaleType.FIT_CENTER
                contentDescription = displayName
                setImageDrawable(image.drawable)
                image.drawable.start()
            }
        },
        update = { view ->
            view.contentDescription = displayName
            if (view.drawable !== image.drawable) {
                view.setImageDrawable(image.drawable)
            }
            if (!image.drawable.isRunning) {
                image.drawable.start()
            }
        },
    )
}

private fun fullscreenImageModifier(
    scale: Float,
    offsetX: Float,
    offsetY: Float,
): Modifier = Modifier
    .fillMaxSize()
    .graphicsLayer(
        scaleX = scale,
        scaleY = scale,
        translationX = offsetX,
        translationY = offsetY,
    )

@Composable
private fun DocumentWebViewer(
    state: ViewerState.Web,
    documentKey: String,
    readerSettingsRepository: ReaderSettingsRepository,
    fontScalePercent: Int,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    var webView by remember(documentKey, state.html) { mutableStateOf<WebView?>(null) }
    var pageFinished by remember(documentKey, state.html) { mutableStateOf(false) }
    var restored by remember(documentKey, state.html) { mutableStateOf(false) }
    var restoreRatio by remember(documentKey, state.html) { mutableStateOf<Float?>(null) }

    LaunchedEffect(readerSettingsRepository, documentKey, state.html) {
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

    DisposableEffect(documentKey, state.html) {
        onDispose {
            webView?.let { view ->
                saveWebReadingPositionAsync(readerSettingsRepository, documentKey, view)
            }
        }
    }

    key(documentKey, state.html) {
        AndroidView(
            modifier = modifier,
            factory = { ctx ->
                WebView(ctx).apply {
                    settings.javaScriptEnabled = false
                    settings.blockNetworkLoads = true
                    settings.allowFileAccess = false
                    settings.allowContentAccess = false
                    settings.textZoom = fontScalePercent
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
                        onPageFinished = { view ->
                            webView = view
                            pageFinished = true
                        },
                    )
                    webView = this
                    loadDataWithBaseURL(vaultBaseUrl(""), state.html, "text/html", "utf-8", null)
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

private sealed interface ViewerState {
    data object Loading : ViewerState
    data class Error(val error: VaultErrorUi) : ViewerState
    data class Image(val uri: Uri) : ViewerState
    data object Pdf : ViewerState
    data class Web(
        val html: String,
        val loadAsset: (String) -> ByteArray?,
        val savableMarkdown: String? = null,
        val assetRoot: File? = null,
        val assetRelativePaths: List<String> = emptyList(),
        val enableZoom: Boolean = false,
    ) : ViewerState
}

private data class LoadedViewerDocument(
    val state: ViewerState,
    val notice: String? = null,
)

private sealed interface FullscreenImage {
    data class Static(val bitmap: ImageBitmap) : FullscreenImage
    data class Animated(val drawable: AnimatedImageDrawable) : FullscreenImage
}

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
                    val target = assetRoot.resolveSafeAsset(relativePath)
                    target.parentFile?.mkdirs()
                    target.writeBytes(bytes)
                }
            }
            LoadedViewerDocument(
                ViewerState.Web(
                    html = PreviewHtmlBuilder.build(markdownEngine.toHtml(imported.markdown)),
                    loadAsset = { relativePath ->
                        assetRoot.resolveSafeAssetOrNull(relativePath)?.takeIf { it.isFile }?.readBytes()
                    },
                    savableMarkdown = imported.markdown,
                    assetRoot = assetRoot,
                    assetRelativePaths = imported.assets.map { it.relativePath },
                ),
            )
        }
    }

    DocumentKind.UNSUPPORTED ->
        LoadedViewerDocument(ViewerState.Error(VaultErrorUi("지원하지 않는 형식입니다: $displayName")))
}

private fun File.resolveSafeAsset(relativePath: String): File {
    val target = normalizeAssetRelativePath(relativePath).fold(this) { parent, segment ->
        File(parent, segment)
    }
    val rootPath = canonicalFile.path
    val targetPath = target.canonicalFile.path
    if (targetPath != rootPath && !targetPath.startsWith(rootPath + File.separator)) {
        throw IOException("이미지 경로가 올바르지 않습니다: $relativePath")
    }
    return target
}

private fun File.resolveSafeAssetOrNull(relativePath: String): File? =
    runCatching { resolveSafeAsset(relativePath) }.getOrNull()

private fun normalizeAssetRelativePath(relativePath: String): List<String> {
    val segments = relativePath.split('/').filter { it.isNotBlank() }
    require(segments.isNotEmpty() && segments.none { it == "." || it == ".." }) {
        "이미지 경로가 올바르지 않습니다: $relativePath"
    }
    return segments
}

private data class MarkdownAssetSaveResult(
    val totalAssets: Int,
    val savedAssets: Int,
)

private data class SafChild(
    val uri: Uri,
    val displayName: String,
    val mimeType: String,
)

private data class CreatedSafDirectory(
    val uri: Uri,
    val displayName: String,
)

private fun saveMarkdownWithAssets(
    resolver: ContentResolver,
    targetTree: Uri,
    baseName: String,
    markdown: String,
    assetRoot: File?,
    assetRelativePaths: List<String>,
): MarkdownAssetSaveResult {
    val rootUri = DocumentsContract.buildDocumentUriUsingTree(
        targetTree,
        DocumentsContract.getTreeDocumentId(targetTree),
    )
    val exportDirectory = createUniqueSafDirectory(resolver, targetTree, rootUri, baseName)
    val markdownUri = DocumentsContract.createDocument(
        resolver,
        exportDirectory.uri,
        MARKDOWN_MIME_TYPE,
        "${exportDirectory.displayName}.md",
    ) ?: throw IOException("MD 파일을 만들 수 없습니다")
    resolver.openOutputStream(markdownUri, "wt")?.use { output ->
        output.write(markdown.toByteArray(Charsets.UTF_8))
    } ?: throw IOException("MD 파일을 쓸 수 없습니다")

    val assetPaths = assetRelativePaths.distinct()
    var savedAssets = 0
    for (relativePath in assetPaths) {
        runCatching {
            val source = assetRoot?.resolveSafeAssetOrNull(relativePath)
                ?.takeIf { it.isFile }
                ?: throw FileNotFoundException(relativePath)
            writeAssetDocument(resolver, targetTree, exportDirectory.uri, relativePath, source)
            savedAssets += 1
        }.onFailure { error ->
            Log.w(TAG, "Failed to save DOCX asset: $relativePath", error)
        }
    }

    return MarkdownAssetSaveResult(
        totalAssets = assetPaths.size,
        savedAssets = savedAssets,
    )
}

private fun createUniqueSafDirectory(
    resolver: ContentResolver,
    treeUri: Uri,
    parentUri: Uri,
    baseName: String,
): CreatedSafDirectory {
    val existingNames = querySafChildren(resolver, treeUri, parentUri)
        .map { it.displayName }
        .toSet()
    var index = 1
    while (true) {
        val candidate = if (index == 1) baseName else "$baseName-$index"
        if (candidate !in existingNames) {
            val uri = DocumentsContract.createDocument(
                resolver,
                parentUri,
                DocumentsContract.Document.MIME_TYPE_DIR,
                candidate,
            ) ?: throw IOException("저장 폴더를 만들 수 없습니다")
            return CreatedSafDirectory(uri = uri, displayName = candidate)
        }
        index += 1
    }
}

private fun writeAssetDocument(
    resolver: ContentResolver,
    treeUri: Uri,
    exportDirectory: Uri,
    relativePath: String,
    source: File,
) {
    val segments = normalizeAssetRelativePath(relativePath)
    val parentUri = ensureSafDirectories(resolver, treeUri, exportDirectory, segments.dropLast(1))
    val fileName = segments.last()
    val assetUri = DocumentsContract.createDocument(
        resolver,
        parentUri,
        assetMimeType(fileName),
        fileName,
    ) ?: throw IOException("이미지 파일을 만들 수 없습니다: $relativePath")
    resolver.openOutputStream(assetUri, "w")?.use { output ->
        source.inputStream().use { input ->
            input.copyTo(output)
        }
    } ?: throw IOException("이미지 파일을 쓸 수 없습니다: $relativePath")
}

private fun ensureSafDirectories(
    resolver: ContentResolver,
    treeUri: Uri,
    startUri: Uri,
    directorySegments: List<String>,
): Uri {
    var parentUri = startUri
    for (segment in directorySegments) {
        val existing = findSafChild(resolver, treeUri, parentUri, segment)
        parentUri = when {
            existing == null -> DocumentsContract.createDocument(
                resolver,
                parentUri,
                DocumentsContract.Document.MIME_TYPE_DIR,
                segment,
            ) ?: throw IOException("이미지 폴더를 만들 수 없습니다: $segment")
            existing.mimeType == DocumentsContract.Document.MIME_TYPE_DIR -> existing.uri
            else -> throw IOException("이미지 폴더 경로가 파일과 충돌합니다: $segment")
        }
    }
    return parentUri
}

private fun findSafChild(
    resolver: ContentResolver,
    treeUri: Uri,
    parentUri: Uri,
    displayName: String,
): SafChild? =
    querySafChildren(resolver, treeUri, parentUri).firstOrNull { it.displayName == displayName }

private fun querySafChildren(
    resolver: ContentResolver,
    treeUri: Uri,
    parentUri: Uri,
): List<SafChild> {
    val parentDocumentId = DocumentsContract.getDocumentId(parentUri)
    val childrenUri = DocumentsContract.buildChildDocumentsUriUsingTree(treeUri, parentDocumentId)
    val children = mutableListOf<SafChild>()
    resolver.query(childrenUri, SAF_CHILD_PROJECTION, null, null, null)?.use { cursor ->
        while (cursor.moveToNext()) {
            children += cursor.toSafChild(treeUri)
        }
    } ?: throw IOException("대상 폴더를 읽을 수 없습니다")
    return children
}

private fun Cursor.toSafChild(treeUri: Uri): SafChild {
    val documentId = getString(getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_DOCUMENT_ID))
    return SafChild(
        uri = DocumentsContract.buildDocumentUriUsingTree(treeUri, documentId),
        displayName = getString(getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_DISPLAY_NAME)).orEmpty(),
        mimeType = getString(getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_MIME_TYPE)).orEmpty(),
    )
}

private fun String.exportBaseName(): String {
    val baseName = substringBeforeLast('.', this)
        .trim()
        .replace('/', '_')
        .replace('\\', '_')
    return baseName.ifBlank { "문서" }
}

private fun assetMimeType(path: String): String =
    when (path.substringAfterLast('.', "").lowercase()) {
        "png" -> "image/png"
        "jpg", "jpeg" -> "image/jpeg"
        "gif" -> "image/gif"
        "webp" -> "image/webp"
        "bmp" -> "image/bmp"
        "svg" -> "image/svg+xml"
        "tif", "tiff" -> "image/tiff"
        else -> BINARY_MIME_TYPE
    }

private fun ContentResolver.hasPersistedReadPermission(uri: Uri): Boolean =
    uri.scheme == "content" && persistedUriPermissions.any { it.uri == uri && it.isReadPermission }

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

private fun decodeFullscreenImage(
    resolver: ContentResolver,
    uri: Uri,
    displayName: String,
    targetWidthPx: Int,
    targetHeightPx: Int,
): FullscreenImage {
    if (isAnimatedImageCandidate(resolver, uri, displayName)) {
        val drawable = ImageDecoder.decodeDrawable(ImageDecoder.createSource(resolver, uri)) { decoder, info, _ ->
            decoder.configureForViewer(info, targetWidthPx, targetHeightPx, useSoftwareAllocator = false)
        }
        if (drawable is AnimatedImageDrawable) {
            return FullscreenImage.Animated(drawable)
        }
    }

    return FullscreenImage.Static(
        decodeSampledBitmap(resolver, uri, targetWidthPx, targetHeightPx).asImageBitmap(),
    )
}

private fun decodeSampledBitmap(
    resolver: ContentResolver,
    uri: Uri,
    targetWidthPx: Int,
    targetHeightPx: Int,
): Bitmap {
    return ImageDecoder.decodeBitmap(ImageDecoder.createSource(resolver, uri)) { decoder, info, _ ->
        decoder.configureForViewer(info, targetWidthPx, targetHeightPx, useSoftwareAllocator = true)
    }
}

private fun ImageDecoder.configureForViewer(
    info: ImageDecoder.ImageInfo,
    targetWidthPx: Int,
    targetHeightPx: Int,
    useSoftwareAllocator: Boolean,
) {
    val size = info.size
    if (size.width <= 0 || size.height <= 0) {
        throw IllegalArgumentException("이미지 정보를 읽을 수 없습니다")
    }
    setTargetSampleSize(calculateInSampleSize(size.width, size.height, targetWidthPx, targetHeightPx))
    if (useSoftwareAllocator) {
        setAllocator(ImageDecoder.ALLOCATOR_SOFTWARE)
    }
}

private fun isAnimatedImageCandidate(
    resolver: ContentResolver,
    uri: Uri,
    displayName: String,
): Boolean {
    val mimeType = resolver.getType(uri)?.lowercase()
    val name = displayName.lowercase()
    return mimeType == "image/gif" ||
        mimeType == "image/webp" ||
        name.endsWith(".gif") ||
        name.endsWith(".webp")
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
private const val MARKDOWN_MIME_TYPE = "text/markdown"
private const val BINARY_MIME_TYPE = "application/octet-stream"
private const val TAG = "SingleDocumentViewer"
private val SAF_CHILD_PROJECTION = arrayOf(
    DocumentsContract.Document.COLUMN_DOCUMENT_ID,
    DocumentsContract.Document.COLUMN_DISPLAY_NAME,
    DocumentsContract.Document.COLUMN_MIME_TYPE,
)
