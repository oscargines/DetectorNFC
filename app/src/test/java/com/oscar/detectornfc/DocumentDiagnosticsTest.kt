package com.oscar.detectornfc

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DocumentDiagnosticsTest {

    @Test
    fun expectedForPassport_isCompactSet() {
        val expected = DocumentDiagnostics.expectedDataGroups("PASSPORT", "ESP", "COMPACT")
        assertEquals(setOf(1, 2, 15), expected)
    }

    @Test
    fun expectedForSpanishId_usesCountryGranularSet() {
        val expected = DocumentDiagnostics.expectedDataGroups("ID_CARD", "ESP", "GRANULAR")
        assertEquals(setOf(1, 2, 7, 11, 13), expected)
    }

    @Test
    fun expectedForGermanEid_isEmptySpecial() {
        val expected = DocumentDiagnostics.expectedDataGroups("GERMAN_EID", "DEU", "SPECIAL")
        assertTrue(expected.isEmpty())
    }

    @Test
    fun compareMarksExpectedAndRead() {
        val rows = DocumentDiagnostics.compare(
            expected = setOf(1, 2, 15),
            readDgs = setOf(1, 2, 7),
            statusByDg = mapOf(1 to "READ_OK", 2 to "READ_OK", 7 to "READ_OK", 15 to "NOT_PRESENT")
        )
        assertEquals(4, rows.size)
        assertTrue(rows.first { it.dg == 15 }.expected)
        assertTrue(!rows.first { it.dg == 15 }.read)
        assertTrue(rows.first { it.dg == 7 }.read)
    }
}
