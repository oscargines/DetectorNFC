package com.oscar.detectornfc.passport

import android.nfc.Tag
import android.nfc.TagLostException
import android.nfc.tech.IsoDep
import android.util.Log
import com.oscar.detectornfc.AccessCredentials
import com.oscar.detectornfc.CardAccessData
import com.oscar.detectornfc.DataGroupInfo
import com.oscar.detectornfc.DocumentClassifier
import com.oscar.detectornfc.DocumentDetection
import com.oscar.detectornfc.EFComData
import com.oscar.detectornfc.MrzCredentials
import com.oscar.detectornfc.NfcReaderMethod
import com.oscar.detectornfc.NfcSessionStatus
import com.oscar.detectornfc.RawStructureData
import com.oscar.detectornfc.SODData
import net.sf.scuba.smartcards.CardServiceException
import net.sf.scuba.smartcards.IsoDepCardService
import org.jmrtd.BACKey
import org.jmrtd.PACEKeySpec
import org.jmrtd.PassportService
import org.jmrtd.lds.CardAccessFile
import org.jmrtd.lds.ChipAuthenticationInfo
import org.jmrtd.lds.PACEInfo
import org.jmrtd.lds.TerminalAuthenticationInfo
import org.jmrtd.lds.icao.DG1File
import org.jmrtd.lds.icao.MRZInfo
import java.io.ByteArrayInputStream
import java.io.IOException
import java.security.MessageDigest

/**
 * Lector ICAO 9303 dedicado a pasaportes/eMRTD (aislado del flujo DNI español/dniedroid).
 * Usa JMRTD+SCUBA ya presentes; prioriza BAC/PACE-MRZ con datos MRZ.
 */
class PassportIcaoReader(private val tag: Tag?) {
    private val tagName = "PassportIcaoReader"
    private val maxRetries = 2

    companion object {
        private val DG_FIDS: Map<Int, Short> = mapOf(
            1 to PassportService.EF_DG1, 2 to PassportService.EF_DG2,
            3 to PassportService.EF_DG3, 11 to PassportService.EF_DG11,
            12 to PassportService.EF_DG12, 14 to PassportService.EF_DG14,
            15 to PassportService.EF_DG15
        )
        private val DG_ORDER = listOf(1, 2, 11, 12, 14, 15, 3)
        private val ESSENTIAL_DGS = setOf(1)
    }

    fun read(credentials: AccessCredentials): RawStructureData {
        Log.i(tagName, "====== PassportIcaoReader.read() INICIO ====== methods=${credentials.describe()}")
        if (tag == null) return failure(null, credentials, "No se detectó un tag NFC válido.")
        val uid = formatUid(tag.id)

        val mrz = credentials.mrz
        if ((mrz == null || !mrz.isComplete()) && !credentials.hasCan()) {
            return failure(
                uid, credentials,
                "Para leer un pasaporte (ICAO 9303) introduce el CAN (6 dígitos) o los datos del MRZ: número de documento, fecha de nacimiento y fecha de caducidad."
            )
        }

        var attempt = 0
        var last: RawStructureData? = null
        while (attempt < maxRetries) {
            attempt++
            Log.i(tagName, "--- Intento pasaporte $attempt/$maxRetries mrz=${mrz?.isComplete() == true} can=${credentials.hasCan()} ---")
            val res = tryRead(uid, credentials, mrz?.takeIf { it.isComplete() }, attempt)
            last = res
            if (res.sessionStatus != NfcSessionStatus.FAILED || !isTransient(res.sessionError)) return res
            try { Thread.sleep(450) } catch (_: InterruptedException) {}
        }
        return last ?: failure(uid, credentials, "No se pudo leer el pasaporte tras varios intentos.")
    }

