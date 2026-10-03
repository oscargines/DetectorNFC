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
import org.jmrtd.lds.icao.DG2File
import org.jmrtd.lds.icao.DG7File
import java.io.ByteArrayInputStream
import java.io.IOException

class IcaoReader(private val tag: Tag?) {

    private val tagName = "IcaoReader"
    private val accessMethodDetail = "PACE-CAN / ICAO JMRTD"
    private val maxRetries = 2

    /** Compatibilidad: lectura con solo CAN (PACE-CAN). */
    fun readWithCan(can: String): RawNfcData = readWithCredentials(AccessCredentials.withCan(can))

    fun readWithCredentials(credentials: AccessCredentials): RawNfcData {
        Log.i(tagName, "====== readWithCredentials() INICIO ======")
        Log.i(tagName, "tag=${tag != null}, methods=${credentials.describe()}")

        if (tag == null) {
            Log.e(tagName, "FAIL: Tag NFC nulo para lectura ICAO")
            return failure(null, "No se detecto un tag NFC valido.")
        }
        if (!credentials.hasAnyAccessMethod()) {
            Log.e(tagName, "FAIL: sin credenciales de acceso (CAN/MRZ)")
            return failure(
                formatUid(tag.id),
                "No se han introducido credenciales de acceso. Usa el CAN del documento o los datos del MRZ (número, fecha de nacimiento y caducidad).",
                detailFor(credentials)
            )
        }

        val uid = formatUid(tag.id)
        val baseDetail = detailFor(credentials)
        Log.i(tagName, "uid=$uid, techs=${tag.techList.joinToString()}")

        var lastError: Exception? = null
        var attempt = 0

        while (attempt < maxRetries) {
            attempt++
            Log.i(tagName, "--- Intento ICAO $attempt/$maxRetries ---")

            val isoDep = IsoDep.get(tag)
            if (isoDep == null) {
                Log.e(tagName, "FAIL: IsoDep.get(tag) devolvió null - techList=${tag.techList.joinToString()}")
                if (attempt >= maxRetries) {
                    return failure(uid, "El documento no expone la tecnología NFC requerida para lectura ICAO.")
                }
                Thread.sleep(300)
                continue
            }
            Log.d(tagName, "IsoDep obtenido: isConnected=${isoDep.isConnected}")

            val cardService = IsoDepCardService(isoDep)
            val passportService = PassportService(
                cardService,
                PassportService.NORMAL_MAX_TRANCEIVE_LENGTH,
                PassportService.DEFAULT_MAX_BLOCKSIZE,
                false,
                false
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
                Log.d(tagName, "EF.CardAccess: ${cardAccess != null}")

                val paceInfo = cardAccess?.securityInfos
                    ?.firstNotNullOfOrNull { it as? PACEInfo }
                Log.d(tagName, "PACEInfo: ${paceInfo != null}, oid=${paceInfo?.objectIdentifier}, paramId=${paceInfo?.parameterId}")

                // ── Canal seguro: PACE (CAN → MRZ → PIN) o BAC con MRZ ──
                val channelMethod = when {
                    paceInfo != null -> establishPace(passportService, credentials, paceInfo)
                    credentials.hasMrz() -> establishBac(passportService, credentials)
                    else -> null
                }
                if (channelMethod == null) {
                    val guidance = if (paceInfo == null && !credentials.hasMrz()) {
                        "El documento no soporta PACE y no se han introducido los datos del MRZ. " +
                            "Los pasaportes requieren el número de documento, la fecha de nacimiento y la fecha de caducidad."
                    } else {
                        accessDeniedGuidance(credentials)
                    }
                    Log.w(tagName, "No se pudo establecer el canal seguro ICAO: $guidance")
                    return failure(uid, guidance, baseDetail)
                }
                val accessDetail = "$channelMethod / ICAO JMRTD"

                Log.d(tagName, "Enviando SELECT applet (true) tras $channelMethod...")
                passportService.sendSelectApplet(true)
                Log.i(tagName, "$channelMethod ICAO completado correctamente (intento $attempt/$maxRetries)")

                val dgMap = mutableMapOf<Int, ByteArray?>()
                val dgAnalysis = mutableMapOf<Int, DataGroupInfo>()
                var fatalSessionError: String? = null

                Log.d(tagName, "Leyendo DG1...")
                fatalSessionError = readDg(1, dgMap, dgAnalysis) {
                    readFileBytes(passportService, PassportService.EF_DG1)
                }
                if (fatalSessionError == null) {
                    Log.d(tagName, "Leyendo DG11...")
                    fatalSessionError = readDg(11, dgMap, dgAnalysis) {
                        readFileBytes(passportService, PassportService.EF_DG11)
                    }
                }
                if (fatalSessionError == null) {
                    Log.d(tagName, "Leyendo DG13...")
                    fatalSessionError = readDg(13, dgMap, dgAnalysis) {
                        readFileBytes(passportService, PassportService.EF_DG13)
                    }
                }
                if (fatalSessionError == null) {
                    Log.d(tagName, "Leyendo DG2...")
                    fatalSessionError = readDg(2, dgMap, dgAnalysis) {
                        val raw = readFileBytes(passportService, PassportService.EF_DG2)
                        extractFaceImage(raw) ?: raw
                    }
                }
                if (fatalSessionError == null) {
                    Log.d(tagName, "Leyendo DG7...")
                    fatalSessionError = readDg(7, dgMap, dgAnalysis) {
                        val raw = readFileBytes(passportService, PassportService.EF_DG7)
                        extractSignatureImage(raw) ?: raw
                    }
                }
                if (fatalSessionError == null) {
                    Log.d(tagName, "Leyendo DG15...")
                    fatalSessionError = readDg(15, dgMap, dgAnalysis) {
                        readFileBytes(passportService, PassportService.EF_DG15)
                    }
                }

                if (fatalSessionError != null) {
                    Log.w(tagName, "Error fatal de sesión: $fatalSessionError")
                    for (pendingDg in listOf(1, 11, 13, 2, 7, 15)) {
                        if (!dgAnalysis.containsKey(pendingDg)) {
                            dgAnalysis[pendingDg] = DataGroupInfo.skipped(pendingDg, fatalSessionError)
                        }
                    }
                }

                val available = dgAnalysis.filter { it.value.status == DGStatus.READ_OK }.keys.sorted()
                Log.i(tagName, "DGs OK: $available, fatal=$fatalSessionError")

                val sessionStatus = when {
                    fatalSessionError == null -> NfcSessionStatus.SUCCESS
                    available.isNotEmpty() -> NfcSessionStatus.PARTIAL
                    else -> NfcSessionStatus.FAILED
                }
                val sessionError = when {
                    fatalSessionError != null -> fatalSessionError
                    sessionStatus == NfcSessionStatus.FAILED -> "No se pudo completar la lectura ICAO del documento."
                    else -> null
                }
                Log.i(tagName, "Lectura ICAO completada. status=$sessionStatus, error=$sessionError")
                Log.i(tagName, "====== readWithCredentials() FIN ======")

                return RawNfcData(
                    uid = uid,
                    can = credentials.can,
                    dataGroups = dgMap,
                    sod = null,
                    dgAnalysis = dgAnalysis,
                    sessionStatus = sessionStatus,
                    sessionError = sessionError,
                    readerMethod = NfcReaderMethod.ICAO_JMRTD,
                    accessMethodDetail = accessDetail,
                    fallbackUsed = true
                )
            } catch (e: Exception) {
                lastError = e
                Log.e(tagName, "EXCEPCIÓN en intento ICAO $attempt/$maxRetries: ${e.javaClass.simpleName}: ${e.message}", e)
                runCatching { passportService.close() }
                runCatching { cardService.close() }
                runCatching { isoDep.close() }

                if (attempt >= maxRetries || isFatalCommunicationError(e)) {
                    val userMessage = when {
                        isFatalCommunicationError(e) ->
                            "Se perdió la conexión NFC durante la lectura ICAO. Mantén el documento inmóvil y reintenta."
                        e is TagLostException || e is IOException || e is CardServiceException ->
                            "No se pudo completar la comunicación con el documento mediante ICAO."
                        else -> "No se pudo leer el documento con el método ICAO alternativo."
                    }
                    Log.e(tagName, "Error final en lectura ICAO (tras $attempt intentos): ${e.message}", e)
                    return failure(uid, userMessage, baseDetail)
                }
                Thread.sleep(500)
            }
        }

        val userMessage = when {
            lastError is TagLostException || lastError is IOException || lastError is CardServiceException ->
                "No se pudo completar la comunicación con el documento mediante ICAO."
            else -> "No se pudo leer el documento con el método ICAO alternativo tras varios intentos."
        }
        return failure(uid, userMessage, baseDetail)
    }

