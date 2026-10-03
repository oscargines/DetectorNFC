package com.oscar.detectornfc

import android.nfc.Tag
import android.nfc.TagLostException
import android.nfc.tech.IsoDep
import android.util.Log
import net.sf.scuba.smartcards.CardServiceException
import net.sf.scuba.smartcards.IsoDepCardService
import org.jmrtd.BACKey
import org.jmrtd.PACEKeySpec
import org.jmrtd.PassportService
import org.jmrtd.lds.CardAccessFile
import org.jmrtd.lds.PACEInfo
import org.jmrtd.lds.ChipAuthenticationInfo
import org.jmrtd.lds.ChipAuthenticationPublicKeyInfo
import org.jmrtd.lds.TerminalAuthenticationInfo
import org.jmrtd.lds.icao.DG1File
import org.jmrtd.lds.icao.DG2File
import org.jmrtd.lds.icao.DG7File
import org.jmrtd.lds.icao.MRZInfo
import java.io.ByteArrayInputStream
import java.io.IOException
import java.security.MessageDigest

/**
 * Lector universal para documentos de identidad europeos (ICAO 9303).
 *
 * Negociación de acceso según el documento detectado:
 *  1. PACE-CAN     → DNIe/TIE español, ID Países Bajos, Cartão de Cidadão 2024+,
 *                    CIE italiano, eDO polaco.
 *  2. PACE-MRZ     → documentos con EF.CardAccess cuya contraseña es el MRZ
 *                    (CNIe francesa, pasaportes modernos).
 *  3. PACE-PIN     → eID alemán (los datos requieren además TA/EAC).
 *  4. BAC          → pasaportes sin PACE (BAC clásico con clave derivada del MRZ).
 *
 * La eID alemana (Personalausweis) no expone el applet ICAO eMRTD: se selecciona
 * el MF directamente, se establece PACE con el CAN y se vuelca la estructura del
 * chip (EF.CardAccess), pero los datos personales requieren Terminal
 * Authentication con certificados oficiales del BSI (EAC v2) y no son legibles
 * por aplicaciones de terceros.
 */
class EuropeanStructureReader(private val tag: Tag?) {

    private val tagName = "EuroReader"
    private val maxRetries = 3

    companion object {
        private val DG_FIDS: Map<Int, Short> = mapOf(
            1  to PassportService.EF_DG1,  2  to PassportService.EF_DG2,
            3  to PassportService.EF_DG3,  4  to PassportService.EF_DG4,
            5  to PassportService.EF_DG5,  6  to PassportService.EF_DG6,
            7  to PassportService.EF_DG7,  8  to PassportService.EF_DG8,
            9  to PassportService.EF_DG9,  10 to PassportService.EF_DG10,
            11 to PassportService.EF_DG11, 12 to PassportService.EF_DG12,
            13 to PassportService.EF_DG13, 14 to PassportService.EF_DG14,
            15 to PassportService.EF_DG15, 16 to PassportService.EF_DG16
        )

        private val UNIVERSAL_DG_ORDER = listOf(1, 2, 7, 11, 12, 13, 15, 16, 3, 4, 5, 6, 8, 9, 10, 14)

        private val ESSENTIAL_DGS = setOf(1, 2)

        private const val GERMAN_EID_EXPLANATION =
            "Documento eID alemán detectado: se ha establecido el canal seguro PACE con el CAN " +
                "y se ha volcado la estructura del chip, pero los datos personales de la eID " +
                "alemana solo son legibles tras una Terminal Authentication (EAC v2) con " +
                "certificados de terminal autorizados por el BSI alemán, reservados a " +
                "terminales oficiales. Ninguna aplicación de terceros puede leerlos."
    }

    /** Compatibilidad: lectura con solo CAN (DNIe/TIE, ID NL, CC PT). */
    fun readAllStructures(can: String): RawStructureData =
        readAllStructures(AccessCredentials.withCan(can))

