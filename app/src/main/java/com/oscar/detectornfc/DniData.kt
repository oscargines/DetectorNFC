package com.oscar.detectornfc

data class DniData(
    val genero: String?,
    val nacionalidad: String?,
    val tipoDocumento: String?,
    val numeroDocumento: String?,
    val numeroSoporte: String?,
    val nombre: String?,
    val apellidos: String?,
    val nombrePadre: String?,
    val nombreMadre: String?,
    val fechaNacimiento: String?,
    val lugarNacimiento: String?,
    val domicilio: String?,
    val uid: String?,
    val can: String?,
    val error: String?,
    val documentType: String? = null,
    val countryCode: String? = null,
    val countryName: String? = null,
    val architecture: String? = null,
    val readerMethod: String? = null,
    val accessMethodDetail: String? = null,
    val fallbackUsed: Boolean = false
)