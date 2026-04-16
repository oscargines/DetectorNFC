package com.oscar.detectornfc

import android.content.Intent
import android.nfc.NfcAdapter
import android.nfc.Tag
import android.os.Bundle
import android.util.Log
import android.view.View
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import com.google.gson.Gson
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * Activity principal de escaneo NFC.
 *
 * Flujo:
 *  1. Se recibe el CAN desde la Activity anterior (6 dígitos del DNIe/TIE).
 *  2. Al detectar un tag NFC en foreground dispatch, se lee el documento en un hilo.
 *  3. Los datos crudos se parsean con NfcDataParser y se envían a ResultActivity como JSON.
 *
 * Para documentos ICAO sin CAN visible (pasaportes), llamar a DniReader.readWithMrz(mrz).
 */
class NFCScanActivity : AppCompatActivity(), NfcAdapter.ReaderCallback {

    companion object {
        private const val TAG = "NFCScanActivity"
        const val EXTRA_CAN = "CAN"
        const val EXTRA_JSON_PATH = "JSON_PATH"
    }

    private var can: String = ""
    private var nfcAdapter: NfcAdapter? = null
    private var isReading = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_nfc_scan)

        can = intent.getStringExtra(EXTRA_CAN) ?: ""
        nfcAdapter = NfcAdapter.getDefaultAdapter(this)
        Log.i(TAG, "onCreate() - canLength=${can.length}, canMasked=${maskSecret(can)}, nfcSupported=${nfcAdapter != null}, nfcEnabled=${nfcAdapter?.isEnabled == true}")

        if (nfcAdapter == null) {
            Log.e(TAG, "Dispositivo sin NFC; cerrando pantalla de escaneo")
            Toast.makeText(this, "Este dispositivo no tiene NFC", Toast.LENGTH_LONG).show()
            finish()
            return
        }

        updateStatus("Acerca el documento al teléfono…")
    }

    override fun onResume() {
        super.onResume()
        // Reader Mode: en API 36 ya no existe FLAG_READER_ISO_DEP.
        // Para documentos de identidad NFC basta con escuchar NFC-A / NFC-B
        // y omitir la comprobación NDEF.
        val flags = NfcAdapter.FLAG_READER_NFC_A or
                NfcAdapter.FLAG_READER_NFC_B or
                NfcAdapter.FLAG_READER_SKIP_NDEF_CHECK // No necesitamos NDEF
        val options = Bundle().apply {
            putInt(NfcAdapter.EXTRA_READER_PRESENCE_CHECK_DELAY, 300)
        }
        Log.d(TAG, "onResume() - enableReaderMode flags=$flags, isReading=$isReading")
        nfcAdapter?.enableReaderMode(this, this, flags, options)
    }

    override fun onPause() {
        super.onPause()
        Log.d(TAG, "onPause() - disableReaderMode")
        nfcAdapter?.disableReaderMode(this)
    }

    // ------------------------------------------------------------------ //
    //  NfcAdapter.ReaderCallback
    // ------------------------------------------------------------------ //

    override fun onTagDiscovered(tag: Tag?) {
        if (tag == null) {
            Log.w(TAG, "onTagDiscovered() recibido con tag nulo")
            return
        }
        if (isReading) {
            Log.w(TAG, "Tag ignorado porque ya hay una lectura en curso")
            return
        }

        Log.i(
            TAG,
            "Tag descubierto - id=${formatUid(tag.id)}, techs=${tag.techList.joinToString()}, isReading=$isReading"
        )
        isReading = true
        updateStatus("Leyendo documento…")

        Thread {
            try {
                Log.d(TAG, "Iniciando hilo de lectura NFC")
                val rawData: RawNfcData

                // Intentamos primero con CAN (DNIe 3/4 y TIE).
                // Si el CAN está vacío, caemos al modo MRZ (no implementado en esta pantalla).
                rawData = if (can.isNotBlank()) {
                    Log.d(TAG, "Leyendo documento con CAN enmascarado=${maskSecret(can)}")
                    readDocumentWithFallback(tag, can)
                } else {
                    Log.w(TAG, "CAN vacío: no se puede leer sin código de acceso")
                    RawNfcData(
                        uid = null,
                        can = null,
                        dataGroups = emptyMap(),
                        sod = null,
                        sessionStatus = NfcSessionStatus.FAILED,
                        sessionError = "CAN vacío. No se puede iniciar la lectura NFC."
                    )
                }

                if (rawData.sessionStatus == NfcSessionStatus.FAILED) {
                    val failureMessage = rawData.sessionError
                        ?: "No se pudo completar la lectura NFC. Inténtalo de nuevo."
                    Log.w(TAG, "Lectura fallida - uid=${rawData.uid ?: "<sin uid>"}, error=$failureMessage")
                    runOnUiThread {
                        isReading = false
                        showRetryDialog(failureMessage)
                    }
                    return@Thread
                }

                if (rawData.uid == null) {
                    Log.w(TAG, "La lectura NFC no devolvió UID; se informa error al usuario")
                    runOnUiThread {
                        isReading = false
                        showRetryDialog("Error al conectar con el chip. Verifica que el documento esté bien posicionado.")
                    }
                    return@Thread
                }

                val availableDgs = rawData.dataGroups.filterValues { it != null }.keys.sorted()
                Log.i(TAG, "Lectura completada - uid=${rawData.uid}, dgCount=${availableDgs.size}, dgs=$availableDgs, sod=${rawData.sod != null}")

                if (rawData.sessionStatus == NfcSessionStatus.PARTIAL) {
                    Log.w(TAG, "Lectura parcial: ${rawData.sessionError ?: "sin detalle"}")
                }

                val parser = NfcDataParser()
                val dniData = parser.parseRawData(rawData)
                Log.i(
                    TAG,
                    "Parse completado - nombre=${dniData.nombre != null}, apellidos=${dniData.apellidos != null}, numeroDocumento=${dniData.numeroDocumento != null}, error=${dniData.error ?: "<ninguno>"}"
                )

                if (dniData.documentType == DocumentType.GERMAN_EID.name) {
                    runOnUiThread {
                        updateStatus("Documento aleman eID detectado: soporte parcial con flujo ICAO")
                        Toast.makeText(
                            this,
                            "Alemania eID detectado. Este documento usa un estándar especial y puede requerir integración dedicada.",
                            Toast.LENGTH_LONG
                        ).show()
                    }
                }

                val gson = Gson()
                val json = gson.toJson(
                    mapOf(
                        "raw" to mapOf(
                            "uid" to rawData.uid,
                            "can" to rawData.can,
                            "readerMethod" to rawData.readerMethod.name,
                            "accessMethodDetail" to rawData.accessMethodDetail,
                            "fallbackUsed" to rawData.fallbackUsed,
                            "fallbackSuggested" to rawData.fallbackSuggested,
                            "fallbackReason" to rawData.fallbackReason,
                            "dgMap" to rawData.dataGroups.mapValues { (_, v) -> v },
                            "dgAnalysis" to rawData.dgAnalysis
                        ),
                        "dni" to dniData
                    )
                )
                Log.d(TAG, "JSON generado para resultados - length=${json.length}")

                // Evitamos TransactionTooLargeException pasando ruta de fichero en vez de un JSON gigante por Intent.
                val jsonFile = File(cacheDir, "scan_result_${System.currentTimeMillis()}.json")
                jsonFile.writeText(json, Charsets.UTF_8)
                Log.d(TAG, "JSON persistido en cache - path=${jsonFile.absolutePath}, bytes=${jsonFile.length()}")

                runOnUiThread {
                    Log.i(TAG, "Abriendo ResultActivity")
                    val intent = Intent(this, ResultActivity::class.java)
                    intent.putExtra(EXTRA_JSON_PATH, jsonFile.absolutePath)
                    startActivity(intent)
                    finish()
                }

            } catch (e: Throwable) {
                Log.e(TAG, "Error en lectura NFC: ${e.message}", e)
                runOnUiThread {
                    val userMessage = when (e) {
                        is NoClassDefFoundError, is LinkageError ->
                            "Falta una dependencia necesaria de la librería NFC. Revisa la instalación de la app."
                        is java.io.IOException ->
                            "Se perdió la conexión NFC durante la lectura. Mantén el documento inmóvil y reintenta."
                        else -> "Error inesperado: ${e.message}. Inténtalo de nuevo."
                    }
                    isReading = false
                    showRetryDialog(userMessage)
                }
            }
        }.start()
    }

    // ------------------------------------------------------------------ //
    //  UI callbacks
    // ------------------------------------------------------------------ //

    /** Botón "Cancelar" del topbar — referenciado por android:onClick en el layout. */
    @Suppress("UNUSED_PARAMETER")
    fun onCancelClick(v: View) {
        Log.d(TAG, "Escaneo cancelado por el usuario desde el topbar")
        finish()
    }

    /**
     * Muestra un diálogo cuando falla la lectura, permitiendo reintentar o volver.
     */
    private fun showRetryDialog(failureMessage: String) {
        if (isFinishing || isDestroyed) return
        
        updateStatus("Error en la lectura")
        
        val dialog = AlertDialog.Builder(this)
            .setTitle("Error en la lectura del documento")
            .setMessage(failureMessage)
            .setCancelable(false)  // Evitar cerrar con back
            .setPositiveButton("Reintentar") { _, _ ->
                Log.d(TAG, "Usuario elige reintentar la lectura")
                updateStatus("Acerca el documento al teléfono…")
                isReading = false
                // El ReaderCallback volverá a escuchar tags al estar en foreground
            }
            .setNegativeButton("Volver") { _, _ ->
                Log.d(TAG, "Usuario elige volver a MainActivity")
                finish()  // Cierra la actividad y regresa
            }
            .show()
    }

    private fun readDocumentWithFallback(tag: Tag, can: String): RawNfcData {
        val primaryResult = DniReader(tag).readDniSync(can)
        if (!primaryResult.fallbackSuggested) {
            return primaryResult
        }

        val fallbackMessage = primaryResult.fallbackReason
            ?: "Se intentará un método ICAO alternativo para este documento."
        Log.i(TAG, "Activando fallback ICAO: $fallbackMessage")
        notifyFallbackStart(fallbackMessage)
        updateStatus("Iniciando procedimiento ICAO alternativo…")
        return IcaoReader(tag).readWithCan(can)
    }

    private fun notifyFallbackStart(message: String) {
        val latch = CountDownLatch(1)
        runOnUiThread {
            updateStatus(message)
            Toast.makeText(this, message, Toast.LENGTH_LONG).show()
            latch.countDown()
        }
        latch.await(350, TimeUnit.MILLISECONDS)
        Thread.sleep(1200)
    }

    // ------------------------------------------------------------------ //
    //  Helpers
    // ------------------------------------------------------------------ //

    private fun updateStatus(msg: String) {
        Log.d(TAG, "updateStatus() - $msg")
        runOnUiThread {
            try {
                findViewById<TextView>(R.id.tv_status)?.text = msg
            } catch (_: Exception) {}
        }
    }

    private fun maskSecret(value: String): String {
        if (value.isBlank()) return "<vacío>"
        if (value.length <= 2) return "*".repeat(value.length)
        return "${value.take(1)}${"*".repeat(value.length - 2)}${value.takeLast(1)}"
    }

    private fun formatUid(id: ByteArray?): String {
        if (id == null || id.isEmpty()) return "<sin uid>"
        return id.joinToString(":") { "%02X".format(it) }
    }
}