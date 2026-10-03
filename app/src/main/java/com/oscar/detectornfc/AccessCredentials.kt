package com.oscar.detectornfc

/**
 * Credenciales de acceso al chip NFC de un documento de identidad.
 *
 * Los documentos europeos utilizan distintas contraseñas para establecer el
 * canal seguro (PACE) o la sesión BAC, según el país:
 *
 *  - CAN (Card Access Number, 6 dígitos impresos en el documento):
 *      DNIe/TIE español, ID Países Bajos (anverso), Cartão de Cidadão
 *      portugués modelo 2024+ (anverso), CIE italiano, eDO polaco.
 *  - MRZ (número de documento + fecha de nacimiento + fecha de caducidad):
 *      pasaportes (BAC o PACE-MRZ), CNIe francesa (BAC reforzado con PACE).
 *  - PIN (6 dígitos): eID alemán; los datos personales siguen requiriendo
 *      Terminal Authentication con certificados oficiales (EAC v2), por lo
 *      que una app de terceros solo puede volcar la estructura del chip.
 */
data class AccessCredentials(
    val can: String? = null,
    val mrz: MrzCredentials? = null,
    val pin: String? = null
) {

    fun hasCan(): Boolean = isValidCan(can)

    fun hasMrz(): Boolean = mrz?.isComplete() == true

    fun hasPin(): Boolean = !pin.isNullOrBlank()

    fun hasAnyAccessMethod(): Boolean = hasCan() || hasMrz() || hasPin()

    /** Descripción de los métodos disponibles, p. ej. "CAN + MRZ" (para logs/UI). */
    fun describe(): String {
        val methods = buildList {
            if (hasCan()) add("CAN")
            if (hasMrz()) add("MRZ")
            if (hasPin()) add("PIN")
        }
        return if (methods.isEmpty()) "sin credenciales" else methods.joinToString(" + ")
    }

    companion object {
        fun isValidCan(can: String?): Boolean =
            can != null && can.length == 6 && can.all { it.isDigit() }

        fun withCan(can: String): AccessCredentials = AccessCredentials(can = can)

        fun withMrz(documentNumber: String, dateOfBirth: String, dateOfExpiry: String): AccessCredentials =
            AccessCredentials(mrz = MrzCredentials(documentNumber, dateOfBirth, dateOfExpiry))

        /**
         * Construye las credenciales a partir de los valores crudos recibidos
         * (p. ej. los extras del Intent de NFCScanActivity). El MRZ solo se
         * incluye si los tres campos están completos; el CAN se normaliza.
         */
        fun fromValues(
            can: String?,
            documentNumber: String?,
            dateOfBirth: String?,
            dateOfExpiry: String?
        ): AccessCredentials {
            val normalizedCan = can?.trim()?.takeIf { it.isNotEmpty() }
            val mrz = if (!documentNumber.isNullOrBlank() &&
                !dateOfBirth.isNullOrBlank() &&
                !dateOfExpiry.isNullOrBlank()
            ) {
                MrzCredentials(
                    documentNumber.trim().uppercase(),
                    dateOfBirth.trim(),
                    dateOfExpiry.trim()
                )
            } else {
                null
            }
            return AccessCredentials(can = normalizedCan, mrz = mrz)
        }
    }
}

/**
 * Datos del MRZ (ICAO 9303) necesarios para derivar las claves BAC/PACE-MRZ.
 * Basta el número de documento (sin dígito de control) y las dos fechas en
 * formato yymmdd; jmrtd calcula los dígitos de control internamente.
 */
data class MrzCredentials(
    val documentNumber: String,
    val dateOfBirth: String,
    val dateOfExpiry: String
) {

    fun isComplete(): Boolean =
        documentNumber.isNotBlank() &&
            MrzCredentials.isValidMrzDate(dateOfBirth) &&
            MrzCredentials.isValidMrzDate(dateOfExpiry)

    override fun toString(): String =
        "MrzCredentials(documentNumber=$documentNumber, dateOfBirth=$dateOfBirth, dateOfExpiry=***)"

    companion object {
        /** Valida una fecha MRZ: 6 dígitos con mes 01-12 y día 01-31. */
        fun isValidMrzDate(value: String): Boolean {
            if (value.length != 6 || !value.all { it.isDigit() }) return false
            val month = value.substring(2, 4).toInt()
            val day = value.substring(4, 6).toInt()
            return month in 1..12 && day in 1..31
        }

        /**
         * Convierte fechas de entrada comunes al formato MRZ yymmdd:
         *  - "dd/mm/yyyy" o "dd-mm-yyyy" (UI de la aplicación)
         *  - "ddMMyyyy" (sin separadores)
         */
        fun toMrzDate(raw: String): String? {
            val trimmed = raw.trim()
            if (trimmed.isEmpty()) return null

            val parts = trimmed.split('/', '-')
            if (parts.size == 3) {
                val day = parts[0].padStart(2, '0')
                val month = parts[1].padStart(2, '0')
                val year = parts[2]
                if (year.length != 4 || !year.all { it.isDigit() }) return null
                return "$year${month}$day".takeIf { isValidMrzDate(it) }
            }

            if (trimmed.length == 8 && trimmed.all { it.isDigit() }) {
                val day = trimmed.substring(0, 2)
                val month = trimmed.substring(2, 4)
                val year = trimmed.substring(4, 8)
                return "$year$month$day".takeIf { isValidMrzDate(it) }
            }

            if (trimmed.length == 6 && trimmed.all { it.isDigit() }) {
                return trimmed.takeIf { isValidMrzDate(it) }
            }

            return null
        }
    }
}
