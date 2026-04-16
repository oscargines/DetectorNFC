# Guía de Implementación: Parsers Multi-País para MRTD Europeos
## Código Kotlin/Java - Android Studio

---

## 1. Detección de Documento y País

### Enum: Tipos de Documentos
```kotlin
package com.ejemplo.nfc.models

enum class DocumentType {
    PASSPORT,           // TD3 - Pasaporte (P<)
    ID_CARD,           // DV1/DV2 - Carnet identidad (C<)
    DRIVING_LICENSE,   // Permiso conducir (D<)
    GERMAN_EID,        // Excepción Alemania
    UNKNOWN
}

enum class DocumentArchitecture {
    COMPACT,           // Pasaportes: DG1, DG2, (DG15)
    GRANULAR,         // ID Cards: DG1, DG2, DG7, DG11, DG13
    SPECIAL           // Alemania, dinámicos
}

// Códigos de país ISO 3166-1 alpha-3
data class CountryInfo(
    val code: String,      // "ESP", "FRA", "DEU"
    val name: String,
    val architecture: DocumentArchitecture,
    val supportedDataGroups: List<Int>
)

object CountryRegistry {
    private val countries = mapOf(
        "ESP" to CountryInfo("ESP", "España", DocumentArchitecture.GRANULAR, 
                            listOf(1, 2, 7, 11, 13)),
        "PRT" to CountryInfo("PRT", "Portugal", DocumentArchitecture.GRANULAR,
                            listOf(1, 2, 7, 11, 13)),
        "FRA" to CountryInfo("FRA", "Francia", DocumentArchitecture.GRANULAR,
                            listOf(1, 2, 7, 11, 13)),
        "ITA" to CountryInfo("ITA", "Italia", DocumentArchitecture.GRANULAR,
                            listOf(1, 2, 7, 11, 13)),
        "BEL" to CountryInfo("BEL", "Bélgica", DocumentArchitecture.GRANULAR,
                            listOf(1, 2, 7, 11, 13)),
        "DEU" to CountryInfo("DEU", "Alemania", DocumentArchitecture.SPECIAL,
                            listOf()),  // Especial
        "GBR" to CountryInfo("GBR", "Reino Unido", DocumentArchitecture.COMPACT,
                            listOf(1, 2, 15)),
        "NLD" to CountryInfo("NLD", "Países Bajos", DocumentArchitecture.GRANULAR,
                            listOf(1, 2, 7, 11, 13))
    )
    
    fun getCountry(code: String): CountryInfo? = countries[code]
}
```

