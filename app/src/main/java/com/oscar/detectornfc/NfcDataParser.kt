package com.oscar.detectornfc

import android.util.Log
import de.tsenger.androsmex.mrtd.DG1_Dnie
import de.tsenger.androsmex.mrtd.DG11
import de.tsenger.androsmex.mrtd.DG13
import org.jmrtd.lds.icao.DG1File
import org.jmrtd.lds.icao.DG11File as JmrtdDG11File
import org.jmrtd.lds.icao.MRZInfo
import java.io.ByteArrayInputStream

/**
 * Parsea los DataGroups leídos del chip NFC.
 *
 * - Método español: usa dniedroid/jmulticard (DG1_Dnie, DG11, DG13)
 * - Método ICAO alternativo: usa JMRTD (DG1File, DG11File, MRZInfo)
 */
class NfcDataParser {

    private val TAG = "NfcDataParser"

    fun parseRawData(rawData: RawNfcData): DniData {
        if (rawData.sessionStatus == NfcSessionStatus.FAILED) {
            val failureMessage = rawData.sessionError ?: "No se pudo completar la sesión NFC."
            Log.w(TAG, "Sesión NFC fallida: $failureMessage")
            return buildFailureData(rawData, failureMessage)
        }

        return when (rawData.readerMethod) {
            NfcReaderMethod.ICAO_JMRTD -> parseIcaoRawData(rawData)
            NfcReaderMethod.SPANISH_DNIE -> parseSpanishRawData(rawData)
        }
    }

    private fun parseSpanishRawData(rawData: RawNfcData): DniData {
        val dg1Bytes = rawData.dataGroups[1]
        val dg11Bytes = rawData.dataGroups[11]
        val dg13Bytes = rawData.dataGroups[13]

        Log.i(
            TAG,
            "parseSpanishRawData() - dg1=${dg1Bytes?.size ?: 0}B, dg11=${dg11Bytes?.size ?: 0}B, dg13=${dg13Bytes?.size ?: 0}B"
        )

        if (dg1Bytes == null && dg13Bytes == null) {
            val errorMessage = rawData.sessionError ?: "No se pudo leer DG1 ni DG13"
            Log.w(TAG, "Sin DG1 ni DG13 — datos insuficientes")
            return buildFailureData(rawData, errorMessage)
        }

        val dg1 = dg1Bytes?.let { safe { DG1_Dnie(it) } }
        val dg11 = dg11Bytes?.let { safe { DG11(it) } }
        val dg13 = dg13Bytes?.let { safe { DG13(it) } }
        val detected = detectDocumentProfile(dg1)

        val nombre = dg13?.getName()?.takeIf { it.isNotBlank() }
            ?: dg11?.getName()?.takeIf { it.isNotBlank() }
            ?: dg1?.getName()?.takeIf { it.isNotBlank() }

        val apellidos = if (dg13 != null) {
            val s1 = dg13.getSurName1()?.takeIf { it.isNotBlank() }
            val s2 = dg13.getSurName2()?.takeIf { it.isNotBlank() }
            when {
                s1 != null && s2 != null -> "$s1 $s2"
                s1 != null -> s1
                else -> dg1?.getSurname()?.takeIf { it.isNotBlank() }
            }
        } else {
            dg1?.getSurname()?.takeIf { it.isNotBlank() }
        }

        val genero = (dg13?.getSex() ?: dg1?.getSex())?.uppercase()?.let {
            when (it) {
                "F" -> "Femenino"
                "M" -> "Masculino"
                else -> null
            }
        }

        val nacionalidad = dg1?.getNationality()?.takeIf { it.isNotBlank() } ?: "ESP"
        val tipoDocumento = dg1?.getDocType()?.takeIf { it.isNotBlank() } ?: "DNI"
        val numeroDocumento = dg13?.getPersonalNumber()?.takeIf { it.isNotBlank() }
            ?: dg1?.getDocNumber()?.takeIf { it.isNotBlank() }
        val numeroSoporte = dg1?.getDocNumber()?.takeIf { it.isNotBlank() }

        val fechaNacimiento = (dg13?.getBirthDate()
            ?: dg11?.getBirthDate()
            ?: dg1?.getDateOfBirth())?.let { formatDate(it) }

        val lugarNacimiento = if (dg13 != null) {
            listOfNotNull(dg13.getBirthPopulation(), dg13.getBirthProvince())
                .filter { it.isNotBlank() }
                .joinToString(", ")
                .takeIf { it.isNotBlank() }
                ?: dg11?.getBirthPlace()?.takeIf { it.isNotBlank() }
        } else {
            dg11?.getBirthPlace()?.takeIf { it.isNotBlank() }
        }

        val domicilio = if (dg13 != null) {
            listOfNotNull(
                dg13.getActualAddress(),
                dg13.getActualPopulation(),
                dg13.getActualProvince()
            ).filter { it.isNotBlank() }.joinToString(", ").takeIf { it.isNotBlank() }
        } else if (dg11 != null) {
            listOfNotNull(
                dg11.getAddress(DG11.ADDR_DIRECCION),
                dg11.getAddress(DG11.ADDR_LOCALIDAD),
                dg11.getAddress(DG11.ADDR_PROVINCIA)
            ).filter { it.isNotBlank() }.joinToString(", ").takeIf { it.isNotBlank() }
        } else {
            null
        }

        val extractionError = if (nombre == null && apellidos == null && numeroDocumento == null) {
            "No se pudieron extraer datos clave del DNI"
        } else {
            null
        }
        val errorMessage = when {
            rawData.sessionStatus == NfcSessionStatus.PARTIAL && !rawData.sessionError.isNullOrBlank() -> rawData.sessionError
            extractionError != null -> extractionError
            else -> null
        }

        Log.i(
            TAG,
            "parseSpanishRawData() OK — nombre=${nombre != null}, apellidos=${apellidos != null}, nif=${numeroDocumento != null}, error=${errorMessage ?: "<ninguno>"}"
        )

        return DniData(
            genero = genero,
            nacionalidad = nacionalidad,
            tipoDocumento = tipoDocumento,
            numeroDocumento = numeroDocumento,
            numeroSoporte = numeroSoporte,
            nombre = nombre,
            apellidos = apellidos,
            nombrePadre = dg13?.getFatherName()?.takeIf { it.isNotBlank() },
            nombreMadre = dg13?.getMotherName()?.takeIf { it.isNotBlank() },
            fechaNacimiento = fechaNacimiento,
            lugarNacimiento = lugarNacimiento,
            domicilio = domicilio,
            uid = rawData.uid,
            can = rawData.can,
            error = errorMessage,
            documentType = detected.documentType.name,
            countryCode = detected.countryCode,
            countryName = detected.countryName,
            architecture = detected.architecture.name,
            readerMethod = rawData.readerMethod.name,
            accessMethodDetail = rawData.accessMethodDetail,
            fallbackUsed = rawData.fallbackUsed
        )
    }

