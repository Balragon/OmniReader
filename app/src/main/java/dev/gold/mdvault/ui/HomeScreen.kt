package dev.gold.mdvault.ui

import android.content.Intent
import android.net.Uri
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
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.gold.mdvault.storage.RecentFilesRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.FileNotFoundException

@Composable
fun HomeScreen(
    recentFilesRepository: RecentFilesRepository,
    canOpenVault: Boolean,
    onOpenDocument: (Uri) -> Unit,
    onOpenVault: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val recentFiles by recentFilesRepository.recentFiles.collectAsState(initial = emptyList())
    var notice by remember { mutableStateOf<String?>(null) }

    val documentPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        runCatching {
            context.contentResolver.takePersistableUriPermission(
                uri,
                Intent.FLAG_GRANT_READ_URI_PERMISSION,
            )
        }
        notice = null
        onOpenDocument(uri)
    }

    LazyColumn(
        modifier = modifier
            .fillMaxSize()
            .padding(horizontal = 24.dp),
        contentPadding = PaddingValues(vertical = 28.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        item {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(
                    text = "mdvault",
                    style = MaterialTheme.typography.headlineMedium,
                )
                Button(
                    onClick = {
                        documentPicker.launch(VIEWER_MIME_TYPES)
                    },
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text("파일 열기")
                }
                Button(
                    onClick = onOpenVault,
                    enabled = canOpenVault,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text("내 폴더")
                }
            }
        }

        notice?.let { message ->
            item {
                Text(
                    text = message,
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }

        item {
            Text(
                text = "최근 파일",
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.padding(top = 8.dp),
            )
        }

        if (recentFiles.isEmpty()) {
            item {
                Text(
                    text = "최근에 연 파일이 없습니다.",
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
        } else {
            items(recentFiles, key = { it.uri }) { entry ->
                RecentFileRow(
                    entry = entry,
                    onClick = {
                        val uri = Uri.parse(entry.uri)
                        scope.launch {
                            try {
                                withContext(Dispatchers.IO) {
                                    context.contentResolver.openInputStream(uri)?.close()
                                        ?: throw FileNotFoundException(entry.uri)
                                }
                                notice = null
                                onOpenDocument(uri)
                            } catch (e: SecurityException) {
                                recentFilesRepository.remove(entry.uri)
                                notice = "권한이 만료되어 목록에서 제거했습니다"
                            } catch (e: Exception) {
                                notice = "파일을 열 수 없습니다"
                            }
                        }
                    },
                )
            }
        }
    }
}

@Composable
private fun RecentFileRow(
    entry: RecentFilesRepository.RecentFile,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(vertical = 10.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = entry.displayName,
                style = MaterialTheme.typography.bodyLarge,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = relativeTime(entry.openedAtMillis),
                style = MaterialTheme.typography.bodySmall,
            )
        }
    }
}

private fun relativeTime(openedAtMillis: Long): String {
    val elapsedMillis = (System.currentTimeMillis() - openedAtMillis).coerceAtLeast(0)
    val minute = 60_000L
    val hour = 60 * minute
    val day = 24 * hour
    return when {
        elapsedMillis < minute -> "방금"
        elapsedMillis < hour -> "${elapsedMillis / minute}분 전"
        elapsedMillis < day -> "${elapsedMillis / hour}시간 전"
        elapsedMillis < 7 * day -> "${elapsedMillis / day}일 전"
        else -> "오래 전"
    }
}

private val VIEWER_MIME_TYPES = arrayOf(
    "text/markdown",
    "text/plain",
    "text/html",
    "application/pdf",
    "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
    "image/*",
)
