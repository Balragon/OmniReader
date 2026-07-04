package dev.gold.mdvault.storage

sealed class VaultError(
    message: String,
    cause: Throwable? = null,
) : Exception(message, cause) {
    class PermissionLost : VaultError("Vault folder permission was lost")
    class DocumentMissing(val name: String) : VaultError("Vault document is missing: $name")
    class ProviderUnavailable : VaultError("Vault provider is unavailable")
    class Unknown(cause: Throwable) : VaultError("Unknown vault error", cause)
}