### Detector de Documento basado en MRZ
```kotlin
package com.ejemplo.nfc.core

class MRZDocumentDetector {
    
    data class DetectionResult(
        val documentType: DocumentType,
        val countryCode: String,
        val docNumber: String,
        val dateOfBirth: String,
        val dateOfExpiry: String,
        val isValid: Boolean,
        val validationError: String? = null
    )
    
    /**
     * Detecta tipo de documento y país a partir del MRZ
     * MRZ Format: 2 líneas de 44 caracteres (para pasaporte TD3)
     */
    fun detectFromMRZ(mrzLine1: String, mrzLine2: String): DetectionResult {
        return try {
            // Validar formato básico
            if (mrzLine1.length < 45 || mrzLine2.length < 45) {
                return DetectionResult(
                    DocumentType.UNKNOWN, "", "", "", "",
                    false, "MRZ format invalid"
                )
            }
            
            // Posición 0: Tipo de documento
            val docTypeChar = mrzLine1[0]
            val documentType = when (docTypeChar) {
                'P' -> DocumentType.PASSPORT
                'C' -> DocumentType.ID_CARD
                'D' -> DocumentType.DRIVING_LICENSE
                else -> DocumentType.UNKNOWN
            }
            
            // Posición 2-4: Código de país
            val countryCode = mrzLine1.substring(2, 5)
            
            // Manejo especial para Alemania
            if (documentType == DocumentType.ID_CARD && countryCode == "DEU") {
                return DetectionResult(
                    DocumentType.GERMAN_EID, countryCode, "", "", "",
                    true, "German eID requires special handler"
                )
            }
            
            // Posición 44: Última línea, diferentes posiciones según tipo
            val docNumber: String
            val dateOfBirth: String
            val dateOfExpiry: String
            
            if (documentType == DocumentType.PASSPORT) {
                // TD3 (Pasaporte) - 44 caracteres
                docNumber = mrzLine1.substring(44, 53)  // Error: fuera de rango
                // Nota: Necesita ajuste según formato real
                // Típicamente: línea 2 contiene DOB, expiry
                dateOfBirth = mrzLine2.substring(13, 19)
                dateOfExpiry = mrzLine2.substring(21, 27)
            } else if (documentType == DocumentType.ID_CARD) {
                // DV1 (Carnet identidad) - formato más compacto
                docNumber = mrzLine1.substring(4, 13)
                dateOfBirth = mrzLine2.substring(13, 19)
                dateOfExpiry = mrzLine2.substring(21, 27)
            } else {
                return DetectionResult(
                    DocumentType.UNKNOWN, countryCode, "", "", "",
                    false, "Unknown document type: $docTypeChar"
                )
            }
            
            // Validar checksums (simplificado)
            val isValid = validateMRZChecksum(mrzLine1, mrzLine2)
            
            DetectionResult(
                documentType = documentType,
                countryCode = countryCode,
                docNumber = docNumber,
                dateOfBirth = dateOfBirth,
                dateOfExpiry = dateOfExpiry,
                isValid = isValid
            )
            
        } catch (e: Exception) {
            DetectionResult(
                DocumentType.UNKNOWN, "", "", "", "",
                false, "Detection error: ${e.message}"
            )
        }
    }
    
    private fun validateMRZChecksum(line1: String, line2: String): Boolean {
        // Implementar validación de checksum según ICAO 9303
        // Pseudocódigo simplificado
        return try {
            val checkDigits = calculateMRZChecksum(line1 + line2)
            true // Placeholder
        } catch (e: Exception) {
            false
        }
    }
    
    private fun calculateMRZChecksum(input: String): Int {
        // Implementación del algoritmo de checksum ICAO
        // Ver Doc 9303 para especificaciones exactas
        val values = mapOf(
            '0' to 0, '1' to 1, '2' to 2, '3' to 3, '4' to 4,
            '5' to 5, '6' to 6, '7' to 7, '8' to 8, '9' to 9,
            'A' to 10, 'B' to 11, 'C' to 12, 'D' to 13, 'E' to 14,
            'F' to 15, 'G' to 16, 'H' to 17, 'I' to 18, 'J' to 19,
            'K' to 20, 'L' to 21, 'M' to 22, 'N' to 23, 'O' to 24,
            'P' to 25, 'Q' to 26, 'R' to 27, 'S' to 28, 'T' to 29,
            'U' to 30, 'V' to 31, 'W' to 32, 'X' to 33, 'Y' to 34,
            'Z' to 35, '<' to 0
        )
        
        var sum = 0
        var weight = 0
        val weights = intArrayOf(7, 3, 1)
        
        for (char in input) {
            val value = values[char] ?: 0
            sum += value * weights[weight % 3]
            weight++
        }
        
        return sum % 10
    }
}
```

---

## 2. Factory Pattern para Crear el Parser Correcto

```kotlin
package com.ejemplo.nfc.core

import com.ejemplo.nfc.models.*
import com.ejemplo.nfc.parsers.*
import com.ejemplo.nfc.countries.*

/**
 * Factory que crea el parser apropriado según tipo y país de documento
 */
class DocumentParserFactory {
    
    interface DocumentParser {
        fun parseFromNFC(dataBlob: Map<Int, ByteArray>): ParsedDocument
    }
    
    fun createParser(
        documentType: DocumentType,
        countryCode: String,
        architecture: DocumentArchitecture
    ): DocumentParser {
        return when {
            // Casos especiales primero
            documentType == DocumentType.GERMAN_EID -> {
                GermanEIDParser()  // Requiere biblioteca especial
            }
            
            // Pasaportes (Arquitectura Compacta)
            documentType == DocumentType.PASSPORT -> {
                when (countryCode) {
                    "ESP" -> SpanishPassportParser()
                    else -> GenericPassportParser()
                }
            }
            
            // Carnet de Identidad (Arquitectura Granular)
            documentType == DocumentType.ID_CARD && 
            architecture == DocumentArchitecture.GRANULAR -> {
                when (countryCode) {
                    "ESP" -> SpanishIDCardParser()  // Tu código DNI existente
                    "PRT" -> PortugueseIDCardParser()
                    "FRA" -> FrenchIDCardParser()
                    "ITA" -> ItalianIDCardParser()
                    "BEL" -> BelgianIDCardParser()
                    "NLD" -> DutchIDCardParser()
                    else -> GenericIDCardParser()
                }
            }
            
            // Permisos de conducir
            documentType == DocumentType.DRIVING_LICENSE -> {
                when (countryCode) {
                    "ESP" -> SpanishDrivingLicenseParser()
                    else -> GenericDrivingLicenseParser()
                }
            }
            
            else -> GenericPassportParser()  // Fallback
        }
    }
}
```

