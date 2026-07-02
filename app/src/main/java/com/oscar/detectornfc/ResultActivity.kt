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

    private var structData: RawStructureData? = null
    private var isStructureMode: Boolean = false

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
            val struct = try {
                gson.fromJson(json, RawStructureData::class.java).also {
                    isStructureMode = true
                }
            } catch (e: Exception) {
                Log.w(TAG, "JSON no es RawStructureData, intentando formato legacy: ${e.message}")
                parseLegacyFormat(json, gson)
            }

            if (struct == null) {
                showFallbackError(json)
                btnSave.isEnabled = false
                return
            }

            structData = struct
            bindSummary(struct)
            bindWarning(struct)
            val hasPhoto = bindPhoto(struct)
            bindSignature(struct)
            bindIdentity(struct)
            bindDGTree(struct)
            bindDiagnostics(struct)

            if (isStructureMode) {
                bindChipSecurity(struct)
                bindHexDump(struct)
            } else {
                bindDataGroupsLegacy(struct)
            }

            bindSaveButton(btnSave, struct, hasPhoto)
        } catch (e: Exception) {
            Log.e(TAG, "Error procesando JSON de resultados: ${e.message}", e)
            showFallbackError(json)
            btnSave.isEnabled = false
        }
    }

    private fun parseLegacyFormat(json: String, gson: com.google.gson.Gson): RawStructureData? {
        return try {
            @Suppress("UNCHECKED_CAST")
            val map = gson.fromJson(json, Map::class.java) as Map<String, Any?>
            val raw = map["raw"] as? Map<*, *> ?: return null
            val dni = map["dni"] as? Map<*, *> ?: emptyMap<String, Any?>()
            val parser = NfcDataParser()

            val dgMapData = raw["dgMap"] as? Map<*, *> ?: emptyMap<Int, Any?>()
            val dgBytes = mutableMapOf<Int, ByteArray?>()
            for ((k, v) in dgMapData) {
                val dg = (k as? String)?.toIntOrNull() ?: (k as? Number)?.toInt() ?: continue
                dgBytes[dg] = toByteArrayFromJsonValue(v)
            }

            val dgAnalysisData = raw["dgAnalysis"] as? Map<*, *> ?: emptyMap<Int, Any?>()
            val dgAnalysis = mutableMapOf<Int, DataGroupInfo>()
            for ((k, v) in dgAnalysisData) {
                val dg = (k as? String)?.toIntOrNull() ?: (k as? Number)?.toInt() ?: continue
                val m = v as? Map<*, *> ?: continue
                dgAnalysis[dg] = DataGroupInfo(
                    index = (m["index"] as? Number)?.toInt() ?: dg,
                    status = try { DGStatus.valueOf(m["status"]?.toString() ?: "READ_ERROR") } catch (e: Exception) { DGStatus.READ_ERROR },
                    sizeBytes = (m["sizeBytes"] as? Number)?.toInt(),
                    sha256 = m["sha256"]?.toString(),
                    exceptionType = m["exceptionType"]?.toString(),
                    exceptionMessage = m["exceptionMessage"]?.toString()
                )
            }

            val docType = DocumentDetection(
                documentType = dni["documentType"]?.toString() ?: "UNKNOWN",
                countryCode = dni["countryCode"]?.toString() ?: "UNK",
                countryName = dni["countryName"]?.toString() ?: "Desconocido",
                architecture = dni["architecture"]?.toString() ?: "UNKNOWN"
            )

            isStructureMode = false
            RawStructureData(
                uid = raw["uid"]?.toString(),
                can = raw["can"]?.toString(),
                sessionStatus = try { NfcSessionStatus.valueOf(raw["sessionStatus"]?.toString() ?: "SUCCESS") } catch (e: Exception) { NfcSessionStatus.SUCCESS },
                sessionError = raw["sessionError"]?.toString(),
                readerMethod = raw["readerMethod"]?.toString() ?: "LEGACY",
                fallbackUsed = raw["fallbackUsed"] as? Boolean ?: false,
                documentDetection = docType,
                dgRawBytes = dgBytes,
                dgAnalysis = dgAnalysis
            )
        } catch (e: Exception) {
            Log.w(TAG, "Error parseando legacy JSON: ${e.message}")
            null
        }
    }

    private fun bindSummary(struct: RawStructureData) {
        val det = struct.documentDetection
        val docType = det?.documentType.orDash()
        val countryCode = det?.countryCode.orDash()
        val countryName = det?.countryName.orEmpty()
        val architecture = det?.architecture.orDash()

        findViewById<TextView>(R.id.tv_doc_title).text = listOf(
            "Documento", docType
        ).joinToString(" ").ifBlank { getString(R.string.results_title) }

        findViewById<TextView>(R.id.tv_doc_subtitle).text =
            "$docType · $countryCode $countryName".trim()

        setInfoText(R.id.tv_uid, R.string.label_uid, struct.uid)
        setInfoText(R.id.tv_can, R.string.label_can, maskCAN(struct.can))
    }

    private fun bindIdentity(struct: RawStructureData) {
        val det = struct.documentDetection

        val dg1Bytes = struct.dgRawBytes?.get(1)
        val dg11Bytes = struct.dgRawBytes?.get(11)
        val dg13Bytes = struct.dgRawBytes?.get(13)

        val dg1 = dg1Bytes?.let { runCatching { DG1_Dnie(it) }.getOrNull() }
        val dg11 = dg11Bytes?.let { runCatching { DG11(it) }.getOrNull() }
        val dg13 = dg13Bytes?.let { runCatching { DG13(it) }.getOrNull() }

        Log.d(TAG, "bindIdentity: dg1=${dg1 != null}, dg11=${dg11 != null}, dg13=${dg13 != null}")

        val nombre = dg13?.getName()?.takeIf { it.isNotBlank() }
            ?: dg11?.getName()?.takeIf { it.isNotBlank() }
            ?: dg1?.getName()?.takeIf { it.isNotBlank() }

        val apellidos = if (dg13 != null) {
            val s1 = dg13.getSurName1()?.takeIf { it.isNotBlank() }
            val s2 = dg13.getSurName2()?.takeIf { it.isNotBlank() }
            when {
                s1 != null && s2 != null -> "$s1 $s2"
                s1 != null -> s1
                else -> dg1?.getSurname()?.takeIf { it.isNotBlank() }
            }
        } else {
            dg1?.getSurname()?.takeIf { it.isNotBlank() }
        }

        val numeroDocumento = dg13?.getPersonalNumber()?.takeIf { it.isNotBlank() }
            ?: dg1?.getDocNumber()?.takeIf { it.isNotBlank() }

        val fechaNacimiento = (dg13?.getBirthDate()
            ?: dg11?.getBirthDate()
            ?: dg1?.getDateOfBirth())?.takeIf { it.isNotBlank() }

        val nacionalidad = dg1?.getNationality()?.takeIf { it.isNotBlank() } ?: "ESP"
        val tipoDocumento = dg1?.getDocType()?.takeIf { it.isNotBlank() } ?: det?.documentType

        val genero = (dg13?.getSex() ?: dg1?.getSex())?.uppercase()?.let {
            when (it) {
                "F" -> "Femenino"
                "M" -> "Masculino"
                else -> null
            }
        }

        val lugarNacimiento = if (dg13 != null) {
            listOfNotNull(dg13.getBirthPopulation(), dg13.getBirthProvince())
                .filter { it.isNotBlank() }
                .joinToString(", ")
                .takeIf { it.isNotBlank() }
                ?: dg11?.getBirthPlace()?.takeIf { it.isNotBlank() }
        } else {
            dg11?.getBirthPlace()?.takeIf { it.isNotBlank() }
        }

        val domicilio = if (dg13 != null) {
            listOfNotNull(
                dg13.getActualAddress(),
                dg13.getActualPopulation(),
                dg13.getActualProvince()
            ).filter { it.isNotBlank() }.joinToString(", ").takeIf { it.isNotBlank() }
        } else if (dg11 != null) {
            listOfNotNull(
                dg11.getAddress(DG11.ADDR_DIRECCION),
                dg11.getAddress(DG11.ADDR_LOCALIDAD),
                dg11.getAddress(DG11.ADDR_PROVINCIA)
            ).filter { it.isNotBlank() }.joinToString(", ").takeIf { it.isNotBlank() }
        } else {
            null
        }

        setInfoText(R.id.tv_name, R.string.label_name, nombre)
        setInfoText(R.id.tv_surname, R.string.label_surname, apellidos)
        setInfoText(R.id.tv_doc_number, R.string.label_doc_number, numeroDocumento)
        setInfoText(R.id.tv_birth_date, R.string.label_birth_date, fechaNacimiento)
        setInfoText(R.id.tv_nationality, R.string.label_nationality, nacionalidad)
        setInfoText(R.id.tv_gender, R.string.label_gender, genero)
        setInfoText(R.id.tv_birth_place, R.string.label_birth_place, lugarNacimiento)
        setInfoText(R.id.tv_address, R.string.label_address, domicilio)
        setInfoText(R.id.tv_father_name, R.string.label_father_name, dg13?.getFatherName()?.takeIf { it.isNotBlank() })
        setInfoText(R.id.tv_mother_name, R.string.label_mother_name, dg13?.getMotherName()?.takeIf { it.isNotBlank() })
        setInfoText(R.id.tv_support_number, R.string.label_support_number, dg1?.getDocNumber()?.takeIf { it.isNotBlank() })
        setInfoText(R.id.tv_type, R.string.label_doc_type, tipoDocumento)
        setInfoText(R.id.tv_country, R.string.label_country, listOf(det?.countryCode, det?.countryName).filterNotNull().joinToString(" ").ifBlank { null })
        setInfoText(R.id.tv_architecture, R.string.label_architecture, det?.architecture)

        val protocols = det?.supportedProtocols
        if (!protocols.isNullOrEmpty()) {
            setInfoText(R.id.tv_error_value, R.string.label_error, "Protocolos: ${protocols.joinToString(", ")}")
        } else {
            setInfoText(R.id.tv_error_value, R.string.label_error, null)
        }
    }

    private fun bindWarning(struct: RawStructureData) {
        val cardWarning = findViewById<View>(R.id.card_warning)
        val tvWarning = findViewById<TextView>(R.id.tv_warning)

        val warningText = when {
            struct.fallbackUsed ->
                "La lectura se completó con un método alternativo porque el método español no era compatible con este documento."
            struct.documentDetection?.documentType == DocumentType.GERMAN_EID.name ->
                getString(R.string.german_eid_warning)
            !struct.sessionError.isNullOrBlank() -> struct.sessionError
            else -> null
        }

        if (warningText.isNullOrBlank()) {
            cardWarning.visibility = View.GONE
        } else {
            cardWarning.visibility = View.VISIBLE
            tvWarning.text = warningText
        }
    }

    private fun bindPhoto(struct: RawStructureData): Boolean {
        val ivPhoto = findViewById<ImageView>(R.id.iv_photo)
        val tvPhotoPlaceholder = findViewById<TextView>(R.id.tv_photo_placeholder)
        return try {
            val dg2Bytes = extractPhotoBytes(struct)
            if (dg2Bytes != null) {
                val result = ImageDecoder.decode(dg2Bytes)
                Log.d(TAG, "DG2 render - bytes=${dg2Bytes.size}, formato=${result.format}, decoded=${result.success}")

                if (result.bitmap != null) {
                    ivPhoto.setImageBitmap(result.bitmap)
                    ivPhoto.visibility = View.VISIBLE
                    tvPhotoPlaceholder.visibility = View.GONE
                    true
                } else {
                    if (result.format == ImageFormat.JP2) {
                        tvPhotoPlaceholder.text = getString(R.string.photo_jp2_decode_failed)
                    } else if (!result.format.isAndroidRenderable) {
                        tvPhotoPlaceholder.text = getString(R.string.photo_format_not_renderable, result.format.name)
                    } else {
                        tvPhotoPlaceholder.text = getString(R.string.no_photo_available)
                    }
                    ivPhoto.visibility = View.GONE
                    tvPhotoPlaceholder.visibility = View.VISIBLE
                    true
                }
            } else {
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

    private fun bindSignature(struct: RawStructureData): Boolean {
        val ivSignature = findViewById<ImageView>(R.id.iv_signature)
        val tvSignaturePlaceholder = findViewById<TextView>(R.id.tv_signature_placeholder)
        return try {
            val dg7Bytes = extractSignatureBytes(struct)
            if (dg7Bytes != null) {
                val result = ImageDecoder.decode(dg7Bytes)
                if (result.bitmap != null) {
                    ivSignature.setImageBitmap(result.bitmap)
                    ivSignature.visibility = View.VISIBLE
                    tvSignaturePlaceholder.visibility = View.GONE
                    true
                } else {
                    if (!result.format.isAndroidRenderable) {
                        tvSignaturePlaceholder.text = getString(R.string.signature_format_not_renderable, result.format.name)
                    } else {
                        tvSignaturePlaceholder.text = getString(R.string.no_signature_available)
                    }
                    ivSignature.visibility = View.GONE
                    tvSignaturePlaceholder.visibility = View.VISIBLE
                    false
                }
            } else {
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

    private fun bindDGTree(struct: RawStructureData) {
        val container = findViewById<LinearLayout>(R.id.ll_dg_rows)
        container.removeAllViews()

        if (struct.dgRawBytes.isEmpty()) {
            container.addView(buildRow(getString(R.string.no_dg_data)))
            return
        }

        val allDgs = (struct.dgAnalysis.keys + struct.dgRawBytes.keys).sorted()
        if (allDgs.isEmpty()) {
            container.addView(buildRow(getString(R.string.no_dg_data)))
            return
        }

        allDgs.forEach { dg ->
            val bytes = struct.dgRawBytes[dg]
            val analysis = struct.dgAnalysis[dg]
            val tlvResult = struct.dgTLV[dg]

            val status = analysis?.status?.name ?: if (bytes != null && bytes.isNotEmpty()) "READ_OK" else "UNKNOWN"
            val size = analysis?.sizeBytes ?: bytes?.size
            val hash = analysis?.sha256 ?: bytes?.let { sha256(it) }
            val tlvNodes = tlvResult?.rootNodes?.size ?: analysis?.tlvNodes
            val asn1Valid = tlvResult?.hasValidASN1 ?: analysis?.hasValidASN1

            val summary = buildString {
                append("DG$dg · $status")
                if (size != null) append(" · $size B")
                if (hash != null) append(" · SHA ${hash.take(12)}…")
                if (tlvNodes != null && tlvNodes > 0) {
                    append(" · ${tlvNodes} tag(s) ASN.1")
                } else if (asn1Valid == false) {
                    append(" · ASN.1 inválido")
                }
            }
            container.addView(buildRow(summary))

            if (tlvResult != null && tlvResult.rootNodes.isNotEmpty()) {
                for (node in tlvResult.rootNodes) {
                    buildTLVTree(container, node, 1)
                }
            }

            val errorText = listOfNotNull(analysis?.exceptionType, analysis?.exceptionMessage)
                .joinToString(": ").nullIfBlank()
            if (errorText != null) {
                container.addView(buildRow("  Error: $errorText"))
            }

            if (isStructureMode && bytes != null && bytes.isNotEmpty()) {
                val hex = toHexPreview(bytes)
                container.addView(buildRow("  HEX: $hex"))

                val ascii = toAsciiPreview(bytes)
                if (ascii != null) {
                    container.addView(buildRow("  ASCII: $ascii"))
                }

                bindParsedDGFields(container, dg, bytes)
            }
        }
    }

    private fun bindParsedDGFields(container: LinearLayout, dgIndex: Int, bytes: ByteArray) {
        val dgObject: Any? = when (dgIndex) {
            1 -> runCatching { DG1_Dnie(bytes) }.getOrNull()
            2 -> runCatching { DG2(bytes) }.getOrNull()
            7 -> runCatching { DG7(bytes) }.getOrNull()
            11 -> runCatching { DG11(bytes) }.getOrNull()
            13 -> runCatching { DG13(bytes) }.getOrNull()
            else -> null
        }

        if (dgObject == null) return

        val className = dgObject.javaClass.simpleName
        container.addView(buildRow("  ── $className (campos parseados) ──"))

        val methods = dgObject.javaClass.methods
            .filter { it.name.startsWith("get") && it.parameterCount == 0 }
            .sortedBy { it.name }
            .filter { it.name !in listOf("getClass", "getBytes") }

        for (method in methods) {
            val label = method.name.removePrefix("get")
                .replaceFirstChar { it.lowercase() }
            try {
                val result = method.invoke(dgObject) ?: continue
                val displayValue = when (result) {
                    is ByteArray -> {
                        if (result.size > 200) {
                            "[${result.size} bytes]"
                        } else {
                            val preview = result.joinToString("") { "%02X".format(it) }.take(120)
                            if (result.size > 60) "$preview… [${result.size}B]" else preview
                        }
                    }
                    is String -> result.takeIf { it.isNotBlank() } ?: continue
                    is Int, is Long, is Boolean -> result.toString()
                    else -> result.toString().takeIf { it.isNotBlank() && it != "null" } ?: continue
                }
                container.addView(buildRow("    $label = $displayValue"))
            } catch (e: Exception) {
                val msg = e.cause?.message ?: e.message
                if (msg != null) {
                    container.addView(buildRow("    $label = [error: ${msg.take(80)}]"))
                }
            }
        }

        val getBytesMethod = methods.firstOrNull { it.name == "getBytes" }
        if (getBytesMethod != null) {
            try {
                val raw = getBytesMethod.invoke(dgObject) as? ByteArray
                if (raw != null) {
                    container.addView(buildRow("    rawBytes = [${raw.size} bytes]"))
                }
            } catch (_: Exception) {}
        }
    }

    private fun buildTLVTree(container: LinearLayout, node: TLVNode, depth: Int) {
        val indent = "  ".repeat(depth)
        val constructed = if (node.isConstructed) "[+]" else ""
        val sb = StringBuilder()

        sb.append("$indent${node.tagHex} $constructed ${node.tagName}")
        if (!node.isConstructed && node.length > 0) sb.append(" (${node.length}B)")

        if (node.valueDecoded != null && !node.isConstructed) {
            sb.append(" = \"${node.valueDecoded}\"")
        } else if (node.valueAscii != null && !node.isConstructed && node.valueHex == null) {
            val short = node.valueAscii.take(60)
            sb.append(" = \"$short\"")
            if (node.valueAscii.length > 60) sb.append("…")
        } else if (node.valueHex != null && !node.isConstructed) {
            sb.append(" = ${node.valueHex}")
        }

        container.addView(buildRow(sb.toString()))

        for (child in node.children) {
            buildTLVTree(container, child, depth + 1)
        }
    }

    private fun bindDiagnostics(struct: RawStructureData) {
        val container = findViewById<LinearLayout>(R.id.ll_diag_rows)
        container.removeAllViews()

        if (struct.dgRawBytes.isEmpty() && struct.dgAnalysis.isEmpty()) {
            container.addView(buildRow(getString(R.string.no_dg_data)))
            return
        }

        val det = struct.documentDetection
        val readDgs = struct.dgAnalysis.filter {
            it.value.status == DGStatus.READ_OK
        }.keys

        val expected = DocumentDiagnostics.expectedDataGroups(
            det?.documentType, det?.countryCode, det?.architecture
        )
        val statusByDg = struct.dgAnalysis.mapValues { it.value.status.name }
        val comparison = DocumentDiagnostics.compare(expected, readDgs, statusByDg)

        if (comparison.isEmpty()) {
            container.addView(buildRow(getString(R.string.no_dg_data)))
            return
        }

        container.addView(buildRow("Esperados: ${expected.toList().sorted().joinToString(", ")} (${expected.size})"))
        container.addView(buildRow("Leídos: ${readDgs.toList().sorted().joinToString(", ")} (${readDgs.size})"))

        comparison.forEach { item ->
            val text = "DG${item.dg} · esperado ${if (item.expected) "sí" else "no"} · leído ${if (item.read) "sí" else "no"} · ${item.status}"
            container.addView(buildRow(text))
        }
    }

    private fun bindChipSecurity(struct: RawStructureData) {
        val containerDg = findViewById<LinearLayout>(R.id.ll_dg_rows)
        val ca = struct.efCardAccess
        val cs = struct.efCardSecurity
        val sod = struct.efSod
        val com = struct.efCom

        val securityInfo = mutableListOf<String>()

        if (ca?.paceSupported == true) {
            securityInfo.add("PACE: ${ca.paceAlgorithm.joinToString(", ")}")
        }
        if (ca?.chipAuthenticationSupported == true) {
            securityInfo.add("ChipAuthentication (CA): soportado")
        }
        if (ca?.terminalAuthenticationSupported == true || cs?.terminalAuthenticationRequired == true) {
            securityInfo.add("TerminalAuthentication (TA): ${cs?.terminalAuthenticationRequired?.let { "requerido" } ?: "soportado"}")
        }
        if (sod != null) {
            securityInfo.add("SOD: ${sod.rawHash?.take(12) ?: "leído"}…")
        }
        if (com != null) {
            securityInfo.add("EF.COM: LDS ${com.ldsVersion ?: "?"}, DGs: ${com.dataGroupsPresent}")
        }
        if (cs?.chipAuthenticationPublicKeySize != null) {
            securityInfo.add("CA Public Key: ${cs.chipAuthenticationPublicKeySize} bytes")
        }

        if (securityInfo.isNotEmpty()) {
            containerDg.addView(buildRow("── Chip Security ──"))
            securityInfo.forEach { containerDg.addView(buildRow(it)) }
        }
    }

    private fun bindHexDump(struct: RawStructureData) {
        if (struct.dgRawBytes.isEmpty()) return
        val container = findViewById<LinearLayout>(R.id.ll_diag_rows)
        val allDgs = struct.dgRawBytes.keys.sorted()

        var hasData = false
        for (dg in allDgs) {
            val bytes = struct.dgRawBytes[dg] ?: continue
            if (bytes.isEmpty()) continue
            if (!hasData) {
                container.addView(buildRow("── Raw Hex Dump ──"))
                hasData = true
            }
            container.addView(buildRow("DG$dg (${bytes.size}B): ${toHexPreview(bytes, 32)}"))
        }
    }

    private fun bindDataGroupsLegacy(struct: RawStructureData) {
        val container = findViewById<LinearLayout>(R.id.ll_dg_rows)
        container.addView(buildRow("── Campos específicos por DG ──"))

        val allDgs = struct.dgRawBytes.keys.sorted()
        allDgs.forEach { dg ->
            val bytes = struct.dgRawBytes[dg] ?: return@forEach
            if (bytes.isEmpty()) return@forEach

            when (dg) {
                1 -> runCatching { DG1_Dnie(bytes) }.onSuccess { dg1 ->
                    container.addView(buildRow("DG1 · ${dg1.getDocType()} / ${dg1.getIssuer()} · ${dg1.getDocNumber()}"))
                    val name = listOfNotNull(dg1.getName(), dg1.getSurname()).joinToString(" ")
                    if (name.isNotBlank()) container.addView(buildRow("DG1 · $name"))
                }
                11 -> runCatching { DG11(bytes) }.onSuccess { dg11 ->
                    container.addView(buildRow("DG11 · ${dg11.getBirthPlace() ?: "-"} · ${dg11.getPersonalNumber() ?: "-"}"))
                }
                13 -> runCatching { DG13(bytes) }.onSuccess { dg13 ->
                    val n = listOfNotNull(dg13.getName(), dg13.getSurName1(), dg13.getSurName2()).joinToString(" ")
                    if (n.isNotBlank()) container.addView(buildRow("DG13 · $n"))
                }
            }
        }
    }

    private fun bindSaveButton(btnSave: Button, struct: RawStructureData, hasPhoto: Boolean) {
        btnSave.isEnabled = hasPhoto
        btnSave.alpha = if (hasPhoto) 1f else 0.5f
        btnSave.setOnClickListener {
            val bytes = extractPhotoBytes(struct)
            if (bytes == null) {
                Toast.makeText(this, getString(R.string.error_saving), Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            val format = ImageFormatDetector.detect(bytes)
            val displayName = "dni_photo_${System.currentTimeMillis()}.${format.extension}"
            Log.i(TAG, "Guardando imagen como $displayName (${bytes.size} B, $format)")

            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    val values = ContentValues().apply {
                        put(MediaStore.Images.Media.DISPLAY_NAME, displayName)
                        put(MediaStore.Images.Media.MIME_TYPE, format.mimeType)
                        put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/DetectorNFC")
                    }
                    val uri = contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
                    if (uri != null) {
                        contentResolver.openOutputStream(uri)?.use { it.write(bytes) }
                        Toast.makeText(this, getString(R.string.saved_to_gallery), Toast.LENGTH_SHORT).show()
                    }
                } else {
                    val values = ContentValues().apply {
                        put(MediaStore.Images.Media.DISPLAY_NAME, displayName)
                        put(MediaStore.Images.Media.MIME_TYPE, format.mimeType)
                    }
                    val uri = contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
                    if (uri != null) {
                        contentResolver.openOutputStream(uri)?.use { it.write(bytes) }
                        Toast.makeText(this, getString(R.string.saved_to_gallery), Toast.LENGTH_SHORT).show()
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

    private fun extractPhotoBytes(struct: RawStructureData): ByteArray? {
        val dg2Raw = struct.dgRawBytes[2] ?: return null
        if (dg2Raw.isEmpty()) return null
        return try {
            DG2(dg2Raw).getImageBytes()
        } catch (e: Exception) {
            Log.w(TAG, "DG2 extract fallback: ${e.message}")
            dg2Raw
        }
    }

    private fun extractSignatureBytes(struct: RawStructureData): ByteArray? {
        val dg7Raw = struct.dgRawBytes[7] ?: return null
        if (dg7Raw.isEmpty()) return null
        return try {
            DG7(dg7Raw).getImageBytes()
        } catch (e: Exception) {
            Log.w(TAG, "DG7 extract fallback: ${e.message}")
            dg7Raw
        }
    }

    private fun setInfoText(viewId: Int, labelRes: Int, value: String?) {
        val label = getString(labelRes)
        val displayValue = value.orDash()
        val full = "$label  $displayValue"
        val spannable = SpannableString(full)
        spannable.setSpan(
            ForegroundColorSpan(getColor(R.color.text_secondary)),
            0, label.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
        )
        spannable.setSpan(
            ForegroundColorSpan(getColor(R.color.text_primary)),
            label.length, full.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
        )
        findViewById<TextView>(viewId).text = spannable
    }

    private fun buildRow(text: String): TextView {
        return TextView(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
            ).also { it.bottomMargin = dp(4) }
            background = androidx.appcompat.content.res.AppCompatResources
                .getDrawable(this@ResultActivity, R.drawable.bg_info_row)
            setPadding(dp(12), dp(10), dp(12), dp(10))
            setTextColor(getColor(R.color.text_primary))
            textSize = 12.5f
            this.text = text
        }
    }

    private fun toHexPreview(bytes: ByteArray, max: Int = 24): String {
        val count = minOf(bytes.size, max)
        val preview = bytes.take(count).joinToString(" ") { "%02X".format(it) }
        return if (bytes.size > max) "$preview …" else preview
    }

    private fun toAsciiPreview(bytes: ByteArray, max: Int = 140): String? {
        val sb = StringBuilder()
        val count = minOf(bytes.size, max)
        for (i in 0 until count) {
            val c = bytes[i].toInt() and 0xFF
            sb.append(if (c in 32..126) c.toChar() else '.')
        }
        val compact = sb.toString().replace(Regex("\\.+"), ".").trim('.')
        return compact.nullIfBlank()?.takeIf { it.any { ch -> ch.isLetterOrDigit() } }
    }

    private fun sha256(bytes: ByteArray): String {
        val hashBytes = MessageDigest.getInstance("SHA-256").digest(bytes)
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

    private fun maskCAN(can: String?): String? {
        if (can == null) return null
        if (can.length <= 2) return "*".repeat(can.length)
        return "${can.take(1)}${"*".repeat(can.length - 2)}${can.takeLast(1)}"
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    private fun String?.orDash(): String = this.nullIfBlank() ?: "-"
    private fun String?.nullIfBlank(): String? = this?.takeIf { it.isNotBlank() }
}
