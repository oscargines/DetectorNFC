# Resumen de Diagnóstico y Solución - DG11 y DG13

## El Problema Que Encontraste

> "Tengo constancia de que hay información en DG11 y DG13, pero el código reporta que no están presentes."

**Causa raíz**: El código anterior trataba TODAS las excepciones de lectura como "DG no presente", sin diferenciar entre:
- "DG no existe en este documento"
- "Hay un error temporal de NFC"
- "El método no está implementado para este documento"
- "Acceso denegado por seguridad"

## Soluciones Implementadas

### 1. **Unwrapping de Excepciones** (`DniReader.kt`)
**Antes:**
```kotlin
try {
    getDg11() // Lanza InvocationTargetException
} catch (e: Exception) {
    // No sabemos qué hay dentro de InvocationTargetException
}
```

**Después:**
```kotlin
fun unwrapException(e: Exception): Exception {
    var cause = e
    while (cause is InvocationTargetException) {
        cause = cause.targetException as? Exception ?: break
    }
    return cause  // Retorna la causa REAL
}
```

**Importancia**: Ahora vemos si el error es `IOException`, `UnsupportedOperationException`, etc.

### 2. **Logging Detallado de Stack Trace** (`DniReader.kt`)
**Nueva función:**
```kotlin
fun logExceptionDetails(tag: String, e: Exception) {
    val cause = unwrapException(e)
    Log.d(TAG, "$tag\n  Causa: ${cause.javaClass.name}\n  Mensaje: ${cause.message}\n  StackTrace: ${cause.stackTraceToString()}")
}
```

**Importancia**: El stack trace muestra EXACTAMENTE dónde falla la lectura.

### 3. **Clasificación Inteligente de Errores** (`DataGroupInfo.kt`)
**Antes:**
```kotlin
// Si no se lee → "no presente"
return if (bytes != null) READ_OK else NOT_PRESENT
```

**Después:**
```kotlin
// Si hay excepción → analiza qué tipo
fun error(index: Int, e: Exception): DataGroupInfo {
    val status = when {
        e is IOException && msg.contains("not present") → NOT_PRESENT_OR_NOT_ALLOWED
        e is IOException → READ_ERROR
        e is UnsupportedOperationException → UNSUPPORTED_ON_DOCUMENT
        e is SecurityException → ACCESS_DENIED
        else → READ_ERROR
    }
    return DataGroupInfo(index, status, exceptionType, exceptionMessage)
}
```

**Importancia**: Ahora distingue entre diferentes tipos de fallos.

### 4. **Captura Explícita de Excepciones en `tryReadDgWithAnalysis()`** (`DniReader.kt`)
**Antes:**
```kotlin
private fun tryReadDgWithAnalysis(card: Any, index: Int): Pair<ByteArray?, DataGroupInfo> {
    val bytes = tryReadDg(card, index)
    return if (bytes != null) {
        bytes to DataGroupInfo.read(index, bytes)
    } else {
        null to DataGroupInfo.notPresent(index)  // ❌ Asume siempre "no presente"
    }
}
```

**Después:**
```kotlin
private fun tryReadDgWithAnalysis(card: Any, index: Int): Pair<ByteArray?, DataGroupInfo> {
    return try {
        val bytes = tryReadDg(card, index)
        if (bytes != null) {
            bytes to DataGroupInfo.read(index, bytes)
        } else {
            null to DataGroupInfo.notPresent(index)
        }
    } catch (e: Exception) {
        val cause = unwrapException(e)
        null to DataGroupInfo.error(index, cause as Exception)  // ✅ Analiza el error
    }
}
```

**Importancia**: Las excepciones NO capturadas se convierten en información diagnóstica.

## Qué Esperar Ahora