    private fun tryRead(uid: String, credentials: AccessCredentials, mrz: MrzCredentials?, attempt: Int): RawStructureData {
        val isoDep = IsoDep.get(tag!!) ?: return failure(uid, credentials, "El chip no expone IsoDep (tecnología NFC no compatible).")
        val cardService = IsoDepCardService(isoDep)
        val passportService = PassportService(cardService, PassportService.NORMAL_MAX_TRANCEIVE_LENGTH, PassportService.DEFAULT_MAX_BLOCKSIZE, false, false)
        var accessDetail = "ICAO 9303 (pasaporte)"
        try {
            isoDep.timeout = 15000
            passportService.open()
            runCatching { passportService.sendSelectApplet(false) }

            val cardAccess = readCardAccess(passportService)
            val paceInfo = cardAccess?.securityInfos?.firstNotNullOfOrNull { it as? PACEInfo }
            val cardAccessData = cardAccess?.let { parseCardAccess(it) }

            val channel = establishChannel(passportService, credentials, mrz, paceInfo)
            if (channel.isFailure) {
                return failure(uid, credentials, channel.errorMessage ?: "No se pudo autenticar con el pasaporte.", channel.detail)
            }
            accessDetail = channel.detail
            Log.i(tagName, "Canal seguro pasaporte: $accessDetail (intento $attempt)")
            runCatching { passportService.sendSelectApplet(true) }

            val dgMap = mutableMapOf<Int, ByteArray?>()
            val dgAnalysis = mutableMapOf<Int, DataGroupInfo>()
            var fatalSessionError: String? = null
            for (dg in DG_ORDER) {
                if (fatalSessionError != null) break
                val fid = DG_FIDS[dg] ?: continue
                fatalSessionError = readDg(dg, dgMap, dgAnalysis) { readFileBytes(passportService, fid) }
            }
            if (fatalSessionError != null) {
                for (pending in DG_ORDER) if (!dgAnalysis.containsKey(pending)) {
                    dgAnalysis[pending] = DataGroupInfo.skipped(pending, fatalSessionError)
                }
            }

            val comData = readCom(passportService)
            val sodData = readSod(passportService)
            val detection = detectDocument(dgMap[1], cardAccessData)

            val available = dgAnalysis.filter { it.value.status == com.oscar.detectornfc.DGStatus.READ_OK }.keys.sorted()
            val (sessionStatus, sessionError) = when {
                fatalSessionError != null && available.isEmpty() -> NfcSessionStatus.FAILED to fatalSessionError
                fatalSessionError != null -> NfcSessionStatus.PARTIAL to fatalSessionError
                available.containsAll(ESSENTIAL_DGS) -> NfcSessionStatus.SUCCESS to null
                available.isNotEmpty() -> NfcSessionStatus.PARTIAL to "Lectura parcial del pasaporte (faltan grupos esenciales). Método: $accessDetail."
                else -> NfcSessionStatus.FAILED to "No se pudo leer ningún Data Group del pasaporte. Método: $accessDetail."
            }

            Log.i(tagName, "Lectura pasaporte fin: status=$sessionStatus, dgs=$available, method=$accessDetail")
            return RawStructureData(
                uid = uid,
                can = credentials.can,
                sessionStatus = sessionStatus,
                sessionError = sessionError,
                readerMethod = NfcReaderMethod.ICAO_PASSPORT.name,
                fallbackUsed = false,
                documentDetection = detection,
                dgRawBytes = dgMap,
                dgAnalysis = dgAnalysis,
                dgTLV = emptyMap(),
                efCom = comData,
                efSod = sodData,
                efCardAccess = cardAccessData,
                efCardSecurity = null
            )
        } catch (e: Exception) {
            Log.e(tagName, "Excepción lectura pasaporte: ${e.javaClass.simpleName}: ${e.message}", e)
            return failure(uid, credentials, mapException(e), accessDetail, e)
        } finally {
            runCatching { passportService.close() }
            runCatching { cardService.close() }
            runCatching { isoDep.close() }
        }
    }

    private data class ChannelOutcome(val detail: String, val errorMessage: String?) {
        val isFailure get() = errorMessage != null
        companion object {
            fun ok(detail: String) = ChannelOutcome(detail, null)
            fun fail(msg: String, detail: String = "ICAO 9303 (pasaporte)") = ChannelOutcome(detail, msg)
        }
    }

