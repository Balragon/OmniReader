package dev.gold.mdvault.ui

import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import dev.gold.mdvault.storage.VaultError

internal enum class VaultErrorRecovery {
    OpenVaultSetup,
    BackToList,
}

internal data class VaultErrorUi(
    val message: String,
    val recovery: VaultErrorRecovery? = null,
)

internal fun VaultError.toVaultErrorUi(): VaultErrorUi =
    when (this) {
        is VaultError.PermissionLost -> VaultErrorUi(
            message = "폴더 접근 권한이 사라졌습니다",
            recovery = VaultErrorRecovery.OpenVaultSetup,
        )
        is VaultError.DocumentMissing -> VaultErrorUi(
            message = "파일이 이동 되었거나 삭제되었습니다",
            recovery = VaultErrorRecovery.BackToList,
        )
        is VaultError.ProviderUnavailable -> VaultErrorUi(
            message = "저장소가 응답하지 않습니다. 잠시 후 다시 시도하세요",
        )
        is VaultError.Unknown -> VaultErrorUi(
            message = "저장소 작업 중 문제가 발생했습니다",
        )
    }

@Composable
internal fun VaultErrorRecoveryButton(
    error: VaultErrorUi,
    onOpenVaultSetup: (() -> Unit)? = null,
    onBackToList: (() -> Unit)? = null,
) {
    when (error.recovery) {
        VaultErrorRecovery.OpenVaultSetup -> {
            if (onOpenVaultSetup != null) {
                TextButton(onClick = onOpenVaultSetup) {
                    Text("폴더 다시 선택")
                }
            }
        }
        VaultErrorRecovery.BackToList -> {
            if (onBackToList != null) {
                TextButton(onClick = onBackToList) {
                    Text("목록으로")
                }
            }
        }
        null -> Unit
    }
}
