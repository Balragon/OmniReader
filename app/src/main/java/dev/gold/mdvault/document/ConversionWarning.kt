package dev.gold.mdvault.document

sealed interface ConversionWarning {
    data class UnsafeLinkDropped(val href: String) : ConversionWarning
    data class UnsupportedFeature(val feature: String) : ConversionWarning
}