---

## 3. Parsers Base Genéricos

### Parser Genérico Pasaporte (Arquitectura Compacta)
```kotlin
package com.ejemplo.nfc.parsers

import com.ejemplo.nfc.data_groups.*
import com.ejemplo.nfc.models.*

open class GenericPassportParser : DocumentParserFactory.DocumentParser {
    
    override fun parseFromNFC(dataBlob: Map<Int, ByteArray>): ParsedDocument {
        return try {
            // Pasaportes siguen arquitectura compacta: DG1, DG2, (DG15)
            
            val dg1Data = if (dataBlob.containsKey(1)) {
                DG1Parser().parse(dataBlob[1]!!)
            } else {
                throw Exception("DG1 (Biographic Data) is mandatory")
            }
            
            val dg2Data = if (dataBlob.containsKey(2)) {
                DG2Parser().parse(dataBlob[2]!!)
            } else {
                throw Exception("DG2 (Facial Image) is mandatory")
            }
            
            // DG15 (Active Authentication) es opcional
            val dg15Data = if (dataBlob.containsKey(15)) {
                try {
                    DG15Parser().parse(dataBlob[15]!!)
                } catch (e: Exception) {
                    Log.w("DG15Parser", "Failed to parse DG15: ${e.message}")
                    null
                }
            } else {
                null
            }
            
            ParsedDocument(
                documentType = DocumentType.PASSPORT,
                biographicData = dg1Data,
                facialImage = dg2Data,
                activeAuthentication = dg15Data,
                rawData = dataBlob
            )
            
        } catch (e: Exception) {
            throw DocumentParsingException("Failed to parse passport: ${e.message}", e)
        }
    }
}

open class GenericIDCardParser : DocumentParserFactory.DocumentParser {
    
    override fun parseFromNFC(dataBlob: Map<Int, ByteArray>): ParsedDocument {
        return try {
            // ID Cards siguen arquitectura granular: DG1, DG2, (DG7, DG11, DG13)
            
            val dg1Data = if (dataBlob.containsKey(1)) {
                DG1Parser().parse(dataBlob[1]!!)
            } else {
                throw Exception("DG1 (Biographic Data) is mandatory")
            }
            
            val dg2Data = if (dataBlob.containsKey(2)) {
                DG2Parser().parse(dataBlob[2]!!)
            } else {
                throw Exception("DG2 (Facial Image) is mandatory")
            }
            
            // Datos opcionales
            val dg7Data = parseOptional(dataBlob, 7, DG7Parser())
            val dg11Data = parseOptional(dataBlob, 11, DG11Parser())
            val dg13Data = parseOptional(dataBlob, 13, DG13Parser())
            
            ParsedDocument(
                documentType = DocumentType.ID_CARD,
                biographicData = dg1Data,
                facialImage = dg2Data,
                signature = dg7Data,
                additionalData = dg11Data,
                biometricData = dg13Data,
                rawData = dataBlob
            )
            
        } catch (e: Exception) {
            throw DocumentParsingException("Failed to parse ID Card: ${e.message}", e)
        }
    }
    
    protected inline fun <T> parseOptional(
        dataBlob: Map<Int, ByteArray>,
        dgNumber: Int,
        parser: DataGroupParser<T>
    ): T? {
        return if (dataBlob.containsKey(dgNumber)) {
            try {
                parser.parse(dataBlob[dgNumber]!!)
            } catch (e: Exception) {
                Log.w("DGParser", "Failed to parse DG$dgNumber: ${e.message}")
                null
            }
        } else {
            null
        }
    }
}
```

---

## 4. Parsers Específicos por País

