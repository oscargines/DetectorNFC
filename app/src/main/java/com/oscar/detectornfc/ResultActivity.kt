package com.oscar.detectornfc

import android.content.ContentValues
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.provider.MediaStore
import android.text.SpannableString
import android.text.Spanned
import android.text.style.ForegroundColorSpan
import android.util.Log
import android.view.View
import android.widget.Button
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.FileProvider
import de.tsenger.androsmex.mrtd.DG1_Dnie
import de.tsenger.androsmex.mrtd.DG11
import de.tsenger.androsmex.mrtd.DG13
import de.tsenger.androsmex.mrtd.DG2
import de.tsenger.androsmex.mrtd.DG7
import java.io.File
import java.io.FileOutputStream
import java.nio.charset.StandardCharsets
import java.security.MessageDigest

class ResultActivity : AppCompatActivity() {
    companion object {
        private const val TAG = "ResultActivity"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_result)

        val btnShare = findViewById<Button>(R.id.btn_share)
        val btnBack = findViewById<Button>(R.id.btn_back)
        val btnSave = findViewById<Button>(R.id.btn_save_image)

        val jsonPath = intent.getStringExtra(NFCScanActivity.EXTRA_JSON_PATH)
        val json = if (!jsonPath.isNullOrBlank()) {
            runCatching { File(jsonPath).readText(StandardCharsets.UTF_8) }
                .getOrElse {
                    Log.w(TAG, "No se pudo leer JSON desde path=$jsonPath: ${it.message}")
                    intent.getStringExtra("JSON") ?: "{}"
                }
        } else {
            intent.getStringExtra("JSON") ?: "{}"
        }
        Log.i(TAG, "onCreate() - jsonLength=${json.length}")

        btnBack.setOnClickListener {
            Log.d(TAG, "Volver pulsado: cerrando ResultActivity")
            finish()
        }

        btnShare.setOnClickListener {
            try {
                val tmp = File(cacheDir, "dni_read.json")
                FileOutputStream(tmp).use { it.write(json.toByteArray()) }
                val uri = FileProvider.getUriForFile(this, "${applicationContext.packageName}.fileprovider", tmp)
                Log.i(TAG, "Preparando compartición de JSON: ${tmp.absolutePath}")
                val share = Intent(Intent.ACTION_SEND)
                share.type = "application/json"
                share.putExtra(Intent.EXTRA_STREAM, uri)
                share.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                startActivity(Intent.createChooser(share, getString(R.string.share_json)))
            } catch (e: Exception) {
                Log.e(TAG, "Error compartiendo JSON: ${e.message}", e)
            }
        }