    fun readAllStructures(credentials: AccessCredentials): RawStructureData {
        Log.i(tagName, "====== readAllStructures() INICIO ======")
        Log.i(tagName, "tag=${tag != null}, methods=${credentials.describe()}")

        if (tag == null) {
            Log.e(tagName, "FAIL: tag es null")
            return failure(null, "No se detectó un tag NFC válido.")
        }
        val uid = formatUid(tag.id)
        Log.i(tagName, "uid=$uid, techs=${tag.techList.joinToString()}")

        if (!credentials.hasAnyAccessMethod()) {
            Log.e(tagName, "FAIL: sin credenciales de acceso (CAN/MRZ/PIN)")
            return failure(
                uid,
                "No se han introducido credenciales de acceso. Usa el CAN del documento o los datos del MRZ (número, fecha de nacimiento y caducidad)."
            )
        }

        val isoDep = IsoDep.get(tag)
        if (isoDep == null) {
            Log.e(tagName, "FAIL: IsoDep.get(tag) devolvió null - techList=${tag.techList.joinToString()}")
            return failure(
                uid,
                "El documento no expone IsoDep. Puede ser un modelo sin chip sin contacto " +
                    "(p. ej. Cartão de Cidadão portugués anterior a junio de 2024, que solo tiene interfaz de contacto)."
            )
        }
        Log.i(tagName, "IsoDep obtenido: isConnected=${isoDep.isConnected}, timeout=${isoDep.timeout}, maxTransceiveLength=${isoDep.maxTransceiveLength}")

        var attempt = 0
        var lastError: Exception? = null

        while (attempt < maxRetries) {
            attempt++
            Log.i(tagName, "--- Intento $attempt/$maxRetries ---")

            val cardService = IsoDepCardService(isoDep)
            val passportService = PassportService(
                cardService,
                PassportService.NORMAL_MAX_TRANCEIVE_LENGTH,
                PassportService.DEFAULT_MAX_BLOCKSIZE,
                false, false
            )

            try {
                isoDep.timeout = 15000
                passportService.open()

                // ── 1. Selección del applet ICAO eMRTD (AID A0000002471001) ──
                // La eID alemana no lo expone: en ese caso se trabaja a nivel de MF.
                var icaoAppletAvailable = false
                try {
                    passportService.sendSelectApplet(false)
                    icaoAppletAvailable = true
                    Log.d(tagName, "SELECT applet ICAO OK")
                } catch (e: Exception) {
                    Log.w(tagName, "Applet ICAO no disponible (posible eID alemana): ${e.javaClass.simpleName}: ${e.message}")
                }

                // ── 2. EF.CardAccess (describe los protocolos PACE/CA/TA del chip) ──
                var cardAccess = if (icaoAppletAvailable) readCardAccess(passportService) else null
                if (cardAccess == null) {
                    try {
                        passportService.sendSelectMF()
                        cardAccess = readCardAccess(passportService)
                        if (cardAccess != null) {
                            Log.d(tagName, "EF.CardAccess leído a nivel de MF tras seleccionar MF")
                        }
                    } catch (e: Exception) {
                        Log.d(tagName, "SELECT MF no disponible: ${e.message}")
                    }
                }
                Log.d(tagName, "EF.CardAccess: ${cardAccess != null}")

                val germanEidDetected = !icaoAppletAvailable && cardAccess != null

                val paceInfo = cardAccess?.securityInfos
                    ?.firstNotNullOfOrNull { it as? PACEInfo }
                Log.d(tagName, "PACEInfo: ${paceInfo != null}, oid=${paceInfo?.objectIdentifier}, paramId=${paceInfo?.parameterId}")

                // ── 3. Establecimiento del canal seguro (PACE o BAC) ──
                val secureChannel = establishSecureChannel(passportService, credentials, paceInfo, germanEidDetected)
                if (secureChannel.isFailure) {
                    return failure(uid, secureChannel.errorMessage ?: "No se pudo establecer el canal seguro con el documento.")
                }
                Log.i(tagName, "Canal seguro establecido: ${secureChannel.method}")

                if (icaoAppletAvailable) {
                    passportService.sendSelectApplet(true)
                }

                var cardAccessData: CardAccessData? = null
                var cardSecurityData: CardSecurityData? = null
                if (cardAccess != null) {
                    cardAccessData = parseCardAccess(cardAccess)
                    Log.d(tagName, "CardAccessData: pace=${cardAccessData.paceSupported}, ca=${cardAccessData.chipAuthenticationSupported}, ta=${cardAccessData.terminalAuthenticationSupported}")
                }

                // Tras PACE/BAC con applet ICAO, EF.CardSecurity es legible con SM.
                val cardSecurity = if (icaoAppletAvailable) readCardSecurity(passportService) else null
                if (cardSecurity != null) {
                    cardSecurityData = parseCardSecurity(cardSecurity)
                }

                // ── 4. Barrido de Data Groups DG1-DG16 ──
                val dgMap = mutableMapOf<Int, ByteArray?>()
                val dgAnalysis = mutableMapOf<Int, DataGroupInfo>()

                for (dg in UNIVERSAL_DG_ORDER) {
                    val fid = DG_FIDS[dg] ?: continue
                    Log.d(tagName, "Leyendo DG$dg (FID=0x${fid.toString(16).uppercase().padStart(4, '0')})...")
                    readDg(dg, dgMap, dgAnalysis) {
                        readFileBytes(passportService, fid)
                    }
                }

                val comData = readCom(passportService)
                Log.d(tagName, "EF.COM: ${comData != null}, dgsPresent=${comData?.dataGroupsPresent}")

                val sodData = readSod(passportService)
                Log.d(tagName, "EF.SOD: ${sodData != null}")

                // ── 5. Detección del documento ──
                var documentDetection: DocumentDetection? = null
                val dg1Bytes = dgMap[1]
                if (dg1Bytes != null && dg1Bytes.isNotEmpty()) {
                    Log.d(tagName, "Detectando documento desde DG1 (${dg1Bytes.size} bytes)...")
                    documentDetection = detectDocument(dg1Bytes, cardAccessData)
                    Log.i(tagName, "Documento detectado: type=${documentDetection?.documentType}, country=${documentDetection?.countryCode}, arch=${documentDetection?.architecture}")
                } else if (germanEidDetected) {
                    Log.i(tagName, "Documento eID alemán detectado (sin applet ICAO, con EF.CardAccess)")
                    documentDetection = DocumentDetection(
                        documentType = DocumentType.GERMAN_EID.name,
                        countryCode = "DEU",
                        countryName = "Alemania",
                        architecture = DocumentArchitecture.SPECIAL.name,
                        supportedProtocols = buildList {
                            if (cardAccessData?.paceSupported == true) add("PACE")
                            if (cardAccessData?.chipAuthenticationSupported == true) add("CA")
                            if (cardAccessData?.terminalAuthenticationSupported == true) add("TA")
                        }
                    )
                } else {
                    Log.w(tagName, "DG1 no disponible, no se puede detectar el documento")
                }

                val available = dgAnalysis.filter { it.value.status == DGStatus.READ_OK }.keys.sorted()
                val errors = dgAnalysis.filter { it.value.status == DGStatus.READ_ERROR }.keys.sorted()
                Log.i(tagName, "Resumen DGs: OK=$available, ERRORS=$errors, germanEid=$germanEidDetected")

                // ── 6. Estado de la sesión ──
                val sessionStatus: NfcSessionStatus
                val sessionError: String?
                when {
                    germanEidDetected && available.isEmpty() -> {
                        sessionStatus = NfcSessionStatus.PARTIAL
                        sessionError = GERMAN_EID_EXPLANATION
                    }
                    germanEidDetected -> {
                        sessionStatus = NfcSessionStatus.PARTIAL
                        sessionError = "$GERMAN_EID_EXPLANATION Se leyeron ${available.size} grupo(s) de datos adicionales."
                    }
                    available.isEmpty() -> {
                        sessionStatus = NfcSessionStatus.FAILED
                        sessionError = "No se pudo leer ningún Data Group del documento con el método ${secureChannel.method}."
                    }
                    available.containsAll(ESSENTIAL_DGS.toList()) -> {
                        sessionStatus = NfcSessionStatus.SUCCESS
                        sessionError = null
                    }
                    else -> {
                        sessionStatus = NfcSessionStatus.PARTIAL
                        sessionError = "Lectura parcial: no se pudieron leer todos los grupos esenciales (DG1/DG2) con el método ${secureChannel.method}."
                    }
                }

                Log.i(tagName, "Lectura completada. status=$sessionStatus, method=${secureChannel.method}")
                Log.i(tagName, "====== readAllStructures() FIN ======")

                return RawStructureData(
                    uid = uid,
                    can = credentials.can,
                    sessionStatus = sessionStatus,
                    sessionError = sessionError,
                    readerMethod = NfcReaderMethod.EUROPEAN_STRUCTURE.name,
                    fallbackUsed = false,
                    documentDetection = documentDetection,
                    dgRawBytes = dgMap,
                    dgAnalysis = dgAnalysis,
                    dgTLV = emptyMap(),
                    efCom = comData,
                    efSod = sodData,
                    efCardAccess = cardAccessData,
                    efCardSecurity = cardSecurityData
                )
            } catch (e: Exception) {
                lastError = e
                Log.e(tagName, "EXCEPCIÓN en intento $attempt/$maxRetries: ${e.javaClass.simpleName}: ${e.message}", e)

                if (attempt >= maxRetries || isFatalCommunicationError(e) ||
                    e is TagLostException || e is IOException
                ) {
                    val userMessage = when {
                        isFatalCommunicationError(e) ->
                            "Se perdió la conexión NFC. Mantén el documento inmóvil y reintenta."
                        e is TagLostException || e is IOException ->
                            "No se pudo comunicar con el documento."
                        else -> "No se pudo leer el documento con el método universal. Error: ${e.message}"
                    }
                    Log.e(tagName, "Error final (tras $attempt intentos): ${e.message}", e)
                    return failure(uid, userMessage)
                }
                Thread.sleep(500)
            } finally {
                runCatching { passportService.close() }
                runCatching { cardService.close() }
                runCatching { isoDep.close() }
            }
        }

        val userMessage = when {
            lastError is TagLostException || lastError is IOException ->
                "No se pudo comunicar con el documento."
            else -> "No se pudo leer el documento con el método universal tras varios intentos."
        }
        return failure(uid, userMessage)
    }