### Parser DNI España (Tu código existente refactorizado)
```kotlin
package com.ejemplo.nfc.countries.spain

import com.ejemplo.nfc.parsers.*
import com.ejemplo.nfc.models.*

class SpanishIDCardParser : GenericIDCardParser() {
    
    override fun parseFromNFC(dataBlob: Map<Int, ByteArray>): ParsedDocument {
        // Usar la implementación base genérica
        // Tu código dniedroid.aar ya hace esto correctamente
        
        val document = super.parseFromNFC(dataBlob)
        
        // Validaciones específicas de España si las hay
        // Por ejemplo: validar que DG13 contiene huellas dactilares
        
        return document.copy(
            countryCode = "ESP",
            countryName = "España"
        )
    }
}

class SpanishPassportParser : GenericPassportParser() {
    
    override fun parseFromNFC(dataBlob: Map<Int, ByteArray>): ParsedDocument {
        val document = super.parseFromNFC(dataBlob)
        
        return document.copy(
            countryCode = "ESP",
            countryName = "España"
        )
    }
}
```

### Parser Documento de Identidad Francia
```kotlin
package com.ejemplo.nfc.countries.france

import com.ejemplo.nfc.parsers.*
import com.ejemplo.nfc.models.*

class FrenchIDCardParser : GenericIDCardParser() {
    
    override fun parseFromNFC(dataBlob: Map<Int, ByteArray>): ParsedDocument {
        val document = super.parseFromNFC(dataBlob)
        
        // Validaciones específicas francesas
        // La CNIE francesa puede tener variaciones en DG11
        
        return document.copy(
            countryCode = "FRA",
            countryName = "Francia"
        )
    }
}
```

### Parser Especial - Alemania (Excepción)
```kotlin
package com.ejemplo.nfc.countries.germany

import com.ejemplo.nfc.parsers.*
import com.ejemplo.nfc.models.*

/**
 * ⚠️ IMPORTANTE: Alemania usa eID que NO sigue ICAO 9303
 * 
 * Requiere biblioteca especial:
 * - eIDAS conformance library
 * - Manejo de EF_CardAccess en lugar de EF_COM
 * - Estructura de datos completamente diferente
 */
class GermanEIDParser : DocumentParserFactory.DocumentParser {
    
    override fun parseFromNFC(dataBlob: Map<Int, ByteArray>): ParsedDocument {
        throw Exception(
            "German eID (NPA) requires special eIDAS library. " +
            "This is NOT ICAO 9303 compatible. " +
            "Use: de.gematik.ti.cardreader or similar"
        )
    }
}
```

---

## 5. Data Group Parsers (Reutilizables)

