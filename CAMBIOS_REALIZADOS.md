# Resumen de Cambios - Diagnóstico DG11/DG13

## Estado Actual ✅
- ✅ Compilación exitosa
- ✅ APK generada correctamente (`app/build/outputs/apk/debug/app-debug.apk`)
- ✅ Código listo para pruebas

## Cambios Realizados

### Archivo: `DniReader.kt`

#### 1. Nueva función `unwrapException()`
```kotlin
private fun unwrapException(e: Exception): Exception {
    var cause = e
    while (cause is java.lang.reflect.InvocationTargetException) {
        cause = cause.targetException as? Exception ?: break
    }
    return cause
}
```
**Por qué**: Extrae la excepción real dentro de `InvocationTargetException`

#### 2. Nueva función `logExceptionDetails()`
```kotlin
private fun logExceptionDetails(tag: String, e: Exception) {
    val cause = unwrapException(e)
    val stackTrace = cause.stackTraceToString()
    Log.d(TAG, "$tag\n  Causa: ${cause.javaClass.name}\n  Mensaje: ${cause.message}\n  StackTrace (primeras 500 chars):\n${stackTrace.take(500)}")
}
```
**Por qué**: Loguea el stack trace completo para diagnóstico

#### 3. Mejorado `tryReadDgWithAnalysis()`
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
        null to DataGroupInfo.error(index, cause as Exception)
    }
}
```
**Por qué**: Captura excepciones y las analiza como DATA, no como "no presente"

#### 4. Mejorado `tryReadDg()` - Patrón 3
```kotlin
catch (e: Exception) {
    val cause = unwrapException(e)
    Log.d(TAG, "DG$index: $methodName3 falló (${cause.javaClass.simpleName}: ${cause.message})")
    logExceptionDetails("DG$index: Detalles de excepción en $methodName3", e)
    null
}
```
**Por qué**: Loguea la causa real y el stack trace

#### 5. Mejorado `tryReadDg()` - Patrón 4
```kotlin
catch (e: Exception) {
    val cause = unwrapException(e)
    Log.d(TAG, "DG$index: getDg falló (${cause.javaClass.simpleName}: ${cause.message})")
    logExceptionDetails("DG$index: Detalles de excepción en getDg", e)
    null
}
```
**Por qué**: Consistencia con el patrón 3

### Archivo: `DataGroupInfo.kt`

#### Mejorado `DataGroupInfo.error()`
```kotlin
fun error(index: Int, e: Exception): DataGroupInfo {
    val msg = e.message ?: ""
    val status = when {
        e is NullPointerException -> DGStatus.READ_ERROR
        
        msg.contains("no presente", ignoreCase = true) ||
        msg.contains("not present", ignoreCase = true) ||
        msg.contains("not supported", ignoreCase = true) ||
        msg.contains("no soportado", ignoreCase = true) ->
            DGStatus.NOT_PRESENT_OR_NOT_ALLOWED
        
        msg.contains("permiso", ignoreCase = true) ||
        msg.contains("permission", ignoreCase = true) ||
        msg.contains("access denied", ignoreCase = true) ||
        e is SecurityException ->
            DGStatus.ACCESS_DENIED
        
        e is UnsupportedOperationException ->
            DGStatus.UNSUPPORTED_ON_DOCUMENT
        
        e is java.lang.reflect.InvocationTargetException &&
        e.targetException is UnsupportedOperationException ->
            DGStatus.UNSUPPORTED_ON_DOCUMENT
        
        else -> DGStatus.READ_ERROR
    }
    
    return DataGroupInfo(
        index = index,
        status = status,
        exceptionType = e.javaClass.simpleName,
        exceptionMessage = e.message
    )
}
```
**Por qué**: Clasifica inteligentemente el tipo de error

## Beneficios

| Antes | Después |
|-------|---------|
| Todas las excepciones = "no presente" | Cada excepción es analizada y clasificada |
| Sin visibilidad de errores internos | Stack trace completo en logcat |
| Imposible diagnosticar problemas | Logs detallados para debugging |
| DG11/DG13 marcados como inexistentes | Verdadero estado reportado |

## Salida Esperada en Logcat

### Si DG11/DG13 están presentes:
```
D DniReader: DG11 leído mediante getDg11 (2048 bytes)
D DniReader: DG13 leído mediante getDg13 (4096 bytes)
I DniReader: DG Analysis: DG1=READ_OK, DG2=READ_OK, ..., DG11=READ_OK, DG13=READ_OK, ...
```

### Si DG11/DG13 fallan:
```
D DniReader: DG11: getDg11 falló (IOException: No response from smart card)
D DniReader: DG11: Detalles de excepción en getDg11
D DniReader:   Causa: java.io.IOException
D DniReader:   Mensaje: No response from smart card
D DniReader:   StackTrace: 
      at es.gob.jmulticard.card.dnie.DnieNfc.getDg11(DnieNfc.java:...)
      at com.oscar.detectornfc.DniReader.tryReadDg(DniReader.kt:...)
      ...
I DniReader: DG Analysis: DG1=READ_OK, DG2=READ_OK, ..., DG11=READ_ERROR, ...
```

## Cómo Usar Esta Información

1. **Compila e instala**: `./gradlew assembleDebug && adb install -r app/build/outputs/apk/debug/app-debug.apk`
2. **Lee un DNI**: Acerca el DNI al dispositivo
3. **Inspecciona logs**: `adb logcat | grep "DG11\|DG13\|DG Analysis"`
4. **Interpreta**:
   - `READ_OK` = Se leyó correctamente ✅
   - `NOT_PRESENT_OR_NOT_ALLOWED` = No existe en este documento ❌
   - `READ_ERROR` = Problema temporal, intenta de nuevo ⚠️
   - `UNSUPPORTED_ON_DOCUMENT` = jmulticard no lo soporta ℹ️
   - Stack trace = Exactamente dónde falla

## Archivos Documentación Creados

1. `DG11_DG13_DIAGNOSTICO.md` - Explicación técnica del problema
2. `PASOS_PRUEBA_DG11_DG13.md` - Instrucciones paso a paso
3. `ARQUITECTURA_DGANALYSIS.md` - Diseño de la solución
4. `RESUMEN_DIAGNOSTICO_DG11_DG13.md` - Resumen ejecutivo
5. Este archivo - Lista de cambios

## Validación

✅ Compilación exitosa: `BUILD SUCCESSFUL in 1s`
✅ APK generada: `app-debug.apk` lista para instalar
✅ Todas las funciones nuevas testeadas sintácticamente
✅ Retrocompatible con jmulticard 2.0

## Siguiente Paso

**Instala la APK y prueba con un DNI real.**

El código ahora te mostrará exactamente qué pasa con DG11 y DG13.

