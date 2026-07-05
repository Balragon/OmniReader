package dev.gold.mdvault.preview

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.pdf.PdfRenderer
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.os.RemoteException
import android.util.Log
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.rememberTransformableState
import androidx.compose.foundation.gestures.transformable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color as ComposeColor
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import dev.gold.mdvault.R
import dev.gold.mdvault.settings.ReaderSettingsRepository
import dev.gold.mdvault.storage.VaultError
import dev.gold.mdvault.ui.VaultErrorRecoveryButton
import dev.gold.mdvault.ui.VaultErrorUi
import dev.gold.mdvault.ui.text
import dev.gold.mdvault.ui.toVaultErrorUi
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.Closeable
import java.io.FileNotFoundException

/**
 * PDF 페이지 세로 스크롤 뷰 — Android 내장 PdfRenderer 사용 (의존성 0개).
 * 페이지 비트맵은 LazyColumn이 화면 밖 항목을 폐기하며 자연 회수된다.
 */
@OptIn(ExperimentalFoundationApi::class, FlowPreview::class)
@Composable
fun PdfPagesView(
    uri: Uri,
    modifier: Modifier = Modifier,
    onBack: (() -> Unit)? = null,
    onOpenVaultSetup: (() -> Unit)? = null,
    readerSettingsRepository: ReaderSettingsRepository? = null,
    documentKey: String = uri.toString(),
) {
    val context = LocalContext.current
    var error by remember(uri) { mutableStateOf<VaultErrorUi?>(null) }
    var holder by remember(uri) { mutableStateOf<PdfDocumentHolder?>(null) }
    var restoredPositionLoaded by remember(uri, documentKey, readerSettingsRepository) {
        mutableStateOf(readerSettingsRepository == null)
    }
    var restoredPosition by remember(uri, documentKey) { mutableStateOf<PdfReadingPosition?>(null) }
    var scale by remember(uri) { mutableStateOf(1f) }
    var offsetX by remember(uri) { mutableStateOf(0f) }
    var offsetY by remember(uri) { mutableStateOf(0f) }
    val transformableState = rememberTransformableState { zoomChange, panChange, _ ->
        val nextScale = (scale * zoomChange).coerceIn(1f, 5f)
        if (nextScale == 1f) {
            offsetX = 0f
            offsetY = 0f
        } else {
            offsetX += panChange.x
            offsetY += panChange.y
        }
        scale = nextScale
    }

    DisposableEffect(uri) {
        val opened = try {
            PdfDocumentHolder.open(context, uri)
        } catch (e: SecurityException) {
            Log.w(TAG, "Permission lost while opening PDF", e)
            error = VaultError.PermissionLost().toVaultErrorUi()
            null
        } catch (e: FileNotFoundException) {
            Log.w(TAG, "PDF document missing", e)
            error = VaultError.DocumentMissing(uri.toString()).toVaultErrorUi()
            null
        } catch (e: RemoteException) {
            Log.w(TAG, "Provider unavailable while opening PDF", e)
            error = VaultError.ProviderUnavailable().toVaultErrorUi()
            null
        } catch (e: IllegalStateException) {
            Log.w(TAG, "Provider unavailable while opening PDF", e)
            error = VaultError.ProviderUnavailable().toVaultErrorUi()
            null
        } catch (e: Exception) {
            error = VaultErrorUi(rawMessage = e.message ?: e.javaClass.simpleName)
            null
        }
        holder = opened
        onDispose { opened?.close() }
    }

    LaunchedEffect(readerSettingsRepository, documentKey) {
        val repository = readerSettingsRepository
        if (repository == null) {
            restoredPosition = null
            restoredPositionLoaded = true
        } else {
            restoredPositionLoaded = false
            restoredPosition = withContext(Dispatchers.IO) {
                repository.readingPosition(documentKey).pdfReadingPositionOrNull()
            }
            restoredPositionLoaded = true
        }
    }

    when {
        error != null -> Column(modifier = Modifier.padding(24.dp)) {
            val currentError = error!!
            val message = currentError.text()
            Text(
                text = stringResource(R.string.viewer_open_pdf_failed, message),
                color = ComposeColor.White,
            )
            VaultErrorRecoveryButton(
                error = currentError,
                onOpenVaultSetup = onOpenVaultSetup,
                onBackToList = onBack,
            )
        }
        holder == null || !restoredPositionLoaded -> Text(
            text = stringResource(R.string.viewer_loading),
            color = ComposeColor.White,
            modifier = Modifier.padding(24.dp),
        )
        else -> {
            val document = holder!!
            val initialPosition = restoredPosition
            val listState = rememberLazyListState(
                initialFirstVisibleItemIndex = initialPosition?.index
                    ?.coerceIn(0, (document.pageCount - 1).coerceAtLeast(0))
                    ?: 0,
                initialFirstVisibleItemScrollOffset = initialPosition?.offset?.coerceAtLeast(0) ?: 0,
            )

            LaunchedEffect(listState, readerSettingsRepository, documentKey) {
                val repository = readerSettingsRepository ?: return@LaunchedEffect
                snapshotFlow {
                    PdfReadingPosition(
                        index = listState.firstVisibleItemIndex,
                        offset = listState.firstVisibleItemScrollOffset,
                    )
                }
                    .debounce(PDF_POSITION_SAVE_DEBOUNCE_MS)
                    .collect { position ->
                        repository.saveReadingPosition(documentKey, position.toPayload())
                    }
            }

            DisposableEffect(listState, readerSettingsRepository, documentKey) {
                onDispose {
                    readerSettingsRepository?.let { repository ->
                        savePdfReadingPositionAsync(
                            repository = repository,
                            documentKey = documentKey,
                            position = PdfReadingPosition(
                                index = listState.firstVisibleItemIndex,
                                offset = listState.firstVisibleItemScrollOffset,
                            ),
                        )
                    }
                }
            }

            Box(
                modifier = modifier
                    .fillMaxSize()
                    .background(ComposeColor.Black)
                    .transformable(
                        state = transformableState,
                        canPan = { scale > 1f },
                    ),
            ) {
                LazyColumn(
                    state = listState,
                    modifier = Modifier
                        .fillMaxSize()
                        .graphicsLayer(
                            scaleX = scale,
                            scaleY = scale,
                            translationX = offsetX,
                            translationY = offsetY,
                        ),
                    // 문서가 화면보다 짧으면(한두 페이지) 세로 중앙 정렬
                    verticalArrangement = Arrangement.Center,
                ) {
                    items(count = document.pageCount) { pageIndex ->
                        PdfPageItem(document, pageIndex)
                    }
                }
            }
        }
    }
}

