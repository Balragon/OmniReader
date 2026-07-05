package dev.gold.mdvault.ui

import androidx.annotation.StringRes
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import dev.gold.mdvault.R
import dev.gold.mdvault.storage.VaultError

internal enum class VaultErrorRecovery {
    OpenVaultSetup,
    BackToList,
}

internal data class VaultErrorUi(
    @StringRes val messageRes: Int? = null,
    val rawMessage: String? = null,
    val recovery: VaultErrorRecovery? = null,
)

internal fun VaultError.toVaultErrorUi(): VaultErrorUi =
    when (this) {
        is VaultError.PermissionLost -> VaultErrorUi(
            messageRes = R.string.error_permission_lost,
            recovery = VaultErrorRecovery.OpenVaultSetup,
        )
        is VaultError.DocumentMissing -> VaultErrorUi(
            messageRes = R.string.error_document_missing,
            recovery = VaultErrorRecovery.BackToList,
        )
        is VaultError.ProviderUnavailable -> VaultErrorUi(
            messageRes = R.string.error_provider_unavailable,
        )
        is VaultError.Unknown -> VaultErrorUi(
            messageRes = R.string.error_unknown,
        )
    }

@Composable
internal fun VaultErrorUi.text(): String =
    messageRes?.let { stringResource(it) } ?: rawMessage ?: ""

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
                    Text(stringResource(R.string.recovery_reselect_folder))
                }
            }
        }
        VaultErrorRecovery.BackToList -> {
            if (onBackToList != null) {
                TextButton(onClick = onBackToList) {
                    Text(stringResource(R.string.recovery_back_to_list))
                }
            }
        }
        null -> Unit
    }
}
