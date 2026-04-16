# Diagnóstico: Lectura de DG11 y DG13

## Problema
El DNI 3.0 debería tener datos en:
- **DG11**: Special Certificates
- **DG13**: Optional Details (datos opcionales del documento)

Sin embargo, el código anterior reportaba estos DGs como `NOT_PRESENT_OR_NOT_ALLOWED` cuando en realidad deberían poder leerse si el documento los contiene.

## Cambios Realizados

### 1. **Mejora de `unwrapException()` en `DniReader.kt`**
   - **Problema anterior**: Cuando se invocaba un método via reflexión que fallaba, se capturaba `InvocationTargetException`, pero su causa subyacente (el verdadero problema) no se mostraba completamente.
   - **Solución**: Agregamos `unwrapException()` que desenvuelve la cadena de excepciones hasta la causa real.
   - **Por qué importa**: Si `getDg11()` lanza `InvocationTargetException(targetException=IOException("No response"))`, necesitamos ver el `IOException`, no solo el wrapper.

### 2. **Nueva función `logExceptionDetails()` en `DniReader.kt`**
   - Logea el stack trace completo de una excepción para diagnóstico.
   - Muestra:
     - Clase de excepción real
     - Mensaje de error
     - Primeros 500 caracteres del stack trace
   - **Importante**: El stack trace revela DÓNDE exactamente falla la lectura.

### 3. **Mejora de `tryReadDgWithAnalysis()` en `DniReader.kt`**
   - **Cambio clave**: Ahora captura TODAS las excepciones lanzadas durante la lectura, no solo retorna `null`.
   - **Antes**: Si se lanzaba excepción → `null` (DG no presente)
   - **Ahora**: Si se lanza excepción → `DataGroupInfo.error()` (analiza qué tipo de error)

### 4. **Lógica mejorada en `DataGroupInfo.error()` en `DataGroupInfo.kt`**
   - Analiza la excepción real (no el wrapper) para clasificarla como:
     - `READ_OK`: Se leyó exitosamente
     - `READ_ERROR`: Error temporal (NFC, IO, etc.)
     - `NOT_PRESENT_OR_NOT_ALLOWED`: DG no existe en el documento
     - `ACCESS_DENIED`: Acceso rechazado por motivos de seguridad
     - `UNSUPPORTED_ON_DOCUMENT`: El método no está implementado para este tipo de documento

## Qué Esperar en el Logcat

Cuando intentes leer un DNI con DG11 y DG13:

### Escenario 1: DG11 / DG13 Están Presentes y Se Leen Correctamente
```
D DniReader: DG11 leído mediante getDg11 (2048 bytes)
D DniReader: DG13 leído mediante getDg13 (4096 bytes)
I DniReader: DG Analysis: DG1=READ_OK, DG2=READ_OK, DG11=READ_OK, DG13=READ_OK, ...
```

### Escenario 2: DG11 / DG13 No Están Presentes en el Documento
```
D DniReader: DG11: getDg11 falló (IOException: No response from smart card)
D DniReader: DG11: Detalles de excepción en getDg11
D DniReader:   Causa: java.io.IOException
D DniReader:   Mensaje: No response from smart card
D DniReader:   StackTrace: ...
```
Resultado: `DG11=READ_ERROR` (o `NOT_PRESENT_OR_NOT_ALLOWED` si el mensaje dice "not present")

### Escenario 3: Acceso Denegado a DG11 / DG13
```
D DniReader: DG11: getDg11 falló (SecurityException: Access denied)
D DniReader: DG11: Detalles de excepción en getDg11
D DniReader:   Causa: java.lang.SecurityException
D DniReader:   Mensaje: Access denied
...
```
Resultado: `DG11=ACCESS_DENIED`

### Escenario 4: DG11 / DG13 No Soportados en Este Tipo de Documento
```
D DniReader: DG11: getDg11 falló (UnsupportedOperationException: getDg11() not implemented)
D DniReader: DG11: Detalles de excepción en getDg11
D DniReader:   Causa: java.lang.UnsupportedOperationException
D DniReader:   Mensaje: getDg11() not implemented
...
```
Resultado: `DG11=UNSUPPORTED_ON_DOCUMENT`

## Cómo Interpretar los Resultados

### En `ResultActivity`:
Se muestra una tabla con:
```
┌─────┬──────────────────────────────────┬──────────────────────────────────────────────────┬──────────────────┐
│ DG# │           Status                 │                      SHA-256                     │     Size (B)     │
├─────┼──────────────────────────────────┼──────────────────────────────────────────────────┼──────────────────┤
│ 11  │ READ_OK                          │ a1b2c3d4...                                      │             2048 │
│ 13  │ READ_OK                          │ e5f6g7h8...                                      │             4096 │
│ 14  │ READ_ERROR                       │                                                  │                  │
```

### En JSON guardado en `cacheDir`:
Cada DG tendrá un objeto con:
```json
{
  "index": 11,
  "status": "READ_OK",
  "sizeBytes": 2048,
  "sha256": "a1b2c3d4e5f6...",
  "exceptionType": null,
  "exceptionMessage": null
}
```

## Próximos Pasos para Diagnóstico

1. **Instala la APK actualizada** con los cambios de arriba.
2. **Lee un DNI** que sabes que tiene DG11 y DG13.
3. **Inspecciona el logcat**:
   ```bash
   adb logcat | grep "DG11\|DG13\|DG Analysis"
   ```
4. **Comparte conmigo**:
   - Los logs de DG11 y DG13 específicamente
   - El stack trace (si aparece)
   - El estado reportado en la tabla

## Nota Técnica: Por Qué Esto Fue Necesario

El problema original era que el código hacía:
```kotlin
try {
    val bytes = tryReadDg(card, 11)
    return if (bytes != null) READ_OK else NOT_PRESENT
} catch (e: Exception) {
    return NOT_PRESENT  // ❌ Incorrecto: asume siempre que no existe
}
```

Ahora hace:
```kotlin
try {
    val bytes = tryReadDg(card, 11)
    return if (bytes != null) READ_OK else NOT_PRESENT
} catch (e: Exception) {
    return DataGroupInfo.error(11, e)  // ✅ Analiza qué tipo de error
}
```

Esto distingue entre:
- **"No existe"** (IOException: "not present")
- **"Error temporal"** (IOException: "No response")
- **"No soportado"** (UnsupportedOperationException)
- **"Acceso denegado"** (SecurityException)

## Debugging Adicional (Si Aún No Funciona)

Si DG11/DG13 todavía no se leen, el stack trace te mostrará:
- **En qué línea** del código de jmulticard se lanza la excepción
- **Qué clase/método** lanzó el error
- **Cadena de llamadas** que llevó al error

Esto permitirá identificar si:
1. El DNI no tiene estos DGs (mensaje específico)
2. Hay un problema de comunicación NFC (IOException, timeout)
3. La API de jmulticard tiene un bug específico
4. Se requiere protocolo adicional (ej: abrir archivo específico del DNI antes de leer)