    // ------------------------------------------------------------------ //
    //  Negociación del canal seguro
    // ------------------------------------------------------------------ //

    private class SecureChannelOutcome private constructor(
        val method: String?,
        val errorMessage: String?
    ) {
        val isFailure: Boolean get() = errorMessage != null

        companion object {
            fun success(method: String) = SecureChannelOutcome(method, null)
            fun failure(message: String) = SecureChannelOutcome(null, message)
        }
    }

    /**
     * Establece el canal seguro negociando PACE (CAN → MRZ → PIN) y, si el
     * documento no ofrece PACE, BAC con la clave derivada del MRZ.
     */
    private fun establishSecureChannel(
        passportService: PassportService,
        credentials: AccessCredentials,
        paceInfo: PACEInfo?,
        germanEidDetected: Boolean
    ): SecureChannelOutcome {
        if (paceInfo == null) {
            // Sin PACE: BAC clásico (pasaportes y documentos solo-BAC).
            val mrz = credentials.mrz
            if (mrz == null || !mrz.isComplete()) {
                return SecureChannelOutcome.failure(
                    "El documento no soporta PACE y no se han introducido los datos del MRZ. " +
                        "Los pasaportes requieren el número de documento, la fecha de nacimiento y la fecha de caducidad."
                )
            }
            return try {
                val bacKey = BACKey(mrz.documentNumber, mrz.dateOfBirth, mrz.dateOfExpiry)
                passportService.doBAC(bacKey)
                Log.i(tagName, "BAC completado con MRZ (doc=${mrz.documentNumber})")
                SecureChannelOutcome.success("BAC")
            } catch (e: Exception) {
                Log.e(tagName, "BAC falló: ${e.javaClass.simpleName}: ${e.message}", e)
                SecureChannelOutcome.failure(
                    "Los datos del documento (MRZ) no fueron aceptados. Verifica el número de documento, " +
                        "la fecha de nacimiento y la fecha de caducidad."
                )
            }
        }

        // PACE disponible: cascada de contraseñas aceptadas.
        if (credentials.hasCan()) {
            try {
                val paceKey = PACEKeySpec.createCANKey(credentials.can)
                passportService.doPACE(
                    paceKey, paceInfo.objectIdentifier,
                    PACEInfo.toParameterSpec(paceInfo.parameterId),
                    paceInfo.parameterId
                )
                Log.i(tagName, "PACE-CAN completado")
                return SecureChannelOutcome.success("PACE-CAN")
            } catch (e: Exception) {
                Log.w(tagName, "PACE-CAN falló: ${e.javaClass.simpleName}: ${e.message}")
            }
        }

        if (credentials.hasMrz()) {
            try {
                val mrz = credentials.mrz!!
                val bacKey = BACKey(mrz.documentNumber, mrz.dateOfBirth, mrz.dateOfExpiry)
                val paceKey = PACEKeySpec.createMRZKey(bacKey)
                passportService.doPACE(
                    paceKey, paceInfo.objectIdentifier,
                    PACEInfo.toParameterSpec(paceInfo.parameterId),
                    paceInfo.parameterId
                )
                Log.i(tagName, "PACE-MRZ completado")
                return SecureChannelOutcome.success("PACE-MRZ")
            } catch (e: Exception) {
                Log.w(tagName, "PACE-MRZ falló: ${e.javaClass.simpleName}: ${e.message}")
            }
        }

        if (credentials.hasPin()) {
            try {
                val paceKey = PACEKeySpec.createPINKey(credentials.pin)
                passportService.doPACE(
                    paceKey, paceInfo.objectIdentifier,
                    PACEInfo.toParameterSpec(paceInfo.parameterId),
                    paceInfo.parameterId
                )
                Log.i(tagName, "PACE-PIN completado")
                return SecureChannelOutcome.success("PACE-PIN")
            } catch (e: Exception) {
                Log.e(tagName, "PACE-PIN falló (no se reintenta para no agotar el contador del PIN): ${e.message}")
                return SecureChannelOutcome.failure(
                    "El PIN no fue aceptado por el documento. No se reintenta para evitar bloquear el PIN " +
                        "(verifícalo e inténtalo de nuevo)."
                )
            }
        }

        return SecureChannelOutcome.failure(accessDeniedGuidance(credentials, germanEidDetected))
    }