### Escenario A: DG11/DG13 Existen y Se Leen
```
D DniReader: DG11 leído mediante getDg11 (2048 bytes)
D DniReader: DG13 leído mediante getDg13 (4096 bytes)
I DniReader: DG Analysis: DG1=READ_OK, DG2=READ_OK, DG11=READ_OK, DG13=READ_OK
```
✅ **Excelente** - Se leen correctamente

### Escenario B: DG11/DG13 No Existen (DNI 3.0 Limited)
```
D DniReader: DG11: getDg11 falló (IOException: File not found)
D DniReader: DG11: Detalles de excepción en getDg11
D DniReader:   Causa: java.io.IOException
D DniReader:   Mensaje: File not found
D DniReader:   StackTrace: ...
I DniReader: DG Analysis: DG1=READ_OK, DG2=READ_OK, DG11=NOT_PRESENT_OR_NOT_ALLOWED
```
⚠️ **OK** - Sabes que no existen, no es un error de código

### Escenario C: Error Temporal de NFC
```
D DniReader: DG11: getDg11 falló (IOException: No response from smart card)
D DniReader: DG11: Detalles de excepción en getDg11
D DniReader:   Causa: java.io.IOException
D DniReader:   Mensaje: No response from smart card
D DniReader:   StackTrace: ...
I DniReader: DG Analysis: DG1=READ_OK, DG2=READ_OK, DG11=READ_ERROR
```
⚠️ **Intenta de nuevo** - Problema temporal de NFC

### Escenario D: Método No Implementado para Este Documento
```
D DniReader: DG11: getDg11 falló (UnsupportedOperationException: getDg11() not implemented)
D DniReader: DG11: Detalles de excepción en getDg11
D DniReader:   Causa: java.lang.UnsupportedOperationException
D DniReader:   Mensaje: getDg11() not implemented
D DniReader:   StackTrace: ...
I DniReader: DG Analysis: DG1=READ_OK, DG2=READ_OK, DG11=UNSUPPORTED_ON_DOCUMENT
```
ℹ️ **Información** - jmulticard no lo soporta para este tipo de documento

## Archivos Modificados

1. **`DniReader.kt`**
   - ✅ Agregada función `unwrapException()`
   - ✅ Agregada función `logExceptionDetails()`
   - ✅ Mejorado `tryReadDgWithAnalysis()` con try-catch
   - ✅ Mejorados logs en `tryReadDg()` para mostrar causa real

2. **`DataGroupInfo.kt`**
   - ✅ Mejorada función `error()` con clasificación inteligente
   - ✅ Lógica más completa para detectar tipo de error

3. **`build.gradle.kts`**
   - ✅ Sin cambios (ya tenía BC correcta)

## Próximo Paso: Prueba

1. Compila e instala la APK actualizada
2. Lee un DNI que sabes que tiene DG11/DG13
3. Inspecciona el logcat
4. Comparte los logs conmigo

Los logs te dirán exactamente:
- ✅ Si se leen (READ_OK)
- ❌ Si no existen (NOT_PRESENT_OR_NOT_ALLOWED)
- ⚠️ Si hay error temporal (READ_ERROR)
- ℹ️ Si no está soportado (UNSUPPORTED_ON_DOCUMENT)

## Ventajas de Esta Solución

✅ **Diagnóstico preciso**: Sabes exactamente por qué falla cada DG
✅ **Retrocompatibilidad**: Sigue funcionando con jmulticard 2.0
✅ **Datos forenses**: SHA-256 y tamaño para análisis comparativo
✅ **Escalable**: Fácil agregar nuevos patrones si cambia API
✅ **Debugging fácil**: Stack trace muestra exactamente dónde falla

## Si Aún No Funciona

El stack trace te mostrará:
- Línea exacta del problema
- Método que lanzó excepción
- Cadena de llamadas (call stack)

Con eso podemos:
1. Identificar si es bug de jmulticard
2. Buscar workaround
3. Ajustar protocolo PACE si es necesario
4. Detectar si DNI necesita protocolo especial para acceder a DG11/DG13

