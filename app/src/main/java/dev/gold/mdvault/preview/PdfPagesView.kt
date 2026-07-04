package dev.gold.mdvault.preview

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.pdf.PdfRenderer
import android.net.Uri
import android.os.ParcelFileDescriptor
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.rememberTransformableState
import androidx.compose.foundation.gestures.transformable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color as ComposeColor
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.Closeable

/**
 * PDF 페이지 세로 스크롤 뷰 — Android 내장 PdfRenderer 사용 (의존성 0개).
 * 페이지 비트맵은 LazyColumn이 화면 밖 항목을 폐기하며 자연 회수된다.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun PdfPagesView(uri: Uri, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    var error by remember(uri) { mutableStateOf<String?>(null) }
    var holder by remember(uri) { mutableStateOf<PdfDocumentHolder?>(null) }
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
        } catch (e: Exception) {
            error = e.message ?: e.javaClass.simpleName
            null
        }
        holder = opened
        onDispose { opened?.close() }
    }

    when {
        error != null -> Text(
            text = "PDF를 열 수 없습니다: $error",
            color = ComposeColor.White,
            modifier = Modifier.padding(24.dp),
        )
        holder == null -> Text(
            text = "여는 중…",
            color = ComposeColor.White,
            modifier = Modifier.padding(24.dp),
        )
        else -> {
            val document = holder!!
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
                    modifier = Modifier
                        .fillMaxSize()
                        .graphicsLayer(
                            scaleX = scale,
                            scaleY = scale,
                            translationX = offsetX,
                            translationY = offsetY,
                        ),
                ) {
                    items((0 until document.pageCount).toList()) { pageIndex ->
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
    if (rendered != null) {
        Image(
            bitmap = rendered.asImageBitmap(),
            contentDescription = "페이지 ${pageIndex + 1}",
            modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp),
            contentScale = ContentScale.FillWidth,
        )
    } else {
        Box(
            modifier = Modifier.fillMaxWidth().height(400.dp),
            contentAlignment = Alignment.Center,
        ) {
            Text("페이지 ${pageIndex + 1} 렌더링 중…")
        }
    }
}

private const val TARGET_WIDTH_PX = 1440

/** PdfRenderer는 스레드 안전이 아니므로 Mutex로 직렬화한다. */
class PdfDocumentHolder private constructor(
    private val fileDescriptor: ParcelFileDescriptor,
    private val renderer: PdfRenderer,
) : Closeable {

    private val mutex = Mutex()
    val pageCount: Int = renderer.pageCount

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
                ?: throw IllegalStateException("파일 디스크립터를 열 수 없음: $uri")
            return try {
                PdfDocumentHolder(descriptor, PdfRenderer(descriptor))
            } catch (e: Exception) {
                descriptor.close()
                throw e
            }
        }
    }
}