    private fun accessDeniedGuidance(credentials: AccessCredentials, germanEidDetected: Boolean): String {
        if (germanEidDetected && credentials.hasCan()) {
            return "PACE falló con el CAN en el documento eID. Verifica el CAN (6 dígitos del anverso de la carta)."
        }
        val hasCan = credentials.hasCan()
        val hasMrz = credentials.hasMrz()
        return when {
            hasCan && !hasMrz ->
                "El documento rechazó el CAN. Si es un pasaporte o la identidad francesa (CNIe), " +
                    "usa el modo \"Datos del documento\" con el número de documento y las fechas del MRZ."
            !hasCan && hasMrz ->
                "El documento rechazó los datos del MRZ. Verifica el número de documento y las fechas. " +
                    "Si el documento tiene CAN (DNIe/TIE español, ID Países Bajos, Cartão de Cidadão 2024+), usa el modo CAN."
            else ->
                "El documento rechazó todas las credenciales proporcionadas (CAN y MRZ). " +
                    "Verifica los datos o prueba con el otro método de acceso."
        }
    }

    // ------------------------------------------------------------------ //
    //  Lectura de ficheros
    // ------------------------------------------------------------------ //

    private fun readCardAccess(passportService: PassportService): CardAccessFile? {
        return runCatching {
            passportService.getInputStream(PassportService.EF_CARD_ACCESS).use { input ->
                CardAccessFile(input)
            }
        }.getOrElse {
            Log.d(tagName, "No se pudo leer EF.CardAccess: ${it.message}")
            null
        }
    }

