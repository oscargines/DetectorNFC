package com.oscar.detectornfc.report

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ReportDataTest {

    private fun identity(
        nombre: String? = null,
        apellidos: String? = null,
        numeroDocumento: String? = null
    ) = IdentityInfo(nombre = nombre, apellidos = apellidos, numeroDocumento = numeroDocumento)

    @Test
    fun `el titulo usa nombre y apellidos en mayusculas`() {
        val report = ReportData(identity = identity("Juan", "García López", "X1234567"))
        assertEquals("JUAN GARCÍA LÓPEZ", report.titleIdentifier)
        assertEquals("JUAN GARCÍA LÓPEZ  ·  Nº X1234567", report.tableTitle)
    }

    @Test
    fun `sin nombre el titulo usa el numero de documento`() {
        val report = ReportData(identity = identity(numeroDocumento = "X1234567"))
        assertEquals("X1234567", report.titleIdentifier)
    }

    @Test
    fun `sin numero de documento el titulo usa el uid y luego el can`() {
        val conUid = ReportData(uid = "04AABBCCDD")
        assertEquals("04AABBCCDD", conUid.titleIdentifier)

        val soloCan = ReportData(can = "123456")
        assertEquals("123456", soloCan.titleIdentifier)

        val nada = ReportData()
        assertEquals("DATOS NO IDENTIFICADOS", nada.titleIdentifier)
    }

    @Test
    fun `el titulo de la tabla usa nombre y documento`() {
        val report = ReportData(identity = identity("Ana", "Pérez Ruiz", "12345678Z"))
        assertEquals("ANA PÉREZ RUIZ  ·  Nº 12345678Z", report.tableTitle)
    }

    @Test
    fun `sin datos la tabla muestra identidad no detectada`() {
        val report = ReportData(uid = "AABBCC")
        assertEquals("IDENTIDAD NO DETECTADA  ·  Nº AABBCC", report.tableTitle)
    }

    @Test
    fun `maskCAN enmascara los digitos centrales`() {
        assertEquals("1****6", maskCAN("123456"))
        assertNull(maskCAN(null))
        assertEquals("*", maskCAN("7"))
    }
}
