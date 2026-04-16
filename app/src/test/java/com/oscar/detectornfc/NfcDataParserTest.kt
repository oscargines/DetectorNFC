package com.oscar.detectornfc

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class NfcDataParserTest {

    private val parser = NfcDataParser()

    @Test
    fun parseRawData_failedSession_propagatesErrorAndMetadata() {
        val raw = RawNfcData(
            uid = "AA:BB",
            can = "123456",
            dataGroups = emptyMap(),
            sod = null,
            sessionStatus = NfcSessionStatus.FAILED,
            sessionError = "Fallo controlado",
            readerMethod = NfcReaderMethod.SPANISH_DNIE,
            accessMethodDetail = "PACE-CAN / SDK espanol",
            fallbackUsed = false
        )

        val result = parser.parseRawData(raw)

        assertEquals("Fallo controlado", result.error)
        assertEquals("AA:BB", result.uid)
        assertEquals("123456", result.can)
        assertEquals(NfcReaderMethod.SPANISH_DNIE.name, result.readerMethod)
        assertEquals("PACE-CAN / SDK espanol", result.accessMethodDetail)
        assertFalse(result.fallbackUsed)
        assertNull(result.nombre)
        assertNull(result.apellidos)
        assertNull(result.numeroDocumento)
    }

    @Test
    fun parseRawData_partialSpanishWithoutDg1AndDg13_usesSessionError() {
        val raw = RawNfcData(
            uid = "11:22",
            can = "654321",
            dataGroups = mapOf(2 to null, 11 to null),
            sod = null,
            sessionStatus = NfcSessionStatus.PARTIAL,
            sessionError = "Error parcial de prueba",
            readerMethod = NfcReaderMethod.SPANISH_DNIE,
            accessMethodDetail = "PACE-CAN / SDK espanol",
            fallbackUsed = false
        )

        val result = parser.parseRawData(raw)

        assertEquals("Error parcial de prueba", result.error)
        assertEquals(NfcReaderMethod.SPANISH_DNIE.name, result.readerMethod)
        assertFalse(result.fallbackUsed)
        assertNull(result.nombre)
        assertNull(result.apellidos)
    }

    @Test
    fun parseRawData_partialSpanishWithoutDg1AndDg13_withoutSessionError_usesDefaultMessage() {
        val raw = RawNfcData(
            uid = "33:44",
            can = "111111",
            dataGroups = emptyMap(),
            sod = null,
            sessionStatus = NfcSessionStatus.PARTIAL,
            sessionError = null,
            readerMethod = NfcReaderMethod.SPANISH_DNIE,
            accessMethodDetail = "PACE-CAN / SDK espanol",
            fallbackUsed = false
        )

        val result = parser.parseRawData(raw)

        assertEquals("No se pudo leer DG1 ni DG13", result.error)
        assertEquals(NfcReaderMethod.SPANISH_DNIE.name, result.readerMethod)
    }

    @Test
    fun parseRawData_icaoWithoutDg1_returnsIcaoFailureAndFallbackMetadata() {
        val raw = RawNfcData(
            uid = "55:66",
            can = "222222",
            dataGroups = emptyMap(),
            sod = null,
            sessionStatus = NfcSessionStatus.SUCCESS,
            sessionError = null,
            readerMethod = NfcReaderMethod.ICAO_JMRTD,
            accessMethodDetail = "PACE-CAN / ICAO JMRTD",
            fallbackUsed = true
        )

        val result = parser.parseRawData(raw)

        assertEquals("No se pudo leer DG1 con el método ICAO.", result.error)
        assertEquals(NfcReaderMethod.ICAO_JMRTD.name, result.readerMethod)
        assertEquals("PACE-CAN / ICAO JMRTD", result.accessMethodDetail)
        assertTrue(result.fallbackUsed)
    }

    @Test
    fun formatDate_yyMMddAndyyyyMMdd_areConverted() {
        assertEquals("01 de enero de 1999", parser.formatDate("990101"))
        assertEquals("01 de enero de 2030", parser.formatDate("300101"))
        assertEquals("31 de diciembre de 2024", parser.formatDate("20241231"))
    }

    @Test
    fun formatDate_invalidValues_returnNull() {
        assertNull(parser.formatDate(null))
        assertNull(parser.formatDate(""))
        assertNull(parser.formatDate("ABCDEF"))
        assertNull(parser.formatDate("20241340"))
    }
}