### Interfaz base para parsers de DG
```kotlin
package com.ejemplo.nfc.data_groups

interface DataGroupParser<T> {
    fun parse(bytes: ByteArray): T
}

class DG1Parser : DataGroupParser<BiographicData> {
    
    override fun parse(bytes: ByteArray): BiographicData {
        return try {
            // Parsear usando TLV (Tag-Length-Value)
            val tlvData = TLVParser.parse(bytes)
            
            // Campos típicos en DG1
            val surname = tlvData["surname"]?.decodeToString() ?: ""
            val givenNames = tlvData["given_names"]?.decodeToString() ?: ""
            val dateOfBirth = tlvData["date_of_birth"]?.decodeToString() ?: ""
            val dateOfExpiry = tlvData["date_of_expiry"]?.decodeToString() ?: ""
            val documentNumber = tlvData["document_number"]?.decodeToString() ?: ""
            val nationality = tlvData["nationality"]?.decodeToString() ?: ""
            val sex = tlvData["sex"]?.decodeToString() ?: ""
            
            BiographicData(
                surname = surname,
                givenNames = givenNames,
                dateOfBirth = parseDate(dateOfBirth),
                dateOfExpiry = parseDate(dateOfExpiry),
                documentNumber = documentNumber,
                nationality = nationality,
                sex = sex
            )
            
        } catch (e: Exception) {
            throw DataGroupParsingException("Failed to parse DG1", e)
        }
    }
    
    private fun parseDate(dateStr: String): Date? {
        return try {
            // YYMMDD format
            val sdf = SimpleDateFormat("yyMMdd", Locale.US)
            sdf.parse(dateStr)
        } catch (e: Exception) {
            null
        }
    }
}

class DG2Parser : DataGroupParser<FacialImage> {
    
    override fun parse(bytes: ByteArray): FacialImage {
        return try {
            // DG2 contiene imagen codificada (JPEG o JPEG2000)
            // Necesita parsear TLV y extraer datos de imagen
            
            val tlvData = TLVParser.parse(bytes)
            val imageData = tlvData["image_data"] ?: bytes
            
            // Detectar formato
            val format = detectImageFormat(imageData)
            
            FacialImage(
                imageBytes = imageData,
                format = format,
                width = 352,  // Típico
                height = 440  // Típico
            )
            
        } catch (e: Exception) {
            throw DataGroupParsingException("Failed to parse DG2", e)
        }
    }
    
    private fun detectImageFormat(data: ByteArray): ImageFormat {
        return when {
            // JPEG: FF D8 FF
            data.size >= 3 && data[0] == 0xFF.toByte() && 
            data[1] == 0xD8.toByte() && data[2] == 0xFF.toByte() -> {
                ImageFormat.JPEG
            }
            // JPEG2000: 00 00 00 0C jP2 
            data.size >= 12 && data[4] == 'j'.code.toByte() &&
            data[5] == 'P'.code.toByte() && data[6] == '2'.code.toByte() -> {
                ImageFormat.JPEG2000
            }
            else -> ImageFormat.UNKNOWN
        }
    }
}

class DG7Parser : DataGroupParser<SignatureImage> {
    
    override fun parse(bytes: ByteArray): SignatureImage {
        return try {
            val tlvData = TLVParser.parse(bytes)
            val signatureData = tlvData["signature_data"] ?: bytes
            
            val format = detectSignatureFormat(signatureData)
            
            SignatureImage(
                imageBytes = signatureData,
                format = format
            )
            
        } catch (e: Exception) {
            throw DataGroupParsingException("Failed to parse DG7", e)
        }
    }
    
    private fun detectSignatureFormat(data: ByteArray): SignatureFormat {
        return when {
            // WSQ format
            data.size >= 4 && data[0] == 0xFF.toByte() && 
            data[1] == 0xA0.toByte() -> SignatureFormat.WSQ
            // JPEG format
            data.size >= 3 && data[0] == 0xFF.toByte() && 
            data[1] == 0xD8.toByte() -> SignatureFormat.JPEG
            else -> SignatureFormat.UNKNOWN
        }
    }
}

class DG11Parser : DataGroupParser<AdditionalData> {
    
    override fun parse(bytes: ByteArray): AdditionalData {
        return try {
            val tlvData = TLVParser.parse(bytes)
            
            AdditionalData(
                placeOfBirth = tlvData["place_of_birth"]?.decodeToString(),
                address = tlvData["address"]?.decodeToString(),
                municipality = tlvData["municipality"]?.decodeToString(),
                province = tlvData["province"]?.decodeToString(),
                country = tlvData["country"]?.decodeToString(),
                telephone = tlvData["telephone"]?.decodeToString(),
                email = tlvData["email"]?.decodeToString(),
                professionalActivity = tlvData["professional_activity"]?.decodeToString(),
                rawData = bytes
            )
            
        } catch (e: Exception) {
            throw DataGroupParsingException("Failed to parse DG11", e)
        }
    }
}

class DG13Parser : DataGroupParser<BiometricData> {
    
    override fun parse(bytes: ByteArray): BiometricData {
        return try {
            val tlvData = TLVParser.parse(bytes)
            
            // DG13 contiene deltas de huellas dactilares
            // Estructura: metadata + deltas comprimidos
            
            BiometricData(
                fingerprintDeltas = tlvData["fingerprint_deltas"],
                fingerPositions = parseFingerPositions(tlvData),
                rawData = bytes
            )
            
        } catch (e: Exception) {
            throw DataGroupParsingException("Failed to parse DG13", e)
        }
    }
    
    private fun parseFingerPositions(tlv: Map<String, ByteArray>): List<String> {
        // Parsear qué dedos están presentes
        return listOf() // Implementar según ISO estándar
    }
}

class DG15Parser : DataGroupParser<ActiveAuthentication> {
    
    override fun parse(bytes: ByteArray): ActiveAuthentication {
        return try {
            val tlvData = TLVParser.parse(bytes)
            
            ActiveAuthentication(
                publicKeyAlgorithm = tlvData["public_key_algorithm"]?.decodeToString(),
                publicKeyBytes = tlvData["public_key"],
                rawData = bytes
            )
            
        } catch (e: Exception) {
            throw DataGroupParsingException("Failed to parse DG15", e)
        }
    }
}
```

---

## 6. Utility: Parser TLV (Tag-Length-Value)

