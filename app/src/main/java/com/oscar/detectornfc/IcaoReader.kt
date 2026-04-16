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
import org.jmrtd.lds.icao.DG2File
import org.jmrtd.lds.icao.DG7File
import java.io.ByteArrayInputStream
import java.io.IOException

class IcaoReader(private val tag: Tag?) {

    private val tagName = "IcaoReader"
    private val accessMethodDetail = "PACE-CAN / ICAO JMRTD"

    fun readWithCan(can: String): RawNfcData {
        if (tag == null) {
            Log.e(tagName, "Tag NFC nulo para lectura ICAO")
            return failure(null, "No se detecto un tag NFC valido.")
        }
        if (can.isBlank()) {
            Log.e(tagName, "CAN vacío: no se puede iniciar PACE ICAO")
            return failure(formatUid(tag.id), "CAN vacío. No se puede iniciar la lectura ICAO con PACE.")
        }

        val uid = formatUid(tag.id)
        val isoDep = IsoDep.get(tag)
        if (isoDep == null) {
            Log.e(tagName, "El tag no expone la tecnología IsoDep")
            return failure(uid, "El documento no expone la tecnología NFC requerida para lectura ICAO.")
        }

        val cardService = IsoDepCardService(isoDep)
        val passportService = PassportService(
            cardService,
            PassportService.NORMAL_MAX_TRANCEIVE_LENGTH,
            PassportService.DEFAULT_MAX_BLOCKSIZE,
            false,
            false
        )

        try {
            isoDep.timeout = 10000
            passportService.open()
            passportService.sendSelectApplet(false)

            val cardAccess = readCardAccess(passportService)
            val paceInfo = cardAccess?.securityInfos
                ?.firstNotNullOfOrNull { it as? PACEInfo }
                ?: return failure(uid, "El documento no ofrece PACE con CAN para el método ICAO.")

            val paceKey = PACEKeySpec.createCANKey(can)
            passportService.doPACE(
                paceKey,
                paceInfo.objectIdentifier,
                PACEInfo.toParameterSpec(paceInfo.parameterId),
                paceInfo.parameterId
            )
            passportService.sendSelectApplet(true)
            Log.i(tagName, "PACE ICAO completado correctamente")

            val dgMap = mutableMapOf<Int, ByteArray?>()
            val dgAnalysis = mutableMapOf<Int, DataGroupInfo>()
            var fatalSessionError: String? = null

            fatalSessionError = readDg(1, dgMap, dgAnalysis) {
                readFileBytes(passportService, PassportService.EF_DG1)
            }
            if (fatalSessionError == null) {
                fatalSessionError = readDg(11, dgMap, dgAnalysis) {
                    readFileBytes(passportService, PassportService.EF_DG11)
                }
            }
            if (fatalSessionError == null) {
                fatalSessionError = readDg(2, dgMap, dgAnalysis) {
                    val raw = readFileBytes(passportService, PassportService.EF_DG2)
                    extractFaceImage(raw) ?: raw
                }
            }
            if (fatalSessionError == null) {
                fatalSessionError = readDg(7, dgMap, dgAnalysis) {
                    val raw = readFileBytes(passportService, PassportService.EF_DG7)
                    extractSignatureImage(raw) ?: raw
                }
            }
            if (fatalSessionError == null) {
                fatalSessionError = readDg(15, dgMap, dgAnalysis) {
                    readFileBytes(passportService, PassportService.EF_DG15)
                }
            }

            if (fatalSessionError != null) {
                for (pendingDg in listOf(1, 11, 2, 7, 15)) {
                    if (!dgAnalysis.containsKey(pendingDg)) {
                        dgAnalysis[pendingDg] = DataGroupInfo.skipped(pendingDg, fatalSessionError)
                    }
                }
            }

            val available = dgAnalysis.filter { it.value.status == DGStatus.READ_OK }.keys.sorted()
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
            Log.i(tagName, "Lectura ICAO completada. DGs OK=$available, status=$sessionStatus")
            return RawNfcData(
                uid = uid,
                can = can,
                dataGroups = dgMap,
                sod = null,
                dgAnalysis = dgAnalysis,
                sessionStatus = sessionStatus,
                sessionError = sessionError,
                readerMethod = NfcReaderMethod.ICAO_JMRTD,
                accessMethodDetail = accessMethodDetail,
                fallbackUsed = true
            )
        } catch (e: Exception) {
            Log.e(tagName, "Error en lectura ICAO: ${e.message}", e)
            val userMessage = when {
                isFatalCommunicationError(e) ->
                    "Se perdió la conexión NFC durante la lectura ICAO. Mantén el documento inmóvil y reintenta."
                e is TagLostException || e is IOException || e is CardServiceException ->
                    "No se pudo completar la comunicación con el documento mediante ICAO."
                else -> "No se pudo leer el documento con el método ICAO alternativo."
            }
            return failure(uid, userMessage)
        } finally {
            runCatching { passportService.close() }
            runCatching { cardService.close() }
            runCatching { isoDep.close() }
        }
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
        return passportService.getInputStream(fid).use { it.readBytes() }
    }

    private fun extractFaceImage(raw: ByteArray): ByteArray? {
        val dg2 = DG2File(ByteArrayInputStream(raw))
        val faceInfo = dg2.faceInfos.firstOrNull() ?: return null
        val faceImage = faceInfo.faceImageInfos.firstOrNull() ?: return null
        return faceImage.imageInputStream.use { it.readBytes() }
    }

    private fun extractSignatureImage(raw: ByteArray): ByteArray? {
        val dg7 = DG7File(ByteArrayInputStream(raw))
        val image = dg7.images.firstOrNull() ?: return null
        return image.imageInputStream.use { it.readBytes() }
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

    private fun failure(uid: String?, message: String): RawNfcData {
        return RawNfcData(
            uid = uid,
            can = null,
            dataGroups = emptyMap(),
            sod = null,
            sessionStatus = NfcSessionStatus.FAILED,
            sessionError = message,
            readerMethod = NfcReaderMethod.ICAO_JMRTD,
            accessMethodDetail = accessMethodDetail,
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

