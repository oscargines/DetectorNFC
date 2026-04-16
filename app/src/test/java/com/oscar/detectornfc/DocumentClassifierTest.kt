package com.oscar.detectornfc

import org.junit.Assert.assertEquals
import org.junit.Test

class DocumentClassifierTest {

    @Test
    fun classifyPassportSpain_compact() {
        val result = DocumentClassifier.classify("P", "ESP")
        assertEquals(DocumentType.PASSPORT, result.documentType)
        assertEquals("ESP", result.countryCode)
        assertEquals(DocumentArchitecture.GRANULAR, CountryRegistry.getCountry("ESP")?.architecture)
        assertEquals(DocumentArchitecture.GRANULAR, CountryRegistry.getCountry(result.countryCode)?.architecture)
        // Pasaporte prevalece sobre arquitectura pais en la clasificacion por tipo.
        assertEquals(DocumentArchitecture.COMPACT, DocumentClassifier.classify("P<", "ESP").architecture)
    }

    @Test
    fun classifyIdFrance_granular() {
        val result = DocumentClassifier.classify("C", "FRA")
        assertEquals(DocumentType.ID_CARD, result.documentType)
        assertEquals("FRA", result.countryCode)
        assertEquals(DocumentArchitecture.GRANULAR, result.architecture)
    }

    @Test
    fun classifyGermanId_specialCase() {
        val result = DocumentClassifier.classify("C", "DEU")
        assertEquals(DocumentType.GERMAN_EID, result.documentType)
        assertEquals(DocumentArchitecture.SPECIAL, result.architecture)
    }

    @Test
    fun classifyUnknown_unknownArchitecture() {
        val result = DocumentClassifier.classify("X", "")
        assertEquals(DocumentType.UNKNOWN, result.documentType)
        assertEquals("UNK", result.countryCode)
        assertEquals(DocumentArchitecture.UNKNOWN, result.architecture)
    }
}