    private fun parseIcaoRawData(rawData: RawNfcData): DniData {
        val dg1Bytes = rawData.dataGroups[1]
        if (dg1Bytes == null) {
            val errorMessage = rawData.sessionError ?: "No se pudo leer DG1 con el método ICAO."
            Log.w(TAG, "Lectura ICAO sin DG1: $errorMessage")
            return buildFailureData(rawData, errorMessage)
        }

        val dg1 = safe { DG1File(ByteArrayInputStream(dg1Bytes)) }
        val mrz = dg1?.mrzInfo
        if (mrz == null) {
            val errorMessage = rawData.sessionError ?: "No se pudo interpretar el MRZ del documento."
            Log.w(TAG, "No se pudo parsear MRZ con JMRTD")
            return buildFailureData(rawData, errorMessage)
        }

        val dg11 = rawData.dataGroups[11]?.let { safe { JmrtdDG11File(ByteArrayInputStream(it)) } }
        val detected = detectIcaoProfile(mrz)

        val nombre = mrz.secondaryIdentifierComponents
            ?.mapNotNull { normalizeMrzText(it) }
            ?.filter { it.isNotBlank() }
            ?.joinToString(" ")
            ?.takeIf { it.isNotBlank() }
        val apellidos = normalizeMrzText(mrz.primaryIdentifier)
        val numeroDocumento = normalizeMrzText(mrz.documentNumber)
        val numeroSoporte = numeroDocumento
        val nacionalidad = normalizeMrzText(mrz.nationality) ?: detected.countryCode
        val tipoDocumento = normalizeMrzText(mrz.documentCode) ?: detected.documentType.name
        val fechaNacimiento = formatDate(mrz.dateOfBirth)
        val lugarNacimiento = dg11?.placeOfBirth
            ?.mapNotNull { normalizeMrzText(it) }
            ?.joinToString(", ")
            ?.takeIf { it.isNotBlank() }
        val domicilio = dg11?.permanentAddress
            ?.mapNotNull { normalizeMrzText(it) }
            ?.joinToString(", ")
            ?.takeIf { it.isNotBlank() }

        val errorMessage = when {
            rawData.sessionStatus == NfcSessionStatus.PARTIAL && !rawData.sessionError.isNullOrBlank() -> rawData.sessionError
            nombre == null && apellidos == null && numeroDocumento == null -> "No se pudieron extraer datos clave del documento ICAO"
            else -> null
        }

        Log.i(
            TAG,
            "parseIcaoRawData() OK — nombre=${nombre != null}, apellidos=${apellidos != null}, doc=${numeroDocumento != null}"
        )

        return DniData(
            genero = parseGender(mrz),
            nacionalidad = nacionalidad,
            tipoDocumento = tipoDocumento,
            numeroDocumento = numeroDocumento,
            numeroSoporte = numeroSoporte,
            nombre = nombre,
            apellidos = apellidos,
            nombrePadre = null,
            nombreMadre = null,
            fechaNacimiento = fechaNacimiento,
            lugarNacimiento = lugarNacimiento,
            domicilio = domicilio,
            uid = rawData.uid,
            can = rawData.can,
            error = errorMessage,
            documentType = detected.documentType.name,
            countryCode = detected.countryCode,
            countryName = detected.countryName,
            architecture = detected.architecture.name,
            readerMethod = rawData.readerMethod.name,
            accessMethodDetail = rawData.accessMethodDetail,
            fallbackUsed = rawData.fallbackUsed
        )
    }