```kotlin
package com.ejemplo.nfc.core

object TLVParser {
    
    fun parse(data: ByteArray): Map<String, ByteArray> {
        val result = mutableMapOf<String, ByteArray>()
        var offset = 0
        
        while (offset < data.size) {
            // Leer TAG (1-2 bytes)
            val tag = data[offset].toInt() and 0xFF
            offset++
            
            val isLongForm = (tag and 0x1F) == 0x1F
            var actualTag = tag
            
            if (isLongForm) {
                // Tag de forma larga (2+ bytes)
                while ((data[offset].toInt() and 0x80) != 0) {
                    actualTag = (actualTag shl 8) or (data[offset].toInt() and 0xFF)
                    offset++
                }
                actualTag = (actualTag shl 8) or (data[offset].toInt() and 0xFF)
                offset++
            }
            
            // Leer LENGTH
            val length = readLength(data, offset)
            offset += getLengthSize(data[offset])
            
            // Leer VALUE
            val value = data.sliceArray(offset until offset + length)
            offset += length
            
            // Mapear a nombre legible
            val tagName = mapTagToName(actualTag)
            result[tagName] = value
        }
        
        return result
    }
    
    private fun readLength(data: ByteArray, offset: Int): Int {
        val firstByte = data[offset].toInt() and 0xFF
        
        return if ((firstByte and 0x80) == 0) {
            // Short form (1 byte)
            firstByte
        } else {
            // Long form
            val numOctets = firstByte and 0x7F
            var length = 0
            for (i in 0 until numOctets) {
                length = (length shl 8) or (data[offset + 1 + i].toInt() and 0xFF)
            }
            length
        }
    }
    
    private fun getLengthSize(lengthByte: Byte): Int {
        val firstByte = lengthByte.toInt() and 0xFF
        return if ((firstByte and 0x80) == 0) 1 else (firstByte and 0x7F) + 1
    }
    
    private fun mapTagToName(tag: Int): String {
        // Mapear tags ICAO 9303 a nombres
        return when (tag) {
            0x5F20 -> "surname"
            0x5F21 -> "given_names"
            0x5F22 -> "date_of_birth"
            0x5F23 -> "date_of_expiry"
            0x5F24 -> "document_number"
            0x5F25 -> "nationality"
            0x5F26 -> "sex"
            0x5F27 -> "place_of_birth"
            0x5F28 -> "address"
            0x5F29 -> "professional_activity"
            0x5F2A -> "image_data"
            else -> "unknown_$tag"
        }
    }
}
```

---

## 7. Ejemplo de Uso Completo

