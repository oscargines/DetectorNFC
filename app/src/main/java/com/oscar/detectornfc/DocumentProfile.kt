package com.oscar.detectornfc

enum class DocumentType {
    PASSPORT,
    ID_CARD,
    DRIVING_LICENSE,
    GERMAN_EID,
    UNKNOWN
}

enum class DocumentArchitecture {
    COMPACT,
    GRANULAR,
    SPECIAL,
    UNKNOWN
}

data class CountryInfo(
    val code: String,
    val name: String,
    val architecture: DocumentArchitecture,
    val supportedDataGroups: List<Int>
)

object CountryRegistry {
    private val countries = mapOf(
        "ESP" to CountryInfo("ESP", "Espana", DocumentArchitecture.GRANULAR, listOf(1, 2, 7, 11, 13)),
        "PRT" to CountryInfo("PRT", "Portugal", DocumentArchitecture.GRANULAR, listOf(1, 2, 7, 11, 13)),
        "FRA" to CountryInfo("FRA", "Francia", DocumentArchitecture.GRANULAR, listOf(1, 2, 7, 11, 13)),
        "ITA" to CountryInfo("ITA", "Italia", DocumentArchitecture.GRANULAR, listOf(1, 2, 7, 11, 13)),
        "BEL" to CountryInfo("BEL", "Belgica", DocumentArchitecture.GRANULAR, listOf(1, 2, 7, 11, 13)),
        "NLD" to CountryInfo("NLD", "Paises Bajos", DocumentArchitecture.GRANULAR, listOf(1, 2, 7, 11, 13)),
        "DEU" to CountryInfo("DEU", "Alemania", DocumentArchitecture.SPECIAL, emptyList())
    )

    fun getCountry(code: String): CountryInfo? = countries[code.uppercase()]
}