    private fun readCardSecurity(passportService: PassportService): CardAccessFile? {
        return runCatching {
            passportService.getInputStream(PassportService.EF_CARD_SECURITY).use { input ->
                CardAccessFile(input)
            }
        }.getOrElse {
            Log.d(tagName, "EF.CardSecurity no disponible: ${it.message}")
            null
        }
    }

    private fun readCom(passportService: PassportService): EFComData? {
        return runCatching {
            val bytes = passportService.getInputStream(PassportService.EF_COM).use { it.readBytes() }
            parseCom(bytes)
        }.getOrElse {
            Log.d(tagName, "EF.COM no disponible: ${it.message}")
            null
        }
    }

    private fun readSod(passportService: PassportService): SODData? {
        return runCatching {
            val bytes = passportService.getInputStream(PassportService.EF_SOD).use { it.readBytes() }
            val hash = sha256(bytes)
            SODData(rawHash = hash, digestAlgorithm = null, signatureAlgorithm = null, certificateIssuer = null)
        }.getOrElse {
            Log.d(tagName, "EF.SOD no disponible: ${it.message}")
            null
        }
    }

    private fun parseCom(bytes: ByteArray): EFComData? {
        return try {
            val dgsPresent = mutableListOf<Int>()
            val input = java.io.ByteArrayInputStream(bytes)
            val asn1 = org.bouncycastle.asn1.ASN1InputStream(input)
            var obj = asn1.readObject()
            while (obj != null) {
                if (obj is org.bouncycastle.asn1.BERTaggedObject) {
                    dgsPresent.add(obj.tagNo)
                } else if (obj is org.bouncycastle.asn1.DERApplicationSpecific) {
                    dgsPresent.add(obj.applicationTag.coerceAtMost(0xFF))
                }
                obj = asn1.readObject()
            }
            asn1.close()
            EFComData(
                ldsVersion = if (bytes.size >= 4) "${bytes[2].toInt() and 0xFF}.${bytes[3].toInt() and 0xFF}" else null,
                unicodeVersion = null,
                dataGroupsPresent = dgsPresent
            )
        } catch (e: Exception) {
            Log.w(tagName, "Error parseando EF.COM: ${e.message}")
            null
        }
    }

