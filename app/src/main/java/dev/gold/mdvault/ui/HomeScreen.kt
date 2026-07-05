package dev.gold.mdvault.ui

import android.content.Intent
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.gold.mdvault.storage.RecentFilesRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.FileNotFoundException

// OmniReader 브랜드 팔레트 (아이콘과 동일 계열). 앱은 라이트 전용.
private val BrandBlue = Color(0xFF3E6FE0)
private val ScreenText = Color(0xFF1B2230)
private val MutedText = Color(0xFF6B7383)
private val CardBg = Color(0xFFF2F5FB)

@Composable
fun HomeScreen(
    recentFilesRepository: RecentFilesRepository,
    onOpenDocument: (Uri) -> Unit,
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
            .padding(horizontal = 20.dp),
        contentPadding = PaddingValues(top = 32.dp, bottom = 28.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item { BrandHeader() }
        item {
            OpenFileButton(
                modifier = Modifier.padding(top = 8.dp),
                onClick = { documentPicker.launch(VIEWER_MIME_TYPES) },
            )
        }

        notice?.let { message ->
            item {
                Text(
                    text = message,
                    style = MaterialTheme.typography.bodySmall,
                    color = MutedText,
                    modifier = Modifier.padding(horizontal = 4.dp),
                )
            }
        }

        item {
            Text(
                text = "최근 파일",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Medium,
                color = ScreenText,
                modifier = Modifier.padding(top = 16.dp, bottom = 2.dp),
            )
        }

        if (recentFiles.isEmpty()) {
            item { EmptyRecent() }
        } else {
            items(recentFiles, key = { it.uri }) { entry ->
                RecentFileCard(
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
private fun BrandHeader() {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Box(
            modifier = Modifier
                .size(46.dp)
                .clip(RoundedCornerShape(13.dp))
                .background(BrandBlue),
            contentAlignment = Alignment.Center,
        ) {
            // 아이콘의 흰 O 링을 축약한 로고 마크
            Canvas(modifier = Modifier.size(20.dp)) {
                drawCircle(
                    color = Color.White,
                    radius = size.minDimension / 2f,
                    style = Stroke(width = size.minDimension * 0.26f),
                )
            }
        }
        Column {
            Text(
                text = "OmniReader",
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.SemiBold,
                color = ScreenText,
            )
            Text(
                text = "문서 뷰어",
                style = MaterialTheme.typography.bodyMedium,
                color = MutedText,
            )
        }
    }
}

@Composable
private fun OpenFileButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(18.dp))
            .background(BrandBlue)
            .clickable(onClick = onClick)
            .padding(horizontal = 20.dp, vertical = 18.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Box(
            modifier = Modifier
                .size(44.dp)
                .clip(RoundedCornerShape(12.dp))
                .background(Color.White.copy(alpha = 0.18f)),
            contentAlignment = Alignment.Center,
        ) {
            Text(text = "+", color = Color.White, fontSize = 26.sp, fontWeight = FontWeight.Light)
        }
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = "파일 열기",
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Medium,
                color = Color.White,
            )
            Text(
                text = "기기의 문서를 열어 읽기",
                style = MaterialTheme.typography.bodyMedium,
                color = Color.White.copy(alpha = 0.85f),
            )
        }
    }
}

@Composable
private fun EmptyRecent() {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(CardBg)
            .padding(vertical = 28.dp, horizontal = 20.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Text(
            text = "최근에 연 파일이 없습니다",
            style = MaterialTheme.typography.bodyLarge,
            color = ScreenText,
        )
        Text(
            text = "\"파일 열기\"로 문서를 열면 여기에 쌓입니다",
            style = MaterialTheme.typography.bodySmall,
            color = MutedText,
        )
    }
}

@Composable
private fun RecentFileCard(
    entry: RecentFilesRepository.RecentFile,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(CardBg)
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 12.dp),
        horizontalArrangement = Arrangement.spacedBy(14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        TypeBadge(entry.kind)
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = entry.displayName,
                style = MaterialTheme.typography.bodyLarge,
                color = ScreenText,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = relativeTime(entry.openedAtMillis),
                style = MaterialTheme.typography.bodySmall,
                color = MutedText,
            )
        }
    }
}

@Composable
private fun TypeBadge(kind: String) {
    val (label, color) = badgeFor(kind)
    Box(
        modifier = Modifier
            .size(42.dp)
            .clip(RoundedCornerShape(11.dp))
            .background(color.copy(alpha = 0.14f)),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = label,
            color = color,
            style = MaterialTheme.typography.labelMedium,
            fontWeight = FontWeight.SemiBold,
        )
    }
}

private fun badgeFor(kind: String): Pair<String, Color> = when (kind) {
    "PDF" -> "PDF" to Color(0xFFE0574B)
    "DOCX" -> "DOC" to BrandBlue
    "IMAGE" -> "IMG" to Color(0xFF2FA36B)
    "HTML" -> "HTM" to Color(0xFF8A5CD6)
    "MARKDOWN" -> "MD" to BrandBlue
    "PLAIN_TEXT" -> "TXT" to Color(0xFF6B7383)
    else -> "FILE" to Color(0xFF6B7383)
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
