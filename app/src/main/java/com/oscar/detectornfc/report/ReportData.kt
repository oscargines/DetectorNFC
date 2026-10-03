package com.oscar.detectornfc.report

import android.graphics.Bitmap
import com.oscar.detectornfc.RawStructureData
import de.tsenger.androsmex.mrtd.DG1_Dnie
import de.tsenger.androsmex.mrtd.DG11
import de.tsenger.androsmex.mrtd.DG13

data class IdentityInfo(
    val nombre: String? = null,
    val apellidos: String? = null,
    val numeroDocumento: String? = null,
    val fechaNacimiento: String? = null,
    val nacionalidad: String? = null,
    val genero: String? = null,
    val lugarNacimiento: String? = null,
    val domicilio: String? = null,
    val padre: String? = null,
    val madre: String? = null,
    val numeroSoporte: String? = null,
    val tipoDocumento: String? = null,
    val pais: String? = null,
    val arquitectura: String? = null,
    val protocolos: String? = null
)

fun parseIdentity(struct: RawStructureData): IdentityInfo {
    val det = struct.documentDetection

    val dg1 = struct.dgRawBytes[1]?.let { runCatching { DG1_Dnie(it) }.getOrNull() }
    val dg11 = struct.dgRawBytes[11]?.let { runCatching { DG11(it) }.getOrNull() }
    val dg13 = struct.dgRawBytes[13]?.let { runCatching { DG13(it) }.getOrNull() }

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

    val pais = listOf(det?.countryCode, det?.countryName)
        .filterNotNull()
        .joinToString(" ")
        .ifBlank { null }

    val protocolos = det?.supportedProtocols
        ?.takeIf { it.isNotEmpty() }
        ?.joinToString(", ")
        ?.let { "Protocolos: $it" }

    return IdentityInfo(
        nombre = nombre,
        apellidos = apellidos,
        numeroDocumento = numeroDocumento,
        fechaNacimiento = fechaNacimiento,
        nacionalidad = nacionalidad,
        genero = genero,
        lugarNacimiento = lugarNacimiento,
        domicilio = domicilio,
        padre = dg13?.getFatherName()?.takeIf { it.isNotBlank() },
        madre = dg13?.getMotherName()?.takeIf { it.isNotBlank() },
        numeroSoporte = dg1?.getDocNumber()?.takeIf { it.isNotBlank() },
        tipoDocumento = tipoDocumento,
        pais = pais,
        arquitectura = det?.architecture?.takeIf { it.isNotBlank() },
        protocolos = protocolos
    )
}

data class ReportData(
    val identity: IdentityInfo = IdentityInfo(),
    val uid: String? = null,
    val can: String? = null,
    val scanTimestamp: Long = 0L,
    val readerMethod: String? = null,
    val fallbackUsed: Boolean? = null,
    val sessionStatus: String? = null,
    val sessionError: String? = null,
    val dataGroups: List<Int> = emptyList(),
    val photo: Bitmap? = null,
    val signature: Bitmap? = null,
    val jsonSha256: String? = null
) {
    val fullName: String
        get() = listOfNotNull(identity.nombre?.trim(), identity.apellidos?.trim())
            .filter { it.isNotBlank() }
            .joinToString(" ")

    val titleIdentifier: String
        get() = fullName.uppercase().takeIf { it.isNotBlank() }
            ?: identity.numeroDocumento.trimmedOrNull()
            ?: uid.trimmedOrNull()
            ?: can.trimmedOrNull()
            ?: "DATOS NO IDENTIFICADOS"

    val tableTitle: String
        get() = buildString {
            val name = fullName
            append(if (name.isNotBlank()) name.uppercase() else "IDENTIDAD NO DETECTADA")
            val doc = identity.numeroDocumento.trimmedOrNull()
                ?: uid.trimmedOrNull()
                ?: can.trimmedOrNull()
            if (doc != null) append("  ·  Nº $doc")
        }

    private fun String?.trimmedOrNull(): String? = this?.trim()?.takeIf { it.isNotBlank() }
}

fun maskCAN(can: String?): String? {
    if (can == null) return null
    if (can.length <= 2) return "*".repeat(can.length)
    return "${can.take(1)}${"*".repeat(can.length - 2)}${can.takeLast(1)}"
}
