package com.oscar.detectornfc.nfc

import android.content.Context
import android.util.Log
import com.oscar.detectornfc.AccessCredentials
import com.oscar.detectornfc.NfcReaderMethod
import com.oscar.detectornfc.NfcSessionStatus
import com.oscar.detectornfc.RawNfcData
import com.oscar.detectornfc.RawStructureData
import com.google.gson.GsonBuilder
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Persiste informes de intentos NFC en almacenamiento app-specific externo,
 * accesible desde ADB: /sdcard/Android/data/com.oscar.detectornfc/files/nfc_logs/
 */
object NfcAttemptLogger {
    private const val TAG = "NfcAttemptLogger"
    private val gson = GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create()

    fun logAttempt(
        context: Context,
        scanMode: String,
        credentials: AccessCredentials,
        result: RawStructureData?,
        rawNfc: RawNfcData? = null,
        extra: Map<String, String?> = emptyMap(),
        error: Throwable? = null
    ): File? {
        return try {
            val dir = context.getExternalFilesDir("nfc_logs") ?: return null
            if (!dir.exists()) dir.mkdirs()
            val ts = SimpleDateFormat("yyyyMMdd_HHmmss_SSS", Locale.US).format(Date())
            val file = File(dir, "nfc_attempt_$ts.json")

            val payload = linkedMapOf<String, Any?>(
                "timestamp" to ts,
                "scanMode" to scanMode,
                "credentials" to maskCredentials(credentials),
                "readerMethod" to (result?.readerMethod ?: rawNfc?.readerMethod?.name),
                "sessionStatus" to (result?.sessionStatus?.name ?: rawNfc?.sessionStatus?.name),
                "sessionError" to (result?.sessionError ?: rawNfc?.sessionError),
                "accessMethodDetail" to (extra["accessMethodDetail"] ?: rawNfc?.accessMethodDetail),
                "uid" to (result?.uid ?: rawNfc?.uid),
                "dgAnalysis" to summarizeDgAnalysis(result, rawNfc),
                "dgsPresent" to (result?.dgRawBytes?.filterValues { it != null && it.isNotEmpty() }?.keys?.sorted()
                    ?: rawNfc?.dataGroups?.filterValues { it != null && it.isNotEmpty() }?.keys?.sorted()),
                "fallbackUsed" to (result?.fallbackUsed ?: rawNfc?.fallbackUsed),
                "fallbackSuggested" to rawNfc?.fallbackSuggested,
                "fallbackReason" to rawNfc?.fallbackReason,
                "documentDetection" to result?.documentDetection?.let {
                    mapOf("type" to it.documentType, "country" to it.countryCode, "arch" to it.architecture)
                },
                "extra" to extra.filterValues { it != null },
                "exception" to error?.let { mapOf("type" to it.javaClass.name, "message" to it.message, "stack" to it.stackTraceToString().take(4000)) }
            )
            file.writeText(gson.toJson(payload), Charsets.UTF_8)

            // Also persist full scan result JSON (same shape as ResultActivity uses) for ADB pull
            val scanJson = when {
                result != null -> gson.toJson(result)
                rawNfc != null -> gson.toJson(rawNfc)
                else -> null
            }
            if (scanJson != null) {
                File(dir, "scan_result_$ts.json").writeText(scanJson, Charsets.UTF_8)
            }

            Log.i(TAG, "Intento NFC persistido: ${file.absolutePath}")
            file
        } catch (e: Exception) {
            Log.w(TAG, "No se pudo persistir intento NFC: ${e.message}", e)
            null
        }
    }

    private fun maskCredentials(credentials: AccessCredentials): Map<String, Any?> {
        val mrz = credentials.mrz
        val maskedDoc = mrz?.documentNumber?.let { maskDoc(it) }
        return mapOf(
            "can" to credentials.can?.let { maskSecret(it) },
            "mrzDocumentNumber" to maskedDoc,
            "mrzDateOfBirth" to mrz?.dateOfBirth?.let { "**${it.takeLast(2)}" },
            "mrzDateOfExpiry" to mrz?.dateOfExpiry?.let { "**${it.takeLast(2)}" },
            "methods" to credentials.describe()
        )
    }

    private fun maskDoc(value: String): String {
        if (value.length <= 3) return "***"
        return "${value.take(2)}${"*".repeat((value.length - 3).coerceAtLeast(1))}${value.takeLast(1)}"
    }

    private fun maskSecret(value: String): String {
        if (value.isBlank()) return "<vacío>"
        if (value.length <= 2) return "*".repeat(value.length)
        return "${value.take(1)}${"*".repeat(value.length - 2)}${value.takeLast(1)}"
    }

    private fun summarizeDgAnalysis(result: RawStructureData?, rawNfc: RawNfcData?): Map<String, Any?> {
        val analysis = result?.dgAnalysis ?: rawNfc?.dgAnalysis ?: return emptyMap()
        return analysis.entries.associate { (dg, info) ->
            dg.toString() to mapOf(
                "status" to info.status.name,
                "sizeBytes" to info.sizeBytes,
                "exceptionType" to info.exceptionType,
                "exceptionMessage" to info.exceptionMessage?.take(240)
            )
        }
    }
}