    // ------------------------------------------------------------------ //
    //  Negociación del canal seguro (PACE con CAN/MRZ/PIN o BAC con MRZ)
    // ------------------------------------------------------------------ //

    /** Cascada PACE: primero CAN, después MRZ y, por último, PIN. Devuelve el método usado. */
    private fun establishPace(
        passportService: PassportService,
        credentials: AccessCredentials,
        paceInfo: PACEInfo
    ): String? {
        if (credentials.hasCan()) {
            try {
                val paceKey = PACEKeySpec.createCANKey(credentials.can)
                passportService.doPACE(
                    paceKey,
                    paceInfo.objectIdentifier,
                    PACEInfo.toParameterSpec(paceInfo.parameterId),
                    paceInfo.parameterId
                )
                Log.i(tagName, "PACE-CAN completado")
                return "PACE-CAN"
            } catch (e: Exception) {
                Log.w(tagName, "PACE-CAN falló: ${e.javaClass.simpleName}: ${e.message}")
            }
        }

        if (credentials.hasMrz()) {
            try {
                val mrz = credentials.mrz!!
                val paceKey = PACEKeySpec.createMRZKey(
                    BACKey(mrz.documentNumber, mrz.dateOfBirth, mrz.dateOfExpiry)
                )
                passportService.doPACE(
                    paceKey,
                    paceInfo.objectIdentifier,
                    PACEInfo.toParameterSpec(paceInfo.parameterId),
                    paceInfo.parameterId
                )
                Log.i(tagName, "PACE-MRZ completado")
                return "PACE-MRZ"
            } catch (e: Exception) {
                Log.w(tagName, "PACE-MRZ falló: ${e.javaClass.simpleName}: ${e.message}")
            }
        }

        if (credentials.hasPin()) {
            try {
                val paceKey = PACEKeySpec.createPINKey(credentials.pin)
                passportService.doPACE(
                    paceKey,
                    paceInfo.objectIdentifier,
                    PACEInfo.toParameterSpec(paceInfo.parameterId),
                    paceInfo.parameterId
                )
                Log.i(tagName, "PACE-PIN completado")
                return "PACE-PIN"
            } catch (e: Exception) {
                Log.e(tagName, "PACE-PIN falló (no se reintenta para no agotar el contador del PIN): ${e.message}")
                return null
            }
        }

        return null
    }

