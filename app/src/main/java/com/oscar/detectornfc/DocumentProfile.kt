package com.oscar.detectornfc

enum class DocumentType {
    PASSPORT,
    ID_CARD,
    DRIVING_LICENSE,
    RESIDENCE_PERMIT,
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
    val supportedDataGroups: List<Int>,
    val hasResidencePermits: Boolean = false,
    val paceCanSupported: Boolean = true
)

object CountryRegistry {
    private val countries = mapOf(
        "ESP" to CountryInfo("ESP", "España", DocumentArchitecture.GRANULAR, listOf(1, 2, 7, 11, 13), hasResidencePermits = true),
        "PRT" to CountryInfo("PRT", "Portugal", DocumentArchitecture.GRANULAR, listOf(1, 2, 7, 11, 13)),
        "FRA" to CountryInfo("FRA", "Francia", DocumentArchitecture.GRANULAR, listOf(1, 2, 7, 11, 13)),
        "ITA" to CountryInfo("ITA", "Italia", DocumentArchitecture.GRANULAR, listOf(1, 2, 7, 11, 13)),
        "BEL" to CountryInfo("BEL", "Bélgica", DocumentArchitecture.GRANULAR, listOf(1, 2, 7, 11)),
        "NLD" to CountryInfo("NLD", "Países Bajos", DocumentArchitecture.GRANULAR, listOf(1, 2, 7, 11)),
        "DEU" to CountryInfo("DEU", "Alemania", DocumentArchitecture.SPECIAL, emptyList(), paceCanSupported = false),
        "GRC" to CountryInfo("GRC", "Grecia", DocumentArchitecture.GRANULAR, listOf(1, 2, 7, 11)),
        "POL" to CountryInfo("POL", "Polonia", DocumentArchitecture.GRANULAR, listOf(1, 2, 7, 11, 13)),
        "SWE" to CountryInfo("SWE", "Suecia", DocumentArchitecture.GRANULAR, listOf(1, 2, 7, 11)),
        "DNK" to CountryInfo("DNK", "Dinamarca", DocumentArchitecture.GRANULAR, listOf(1, 2, 7, 11)),
        "FIN" to CountryInfo("FIN", "Finlandia", DocumentArchitecture.GRANULAR, listOf(1, 2, 7, 11)),
        "AUT" to CountryInfo("AUT", "Austria", DocumentArchitecture.GRANULAR, listOf(1, 2, 7, 11, 13)),
        "IRL" to CountryInfo("IRL", "Irlanda", DocumentArchitecture.GRANULAR, listOf(1, 2, 7, 11)),
        "CZE" to CountryInfo("CZE", "República Checa", DocumentArchitecture.GRANULAR, listOf(1, 2, 7, 11)),
        "LUX" to CountryInfo("LUX", "Luxemburgo", DocumentArchitecture.GRANULAR, listOf(1, 2, 7, 11)),
        "HRV" to CountryInfo("HRV", "Croacia", DocumentArchitecture.GRANULAR, listOf(1, 2, 7, 11)),
        "SVK" to CountryInfo("SVK", "Eslovaquia", DocumentArchitecture.GRANULAR, listOf(1, 2, 7, 11)),
        "SVN" to CountryInfo("SVN", "Eslovenia", DocumentArchitecture.GRANULAR, listOf(1, 2, 7, 11)),
        "ROU" to CountryInfo("ROU", "Rumanía", DocumentArchitecture.GRANULAR, listOf(1, 2, 7, 11, 13)),
        "HUN" to CountryInfo("HUN", "Hungría", DocumentArchitecture.GRANULAR, listOf(1, 2, 7, 11)),
        "BGR" to CountryInfo("BGR", "Bulgaria", DocumentArchitecture.GRANULAR, listOf(1, 2, 7, 11)),
        "LTU" to CountryInfo("LTU", "Lituania", DocumentArchitecture.GRANULAR, listOf(1, 2, 7, 11)),
        "LVA" to CountryInfo("LVA", "Letonia", DocumentArchitecture.GRANULAR, listOf(1, 2, 7, 11)),
        "EST" to CountryInfo("EST", "Estonia", DocumentArchitecture.GRANULAR, listOf(1, 2, 7, 11)),
        "MLT" to CountryInfo("MLT", "Malta", DocumentArchitecture.GRANULAR, listOf(1, 2, 7, 11)),
        "CYP" to CountryInfo("CYP", "Chipre", DocumentArchitecture.GRANULAR, listOf(1, 2, 7, 11)),
        "NOR" to CountryInfo("NOR", "Noruega", DocumentArchitecture.GRANULAR, listOf(1, 2, 7, 11)),
        "ISL" to CountryInfo("ISL", "Islandia", DocumentArchitecture.GRANULAR, listOf(1, 2, 7, 11)),
        "LIE" to CountryInfo("LIE", "Liechtenstein", DocumentArchitecture.GRANULAR, listOf(1, 2, 7, 11)),
        "CHE" to CountryInfo("CHE", "Suiza", DocumentArchitecture.GRANULAR, listOf(1, 2, 7, 11)),
        "GBR" to CountryInfo("GBR", "Reino Unido", DocumentArchitecture.GRANULAR, listOf(1, 2, 7, 11)),
        "USA" to CountryInfo("USA", "Estados Unidos", DocumentArchitecture.COMPACT, listOf(1, 2)),
        "CAN" to CountryInfo("CAN", "Canadá", DocumentArchitecture.COMPACT, listOf(1, 2)),
        "AUS" to CountryInfo("AUS", "Australia", DocumentArchitecture.COMPACT, listOf(1, 2)),
        "JPN" to CountryInfo("JPN", "Japón", DocumentArchitecture.COMPACT, listOf(1, 2)),
        "CHN" to CountryInfo("CHN", "China", DocumentArchitecture.COMPACT, listOf(1, 2)),
        "RUS" to CountryInfo("RUS", "Rusia", DocumentArchitecture.COMPACT, listOf(1, 2)),
        "BRA" to CountryInfo("BRA", "Brasil", DocumentArchitecture.COMPACT, listOf(1, 2)),
        "ARG" to CountryInfo("ARG", "Argentina", DocumentArchitecture.COMPACT, listOf(1, 2)),
        "MEX" to CountryInfo("MEX", "México", DocumentArchitecture.COMPACT, listOf(1, 2))
    )

    fun getCountry(code: String): CountryInfo? = countries[code.uppercase()]

    fun isEuropeanCountry(code: String): Boolean {
        val europeanCodes = setOf(
            "ESP", "PRT", "FRA", "ITA", "BEL", "NLD", "DEU", "GRC", "POL", "SWE",
            "DNK", "FIN", "AUT", "IRL", "CZE", "LUX", "HRV", "SVK", "SVN", "ROU",
            "HUN", "BGR", "LTU", "LVA", "EST", "MLT", "CYP", "NOR", "ISL", "LIE", "CHE", "GBR"
        )
        return europeanCodes.contains(code.uppercase())
    }

    fun getResidencePermitCountries(): List<CountryInfo> {
        return countries.values.filter { it.hasResidencePermits }
    }
}