    private fun establishChannel(
        ps: PassportService,
        credentials: AccessCredentials,
        mrz: MrzCredentials?,
        paceInfo: PACEInfo?
    ): ChannelOutcome {
        val hasMrz = mrz != null && mrz.isComplete()
        val hasCan = credentials.hasCan()
        val bacKey = if (hasMrz) BACKey(mrz!!.normalizedDocumentNumber(), mrz.dateOfBirth, mrz.dateOfExpiry) else null

        if (paceInfo != null) {
            // CAN-only (1er intento pasaporte): probar PACE-CAN primero.
            if (hasCan && !hasMrz) {
                try {
                    val paceKey = PACEKeySpec.createCANKey(credentials.can)
                    ps.doPACE(paceKey, paceInfo.objectIdentifier, PACEInfo.toParameterSpec(paceInfo.parameterId), paceInfo.parameterId)
                    Log.i(tagName, "PACE-CAN OK (pasaporte, sin MRZ)")
                    return ChannelOutcome.ok("PACE-CAN / ICAO 9303 (pasaporte)")
                } catch (e: Exception) {
                    Log.w(tagName, "PACE-CAN falló (sin MRZ): ${e.javaClass.simpleName}: ${e.message}; SW/hint=${apduHint(e)}")
                }
                return ChannelOutcome.fail(accessDeniedGuidance(credentials, pacePresent = true, mrzAvailable = false), "PACE-CAN / ICAO 9303 (pasaporte)")
            }

            if (hasMrz) {
                // 1) PACE-MRZ (preferido para pasaportes)
                try {
                    val paceKey = PACEKeySpec.createMRZKey(bacKey)
                    ps.doPACE(paceKey, paceInfo.objectIdentifier, PACEInfo.toParameterSpec(paceInfo.parameterId), paceInfo.parameterId)
                    Log.i(tagName, "PACE-MRZ OK (doc=${maskDoc(mrz!!.normalizedDocumentNumber())})")
                    return ChannelOutcome.ok("PACE-MRZ / ICAO 9303 (pasaporte)")
                } catch (e: Exception) {
                    Log.w(tagName, "PACE-MRZ falló: ${e.javaClass.simpleName}: ${e.message}; SW/hint=${apduHint(e)}")
                }
                // 2) BAC
                try {
                    ps.doBAC(bacKey)
                    Log.i(tagName, "BAC OK tras fallo PACE-MRZ (doc=${maskDoc(mrz!!.normalizedDocumentNumber())})")
                    return ChannelOutcome.ok("BAC / ICAO 9303 (pasaporte)")
                } catch (e: Exception) {
                    Log.w(tagName, "BAC tras PACE-MRZ falló: ${e.javaClass.simpleName}: ${e.message}; SW/hint=${apduHint(e)}")
                }
                // 3) PACE-CAN intento opcional
                if (hasCan) {
                    try {
                        val paceKey = PACEKeySpec.createCANKey(credentials.can)
                        ps.doPACE(paceKey, paceInfo.objectIdentifier, PACEInfo.toParameterSpec(paceInfo.parameterId), paceInfo.parameterId)
                        Log.i(tagName, "PACE-CAN OK (pasaporte)")
                        return ChannelOutcome.ok("PACE-CAN / ICAO 9303 (pasaporte)")
                    } catch (e: Exception) {
                        Log.w(tagName, "PACE-CAN (opcional) rechazado: ${e.javaClass.simpleName}: ${e.message}; SW/hint=${apduHint(e)}")
                    }
                }
            } else if (hasCan) {
                try {
                    val paceKey = PACEKeySpec.createCANKey(credentials.can)
                    ps.doPACE(paceKey, paceInfo.objectIdentifier, PACEInfo.toParameterSpec(paceInfo.parameterId), paceInfo.parameterId)
                    Log.i(tagName, "PACE-CAN OK (pasaporte)")
                    return ChannelOutcome.ok("PACE-CAN / ICAO 9303 (pasaporte)")
                } catch (e: Exception) {
                    Log.w(tagName, "PACE-CAN falló: ${e.javaClass.simpleName}: ${e.message}; SW/hint=${apduHint(e)}")
                }
            }
            return ChannelOutcome.fail(accessDeniedGuidance(credentials, pacePresent = true, mrzAvailable = hasMrz), "PACE/BAC / ICAO 9303 (pasaporte)")
        }

        // Sin PACE: BAC requiere MRZ
        if (!hasMrz) {
            return ChannelOutcome.fail(
                "Este pasaporte no ofrece PACE y requiere los datos del MRZ (nº documento, fecha de nacimiento y caducidad) para BAC.",
                "BAC / ICAO 9303 (pasaporte)"
            )
        }
        try {
            ps.doBAC(bacKey)
            Log.i(tagName, "BAC OK (sin PACE, doc=${maskDoc(mrz!!.normalizedDocumentNumber())})")
            return ChannelOutcome.ok("BAC / ICAO 9303 (pasaporte)")
        } catch (e: Exception) {
            Log.e(tagName, "BAC falló: ${e.javaClass.simpleName}: ${e.message}; SW/hint=${apduHint(e)}", e)
            return ChannelOutcome.fail(accessDeniedGuidance(credentials, pacePresent = false, mrzAvailable = true), "BAC / ICAO 9303 (pasaporte)")
        }
    }