    private fun detectDocumentProfile(dg1: DG1_Dnie?): ClassificationResult {
        if (dg1 == null) {
            return ClassificationResult(
                documentType = DocumentType.UNKNOWN,
                countryCode = "UNK",
                countryName = "Desconocido",
                architecture = DocumentArchitecture.UNKNOWN
            )
        }
        return DocumentClassifier.classify(dg1.getDocType(), dg1.getIssuer())
    }

    private fun detectIcaoProfile(mrz: MRZInfo): ClassificationResult {
        return DocumentClassifier.classify(mrz.documentCode, mrz.issuingState)
    }

    private fun buildFailureData(rawData: RawNfcData, errorMessage: String): DniData {
        return DniData(
            genero = null,
            nacionalidad = null,
            tipoDocumento = null,
            numeroDocumento = null,
            numeroSoporte = null,
            nombre = null,
            apellidos = null,
            nombrePadre = null,
            nombreMadre = null,
            fechaNacimiento = null,
            lugarNacimiento = null,
            domicilio = null,
            uid = rawData.uid,
            can = rawData.can,
            error = errorMessage,
            readerMethod = rawData.readerMethod.name,
            accessMethodDetail = rawData.accessMethodDetail,
            fallbackUsed = rawData.fallbackUsed
        )
    }

    private fun normalizeMrzText(value: String?): String? {
        if (value.isNullOrBlank()) return null
        return value.replace('<', ' ')
            .replace(Regex("\\s+"), " ")
            .trim()
            .takeIf { it.isNotBlank() }
    }

    private fun parseGender(mrz: MRZInfo): String? {
        return when (mrz.gender.toString().uppercase()) {
            "F", "FEMALE" -> "Femenino"
            "M", "MALE" -> "Masculino"
            else -> null
        }
    }

    /**
     * Convierte varios formatos de fecha a "DD de mes de YYYY":
     *  - "YYMMDD"     (DG1 TD3 — pasaportes)
     *  - "YYYYMMDD"   (DG1 TD1 — DNIe)
     *  - "DD MM YYYY" (DG13 / DG11 español — con espacios)
     *  - "DD.MM.YYYY" (variante con puntos)
     */
    internal fun formatDate(raw: String?): String? {
        if (raw.isNullOrBlank()) return null
        val s = raw.trim()

        return when {
            s.contains(" ") || s.contains(".") -> {
                val parts = s.split(Regex("[\\s.]+"))
                if (parts.size != 3) return null
                buildDate(parts[0], parts[1].toIntOrNull() ?: return null, parts[2])
            }
            s.length == 6 -> {
                val yy = s.substring(0, 2).toIntOrNull() ?: return null
                val year = if (yy > 30) "19$yy" else "20$yy"
                val month = s.substring(2, 4).toIntOrNull() ?: return null
                val day = s.substring(4, 6)
                buildDate(day, month, year)
            }
            s.length == 8 -> {
                val year = s.substring(0, 4)
                val month = s.substring(4, 6).toIntOrNull() ?: return null
                val day = s.substring(6, 8)
                buildDate(day, month, year)
            }
            else -> null
        }
    }

    private fun buildDate(day: String, month: Int, year: String): String? {
        val m = monthName(month) ?: return null
        return "$day de $m de $year"
    }

    private fun monthName(m: Int) = when (m) {
        1 -> "enero"
        2 -> "febrero"
        3 -> "marzo"
        4 -> "abril"
        5 -> "mayo"
        6 -> "junio"
        7 -> "julio"
        8 -> "agosto"
        9 -> "septiembre"
        10 -> "octubre"
        11 -> "noviembre"
        12 -> "diciembre"
        else -> null
    }

    /** Ejecuta el bloque y devuelve null si lanza cualquier excepción. */
    private fun <T> safe(block: () -> T): T? = runCatching(block).getOrNull()
}