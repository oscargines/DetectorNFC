package com.oscar.detectornfc

data class DgComparison(
    val dg: Int,
    val expected: Boolean,
    val read: Boolean,
    val status: String
)

object DocumentDiagnostics {
    private val compactDefault = setOf(1, 2, 15)
    private val granularDefault = setOf(1, 2, 7, 11, 13)

    fun expectedDataGroups(
        documentType: String?,
        countryCode: String?,
        architecture: String?
    ): Set<Int> {
        val normalizedCountry = (countryCode ?: "").uppercase().trim()
        val countrySpecific = CountryRegistry.getCountry(normalizedCountry)?.supportedDataGroups?.toSet()

        val parsedType = runCatching { DocumentType.valueOf((documentType ?: "").uppercase().trim()) }
            .getOrDefault(DocumentType.UNKNOWN)
        val parsedArch = runCatching { DocumentArchitecture.valueOf((architecture ?: "").uppercase().trim()) }
            .getOrDefault(DocumentArchitecture.UNKNOWN)

        return when {
            parsedType == DocumentType.GERMAN_EID || parsedArch == DocumentArchitecture.SPECIAL -> emptySet()
            parsedType == DocumentType.PASSPORT || parsedArch == DocumentArchitecture.COMPACT -> compactDefault
            parsedType == DocumentType.ID_CARD || parsedArch == DocumentArchitecture.GRANULAR ->
                countrySpecific ?: granularDefault
            else -> countrySpecific ?: setOf(1, 2)
        }
    }

    fun compare(
        expected: Set<Int>,
        readDgs: Set<Int>,
        statusByDg: Map<Int, String>
    ): List<DgComparison> {
        val all = (expected + readDgs).sorted()
        return all.map { dg ->
            val read = readDgs.contains(dg)
            val status = statusByDg[dg] ?: if (read) "READ_OK" else "NOT_PRESENT"
            DgComparison(
                dg = dg,
                expected = expected.contains(dg),
                read = read,
                status = status
            )
        }
    }
}
