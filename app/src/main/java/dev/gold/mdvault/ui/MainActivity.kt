package dev.gold.mdvault.ui

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import dev.gold.mdvault.AppContainer
import dev.gold.mdvault.preview.SingleDocumentViewerScreen

class MainActivity : ComponentActivity() {
    private val container: AppContainer by lazy(LazyThreadSafetyMode.NONE) {
        AppContainer(applicationContext)
    }
    private var externalUriState by mutableStateOf<Uri?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        externalUriState = externalDocumentUri(intent)
        persistReadIfPossible(externalUriState)
        setContent {
            MaterialTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    val externalUri = externalUriState
                    if (externalUri != null) {
                        // "내 파일" 등에서 연결 앱으로 열린 경우 — 뷰어만 표시,
                        // 뒤로 가면 원래 앱으로 복귀
                        SingleDocumentViewerScreen(
                            uri = externalUri,
                            markdownEngine = container.markdownEngine,
                            docxImporter = container.docxToMarkdownImporter,
                            recentFiles = container.recentFilesRepository,
                            readerSettingsRepository = container.readerSettingsRepository,
                            onBack = { finish() },
                        )
                    } else {
                        MdvaultApp(container)
                    }
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        externalUriState = externalDocumentUri(intent)
        persistReadIfPossible(externalUriState)
    }

    private fun externalDocumentUri(intent: Intent?): Uri? = when (intent?.action) {
        Intent.ACTION_VIEW -> intent.data
        Intent.ACTION_SEND ->
            @Suppress("DEPRECATION")
            intent.getParcelableExtra(Intent.EXTRA_STREAM)
        else -> null
    }

    /**
     * 외부 인텐트로 받은 문서에 영구 읽기 권한을 시도한다. 성공하면 최근 목록에서
     * 다시 열 수 있다(권한을 부여한 provider 한정). VIEW 그랜트가 persistable이
     * 아니면 조용히 실패하며, 그 파일은 최근 목록에 기록되지 않는다(뷰어가 판단).
     */
    private fun persistReadIfPossible(uri: Uri?) {
        if (uri == null || uri.scheme != "content") return
        runCatching {
            contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
    }
}

@Composable
private fun MdvaultApp(container: AppContainer) {
    var viewerUriString by rememberSaveable { mutableStateOf<String?>(null) }
    val activeViewerUri = viewerUriString?.let(Uri::parse)

    if (activeViewerUri == null) {
        HomeScreen(
            recentFilesRepository = container.recentFilesRepository,
            onOpenDocument = { uri -> viewerUriString = uri.toString() },
        )
    } else {
        SingleDocumentViewerScreen(
            uri = activeViewerUri,
            markdownEngine = container.markdownEngine,
            docxImporter = container.docxToMarkdownImporter,
            recentFiles = container.recentFilesRepository,
            readerSettingsRepository = container.readerSettingsRepository,
            onBack = { viewerUriString = null },
        )
    }
}
