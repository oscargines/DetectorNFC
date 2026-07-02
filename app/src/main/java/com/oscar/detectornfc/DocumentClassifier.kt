package com.oscar.detectornfc

data class ClassificationResult(
    val documentType: DocumentType,
    val countryCode: String,
    val countryName: String,
    val architecture: DocumentArchitecture,
    val isResidencePermit: Boolean = false,
    val documentSubtype: String? = null
)

object DocumentClassifier {
    fun classify(docTypeRaw: String?, issuerRaw: String?): ClassificationResult {
        val docType = (docTypeRaw ?: "").trim().uppercase()
        val issuer = (issuerRaw ?: "").trim().uppercase()

        val type = when {
            docType.startsWith("P") -> DocumentType.PASSPORT
            docType.startsWith("C") || docType.startsWith("I") -> DocumentType.ID_CARD
            docType.startsWith("D") -> DocumentType.DRIVING_LICENSE
            docType.startsWith("A") && issuer == "ESP" -> DocumentType.RESIDENCE_PERMIT
            else -> DocumentType.UNKNOWN
        }

        if (type == DocumentType.ID_CARD && issuer == "DEU") {
            return ClassificationResult(
                documentType = DocumentType.GERMAN_EID,
                countryCode = "DEU",
                countryName = "Alemania",
                architecture = DocumentArchitecture.SPECIAL,
                documentSubtype = "eID"
            )
        }

        val country = CountryRegistry.getCountry(issuer)
        val architecture = when {
            type == DocumentType.PASSPORT -> DocumentArchitecture.COMPACT
            country != null -> country.architecture
            type == DocumentType.ID_CARD -> DocumentArchitecture.GRANULAR
            else -> DocumentArchitecture.UNKNOWN
        }

        val isResidencePermit = detectResidencePermit(docType, issuer, docTypeRaw)
        val finalType = if (isResidencePermit) DocumentType.RESIDENCE_PERMIT else type

        val subtype = when {
            isResidencePermit && issuer == "ESP" -> detectSpanishResidenceType(docTypeRaw)
            else -> null
        }

        return ClassificationResult(
            documentType = finalType,
            countryCode = if (issuer.isNotBlank()) issuer else "UNK",
            countryName = country?.name ?: if (issuer.isBlank()) "Desconocido" else issuer,
            architecture = architecture,
            isResidencePermit = isResidencePermit,
            documentSubtype = subtype
        )
    }

    fun classifyFromMrz(mrzLines: List<String>): ClassificationResult? {
        if (mrzLines.isEmpty()) return null

        val firstLine = mrzLines.firstOrNull()?.trim() ?: return null
        if (firstLine.length < 5) return null

        val docCode = firstLine.substring(0, 2).trimEnd('<')
        val issuer = firstLine.substring(2, 5).trimEnd('<')

        return classify(docCode, issuer)
    }

    private fun detectResidencePermit(docType: String, issuer: String, rawDocType: String?): Boolean {
        if (issuer != "ESP") return false

        val raw = rawDocType?.trim()?.uppercase() ?: return false

        if (raw.startsWith("A")) return true

        if (docType.startsWith("I") && raw.length >= 3) {
            val thirdChar = raw.getOrNull(2)
            if (thirdChar != null && thirdChar.isLetter() && thirdChar != 'D') {
                return true
            }
        }

        return false
    }

    private fun detectSpanishResidenceType(rawDocType: String?): String? {
        val raw = rawDocType?.trim()?.uppercase() ?: return null
        return when {
            raw.startsWith("A") || raw.startsWith("API") -> "Permiso de Residencia"
            raw.startsWith("AT") -> "Tarjeta de Residencia"
            raw.startsWith("IC") || raw.startsWith("IE") -> "Tarjeta de Extranjero"
            raw.startsWith("NIE") -> "NIE"
            else -> "Documento de Residencia"
        }
    }

    fun isSpanishDni(result: ClassificationResult): Boolean {
        return result.countryCode == "ESP" &&
            result.documentType == DocumentType.ID_CARD &&
            !result.isResidencePermit
    }

    fun isSpanishTie(result: ClassificationResult): Boolean {
        return result.countryCode == "ESP" && result.isResidencePermit
    }

    fun isEuropeanIdCard(result: ClassificationResult): Boolean {
        return result.documentType == DocumentType.ID_CARD &&
            CountryRegistry.isEuropeanCountry(result.countryCode) &&
            result.countryCode != "DEU"
    }
}