    private fun accessDeniedGuidance(credentials: AccessCredentials, pacePresent: Boolean, mrzAvailable: Boolean): String {
        if (!mrzAvailable) {
            return "No se obtuvo acceso con el CAN. Muchos pasaportes no aceptan CAN o requieren PACE-MRZ/BAC con los datos del MRZ. Prueba «Escanear con MRZ» e introduce nº de documento, fecha de nacimiento y caducidad."
        }
        val base = if (pacePresent) "El pasaporte rechazó la autenticación (PACE-MRZ/BAC)." else "El pasaporte rechazó la autenticación BAC (MRZ)."
        val canNote = if (credentials.hasCan()) " También se intentó CAN sin éxito." else ""
        return "$base Verifica nº de documento (sin espacios ni dígito de control erróneo), fecha de nacimiento y caducidad (formato MRZ). Revisa confusiones OCR O/0, I/1, ‘<’.$canNote"
    }

    private fun readCardAccess(ps: PassportService): CardAccessFile? = runCatching {
        ps.getInputStream(PassportService.EF_CARD_ACCESS).use { CardAccessFile(it) }
    }.onFailure { Log.d(tagName, "EF.CardAccess no disponible: ${it.message}") }.getOrNull()

    private fun parseCardAccess(cardAccess: CardAccessFile): CardAccessData {
        val paceAlgs = mutableListOf<String>()
        var ca = false; var ta = false
        for (info in cardAccess.securityInfos) when (info) {
            is PACEInfo -> paceAlgs.add(info.objectIdentifier)
            is ChipAuthenticationInfo -> ca = true
            is TerminalAuthenticationInfo -> ta = true
        }
        return CardAccessData(paceSupported = paceAlgs.isNotEmpty(), paceAlgorithm = paceAlgs, chipAuthenticationSupported = ca, terminalAuthenticationSupported = ta)
    }

    private fun readCom(ps: PassportService): EFComData? = runCatching {
        val bytes = ps.getInputStream(PassportService.EF_COM).use { it.readBytes() }
        EFComData(if (bytes.size >= 4) "${bytes[2].toInt() and 0xFF}.${bytes[3].toInt() and 0xFF}" else null, null, emptyList())
    }.getOrNull()

    private fun readSod(ps: PassportService): SODData? = runCatching {
        val bytes = ps.getInputStream(PassportService.EF_SOD).use { it.readBytes() }
        val hash = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
        SODData(rawHash = hash, digestAlgorithm = null, signatureAlgorithm = null, certificateIssuer = null)
    }.getOrNull()

    private fun detectDocument(dg1Bytes: ByteArray?, cardAccessData: CardAccessData?): DocumentDetection? {
        if (dg1Bytes == null || dg1Bytes.isEmpty()) return null
        return try {
            val dg1 = DG1File(ByteArrayInputStream(dg1Bytes))
            val mrz: MRZInfo = dg1.mrzInfo ?: return null
            val c = DocumentClassifier.classify(mrz.documentCode, mrz.issuingState)
            val protocols = buildList {
                if (cardAccessData?.paceSupported == true) add("PACE")
                if (cardAccessData?.chipAuthenticationSupported == true) add("CA")
                if (cardAccessData?.terminalAuthenticationSupported == true) add("TA")
            }
            DocumentDetection(c.documentType.name, c.countryCode, c.countryName, c.architecture.name, supportedProtocols = protocols)
        } catch (e: Exception) {
            Log.w(tagName, "No se pudo detectar documento desde DG1: ${e.message}")
            null
        }
    }

