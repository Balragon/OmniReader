package dev.gold.mdvault.document

sealed interface ConversionWarning {
    data class UnsafeLinkDropped(val href: String) : ConversionWarning
    data class UnsupportedFeature(val feature: String) : ConversionWarning

    /** DOCX의 XML 파트에서 XML 1.0 불법 제어문자를 제거하고 계속 진행했음. */
    data class IllegalXmlCharactersStripped(val count: Int) : ConversionWarning
}