```kotlin
package com.ejemplo.nfc.activities

import android.nfc.NfcAdapter
import android.nfc.Tag
import android.nfc.tech.IsoDep
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import com.ejemplo.nfc.core.*
import com.ejemplo.nfc.models.*
import kotlinx.coroutines.*

class DocumentReaderActivity : AppCompatActivity() {
    
    private val nfcAdapter by lazy { NfcAdapter.getDefaultAdapter(this) }
    private val mrzDetector = MRZDocumentDetector()
    private val parserFactory = DocumentParserFactory()
    private val mrzExtractor = MRZExtractor()  // OCR
    
    override fun onResume() {
        super.onResume()
        val intent = intent
        val tag: Tag? = intent?.getParcelableExtra(NfcAdapter.EXTRA_TAG)
        
        tag?.let { handleNFCTag(it) }
    }
    
    private fun handleNFCTag(tag: Tag) {
        viewModelScope.launch(Dispatchers.Default) {
            try {
                // Paso 1: Extraer MRZ usando OCR (desde cámara)
                val (mrzLine1, mrzLine2) = mrzExtractor.extractFromCamera()
                
                // Paso 2: Detectar tipo de documento
                val detection = mrzDetector.detectFromMRZ(mrzLine1, mrzLine2)
                
                if (!detection.isValid) {
                    showError("Invalid MRZ: ${detection.validationError}")
                    return@launch
                }
                
                // Paso 3: Establecer conexión NFC
                val isoDep = IsoDep.get(tag)
                isoDep?.connect()
                
                // Paso 4: Realizar BAC (Basic Access Control)
                val bacData = BAC(
                    documentNumber = detection.docNumber,
                    dateOfBirth = detection.dateOfBirth,
                    dateOfExpiry = detection.dateOfExpiry
                )
                
                val bacKeys = bacData.computeKeys()
                val sessionKeys = bacData.authenticate(isoDep, bacKeys)
                
                // Paso 5: Leer Data Groups
                val dataBlob = readDataGroups(
                    isoDep = isoDep,
                    sessionKeys = sessionKeys,
                    supportedDGs = CountryRegistry.getCountry(detection.countryCode)
                        ?.supportedDataGroups ?: listOf(1, 2)
                )
                
                // Paso 6: Crear parser apropiado
                val countryInfo = CountryRegistry.getCountry(detection.countryCode)
                    ?: throw Exception("Unknown country: ${detection.countryCode}")
                
                val parser = parserFactory.createParser(
                    documentType = detection.documentType,
                    countryCode = detection.countryCode,
                    architecture = countryInfo.architecture
                )
                
                // Paso 7: Parsear documento
                val parsedDocument = parser.parseFromNFC(dataBlob)
                
                // Paso 8: Mostrar resultados
                withContext(Dispatchers.Main) {
                    displayDocument(parsedDocument)
                }
                
            } catch (e: DocumentParsingException) {
                showError("Parsing Error: ${e.message}")
            } catch (e: Exception) {
                showError("Unexpected Error: ${e.message}")
            }
        }
    }
    
    private fun readDataGroups(
        isoDep: IsoDep,
        sessionKeys: SessionKey,
        supportedDGs: List<Int>
    ): Map<Int, ByteArray> {
        val result = mutableMapOf<Int, ByteArray>()
        
        for (dgNumber in supportedDGs) {
            try {
                // Enviar comando APDU para leer DG específico
                val command = buildAPDUForDG(dgNumber)
                val response = isoDep.transceive(command)
                result[dgNumber] = response
            } catch (e: Exception) {
                Log.w("DGReader", "Failed to read DG$dgNumber: ${e.message}")
                // Continuar con siguiente DG
            }
        }
        
        return result
    }
    
    private fun buildAPDUForDG(dgNumber: Int): ByteArray {
        // Construir comando APDU para leer DG específico
        val dgFile = when (dgNumber) {
            1 -> 0x0101  // EF_DG1
            2 -> 0x0102  // EF_DG2
            7 -> 0x0107  // EF_DG7
            11 -> 0x010B // EF_DG11
            13 -> 0x010D // EF_DG13
            15 -> 0x010F // EF_DG15
            else -> return byteArrayOf()
        }
        
        // SELECT FILE command
        return byteArrayOf(
            0x00, 0xA4, 0x02, 0x0C,  // CLA INS P1 P2
            0x02,                     // Lc
            (dgFile shr 8).toByte(),
            dgFile.toByte()
        )
    }
    
    private fun displayDocument(document: ParsedDocument) {
        // Mostrar datos parsados en UI
        val textView = findViewById<TextView>(R.id.documentData)
        textView.text = buildString {
            append("Documento: ${document.documentType}\n")
            append("País: ${document.countryName}\n")
            append("Nombre: ${document.biographicData?.givenNames} ${document.biographicData?.surname}\n")
            append("Nacimiento: ${document.biographicData?.dateOfBirth}\n")
            append("Caducidad: ${document.biographicData?.dateOfExpiry}\n")
            if (document.additionalData != null) {
                append("Dirección: ${document.additionalData.address}\n")
                append("Municipio: ${document.additionalData.municipality}\n")
            }
        }
        
        // Mostrar foto
        if (document.facialImage != null) {
            val bitmap = BitmapFactory.decodeByteArray(
                document.facialImage.imageBytes,
                0,
                document.facialImage.imageBytes.size
            )
            findViewById<ImageView>(R.id.documentPhoto).setImageBitmap(bitmap)
        }
    }
}
```

---

## 8. Modelos de Datos

