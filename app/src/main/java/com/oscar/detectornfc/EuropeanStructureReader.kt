package com.oscar.detectornfc

import android.nfc.Tag
import android.nfc.TagLostException
import android.nfc.tech.IsoDep
import android.util.Log
import net.sf.scuba.smartcards.CardServiceException
import net.sf.scuba.smartcards.IsoDepCardService
import org.jmrtd.PACEKeySpec
import org.jmrtd.PassportService
import org.jmrtd.lds.CardAccessFile
import org.jmrtd.lds.PACEInfo
import org.jmrtd.lds.SecurityInfo
import org.jmrtd.lds.ChipAuthenticationInfo
import org.jmrtd.lds.ChipAuthenticationPublicKeyInfo
import org.jmrtd.lds.TerminalAuthenticationInfo
import org.jmrtd.lds.icao.DG1File
import org.jmrtd.lds.icao.DG2File
import org.jmrtd.lds.icao.DG7File
import org.jmrtd.lds.icao.DG11File
import org.jmrtd.lds.icao.MRZInfo
import java.io.ByteArrayInputStream
import java.io.IOException
import java.security.MessageDigest

class EuropeanStructureReader(private val tag: Tag?) {

    private val tagName = "EuroReader"
    private val accessMethodDetail = "PACE-CAN / European Universal"
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
        private val OPTIONAL_DGS = setOf(7, 11, 12, 13, 15, 16)
    }

    fun readAllStructures(can: String): RawStructureData {
        Log.i(tagName, "====== readAllStructures() INICIO ======")
        Log.i(tagName, "tag=${tag != null}, can.length=${can.length}, can.isBlank=${can.isBlank()}")

        if (tag == null) {
            Log.e(tagName, "FAIL: tag es null")
            return failure(null, "No se detectó un tag NFC válido.")
        }
        val uid = formatUid(tag.id)
        Log.i(tagName, "uid=$uid, techs=${tag.techList.joinToString()}")

        if (can.isBlank()) {
            Log.e(tagName, "FAIL: CAN vacío")
            return failure(uid, "CAN vacío. No se puede iniciar PACE.")
        }

        val isoDep = IsoDep.get(tag)
        if (isoDep == null) {
            Log.e(tagName, "FAIL: IsoDep.get(tag) devolvió null - techList=${tag.techList.joinToString()}")
            return failure(uid, "El documento no expone IsoDep.")
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
                Log.d(tagName, "Abriendo passportService...")
                passportService.open()
                Log.d(tagName, "passportService.open() OK")

                Log.d(tagName, "Enviando SELECT applet (false)...")
                passportService.sendSelectApplet(false)
                Log.d(tagName, "SELECT applet (false) OK")

                Log.d(tagName, "Leyendo EF.CardAccess...")
                val cardAccess = readCardAccess(passportService)
                Log.d(tagName, "EF.CardAccess leído: cardAccess=${cardAccess != null}")

                if (cardAccess == null) {
                    Log.w(tagName, "EF.CardAccess es null, buscando PACEInfo imposible")
                    return failure(uid, "El documento no ofrece PACE con CAN.")
                }

                val paceInfo = cardAccess.securityInfos
                    .firstNotNullOfOrNull { it as? PACEInfo }
                Log.d(tagName, "PACEInfo encontrado: ${paceInfo != null}, totalSecurityInfos=${cardAccess.securityInfos.size}")

                if (paceInfo == null) {
                    Log.w(tagName, "No se encontró PACEInfo en CardAccess. SecurityInfos: ${cardAccess.securityInfos.map { it.objectIdentifier }}")
                    return failure(uid, "El documento no ofrece PACE con CAN.")
                }

                Log.d(tagName, "PACEInfo: oid=${paceInfo.objectIdentifier}, paramId=${paceInfo.parameterId}")

                var cardAccessData: CardAccessData? = null
                var cardSecurityData: CardSecurityData? = null
                cardAccessData = parseCardAccess(cardAccess)
                Log.d(tagName, "CardAccessData: pace=${cardAccessData.paceSupported}, ca=${cardAccessData.chipAuthenticationSupported}, ta=${cardAccessData.terminalAuthenticationSupported}")

                Log.d(tagName, "Creando PACE key con CAN...")
                val paceKey = PACEKeySpec.createCANKey(can)
                Log.d(tagName, "Ejecutando doPACE...")
                passportService.doPACE(
                    paceKey, paceInfo.objectIdentifier,
                    PACEInfo.toParameterSpec(paceInfo.parameterId),
                    paceInfo.parameterId
                )
                Log.i(tagName, "doPACE() completado correctamente")

                Log.d(tagName, "Enviando SELECT applet (true) post-PACE...")
                passportService.sendSelectApplet(true)
                Log.i(tagName, "PACE completado (intento $attempt/$maxRetries)")

                val cardSecurity = readCardSecurity(passportService)
                if (cardSecurity != null) {
                    cardSecurityData = parseCardSecurity(cardSecurity)
                }

                val dgMap = mutableMapOf<Int, ByteArray?>()
                val dgAnalysis = mutableMapOf<Int, DataGroupInfo>()

                for (dg in UNIVERSAL_DG_ORDER) {
                    val fid = DG_FIDS[dg] ?: continue
                    Log.d(tagName, "Leyendo DG$dg (FID=0x${fid.toString(16).uppercase().padStart(4, '0')})...")
                    readDg(dg, dgMap, dgAnalysis) {
                        readFileBytes(passportService, fid)
                    }
                }

                Log.d(tagName, "Leyendo EF.COM...")
                val comData = readCom(passportService)
                Log.d(tagName, "EF.COM: ${comData != null}, dgsPresent=${comData?.dataGroupsPresent}")

                Log.d(tagName, "Leyendo EF.SOD...")
                val sodData = readSod(passportService)
                Log.d(tagName, "EF.SOD: ${sodData != null}")

                var documentDetection: DocumentDetection? = null
                val dg1Bytes = dgMap[1]
                if (dg1Bytes != null && dg1Bytes.isNotEmpty()) {
                    Log.d(tagName, "Detectando documento desde DG1 (${dg1Bytes.size} bytes)...")
                    documentDetection = detectDocument(dg1Bytes, cardAccessData)
                    Log.i(tagName, "Documento detectado: type=${documentDetection?.documentType}, country=${documentDetection?.countryCode}, arch=${documentDetection?.architecture}")
                } else {
                    Log.w(tagName, "DG1 no disponible, no se puede detectar documento")
                }

                val available = dgAnalysis.filter { it.value.status == DGStatus.READ_OK }.keys.sorted()
                val notPresent = dgAnalysis.filter { it.value.status == DGStatus.NOT_PRESENT_OR_NOT_ALLOWED }.keys.sorted()
                val errors = dgAnalysis.filter { it.value.status == DGStatus.READ_ERROR }.keys.sorted()
                Log.i(tagName, "Resumen DGs: OK=$available, NOT_PRESENT=$notPresent, ERRORS=$errors")

                val sessionStatus = when {
                    available.isEmpty() -> NfcSessionStatus.FAILED
                    available.containsAll(ESSENTIAL_DGS.toList()) -> NfcSessionStatus.SUCCESS
                    else -> NfcSessionStatus.PARTIAL
                }
                val sessionError = when {
                    sessionStatus == NfcSessionStatus.FAILED -> "No se pudo completar la lectura universal del documento."
                    else -> null
                }

                Log.i(tagName, "Lectura completada. status=$sessionStatus, error=$sessionError")
                Log.i(tagName, "====== readAllStructures() FIN ======")

                return RawStructureData(
                    uid = uid, can = can,
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
                    e is TagLostException || e is IOException || e is CardServiceException
                ) {
                    val userMessage = when {
                        isFatalCommunicationError(e) ->
                            "Se perdió la conexión NFC. Mantén el documento inmóvil y reintenta."
                        e is TagLostException || e is IOException ->
                            "No se pudo comunicar con el documento mediante PACE."
                        e.message?.contains("6a82", ignoreCase = true) == true ->
                            "CAN incorrecto o documento no compatible con PACE-CAN."
                        e.message?.contains("6988", ignoreCase = true) == true ->
                            "Error de autenticación PACE. Verifica el CAN."
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

    private fun readCardAccess(passportService: PassportService): CardAccessFile? {
        return runCatching {
            passportService.getInputStream(PassportService.EF_CARD_ACCESS).use { input ->
                CardAccessFile(input)
            }
        }.getOrElse {
            Log.w(tagName, "No se pudo leer EF.CardAccess: ${it.message}")
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
            val mrz = dg1.mrzInfo ?: return null

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
        try {
            return passportService.getInputStream(fid).use { it.readBytes() }
        } catch (e: Exception) {
            val msg = e.message?.lowercase() ?: ""
            if (msg.contains("6a82") || msg.contains("6988") || msg.contains("6a86") ||
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