    private fun readFileBytes(ps: PassportService, fid: Short): ByteArray = try {
        ps.getInputStream(fid).use { it.readBytes() }
    } catch (e: Exception) {
        val sw = (e as? CardServiceException)?.sw ?: -1
        val m = e.message?.lowercase() ?: ""
        if (sw == 0x6A82 || sw == 0x6982 || sw == 0x6985 || sw == 0x6988 || sw == 0x6A86 ||
            m.contains("6a82") || m.contains("6982") || m.contains("6985") || m.contains("6988") || m.contains("6a86") ||
            m.contains("not found") || m.contains("file not found") || m.contains("no existe") || m.contains("not supported") || m.contains("security status")
        ) ByteArray(0) else throw e
    }

    private fun readDg(dg: Int, dgMap: MutableMap<Int, ByteArray?>, dgAnalysis: MutableMap<Int, DataGroupInfo>, read: () -> ByteArray?): String? = try {
        val bytes = read()
        if (bytes != null && bytes.isNotEmpty()) {
            dgMap[dg] = bytes; dgAnalysis[dg] = DataGroupInfo.read(dg, bytes)
            Log.d(tagName, "DG$dg OK: ${bytes.size}B")
        } else {
            dgAnalysis[dg] = DataGroupInfo.notPresent(dg); Log.d(tagName, "DG$dg no disponible")
        }
        null
    } catch (e: Exception) {
        dgAnalysis[dg] = DataGroupInfo.error(dg, e)
        if (isFatal(e)) "Conexión NFC perdida durante DG$dg (${apduHint(e)}). Mantén el pasaporte inmóvil y reintenta."
        else { Log.w(tagName, "DG$dg error: ${e.javaClass.simpleName}: ${apduHint(e)}"); null }
    }

    private fun failure(uid: String?, credentials: AccessCredentials, msg: String, detail: String = "ICAO 9303 (pasaporte)", error: Exception? = null): RawStructureData {
        if (error != null) Log.w(tagName, "Fallo pasaporte: $msg | ${apduHint(error)}")
        return RawStructureData(
            uid = uid, can = credentials.can,
            sessionStatus = NfcSessionStatus.FAILED, sessionError = msg,
            readerMethod = NfcReaderMethod.ICAO_PASSPORT.name, fallbackUsed = false
        )
    }

    private fun isFatal(e: Throwable): Boolean {
        val m = e.message.orEmpty()
        return e is TagLostException || e is IOException || m.contains("Tag was lost", true) || m.contains("transceive failed", true) || m.contains("connection lost", true)
    }

    private fun isTransient(msg: String?): Boolean {
        val m = msg?.lowercase() ?: return false
        return m.contains("perdida") || m.contains("transceive") || m.contains("tag was lost") || m.contains("conexión nfc")
    }

    private fun apduHint(e: Throwable): String {
        val sw = (e as? CardServiceException)?.sw?.let { "SW=0x${it.toString(16).uppercase()}" }
        return listOfNotNull(e.javaClass.simpleName, e.message, sw).joinToString(" | ")
    }

    private fun mapException(e: Exception): String = when {
        isFatal(e) -> "Se perdió la conexión NFC con el pasaporte. Mantén el documento inmóvil y reintenta."
        e is CardServiceException -> "Error de comunicación con el chip del pasaporte (${apduHint(e)})."
        else -> "No se pudo leer el pasaporte: ${e.javaClass.simpleName}."
    }

    private fun maskDoc(v: String) = if (v.length <= 3) "***" else "${v.take(2)}${"*".repeat((v.length - 3).coerceAtLeast(1))}${v.takeLast(1)}"
    private fun formatUid(id: ByteArray?): String = if (id == null || id.isEmpty()) "<sin uid>" else id.joinToString(":") { "%02X".format(it) }
}