```kotlin
package com.ejemplo.nfc.models

import java.util.Date

data class ParsedDocument(
    val documentType: DocumentType,
    val countryCode: String = "",
    val countryName: String = "",
    val biographicData: BiographicData?,
    val facialImage: FacialImage?,
    val signature: SignatureImage? = null,
    val additionalData: AdditionalData? = null,
    val biometricData: BiometricData? = null,
    val activeAuthentication: ActiveAuthentication? = null,
    val rawData: Map<Int, ByteArray> = emptyMap()
)

data class BiographicData(
    val surname: String,
    val givenNames: String,
    val dateOfBirth: Date?,
    val dateOfExpiry: Date?,
    val documentNumber: String,
    val nationality: String,
    val sex: String
)

data class FacialImage(
    val imageBytes: ByteArray,
    val format: ImageFormat,
    val width: Int = 0,
    val height: Int = 0
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is FacialImage) return false
        if (!imageBytes.contentEquals(other.imageBytes)) return false
        if (format != other.format) return false
        if (width != other.width) return false
        if (height != other.height) return false
        return true
    }

    override fun hashCode(): Int {
        var result = imageBytes.contentHashCode()
        result = 31 * result + format.hashCode()
        result = 31 * result + width
        result = 31 * result + height
        return result
    }
}

data class SignatureImage(
    val imageBytes: ByteArray,
    val format: SignatureFormat
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is SignatureImage) return false
        if (!imageBytes.contentEquals(other.imageBytes)) return false
        if (format != other.format) return false
        return true
    }

    override fun hashCode(): Int {
        var result = imageBytes.contentHashCode()
        result = 31 * result + format.hashCode()
        return result
    }
}

data class AdditionalData(
    val placeOfBirth: String?,
    val address: String?,
    val municipality: String?,
    val province: String?,
    val country: String?,
    val telephone: String?,
    val email: String?,
    val professionalActivity: String?,
    val rawData: ByteArray
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is AdditionalData) return false
        if (placeOfBirth != other.placeOfBirth) return false
        if (address != other.address) return false
        if (municipality != other.municipality) return false
        if (province != other.province) return false
        if (country != other.country) return false
        if (telephone != other.telephone) return false
        if (email != other.email) return false
        if (professionalActivity != other.professionalActivity) return false
        if (!rawData.contentEquals(other.rawData)) return false
        return true
    }

    override fun hashCode(): Int {
        var result = placeOfBirth?.hashCode() ?: 0
        result = 31 * result + (address?.hashCode() ?: 0)
        result = 31 * result + (municipality?.hashCode() ?: 0)
        result = 31 * result + (province?.hashCode() ?: 0)
        result = 31 * result + (country?.hashCode() ?: 0)
        result = 31 * result + (telephone?.hashCode() ?: 0)
        result = 31 * result + (email?.hashCode() ?: 0)
        result = 31 * result + (professionalActivity?.hashCode() ?: 0)
        result = 31 * result + rawData.contentHashCode()
        return result
    }
}

data class BiometricData(
    val fingerprintDeltas: ByteArray?,
    val fingerPositions: List<String>,
    val rawData: ByteArray
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is BiometricData) return false
        if (fingerprintDeltas != null) {
            if (other.fingerprintDeltas == null) return false
            if (!fingerprintDeltas.contentEquals(other.fingerprintDeltas)) return false
        } else if (other.fingerprintDeltas != null) return false
        if (fingerPositions != other.fingerPositions) return false
        if (!rawData.contentEquals(other.rawData)) return false
        return true
    }

    override fun hashCode(): Int {
        var result = fingerprintDeltas?.contentHashCode() ?: 0
        result = 31 * result + fingerPositions.hashCode()
        result = 31 * result + rawData.contentHashCode()
        return result
    }
}

data class ActiveAuthentication(
    val publicKeyAlgorithm: String?,
    val publicKeyBytes: ByteArray?,
    val rawData: ByteArray
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is ActiveAuthentication) return false
        if (publicKeyAlgorithm != other.publicKeyAlgorithm) return false
        if (publicKeyBytes != null) {
            if (other.publicKeyBytes == null) return false
            if (!publicKeyBytes.contentEquals(other.publicKeyBytes)) return false
        } else if (other.publicKeyBytes != null) return false
        if (!rawData.contentEquals(other.rawData)) return false
        return true
    }

    override fun hashCode(): Int {
        var result = publicKeyAlgorithm?.hashCode() ?: 0
        result = 31 * result + (publicKeyBytes?.contentHashCode() ?: 0)
        result = 31 * result + rawData.contentHashCode()
        return result
    }
}

enum class ImageFormat {
    JPEG, JPEG2000, UNKNOWN
}

enum class SignatureFormat {
    WSQ, JPEG, UNKNOWN
}

class DocumentParsingException(message: String, cause: Throwable? = null) 
    : Exception(message, cause)

class DataGroupParsingException(message: String, cause: Throwable? = null) 
    : Exception(message, cause)
```

---

## Conclusión

Esta estructura te proporciona:

1. **Reutilización**: Tu código DNI (`dniedroid.aar`) se integra como `SpanishIDCardParser`
2. **Extensibilidad**: Agregar nuevos países es cuestión de crear una clase nueva que extienda `GenericIDCardParser` o `GenericPassportParser`
3. **Mantenibilidad**: Separación clara de responsabilidades (detección, parsing, modelos)
4. **Robustez**: Manejo de excepciones y fallbacks para documentos desconocidos
5. **Casos especiales**: Alemania y otros casos especiales se detectan y manejan apropiadamente

El siguiente paso sería pruebas exhaustivas con documentos reales de cada país europeo.
