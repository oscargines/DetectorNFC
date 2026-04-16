package com.oscar.detectornfc

data class ClassificationResult(
    val documentType: DocumentType,
    val countryCode: String,
    val countryName: String,
    val architecture: DocumentArchitecture
)

object DocumentClassifier {
    fun classify(docTypeRaw: String?, issuerRaw: String?): ClassificationResult {
        val docType = (docTypeRaw ?: "").trim().uppercase()
        val issuer = (issuerRaw ?: "").trim().uppercase()

        val type = when {
            docType.startsWith("P") -> DocumentType.PASSPORT
            docType.startsWith("C") || docType.startsWith("I") -> DocumentType.ID_CARD
            docType.startsWith("D") -> DocumentType.DRIVING_LICENSE
            else -> DocumentType.UNKNOWN
        }

        if (type == DocumentType.ID_CARD && issuer == "DEU") {
            return ClassificationResult(
                documentType = DocumentType.GERMAN_EID,
                countryCode = "DEU",
                countryName = "Alemania",
                architecture = DocumentArchitecture.SPECIAL
            )
        }

        val country = CountryRegistry.getCountry(issuer)
        val architecture = when {
            type == DocumentType.PASSPORT -> DocumentArchitecture.COMPACT
            country != null -> country.architecture
            type == DocumentType.ID_CARD -> DocumentArchitecture.GRANULAR
            else -> DocumentArchitecture.UNKNOWN
        }

        return ClassificationResult(
            documentType = type,
            countryCode = if (issuer.isNotBlank()) issuer else "UNK",
            countryName = country?.name ?: if (issuer.isBlank()) "Desconocido" else issuer,
            architecture = architecture
        )
    }
}