@Composable
private fun PdfPageItem(document: PdfDocumentHolder, pageIndex: Int) {
    var bitmap by remember(document, pageIndex) { mutableStateOf<Bitmap?>(null) }

    LaunchedEffect(document, pageIndex) {
        bitmap = withContext(Dispatchers.IO) {
            runCatching { document.renderPage(pageIndex, TARGET_WIDTH_PX) }.getOrNull()
        }
    }

    val rendered = bitmap
    val pageNumber = pageIndex + 1
    if (rendered != null) {
        Image(
            bitmap = rendered.asImageBitmap(),
            contentDescription = stringResource(R.string.pdf_page, pageNumber),
            modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp),
            contentScale = ContentScale.FillWidth,
        )
    } else {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(document.firstPageWidthHeightRatio)
                .padding(bottom = 8.dp),
            contentAlignment = Alignment.Center,
        ) {
            Text(stringResource(R.string.pdf_page_rendering, pageNumber))
        }
    }
}

private data class PdfReadingPosition(
    val index: Int,
    val offset: Int,
) {
    fun toPayload(): String = "pdf:${index.coerceAtLeast(0)}:${offset.coerceAtLeast(0)}"
}

private fun String?.pdfReadingPositionOrNull(): PdfReadingPosition? {
    val fields = this?.split(':') ?: return null
    if (fields.size != 3 || fields[0] != "pdf") return null
    val index = fields[1].toIntOrNull() ?: return null
    val offset = fields[2].toIntOrNull() ?: return null
    if (index < 0 || offset < 0) return null
    return PdfReadingPosition(index = index, offset = offset)
}

private fun savePdfReadingPositionAsync(
    repository: ReaderSettingsRepository,
    documentKey: String,
    position: PdfReadingPosition,
) {
    CoroutineScope(Dispatchers.IO).launch {
        runCatching {
            repository.saveReadingPosition(documentKey, position.toPayload())
        }
    }
}

private const val TARGET_WIDTH_PX = 1440
private const val TAG = "PdfPagesView"
private const val PDF_POSITION_SAVE_DEBOUNCE_MS = 1_000L

/** PdfRenderer는 스레드 안전이 아니므로 Mutex로 직렬화한다. */
class PdfDocumentHolder private constructor(
    private val fileDescriptor: ParcelFileDescriptor,
    private val renderer: PdfRenderer,
) : Closeable {

    private val mutex = Mutex()
    val pageCount: Int = renderer.pageCount
    val firstPageWidthHeightRatio: Float = renderer.firstPageWidthHeightRatio()

    suspend fun renderPage(index: Int, targetWidthPx: Int): Bitmap = mutex.withLock {
        val page = renderer.openPage(index)
        try {
            val scale = targetWidthPx.toFloat() / page.width
            val bitmap = Bitmap.createBitmap(
                targetWidthPx,
                (page.height * scale).toInt().coerceAtLeast(1),
                Bitmap.Config.ARGB_8888,
            )
            bitmap.eraseColor(Color.WHITE)
            page.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
            bitmap
        } finally {
            page.close()
        }
    }

    override fun close() {
        runCatching { renderer.close() }
        runCatching { fileDescriptor.close() }
    }

    companion object {
        fun open(context: Context, uri: Uri): PdfDocumentHolder {
            val descriptor = context.contentResolver.openFileDescriptor(uri, "r")
                ?: throw IllegalStateException("Couldn't open file descriptor: $uri")
            return try {
                PdfDocumentHolder(descriptor, PdfRenderer(descriptor))
            } catch (e: Exception) {
                descriptor.close()
                throw e
            }
        }
    }
}

private fun PdfRenderer.firstPageWidthHeightRatio(): Float {
    if (pageCount <= 0) return DEFAULT_PDF_PAGE_WIDTH_HEIGHT_RATIO
    val page = openPage(0)
    return try {
        page.width.toFloat() / page.height.toFloat().coerceAtLeast(1f)
    } finally {
        page.close()
    }
}

private const val DEFAULT_PDF_PAGE_WIDTH_HEIGHT_RATIO = 0.707f
