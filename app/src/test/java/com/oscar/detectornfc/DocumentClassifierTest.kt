package com.oscar.detectornfc

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
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

    @Test
    fun classifySpanishTie_residencePermit() {
        val result = DocumentClassifier.classify("A", "ESP")
        assertEquals(DocumentType.RESIDENCE_PERMIT, result.documentType)
        assertEquals("ESP", result.countryCode)
        assertTrue(result.isResidencePermit)
    }

    @Test
    fun classifySpanishTie_apiType() {
        val result = DocumentClassifier.classify("API", "ESP")
        assertEquals(DocumentType.RESIDENCE_PERMIT, result.documentType)
        assertTrue(result.isResidencePermit)
        assertEquals("Permiso de Residencia", result.documentSubtype)
    }

    @Test
    fun classifySpanishDni_notResidencePermit() {
        val result = DocumentClassifier.classify("C", "ESP")
        assertEquals(DocumentType.ID_CARD, result.documentType)
        assertFalse(result.isResidencePermit)
        assertTrue(DocumentClassifier.isSpanishDni(result))
    }

    @Test
    fun isSpanishTie_true() {
        val result = DocumentClassifier.classify("A", "ESP")
        assertTrue(DocumentClassifier.isSpanishTie(result))
    }

    @Test
    fun isSpanishTie_falseForDni() {
        val result = DocumentClassifier.classify("C", "ESP")
        assertFalse(DocumentClassifier.isSpanishTie(result))
    }

    @Test
    fun isEuropeanIdCard_true() {
        val result = DocumentClassifier.classify("C", "FRA")
        assertTrue(DocumentClassifier.isEuropeanIdCard(result))
    }

    @Test
    fun isEuropeanIdCard_falseForGerman() {
        val result = DocumentClassifier.classify("C", "DEU")
        assertFalse(DocumentClassifier.isEuropeanIdCard(result))
    }

    @Test
    fun countryRegistry_europeanCountries() {
        assertTrue(CountryRegistry.isEuropeanCountry("ESP"))
        assertTrue(CountryRegistry.isEuropeanCountry("FRA"))
        assertTrue(CountryRegistry.isEuropeanCountry("DEU"))
        assertFalse(CountryRegistry.isEuropeanCountry("USA"))
        assertFalse(CountryRegistry.isEuropeanCountry("CHN"))
    }

    @Test
    fun countryRegistry_residencePermitCountries() {
        val countries = CountryRegistry.getResidencePermitCountries()
        assertTrue(countries.any { it.code == "ESP" })
    }
}
