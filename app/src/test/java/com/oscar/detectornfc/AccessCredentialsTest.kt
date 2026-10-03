package com.oscar.detectornfc

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AccessCredentialsTest {

    @Test
    fun `solo CAN - credenciales validas sin MRZ`() {
        val creds = AccessCredentials.fromValues(
            can = "123456",
            documentNumber = null,
            dateOfBirth = null,
            dateOfExpiry = null
        )
        assertTrue(creds.hasCan())
        assertFalse(creds.hasMrz())
        assertTrue(creds.hasAnyAccessMethod())
        assertEquals("CAN", creds.describe())
    }

    @Test
    fun `solo MRZ - credenciales validas con documento normalizado`() {
        val creds = AccessCredentials.fromValues(
            can = null,
            documentNumber = "  ab1234567 ",
            dateOfBirth = "850412",
            dateOfExpiry = "300601"
        )
        assertFalse(creds.hasCan())
        assertTrue(creds.hasMrz())
        assertTrue(creds.hasAnyAccessMethod())
        assertEquals("AB1234567", creds.mrz?.documentNumber)
        assertEquals("MRZ", creds.describe())
    }

    @Test
    fun `MRZ incompleto - se ignora y no hay metodo de acceso`() {
        val creds = AccessCredentials.fromValues(
            can = null,
            documentNumber = "X1234567",
            dateOfBirth = "850412",
            dateOfExpiry = null
        )
        assertNull(creds.mrz)
        assertFalse(creds.hasAnyAccessMethod())
    }

    @Test
    fun `MRZ con fecha invalida - no es un metodo de acceso utilizable`() {
        val creds = AccessCredentials.fromValues(
            can = null,
            documentNumber = "X1234567",
            dateOfBirth = "999999",
            dateOfExpiry = "300601"
        )
        assertFalse(creds.hasMrz())
        assertFalse(creds.hasAnyAccessMethod())
    }

    @Test
    fun `CAN y MRZ simultaneos - se describen los dos metodos`() {
        val creds = AccessCredentials.fromValues(
            can = "123456",
            documentNumber = "X1234567",
            dateOfBirth = "850412",
            dateOfExpiry = "300601"
        )
        assertTrue(creds.hasCan())
        assertTrue(creds.hasMrz())
        assertEquals("CAN + MRZ", creds.describe())
    }

    @Test
    fun `sin credenciales - ningun metodo disponible`() {
        val creds = AccessCredentials.fromValues(null, null, null, null)
        assertFalse(creds.hasAnyAccessMethod())
        assertEquals("sin credenciales", creds.describe())
    }

    @Test
    fun `CAN con espacios - se normaliza`() {
        val creds = AccessCredentials.fromValues(" 123456 ", null, null, null)
        assertEquals("123456", creds.can)
        assertTrue(creds.hasCan())
    }
}