    /** BAC clásico con la clave derivada del MRZ (pasaportes sin PACE). */
    private fun establishBac(
        passportService: PassportService,
        credentials: AccessCredentials
    ): String? {
        val mrz = credentials.mrz
        if (mrz == null || !mrz.isComplete()) return null
        return try {
            val bacKey = BACKey(mrz.documentNumber, mrz.dateOfBirth, mrz.dateOfExpiry)
            passportService.doBAC(bacKey)
            Log.i(tagName, "BAC completado con MRZ (doc=${mrz.documentNumber})")
            "BAC"
        } catch (e: Exception) {
            Log.e(tagName, "BAC falló: ${e.javaClass.simpleName}: ${e.message}", e)
            null
        }
    }

    private fun accessDeniedGuidance(credentials: AccessCredentials): String {
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

    /** Descripción del método de acceso disponible, p. ej. "PACE-MRZ/BAC / ICAO JMRTD". */
    private fun detailFor(credentials: AccessCredentials): String {
        val methods = buildList {
            if (credentials.hasCan()) add("PACE-CAN")
            if (credentials.hasMrz()) add("PACE-MRZ/BAC")
            if (credentials.hasPin()) add("PACE-PIN")
        }
        val joined = methods.joinToString(" + ").ifBlank { "sin credenciales" }
        return "$joined / ICAO JMRTD"
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

    private fun readFileBytes(passportService: PassportService, fid: Short): ByteArray {
        try {
            return passportService.getInputStream(fid).use { it.readBytes() }
        } catch (e: Exception) {
            val msg = e.message?.lowercase() ?: ""
            if (msg.contains("6a82") || msg.contains("6988") || msg.contains("6a86") ||
                msg.contains("not found") || msg.contains("file not found") ||
                msg.contains("no existe") || msg.contains("not supported")) {
                return ByteArray(0)
            }
            throw e
        }
    }

    private fun extractFaceImage(raw: ByteArray): ByteArray? {
        if (raw.size < 4) return null
        return try {
            val dg2 = DG2File(ByteArrayInputStream(raw))
            val faceInfo = dg2.faceInfos.firstOrNull() ?: return null
            val faceImage = faceInfo.faceImageInfos.firstOrNull() ?: return null
            faceImage.imageInputStream.use { it.readBytes() }
        } catch (e: Exception) {
            Log.w(tagName, "Error extrayendo imagen facial ICAO: ${e.message}")
            null
        }
    }

    private fun extractSignatureImage(raw: ByteArray): ByteArray? {
        if (raw.size < 4) return null
        return try {
            val dg7 = DG7File(ByteArrayInputStream(raw))
            val image = dg7.images.firstOrNull() ?: return null
            return image.imageInputStream.use { it.readBytes() }
        } catch (e: Exception) {
            Log.w(tagName, "Error extrayendo firma ICAO: ${e.message}")
            null
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
                Log.d(tagName, "DG$dgIndex leído con ICAO: ${bytes.size} bytes")
            } else {
                dgAnalysis[dgIndex] = DataGroupInfo.notPresent(dgIndex)
                Log.d(tagName, "DG$dgIndex no disponible con ICAO")
            }
            null
        } catch (e: Exception) {
            dgAnalysis[dgIndex] = DataGroupInfo.error(dgIndex, e)
            if (isFatalCommunicationError(e)) {
                val message = "Conexión NFC perdida durante DG$dgIndex con el método ICAO. Mantén el documento inmóvil y reintenta."
                Log.e(tagName, message, e)
                message
            } else {
                Log.w(tagName, "DG$dgIndex error ICAO: ${e.javaClass.simpleName}: ${e.message}")
                null
            }
        }
    }

    private fun failure(uid: String?, message: String, detail: String = accessMethodDetail): RawNfcData {
        return RawNfcData(
            uid = uid,
            can = null,
            dataGroups = emptyMap(),
            sod = null,
            sessionStatus = NfcSessionStatus.FAILED,
            sessionError = message,
            readerMethod = NfcReaderMethod.ICAO_JMRTD,
            accessMethodDetail = detail,
            fallbackUsed = true
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
}

