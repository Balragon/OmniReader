package dev.gold.mdvault.ui

import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import dev.gold.mdvault.BuildConfig
import dev.gold.mdvault.storage.VaultRepository
import kotlinx.coroutines.launch

@Composable
fun VaultSetupScreen(
    vaultRepository: VaultRepository,
    vaultTreeUri: Uri?,
    onVaultSelected: () -> Unit,
    onOpenSpike: () -> Unit,
    onBack: (() -> Unit)? = null,
    modifier: Modifier = Modifier,
) {
    val scope = rememberCoroutineScope()
    val currentPath = remember(vaultTreeUri) {
        vaultTreeUri?.let(::formatVaultPath)
    }
    val treePicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            vaultRepository.setVaultTreeUri(uri)
            onVaultSelected()
        }
    }

    if (onBack != null) {
        BackHandler(onBack = onBack)
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text(
            text = "Markdown 볼트",
            style = MaterialTheme.typography.headlineSmall,
        )
        if (currentPath == null) {
            Text(
                text = "문서를 저장할 볼트 폴더를 선택하세요.",
                style = MaterialTheme.typography.bodyLarge,
            )
            Button(onClick = { treePicker.launch(null) }) {
                Text("볼트 폴더 선택")
            }
        } else {
            Text(
                text = "현재 볼트: $currentPath",
                style = MaterialTheme.typography.bodyLarge,
            )
            Button(onClick = { treePicker.launch(vaultTreeUri) }) {
                Text("볼트 변경")
            }
        }
        Spacer(modifier = Modifier.weight(1f))
        // 개발 진단 화면(성능 측정·IME 판정) — debug 빌드에서만 노출
        if (BuildConfig.DEBUG) {
            TextButton(
                onClick = onOpenSpike,
                modifier = Modifier.align(Alignment.CenterHorizontally),
            ) {
                Text("Spike")
            }
        }
    }
}

private fun formatVaultPath(uri: Uri): String =
    Uri.decode(uri.lastPathSegment ?: uri.toString())
