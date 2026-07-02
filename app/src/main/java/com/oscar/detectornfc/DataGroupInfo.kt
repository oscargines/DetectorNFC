package com.oscar.detectornfc

/**
 * Información analítica sobre un DataGroup específico.
 * Útil para comparar qué DGs están presentes/disponibles en diferentes documentos y países.
 */
data class DataGroupInfo(
    val index: Int,
    val status: DGStatus,
    val sizeBytes: Int? = null,
    val sha256: String? = null,
    val exceptionType: String? = null,
    val exceptionMessage: String? = null,
    val tlvNodes: Int? = null,
    val hasValidASN1: Boolean? = null
) {
    companion object {
        fun read(index: Int, bytes: ByteArray): DataGroupInfo {
            val hash = sha256(bytes)
            return DataGroupInfo(
                index = index,
                status = DGStatus.READ_OK,
                sizeBytes = bytes.size,
                sha256 = hash
            )
        }

        fun notPresent(index: Int): DataGroupInfo {
            return DataGroupInfo(
                index = index,
                status = DGStatus.NOT_PRESENT_OR_NOT_ALLOWED
            )
        }

        fun error(index: Int, e: Exception): DataGroupInfo {
            val msg = e.message ?: ""
            val status = when {
                // Error 6988: "Objetos de datos incorrectos para el mensaje seguro"
                // Esto significa que el archivo NO existe o NO está permitido en ESTE DNI
                msg.contains("6988") || 
                msg.contains("Objetos de datos incorrectos") ||
                msg.contains("incorrect data") ->
                    DGStatus.NOT_PRESENT_OR_NOT_ALLOWED
                
                // Si el mensaje contiene "null" sin más contexto, es un error
                e is NullPointerException -> DGStatus.READ_ERROR
                
                // Excepciones explícitas de "no presente"
                msg.contains("no presente", ignoreCase = true) ||
                msg.contains("not present", ignoreCase = true) ||
                msg.contains("not supported", ignoreCase = true) ||
                msg.contains("no soportado", ignoreCase = true) ||
                msg.contains("no tiene", ignoreCase = true) ->
                    DGStatus.NOT_PRESENT_OR_NOT_ALLOWED
                
                // Excepciones de permiso/acceso
                msg.contains("permiso", ignoreCase = true) ||
                msg.contains("permission", ignoreCase = true) ||
                msg.contains("access denied", ignoreCase = true) ||
                e is SecurityException ->
                    DGStatus.ACCESS_DENIED
                
                // UnsupportedOperationException = método no implementado para este documento
                e is UnsupportedOperationException ->
                    DGStatus.UNSUPPORTED_ON_DOCUMENT
                
                // InvocationTargetException wrapping UnsupportedOperationException
                e is java.lang.reflect.InvocationTargetException &&
                e.targetException is UnsupportedOperationException ->
                    DGStatus.UNSUPPORTED_ON_DOCUMENT
                
                // Otros errores = READ_ERROR (problemas de comunicación NFC, etc.)
                else -> DGStatus.READ_ERROR
            }
            
            return DataGroupInfo(
                index = index,
                status = status,
                exceptionType = e.javaClass.simpleName,
                exceptionMessage = e.message
            )
        }

        fun skipped(index: Int, reason: String): DataGroupInfo {
            return DataGroupInfo(
                index = index,
                status = DGStatus.SKIPPED_AFTER_SESSION_ERROR,
                exceptionType = "SessionInterrupted",
                exceptionMessage = reason
            )
        }
    }
}

enum class DGStatus {
    READ_OK,
    NOT_PRESENT_OR_NOT_ALLOWED,
    ACCESS_DENIED,
    UNSUPPORTED_ON_DOCUMENT,
    READ_ERROR,
    SKIPPED_AFTER_SESSION_ERROR
}

private fun sha256(bytes: ByteArray): String {
    val md = java.security.MessageDigest.getInstance("SHA-256")
    val hashBytes = md.digest(bytes)
    return hashBytes.joinToString("") { "%02x".format(it) }
}