    private fun parseCardAccess(cardAccess: CardAccessFile): CardAccessData {
        val paceAlgs = mutableListOf<String>()
        var caSupported = false
        var taSupported = false
        for (info in cardAccess.securityInfos) {
            when (info) {
                is PACEInfo -> paceAlgs.add(info.objectIdentifier)
                is ChipAuthenticationInfo -> caSupported = true
                is TerminalAuthenticationInfo -> taSupported = true
                else -> {}
            }
        }
        return CardAccessData(
            paceSupported = paceAlgs.isNotEmpty(),
            paceAlgorithm = paceAlgs,
            chipAuthenticationSupported = caSupported,
            terminalAuthenticationSupported = taSupported
        )
    }

    private fun parseCardSecurity(cardSecurity: CardAccessFile): CardSecurityData {
        var pkSize: Int? = null
        var taRequired: Boolean? = null
        for (info in cardSecurity.securityInfos) {
            if (info is ChipAuthenticationPublicKeyInfo) {
                val key = info.subjectPublicKey
                pkSize = key?.encoded?.size
            }
            if (info is TerminalAuthenticationInfo) {
                taRequired = true
            }
        }
        return CardSecurityData(
            chipAuthenticationPublicKeySize = pkSize,
            terminalAuthenticationRequired = taRequired
        )
    }

    private fun detectDocument(dg1Bytes: ByteArray, cardAccessData: CardAccessData?): DocumentDetection? {
        return try {
            val dg1 = DG1File(ByteArrayInputStream(dg1Bytes))
            val mrz: MRZInfo = dg1.mrzInfo ?: return null

            val classifier = DocumentClassifier.classify(
                mrz.documentCode, mrz.issuingState
            )

            val protocols = mutableListOf<String>()
            if (cardAccessData?.paceSupported == true) protocols.add("PACE")
            if (cardAccessData?.chipAuthenticationSupported == true) protocols.add("CA")
            if (cardAccessData?.terminalAuthenticationSupported == true) protocols.add("TA")

            val mrzLines = mutableListOf<String>()
            mrz.issuingState?.takeIf { it.isNotBlank() }?.let { mrzLines.add("ISSUER: $it") }
            mrz.documentNumber?.takeIf { it.isNotBlank() }?.let { mrzLines.add("DOC#: $it") }
            mrz.dateOfBirth?.takeIf { it.isNotBlank() }?.let { mrzLines.add("DOB: $it") }
            mrz.dateOfExpiry?.takeIf { it.isNotBlank() }?.let { mrzLines.add("EXP: $it") }

            DocumentDetection(
                documentType = classifier.documentType.name,
                countryCode = classifier.countryCode,
                countryName = classifier.countryName,
                architecture = classifier.architecture.name,
                mrzRawLines = mrzLines.ifEmpty { null },
                supportedProtocols = protocols
            )
        } catch (e: Exception) {
            Log.w(tagName, "Error detectando documento: ${e.message}")
            null
        }
    }

