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
    private var retryDialog: AlertDialog? = null

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
                var structResult: RawStructureData? = null
                var dniResult: DniData? = null

                if (can.isNotBlank()) {
                    Log.d(TAG, "Leyendo documento con CAN enmascarado=${maskSecret(can)}")
                    structResult = readDocumentStructure(tag, can)
                } else {
                    Log.w(TAG, "CAN vacío: no se puede leer sin código de acceso")
                    structResult = RawStructureData(
                        uid = null, can = null,
                        sessionStatus = NfcSessionStatus.FAILED,
                        sessionError = "CAN vacío. No se puede iniciar la lectura NFC.",
                        readerMethod = NfcReaderMethod.EUROPEAN_STRUCTURE.name
                    )
                }

                if (structResult.sessionStatus == NfcSessionStatus.FAILED) {
                    val failureMessage = structResult.sessionError
                        ?: "No se pudo completar la lectura NFC. Inténtalo de nuevo."
                    Log.w(TAG, "Lectura fallida - uid=${structResult.uid ?: "<sin uid>"}, error=$failureMessage")
                    runOnUiThread {
                        isReading = false
                        showRetryDialog(failureMessage)
                    }
                    return@Thread
                }

                if (structResult.uid == null) {
                    Log.w(TAG, "La lectura NFC no devolvió UID; se informa error al usuario")
                    runOnUiThread {
                        isReading = false
                        showRetryDialog("Error al conectar con el chip. Verifica que el documento esté bien posicionado.")
                    }
                    return@Thread
                }

                val parser = NfcDataParser()
                val analyzedResult = parser.analyzeStructure(structResult)

                val availableDgs = analyzedResult.dgRawBytes.filterValues { it != null && it.isNotEmpty() }.keys.sorted()
                Log.i(TAG, "Lectura completada - uid=${analyzedResult.uid}, dgCount=${availableDgs.size}, dgs=$availableDgs")

                if (analyzedResult.sessionStatus == NfcSessionStatus.PARTIAL) {
                    Log.w(TAG, "Lectura parcial: ${analyzedResult.sessionError ?: "sin detalle"}")
                }

                val detection = analyzedResult.documentDetection
                if (detection?.documentType == DocumentType.GERMAN_EID.name) {
                    runOnUiThread {
                        updateStatus("Documento aleman eID detectado: soporte parcial con flujo universal")
                        Toast.makeText(
                            this,
                            "Alemania eID detectado. Este documento usa un estándar especial.",
                            Toast.LENGTH_LONG
                        ).show()
                    }
                }

                val gson = Gson()
                val json = gson.toJson(analyzedResult)
                Log.d(TAG, "JSON generado para resultados - length=${json.length}")

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
                    val userMessage = when {
                        e is NoClassDefFoundError || e is LinkageError -> {
                            val missingClass = (e as? NoClassDefFoundError)?.message ?: "desconocida"
                            Log.e(TAG, "Clase no encontrada: $missingClass")
                            "Error de compatibilidad de librerías. El documento puede requerir un método de lectura diferente. Intente con otro documento o actualice la app."
                        }
                        e is java.io.IOException ->
                            "Se perdió la conexión NFC durante la lectura. Mantén el documento inmóvil y reintenta."
                        e is SecurityException ->
                            "Error de seguridad en la comunicación con el chip. Verifica que el CAN sea correcto."
                        e.message?.contains("6a82", ignoreCase = true) == true ||
                        e.message?.contains("6988", ignoreCase = true) == true ->
                            "El documento rechazó el código de acceso. Verifica que el CAN sea correcto (6 dígitos en la parte inferior del documento)."
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
        retryDialog?.dismiss()
        
        updateStatus("Error en la lectura")
        
        retryDialog = AlertDialog.Builder(this)
            .setTitle("Error en la lectura del documento")
            .setMessage(failureMessage)
            .setCancelable(false)
            .setPositiveButton("Reintentar") { _, _ ->
                Log.d(TAG, "Usuario elige reintentar la lectura")
                updateStatus("Acerca el documento al teléfono…")
                isReading = false
            }
            .setNegativeButton("Volver") { _, _ ->
                Log.d(TAG, "Usuario elige volver a MainActivity")
                finish()
            }
            .show()
    }

    override fun onDestroy() {
        super.onDestroy()
        retryDialog?.dismiss()
        retryDialog = null
    }

    private fun readDocumentStructure(tag: Tag, can: String): RawStructureData {
        Log.i(TAG, "====== readDocumentStructure() INICIO ======")

        // Si dniedroid está disponible, intentar primero el método español (DNIe).
        // Esto evita el ciclo en el que EuropeanStructureReader falla con 6982
        // antes de poder detectar el tipo de documento.
        Log.i(TAG, "Paso 1: Verificando dependencias dniedroid...")
        val depsAvailable = DniReader.areDependenciesAvailable()
        Log.i(TAG, "Dependencias dniedroid disponibles: $depsAvailable")

        if (depsAvailable) {
            Log.i(TAG, "Paso 2: Intentando DniReader (método español)")
            updateStatus("Iniciando lectura DNIe…")

            val dniReader = DniReader(tag)
            val dniResult = dniReader.readDniSync(can)
            Log.i(TAG, "Resultado DniReader: status=${dniResult.sessionStatus}, error=${dniResult.sessionError}, fallbackSuggested=${dniResult.fallbackSuggested}")

            if (dniResult.sessionStatus != NfcSessionStatus.FAILED) {
                Log.i(TAG, "DniReader exitoso, convirtiendo resultado")
                val parser = NfcDataParser()
                val result = parser.convertFromRawNfcData(dniResult)
                Log.i(TAG, "====== readDocumentStructure() FIN (DNIe OK) ======")
                return result
            }

            if (!dniResult.fallbackSuggested) {
                Log.w(TAG, "DniReader falló con error fatal (sin fallback): ${dniResult.sessionError}")
                val parser = NfcDataParser()
                return parser.convertFromRawNfcData(dniResult)
            }

            val fallbackMessage = dniResult.fallbackReason
                ?: "El método DNIe no funcionó. Se intentará un método alternativo."
            Log.w(TAG, "DniReader sugiere fallback: $fallbackMessage")
            notifyFallbackStart(fallbackMessage)
        } else {
            Log.w(TAG, "Dependencias dniedroid no disponibles")
        }

        Log.i(TAG, "Paso 3: Intentando EuropeanStructureReader (método universal)")
        val reader = EuropeanStructureReader(tag)
        val structResult = reader.readAllStructures(can)
        Log.i(TAG, "Resultado EuropeanStructureReader: status=${structResult.sessionStatus}, error=${structResult.sessionError}, uid=${structResult.uid}")

        if (structResult.sessionStatus != NfcSessionStatus.FAILED) {
            Log.i(TAG, "EuropeanStructureReader exitoso, retornando resultado")
            Log.i(TAG, "====== readDocumentStructure() FIN (EuropeanReader OK) ======")
            return structResult
        }

        Log.w(TAG, "Paso 4: EuropeanStructureReader falló. Intentando ICAO fallback...")
        val result = tryIcaoFallback(tag, can, "Documento leído con método ICAO estándar.")
        Log.i(TAG, "====== readDocumentStructure() FIN (ICAO fallback) ======")
        return result
    }

    private fun tryIcaoFallback(tag: Tag, can: String, message: String): RawStructureData {
        Log.i(TAG, "====== tryIcaoFallback() INICIO ======")
        Log.i(TAG, "Mensaje: $message")
        updateStatus(message)
        val icaoReader = IcaoReader(tag)
        val icaoResult = icaoReader.readWithCan(can)
        Log.i(TAG, "Resultado IcaoReader: status=${icaoResult.sessionStatus}, error=${icaoResult.sessionError}, dgs=${icaoResult.dataGroups.keys}")
        val parser = NfcDataParser()
        val result = parser.convertFromRawNfcData(icaoResult)
        Log.i(TAG, "====== tryIcaoFallback() FIN ======")
        return result
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