        try {
            val gson = com.google.gson.Gson()
            @Suppress("UNCHECKED_CAST")
            val map = gson.fromJson(json, Map::class.java) as Map<String, Any?>
            val raw = map["raw"] as? Map<*, *> ?: emptyMap<String, Any?>()
            val dni = map["dni"] as? Map<*, *> ?: emptyMap<String, Any?>()
            Log.d(TAG, "JSON parseado - rawKeys=${raw.keys}, dniKeys=${dni.keys}")
            logBiometricReadContext(raw)

            bindSummary(raw, dni)
            bindWarning(dni)
            val hasPhoto = bindPhoto(raw)
            bindSignature(raw)
            bindIdentity(dni)
            bindDataGroups(raw)
            bindDiagnostics(raw, dni)
            bindSaveButton(btnSave, raw, hasPhoto)
        } catch (e: Exception) {
            Log.e(TAG, "Error procesando JSON de resultados: ${e.message}", e)
            showFallbackError(json)
            btnSave.isEnabled = false
        }
    }

    private fun bindSummary(raw: Map<*, *>, dni: Map<*, *>) {
        val docType = dni["documentType"]?.toString().orDash()
        val countryCode = dni["countryCode"]?.toString().orDash()
        val countryName = dni["countryName"]?.toString().orEmpty()
        val architecture = dni["architecture"]?.toString().orDash()

        findViewById<TextView>(R.id.tv_doc_title).text = listOf(
            dni["nombre"]?.toString().nullIfBlank(),
            dni["apellidos"]?.toString().nullIfBlank()
        ).joinToString(" ").ifBlank { getString(R.string.results_title) }

        findViewById<TextView>(R.id.tv_doc_subtitle).text =
            "$docType · $countryCode ${countryName}".trim()

        setInfoText(R.id.tv_uid, R.string.label_uid, raw["uid"]?.toString())
        setInfoText(R.id.tv_can, R.string.label_can, raw["can"]?.toString())
    }

    private fun bindIdentity(dni: Map<*, *>) {
        setInfoText(R.id.tv_name, R.string.label_name, dni["nombre"]?.toString())
        setInfoText(R.id.tv_surname, R.string.label_surname, dni["apellidos"]?.toString())
        setInfoText(R.id.tv_doc_number, R.string.label_doc_number, dni["numeroDocumento"]?.toString())
        setInfoText(R.id.tv_birth_date, R.string.label_birth_date, dni["fechaNacimiento"]?.toString())
        setInfoText(R.id.tv_nationality, R.string.label_nationality, dni["nacionalidad"]?.toString())
        setInfoText(R.id.tv_type, R.string.label_doc_type, dni["documentType"]?.toString())
        setInfoText(
            R.id.tv_country,
            R.string.label_country,
            listOf(dni["countryCode"]?.toString().nullIfBlank(), dni["countryName"]?.toString().nullIfBlank())
                .joinToString(" ")
                .ifBlank { null }
        )
        setInfoText(R.id.tv_architecture, R.string.label_architecture, dni["architecture"]?.toString())
        setInfoText(R.id.tv_error_value, R.string.label_error, dni["error"]?.toString())
    }

    private fun bindWarning(dni: Map<*, *>) {
        val cardWarning = findViewById<View>(R.id.card_warning)
        val tvWarning = findViewById<TextView>(R.id.tv_warning)

        val warningText = when {
            dni["fallbackUsed"] as? Boolean == true ->
                "La lectura se completó con un método ICAO alternativo porque el método español no era compatible con este documento."
            dni["documentType"]?.toString() == DocumentType.GERMAN_EID.name -> getString(R.string.german_eid_warning)
            !dni["error"]?.toString().isNullOrBlank() -> dni["error"]?.toString()
            else -> null
        }

        if (warningText.isNullOrBlank()) {
            cardWarning.visibility = View.GONE
        } else {
            cardWarning.visibility = View.VISIBLE
            tvWarning.text = warningText
        }
    }

    private fun bindPhoto(raw: Map<*, *>): Boolean {
        val ivPhoto = findViewById<ImageView>(R.id.iv_photo)
        val tvPhotoPlaceholder = findViewById<TextView>(R.id.tv_photo_placeholder)
        return try {
            Log.d(TAG, "DG2 render - iniciando extracción de bytes")
            val dg2Bytes = extractPhotoBytes(raw)
            if (dg2Bytes != null) {
                val result = ImageDecoder.decode(dg2Bytes)
                Log.d(TAG, "DG2 render - bytes=${dg2Bytes.size}, head=${hexHead(dg2Bytes)}, formato=${result.format}, decoded=${result.success}")

                if (result.bitmap != null) {
                    Log.i(TAG, "Foto DG2 decodificada correctamente - ${result.bitmap.width}×${result.bitmap.height}, formato=${result.format}")
                    ivPhoto.setImageBitmap(result.bitmap)
                    ivPhoto.visibility = View.VISIBLE
                    tvPhotoPlaceholder.visibility = View.GONE
                    true
                } else {
                    // Decode falló — distinguir JP2 vs otros formatos
                    if (result.format == ImageFormat.JP2) {
                        Log.w(TAG, "Formato no soportado para render: JP2 (OpenJPEG devolvió null)")
                        tvPhotoPlaceholder.text = getString(R.string.photo_jp2_decode_failed)
                    } else if (!result.format.isAndroidRenderable) {
                        Log.w(TAG, "Formato no soportado para render: ${result.format.name}")
                        tvPhotoPlaceholder.text = getString(R.string.photo_format_not_renderable, result.format.name)
                    } else {
                        Log.w(TAG, "DG2 render - decode devolvió null. bytes=${dg2Bytes.size}, head=${hexHead(dg2Bytes)}")
                        tvPhotoPlaceholder.text = getString(R.string.no_photo_available)
                    }
                    ivPhoto.visibility = View.GONE
                    tvPhotoPlaceholder.visibility = View.VISIBLE
                    true // hay bytes → se puede guardar
                }
            } else {
                Log.w(TAG, "DG2 render - no hay bytes para pintar imagen")
                ivPhoto.visibility = View.GONE
                tvPhotoPlaceholder.text = getString(R.string.no_photo_available)
                tvPhotoPlaceholder.visibility = View.VISIBLE
                false
            }
        } catch (e: Exception) {
            Log.d(TAG, "No se pudo procesar DG2: ${e.message}")
            ivPhoto.visibility = View.GONE
            tvPhotoPlaceholder.text = getString(R.string.no_photo_available)
            tvPhotoPlaceholder.visibility = View.VISIBLE
            false
        }
    }

    private fun bindSignature(raw: Map<*, *>): Boolean {
        val ivSignature = findViewById<ImageView>(R.id.iv_signature)
        val tvSignaturePlaceholder = findViewById<TextView>(R.id.tv_signature_placeholder)
        return try {
            Log.d(TAG, "DG7 render - iniciando extracción de bytes")
            val dg7Bytes = extractSignatureBytes(raw)
            if (dg7Bytes != null) {
                val result = ImageDecoder.decode(dg7Bytes)
                Log.d(TAG, "DG7 render - bytes=${dg7Bytes.size}, head=${hexHead(dg7Bytes)}, formato=${result.format}, decoded=${result.success}")

                if (result.bitmap != null) {
                    Log.i(TAG, "Firma DG7 decodificada correctamente - ${result.bitmap.width}×${result.bitmap.height}, formato=${result.format}")
                    ivSignature.setImageBitmap(result.bitmap)
                    ivSignature.visibility = View.VISIBLE
                    tvSignaturePlaceholder.visibility = View.GONE
                    true
                } else {
                    if (result.format == ImageFormat.JP2) {
                        Log.w(TAG, "Formato no soportado para render: JP2 (OpenJPEG devolvió null)")
                        tvSignaturePlaceholder.text = getString(R.string.signature_jp2_decode_failed)
                    } else if (!result.format.isAndroidRenderable) {
                        Log.w(TAG, "Formato no soportado para render: ${result.format.name}")
                        tvSignaturePlaceholder.text = getString(R.string.signature_format_not_renderable, result.format.name)
                    } else {
                        Log.w(TAG, "DG7 render - decode devolvió null. bytes=${dg7Bytes.size}, head=${hexHead(dg7Bytes)}")
                        tvSignaturePlaceholder.text = getString(R.string.no_signature_available)
                    }
                    ivSignature.visibility = View.GONE
                    tvSignaturePlaceholder.visibility = View.VISIBLE
                    false
                }
            } else {
                Log.w(TAG, "DG7 render - no hay bytes para pintar firma")
                ivSignature.visibility = View.GONE
                tvSignaturePlaceholder.text = getString(R.string.no_signature_available)
                tvSignaturePlaceholder.visibility = View.VISIBLE
                false
            }
        } catch (e: Exception) {
            Log.d(TAG, "No se pudo procesar DG7: ${e.message}")
            ivSignature.visibility = View.GONE
            tvSignaturePlaceholder.text = getString(R.string.no_signature_available)
            tvSignaturePlaceholder.visibility = View.VISIBLE
            false
        }
    }

    private fun bindDataGroups(raw: Map<*, *>) {
        val container = findViewById<LinearLayout>(R.id.ll_dg_rows)
        container.removeAllViews()

        val dgMap = raw["dgMap"] as? Map<*, *>
        val dgAnalysisMap = raw["dgAnalysis"] as? Map<*, *>
        val dgIndexes = collectDgIndexes(dgMap, dgAnalysisMap)
        if (dgIndexes.isEmpty()) {
            container.addView(buildRow(getString(R.string.no_dg_data)))
            return
        }

        dgIndexes.forEach { dg ->
            val bytes = dgMap?.let { readDgBytes(it, dg) }
            val analysis = parseDgAnalysisEntry(dgAnalysisMap, dg)

            val status = analysis?.status ?: if (bytes != null) "READ_OK" else "UNKNOWN"
            val size = analysis?.sizeBytes ?: bytes?.size
            val hash = analysis?.sha256 ?: bytes?.let { sha256(it) }
            container.addView(buildRow("DG$dg · $status · ${size?.let { "$it B" } ?: "-"} · SHA ${hash?.take(12) ?: "-"}…"))

            val errorText = listOfNotNull(analysis?.exceptionType, analysis?.exceptionMessage)
                .joinToString(": ")
                .nullIfBlank()
            if (errorText != null) {
                container.addView(buildRow("DG$dg · Error lectura: $errorText"))
            }

            if (bytes != null) {
                container.addView(buildRow("DG$dg · HEX: ${toHexPreview(bytes)}"))

                val asciiPreview = toAsciiPreview(bytes)
                if (asciiPreview != null) {
                    container.addView(buildRow("DG$dg · ASCII: $asciiPreview"))
                }

                val decoded = decodeFieldsByDg(dg, bytes)
                if (decoded.isNotEmpty()) {
                    decoded.forEach { container.addView(buildRow(it)) }
                } else {
                    container.addView(buildRow("DG$dg · Sin parser específico en la app (solo bruto)."))
                }
            }
        }
    }

    private fun bindDiagnostics(raw: Map<*, *>, dni: Map<*, *>) {
        val container = findViewById<LinearLayout>(R.id.ll_diag_rows)
        container.removeAllViews()

        val dgMap = raw["dgMap"] as? Map<*, *>
        val dgAnalysisMap = raw["dgAnalysis"] as? Map<*, *>
        if (dgMap == null) {
            container.addView(buildRow(getString(R.string.no_dg_data)))
            return
        }

        val documentType = dni["documentType"]?.toString()
        val countryCode = dni["countryCode"]?.toString()
        val architecture = dni["architecture"]?.toString()

        val readDgs = (1..16).filter { i -> (dgMap[i.toString()] ?: dgMap[i]) != null }.toSet()
        val expected = DocumentDiagnostics.expectedDataGroups(documentType, countryCode, architecture)
        val statusByDg = parseStatusByDg(dgAnalysisMap)
        val comparison = DocumentDiagnostics.compare(expected, readDgs, statusByDg)

        if (comparison.isEmpty()) {
            container.addView(buildRow(getString(R.string.no_dg_data)))
            return
        }

        comparison.forEach { item ->
            val text = "DG${item.dg} · esperado ${if (item.expected) "sí" else "no"} · leído ${if (item.read) "sí" else "no"} · ${item.status}"
            container.addView(buildRow(text))
        }
    }

    private fun bindSaveButton(btnSave: Button, raw: Map<*, *>, hasPhoto: Boolean) {
        btnSave.isEnabled = hasPhoto
        btnSave.alpha = if (hasPhoto) 1f else 0.5f
        btnSave.setOnClickListener {
            val bytes = extractPhotoBytes(raw)
            if (bytes == null) {
                Toast.makeText(this, getString(R.string.error_saving), Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }

            // Detección de formato por cabecera → extensión y MIME correctos
            val format = ImageFormatDetector.detect(bytes)
            val mimeType = format.mimeType
            val extension = format.extension
            val displayName = "dni_photo_${System.currentTimeMillis()}.$extension"
            Log.i(TAG, "Guardando imagen DG2 en galería como $displayName (${bytes.size} bytes, formato=$format, MIME=$mimeType)")

            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    val values = ContentValues().apply {
                        put(MediaStore.Images.Media.DISPLAY_NAME, displayName)
                        put(MediaStore.Images.Media.MIME_TYPE, mimeType)
                        put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/DetectorNFC")
                    }
                    val uri = contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
                    if (uri != null) {
                        contentResolver.openOutputStream(uri)?.use { it.write(bytes) }
                        Log.i(TAG, "Imagen guardada correctamente en galería (Android Q+)")
                        Toast.makeText(this, getString(R.string.saved_to_gallery), Toast.LENGTH_SHORT).show()
                    } else {
                        Log.w(TAG, "No se obtuvo URI de MediaStore para guardar la imagen")
                        Toast.makeText(this, getString(R.string.error_saving), Toast.LENGTH_SHORT).show()
                    }
                } else {
                    val values = ContentValues().apply {
                        put(MediaStore.Images.Media.DISPLAY_NAME, displayName)
                        put(MediaStore.Images.Media.MIME_TYPE, mimeType)
                    }
                    val uri = contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
                    if (uri != null) {
                        contentResolver.openOutputStream(uri)?.use { it.write(bytes) }
                        Log.i(TAG, "Imagen guardada correctamente en galería (Android < Q)")
                        Toast.makeText(this, getString(R.string.saved_to_gallery), Toast.LENGTH_SHORT).show()
                    } else {
                        Log.w(TAG, "No se obtuvo URI de MediaStore para guardar la imagen en Android < Q")
                        Toast.makeText(this, getString(R.string.error_saving), Toast.LENGTH_SHORT).show()
                    }
                }
            } catch (ex: Exception) {
                Log.e(TAG, "Error guardando imagen: ${ex.message}", ex)
                Toast.makeText(this, getString(R.string.error_saving), Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun showFallbackError(json: String) {
        findViewById<View>(R.id.card_warning).visibility = View.VISIBLE
        findViewById<TextView>(R.id.tv_warning).text = getString(R.string.generic_result_error)
        findViewById<TextView>(R.id.tv_doc_title).text = getString(R.string.results_title)
        findViewById<TextView>(R.id.tv_doc_subtitle).text = "JSON"
        setInfoText(R.id.tv_uid, R.string.label_uid, null)
        setInfoText(R.id.tv_can, R.string.label_can, null)
        setInfoText(R.id.tv_name, R.string.label_name, null)
        setInfoText(R.id.tv_surname, R.string.label_surname, null)
        setInfoText(R.id.tv_doc_number, R.string.label_doc_number, null)
        setInfoText(R.id.tv_birth_date, R.string.label_birth_date, null)
        setInfoText(R.id.tv_nationality, R.string.label_nationality, null)
        setInfoText(R.id.tv_type, R.string.label_doc_type, null)
        setInfoText(R.id.tv_country, R.string.label_country, null)
        setInfoText(R.id.tv_architecture, R.string.label_architecture, null)
        setInfoText(R.id.tv_error_value, R.string.label_error, json.take(500))
        findViewById<LinearLayout>(R.id.ll_dg_rows).apply {
            removeAllViews()
            addView(buildRow(json.take(500)))
        }
        findViewById<LinearLayout>(R.id.ll_diag_rows).apply {
            removeAllViews()
            addView(buildRow(getString(R.string.no_dg_data)))
        }
    }

    private fun setInfoText(viewId: Int, labelRes: Int, value: String?) {
        val label = getString(labelRes)
        val displayValue = value.orDash()
        val full = "$label  $displayValue"
        val spannable = SpannableString(full)
        // Label: gris (#8E8E93), mismo tamaño
        spannable.setSpan(
            ForegroundColorSpan(getColor(R.color.text_secondary)),
            0, label.length,
            Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
        )
        // Valor: negro/oscuro
        spannable.setSpan(
            ForegroundColorSpan(getColor(R.color.text_primary)),
            label.length, full.length,
            Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
        )
        findViewById<TextView>(viewId).text = spannable
    }

    private fun buildRow(text: String): TextView {
        return TextView(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).also { it.bottomMargin = dp(4) }
            background = androidx.appcompat.content.res.AppCompatResources
                .getDrawable(this@ResultActivity, R.drawable.bg_info_row)
            setPadding(dp(12), dp(10), dp(12), dp(10))
            setTextColor(getColor(R.color.text_primary))
            textSize = 12.5f
            this.text = text
        }
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    private fun sha256(bytes: ByteArray): String {
        val md = MessageDigest.getInstance("SHA-256")
        val hashBytes = md.digest(bytes)
        return hashBytes.joinToString("") { "%02x".format(it) }
    }

    private fun toByteArrayFromJsonValue(value: Any?): ByteArray? {
        return when (value) {
            is ByteArray -> value
            is List<*> -> {
                val out = ByteArray(value.size)
                for ((i, v) in value.withIndex()) {
                    val n = (v as? Number)?.toInt() ?: return null
                    out[i] = n.toByte()
                }
                out
            }
            else -> null
        }
    }

    private fun parseStatusByDg(dgAnalysisMap: Map<*, *>?): Map<Int, String> {
        if (dgAnalysisMap == null) return emptyMap()
        val result = mutableMapOf<Int, String>()
        for ((k, v) in dgAnalysisMap) {
            val dg = (k as? String)?.toIntOrNull() ?: (k as? Number)?.toInt() ?: continue
            val status = when (v) {
                is Map<*, *> -> v["status"]?.toString()
                else -> null
            }
            if (!status.isNullOrBlank()) {
                result[dg] = status
            }
        }
        return result
    }

    private fun collectDgIndexes(dgMap: Map<*, *>?, dgAnalysisMap: Map<*, *>?): List<Int> {
        val indexes = mutableSetOf<Int>()
        dgMap?.keys?.forEach { key -> keyToDgIndex(key)?.let { indexes.add(it) } }
        dgAnalysisMap?.keys?.forEach { key -> keyToDgIndex(key)?.let { indexes.add(it) } }
        return indexes.sorted()
    }

    private fun keyToDgIndex(key: Any?): Int? {
        return when (key) {
            is String -> key.toIntOrNull()
            is Number -> key.toInt()
            else -> null
        }
    }

    private fun parseDgAnalysisEntry(dgAnalysisMap: Map<*, *>?, dg: Int): DgAnalysisEntry? {
        if (dgAnalysisMap == null) return null
        val value = dgAnalysisMap[dg] ?: dgAnalysisMap[dg.toString()] ?: return null
        val map = value as? Map<*, *> ?: return null

        return DgAnalysisEntry(
            status = map["status"]?.toString(),
            sizeBytes = (map["sizeBytes"] as? Number)?.toInt(),
            sha256 = map["sha256"]?.toString(),
            exceptionType = map["exceptionType"]?.toString(),
            exceptionMessage = map["exceptionMessage"]?.toString()
        )
    }

    private fun decodeFieldsByDg(dg: Int, bytes: ByteArray): List<String> {
        val rows = mutableListOf<String>()
        when (dg) {
            1 -> runCatching { DG1_Dnie(bytes) }.onSuccess { dg1 ->
                addDgField(rows, 1, "Tipo documento", dg1.getDocType())
                addDgField(rows, 1, "Emisor", dg1.getIssuer())
                addDgField(rows, 1, "Numero documento", dg1.getDocNumber())
                addDgField(rows, 1, "Nombre", dg1.getName())
                addDgField(rows, 1, "Apellidos", dg1.getSurname())
                addDgField(rows, 1, "Sexo", dg1.getSex())
                addDgField(rows, 1, "Nacionalidad", dg1.getNationality())
                addDgField(rows, 1, "Nacimiento", dg1.getDateOfBirth())
                addDgField(rows, 1, "Caducidad", dg1.getDateOfExpiry())
                addDgField(rows, 1, "Dato opcional", dg1.getOptData())
            }

            11 -> runCatching { DG11(bytes) }.onSuccess { dg11 ->
                addDgField(rows, 11, "Nombre", dg11.getName())
                addDgField(rows, 11, "Nombre ICAO", dg11.getIcaoName())
                addDgField(rows, 11, "Titulo", dg11.getTitle())
                addDgField(rows, 11, "Numero personal", dg11.getPersonalNumber())
                addDgField(rows, 11, "Nacimiento", dg11.getBirthDate())
                addDgField(rows, 11, "Lugar nacimiento", dg11.getBirthPlace())
                addDgField(rows, 11, "Direccion", dg11.getAddress(DG11.ADDR_DIRECCION))
                addDgField(rows, 11, "Localidad", dg11.getAddress(DG11.ADDR_LOCALIDAD))
                addDgField(rows, 11, "Provincia", dg11.getAddress(DG11.ADDR_PROVINCIA))
                addDgField(rows, 11, "Telefono", dg11.getPhone())
                addDgField(rows, 11, "Profesion", dg11.getProfession())
                addDgField(rows, 11, "Custodia", dg11.getCustodyInfo())
                addDgField(rows, 11, "Otros", dg11.getOtherInfo())
            }

            13 -> runCatching { DG13(bytes) }.onSuccess { dg13 ->
                addDgField(rows, 13, "Numero personal", dg13.getPersonalNumber())
                addDgField(rows, 13, "Nombre", dg13.getName())
                addDgField(rows, 13, "Apellido 1", dg13.getSurName1())
                addDgField(rows, 13, "Apellido 2", dg13.getSurName2())
                addDgField(rows, 13, "Sexo", dg13.getSex())
                addDgField(rows, 13, "Nacimiento", dg13.getBirthDate())
                addDgField(rows, 13, "Poblacion nacimiento", dg13.getBirthPopulation())
                addDgField(rows, 13, "Provincia nacimiento", dg13.getBirthProvince())
                addDgField(rows, 13, "Direccion actual", dg13.getActualAddress())
                addDgField(rows, 13, "Poblacion actual", dg13.getActualPopulation())
                addDgField(rows, 13, "Provincia actual", dg13.getActualProvince())
                addDgField(rows, 13, "Caducidad", dg13.getExpirationDate())
                addDgField(rows, 13, "Nombre padre", dg13.getFatherName())
                addDgField(rows, 13, "Nombre madre", dg13.getMotherName())
            }

            2 -> runCatching { DG2(bytes).getImageBytes() }.onSuccess { img ->
                rows.add("DG2 · Imagen biometrica: ${img.size} B")
            }

            7 -> runCatching { DG7(bytes).getImageBytes() }.onSuccess { img ->
                rows.add("DG7 · Firma manuscrita: ${img.size} B")
            }
        }
        return rows
    }

    private fun toHexPreview(bytes: ByteArray, maxBytes: Int = 24): String {
        val count = minOf(bytes.size, maxBytes)
        val preview = bytes.take(count).joinToString(" ") { "%02X".format(it) }
        return if (bytes.size > maxBytes) "$preview ..." else preview
    }

    private fun toAsciiPreview(bytes: ByteArray, maxChars: Int = 140): String? {
        if (bytes.isEmpty()) return null
        val raw = buildString {
            val count = minOf(bytes.size, maxChars)
            for (i in 0 until count) {
                val c = bytes[i].toInt() and 0xFF
                append(if (c in 32..126) c.toChar() else '.')
            }
        }
        val compact = raw.replace(Regex("\\.+"), ".").trim('.')
        return compact.nullIfBlank()?.takeIf { it.any { ch -> ch.isLetterOrDigit() } }
    }

    private data class DgAnalysisEntry(
        val status: String?,
        val sizeBytes: Int?,
        val sha256: String?,
        val exceptionType: String?,
        val exceptionMessage: String?
    )

    private fun addDgField(rows: MutableList<String>, dg: Int, label: String, value: String?) {
        val normalized = value.nullIfBlank() ?: return
        rows.add("DG$dg · $label: $normalized")
    }

    private fun readDgBytes(dgMap: Map<*, *>, dg: Int): ByteArray? {
        val value = dgMap[dg] ?: dgMap[dg.toString()]
        return toByteArrayFromJsonValue(value)
    }

    private fun extractPhotoBytes(raw: Map<*, *>): ByteArray? {
        val dgMap = raw["dgMap"] as? Map<*, *> ?: return null
        val dg2Raw = readDgBytes(dgMap, 2) ?: return null
        Log.d(TAG, "DG2 extract - bytes crudos=${dg2Raw.size}, head=${hexHead(dg2Raw)}")
        return runCatching {
            val image = DG2(dg2Raw).getImageBytes()
            Log.d(TAG, "DG2 extract - getImageBytes OK. bytes imagen=${image.size}, head=${hexHead(image)}")
            image
        }.getOrElse {
            Log.w(TAG, "DG2 extract - fallo getImageBytes (${it.javaClass.simpleName}: ${it.message}). Se usa fallback crudo.")
            dg2Raw
        }
    }

    private fun extractSignatureBytes(raw: Map<*, *>): ByteArray? {
        val dgMap = raw["dgMap"] as? Map<*, *> ?: return null
        val dg7Raw = readDgBytes(dgMap, 7) ?: return null
        Log.d(TAG, "DG7 extract - bytes crudos=${dg7Raw.size}, head=${hexHead(dg7Raw)}")
        return runCatching {
            val image = DG7(dg7Raw).getImageBytes()
            Log.d(TAG, "DG7 extract - getImageBytes OK. bytes imagen=${image.size}, head=${hexHead(image)}")
            image
        }.getOrElse {
            Log.w(TAG, "DG7 extract - fallo getImageBytes (${it.javaClass.simpleName}: ${it.message}). Se usa fallback crudo.")
            dg7Raw
        }
    }

    private fun logBiometricReadContext(raw: Map<*, *>) {
        val dgMap = raw["dgMap"] as? Map<*, *>
        val dgAnalysis = raw["dgAnalysis"] as? Map<*, *>
        Log.i(TAG, "Contexto biometrico - dgMapKeys=${dgMap?.keys ?: emptyList<Any>()}, dgAnalysisKeys=${dgAnalysis?.keys ?: emptyList<Any>()}")

        listOf(2, 7).forEach { dg ->
            val bytes = dgMap?.let { readDgBytes(it, dg) }
            val analysis = parseDgAnalysisEntry(dgAnalysis, dg)
            Log.i(
                TAG,
                "DG$dg contexto - rawBytes=${bytes?.size ?: 0}, status=${analysis?.status ?: "-"}, " +
                    "sizeBytes=${analysis?.sizeBytes ?: -1}, exceptionType=${analysis?.exceptionType ?: "-"}, " +
                    "exceptionMessage=${analysis?.exceptionMessage ?: "-"}"
            )
        }
    }

    private fun hexHead(bytes: ByteArray, size: Int = 12): String {
        if (bytes.isEmpty()) return "<empty>"
        val count = minOf(bytes.size, size)
        val head = bytes.take(count).joinToString(" ") { "%02X".format(it) }
        return if (bytes.size > size) "$head ..." else head
    }

    private fun String?.orDash(): String = this.nullIfBlank() ?: "-"

    private fun String?.nullIfBlank(): String? = this?.takeIf { it.isNotBlank() }
}