    private fun readFileBytes(passportService: PassportService, fid: Short): ByteArray {
        return try {
            passportService.getInputStream(fid).use { it.readBytes() }
        } catch (e: Exception) {
            // 6982 (estado de seguridad no satisfecho, p. ej. DG protegido por EAC/TA)
            // NO se traga: se registra como ACCESS_DENIED en el análisis del DG.
            val sw = (e as? CardServiceException)?.sw ?: -1
            val msg = e.message?.lowercase() ?: ""
            if (msg.contains("6a82") || sw == 0x6A82 ||
                msg.contains("6988") || sw == 0x6988 ||
                msg.contains("6a86") || sw == 0x6A86 ||
                msg.contains("not found") || msg.contains("file not found") ||
                msg.contains("no existe") || msg.contains("not supported")
            ) {
                return ByteArray(0)
            }
            throw e
        }
    }

    private fun readDg(
        dgIndex: Int,
        dgMap: MutableMap<Int, ByteArray?>,
        dgAnalysis: MutableMap<Int, DataGroupInfo>,
        readBlock: () -> ByteArray?
    ): String? {
        return try {
            val bytes = readBlock()
            if (bytes != null && bytes.isNotEmpty()) {
                dgMap[dgIndex] = bytes
                dgAnalysis[dgIndex] = DataGroupInfo.read(dgIndex, bytes)
                Log.d(tagName, "DG$dgIndex leído: ${bytes.size} bytes")
            } else {
                dgAnalysis[dgIndex] = DataGroupInfo.notPresent(dgIndex)
                Log.d(tagName, "DG$dgIndex no disponible")
            }
            null
        } catch (e: Exception) {
            dgAnalysis[dgIndex] = DataGroupInfo.error(dgIndex, e)
            if (isFatalCommunicationError(e)) {
                val message = "Conexión NFC perdida durante DG$dgIndex. Mantén el documento inmóvil y reintenta."
                Log.e(tagName, message, e)
                message
            } else {
                Log.w(tagName, "DG$dgIndex error: ${e.javaClass.simpleName}: ${e.message}")
                null
            }
        }
    }

    private fun failure(uid: String?, message: String): RawStructureData {
        return RawStructureData(
            uid = uid, can = null,
            sessionStatus = NfcSessionStatus.FAILED,
            sessionError = message,
            readerMethod = NfcReaderMethod.EUROPEAN_STRUCTURE.name
        )
    }

    private fun isFatalCommunicationError(error: Throwable): Boolean {
        val message = error.message.orEmpty()
        return error is TagLostException ||
            error is IOException ||
            message.contains("Tag was lost", ignoreCase = true) ||
            message.contains("transceive failed", ignoreCase = true) ||
            message.contains("connection lost", ignoreCase = true)
    }

    private fun formatUid(id: ByteArray?): String {
        if (id == null || id.isEmpty()) return "<sin uid>"
        return id.joinToString(":") { "%02X".format(it) }
    }

    private fun sha256(bytes: ByteArray): String {
        val hashBytes = MessageDigest.getInstance("SHA-256").digest(bytes)
        return hashBytes.joinToString("") { "%02x".format(it) }
    }

    private fun extractFaceImage(dg2Raw: ByteArray): ByteArray? {
        return try {
            val dg2 = DG2File(ByteArrayInputStream(dg2Raw))
            val faceInfo = dg2.faceInfos.firstOrNull() ?: return null
            val faceImage = faceInfo.faceImageInfos.firstOrNull() ?: return null
            faceImage.imageInputStream.use { it.readBytes() }
        } catch (e: Exception) {
            Log.w(tagName, "Error extrayendo imagen facial: ${e.message}")
            dg2Raw
        }
    }

    private fun extractSignatureImage(dg7Raw: ByteArray): ByteArray? {
        return try {
            val dg7 = DG7File(ByteArrayInputStream(dg7Raw))
            val image = dg7.images.firstOrNull() ?: return null
            image.imageInputStream.use { it.readBytes() }
        } catch (e: Exception) {
            Log.w(tagName, "Error extrayendo firma: ${e.message}")
            dg7Raw
        }
    }

    fun getExtractedPhoto(dataGroups: Map<Int, ByteArray?>): ByteArray? {
        return dataGroups[2]?.let { extractFaceImage(it) }
    }

    fun getExtractedSignature(dataGroups: Map<Int, ByteArray?>): ByteArray? {
        return dataGroups[7]?.let { extractSignatureImage(it) }
    }
}
