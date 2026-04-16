package com.oscar.detectornfc

import java.io.Serializable

/**
 * Datos brutos leídos del chip NFC de un documento de identidad.
 *
 * @param uid         UID del chip en formato "AA:BB:CC:DD"
 * @param can         Código de acceso utilizado (CAN de 6 dígitos o "MRZ")
 * @param dataGroups  Mapa DG-index → bytes crudos (null si el DG no está disponible o falló la lectura)
 * @param sod         Security Object Document (SOD), usado para verificación de integridad
 * @param dgAnalysis  Análisis detallado de cada DG (estado, tamaño, hash) para auditoría forense
 */
data class RawNfcData(
    val uid: String?,
    val can: String?,
    val dataGroups: Map<Int, ByteArray?>,
    val sod: ByteArray?,
    val dgAnalysis: Map<Int, DataGroupInfo> = emptyMap(),
    val sessionStatus: NfcSessionStatus = NfcSessionStatus.SUCCESS,
    val sessionError: String? = null,
    val readerMethod: NfcReaderMethod = NfcReaderMethod.SPANISH_DNIE,
    val accessMethodDetail: String? = null,
    val fallbackUsed: Boolean = false,
    val fallbackSuggested: Boolean = false,
    val fallbackReason: String? = null
) : Serializable

enum class NfcReaderMethod {
    SPANISH_DNIE,
    ICAO_JMRTD
}

enum class NfcSessionStatus {
    SUCCESS,
    PARTIAL,
    FAILED
}
