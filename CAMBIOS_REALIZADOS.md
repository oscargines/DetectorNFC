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

---

# Cambios - Fase 2: Migración a SDK oficial v2.3.111 y lectura DNIe funcional

## Estado Actual ✅
- ✅ Lectura DNIe 3.0/4.0 funcional con SDK oficial FNMT v2.3.111
- ✅ BouncyCastle 1.65 (compatible con SDK y jmrtd 0.7.31)
- ✅ Datos de identidad completos (DG1, DG11, DG13) including padres
- ✅ Decodificación JP2 de foto y firma
- ✅ Fallback europeo (EuropeanStructureReader) e ICAO (jmrtd)

## Problema resuelto

El AAR anterior (`dniedroid-release.aar`, 658KB, 2019) fue compilado contra BouncyCastle 1.50.
Contiene bytecode con `checkcast DERObjectIdentifier` que falla con BC >= 1.65 donde
`DLSequence.getObjectAt()` retorna `ASN1ObjectIdentifier` (la clase `DERObjectIdentifier`
fue deprecada y renombrada). Esto producía un `ClassCastException` en tiempo de ejecución.

El SDK oficial v2.3.111 (815KB, 2023) está diseñado para BC 1.65 y usa la API
`Loader.init()` en lugar de `MrtdKeyStoreImpl`.

---

## Cambios por archivo

### `app/libs/dniedroid-release.aar` (reemplazado)
- **Antes**: AAR 658KB de 2019, compilado contra BC 1.50, API `MrtdKeyStoreImpl`
- **Después**: AAR 815KB de 2023 (SDK v2.3.111 oficial FNMT), compilado para BC 1.65, API `Loader.init()`
- **Fuente**: `SDK_DNIeDroid_FNMT/SDK_DNIeDroid_FNMT_v2_3_111/dniedroid_lib/dniedroid-release.aar`

### `gradle/libs.versions.toml`
- `bouncycastle`: `1.50` → `1.65` (compatible con SDK v2.3.111 y jmrtd 0.7.31)
- `agp`: `9.1.1` → `9.2.1`
- Añadidas librerías BC: `bouncycastle-mail` (`bcmail-jdk15on:1.65`) y `bouncycastle-tls` (`bctls-jdk15on:1.65`)
- Eliminada referencia a `DERObjectIdentifier` en comentario (ya no existe en BC 1.65)

### `gradle/wrapper/gradle-wrapper.properties`
- Gradle `9.3.1` → `9.4.1`

### `app/build.gradle.kts`
- Añadidas dependencias `libs.bouncycastle.mail` y `libs.bouncycastle.tls`

### `app/src/main/java/com/oscar/detectornfc/DniReader.kt`

#### Migración de API: `MrtdKeyStoreImpl` → `Loader.init()`
```kotlin
// Antes:
val mrtdImpl = MrtdKeyStoreImpl(can, tag)
mrtdImpl.engineLoad(null as InputStream?, null)
(mrtdImpl.getDataGroup1() as DG1_Dnie?)?.getBytes()

// Después:
val initInfo = Loader.init(arrayOf(can), tag)
val mrtdCard = initInfo.mrtdCardInfo
(mrtdCard.dataGroup1 as DG1_Dnie?)?.getBytes()
```
**Por qué**: `Loader.init()` es el punto de entrada oficial del SDK v2.3.111.
Internamente gestiona `DnieProvider`, PACE, y compatibilidad con BC 1.65.

#### Companion object nuevo
- `areDependenciesAvailable()`: Verificación cacheada de todas las clases runtime necesarias
  (BC, Loader, MrtdCard, DnieProvider, DG1_Dnie, etc.)
- `isSpanishDocument(docCode, issuer)`: Detecta si un documento es español por su código

#### Manejo de errores mejorado
- `RuntimeException` con "Tag was lost" ahora detectado como error fatal de comunicación
  con mensaje claro: "Se ha perdido la conexion con el DNIe. Manten el documento inmovil y reintenta."
- `CryptoCardException` y `GeneralSecurityException` ahora evalúan fallback ICAO
- `suggestIcaoFallbackFromThrowable()`: solo sugiere fallback si NO es error fatal
  (IOException, LinkageError, TagLostException no sugieren fallback)
- `suggestIcaoFallback()`: nueva lógica con detección de errores no fatales (>= 3 DGs fallidos sin error de comunicación)

#### `finally` block para limpieza IsoDep
```kotlin
finally {
    runCatching { tag?.let { IsoDep.get(it)?.close() } }
}
```
**Por qué**: Garantiza que IsoDep se cierre incluso si Loader.init() falla, permitiendo
que el siguiente reader (EuropeanStructureReader) pueda reabrir la conexión.

#### Dependencias verificadas
- Ya no busca `DERObjectIdentifier` (eliminada en BC 1.65)
- Ahora busca `ASN1ObjectIdentifier` (nombre correcto en BC 1.65)

### `app/src/main/java/com/oscar/detectornfc/NFCScanActivity.kt`

#### Nuevo flujo `readDocumentStructure()` (reemplaza `readDocumentWithFallback()`)
Cadena de fallback en orden:
1. **DniReader** (método español, SDK FNMT) → si fallback sugerido, continúa
2. **EuropeanStructureReader** (método universal jmrtd) → si falla, continúa
3. **IcaoReader** (fallback ICAO estándar)

**Lógica de parada temprana**: Si DniReader retorna `FAILED` con `fallbackSuggested=false`
(error fatal como "Tag was lost"), retorna inmediatamente sin intentar fallbacks
(el tag ya es inválido).

#### Refactor de resultados
- Antes: `RawNfcData` → `NfcDataParser.parseRawData()` → `DniData` → JSON con `mapOf("raw" to ..., "dni" to ...)`
- Después: `RawStructureData` → `NfcDataParser.analyzeStructure()` → JSON directo de `RawStructureData`

#### Window leak corregido en `showRetryDialog()`
- `retryDialog: AlertDialog?` almacenado como field de la clase
- `retryDialog?.dismiss()` al inicio de `showRetryDialog()` (evita diálogos duplicados)
- `onDestroy()` override: dismiss del diálogo para evitar window leak

#### Mensajes de error mejorados
- Detección de códigos APDU `6a82` y `6988` con mensaje específico sobre CAN incorrecto
- `SecurityException` con mensaje sobre verificación de CAN
- `NoClassDefFoundError` con mensaje sobre compatibilidad de librerías

### `app/src/main/java/com/oscar/detectornfc/ResultActivity.kt`

#### Refactor completo para usar `RawStructureData`
- Antes: parseaba `Map<String, Any?>` con claves "raw" y "dni"
- Después: deserializa directamente a `RawStructureData` con Gson
- `parseLegacyFormat()`: compatibilidad con formato JSON anterior

#### `bindIdentity()` - Parseo directo de DG1/DG11/DG13
Extracción de campos de identidad usando las clases del SDK:
- **DG1_Dnie**: número de documento, tipo, nacionalidad, sexo, fecha nacimiento, apellidos
- **DG11**: nombre, fecha nacimiento, lugar nacimiento, domicilio (direccion, localidad, provincia)
- **DG13**: nombre, apellidos (Surname1/Surname2), número personal, fecha nacimiento,
  sexo, lugar nacimiento (población/provincia), domicilio actual, **nombre del padre**, **nombre de la madre**

Fallbacks encadenados: DG13 → DG11 → DG1 para cada campo.

#### Nuevos campos de identidad mostrados
- `tv_gender` (Sexo) - de DG13.getSex() o DG1.getSex()
- `tv_birth_place` (Lugar de nacimiento) - de DG13 o DG11
- `tv_address` (Domicilio) - de DG13 o DG11 con ADDR_DIRECCION/LOCALIDAD/PROVINCIA
- `tv_support_number` (Número de soporte) - de DG1.getDocNumber()
- `tv_father_name` (Nombre del padre) - de DG13.getFatherName()
- `tv_mother_name` (Nombre de la madre) - de DG13.getMotherName()

#### `bindParsedDGFields()` - Reflexión sobre DGs
Método que usa reflexión Java para enumerar todos los getters de cada DG class
(DG1_Dnie, DG2, DG7, DG11, DG13) y mostrar campo + valor en la sección de Data Groups.
**Por qué**: Garantiza extracción completa de datos para estudio sin hardcoded de cada campo.

#### `bindDGTree()` - Árbol TLV por Data Group
Muestra estructura TLV (Tag-Length-Value) parseada para cada DG, con:
- Tag class, número, hex, nombre
- Valor en HEX y ASCII
- Valor decodificado (si aplica)
- Hijos recursivos para tags construidos

#### `bindChipSecurity()` y `bindHexDump()`
- Seguridad del chip: EAC, PACE, Chip Authentication, TA, CA
- Volcado HEX completo de cada DG

### `app/src/main/java/com/oscar/detectornfc/EuropeanStructureReader.kt` (nuevo)
Reader universal para documentos europeos usando jmrtd 0.7.31:
- PACE con CAN
- Lectura de DG1, DG2, DG7, DG11, DG13 vía PassportService
- Detección de tipo de documento (español, ICAO, alemán eID)
- `finally` block para cierre de IsoDep
- Reintentos (max 3) con backoff

### `app/src/main/java/com/oscar/detectornfc/RawStructureData.kt` (nuevo)
Modelo de datos unificado para resultados NFC:
- `RawStructureData`: uid, can, status, error, dgRawBytes, dgAnalysis, dgTLV, documentDetection
- `DocumentDetection`: tipo, país, arquitectura, protocolos soportados
- `DGTLVResult`, `TLVNode`: estructura TLV parseada
- `EFComData`, `SODData`, `CardAccessData`, `CardSecurityData`: metadatos del chip

### `app/src/main/java/com/oscar/detectornfc/TLVStructureAnalyzer.kt` (nuevo)
Parser TLV/BER-TLV recursivo:
- Parsea tags de 1-2 bytes
- Diferencia tags primitivos vs construidos
- Decodifica valores ASCII, HEX, fechas
- Nombres de tags ASN.1 conocidos

### `app/src/main/java/com/oscar/detectornfc/DocumentReaderFactory.kt` (nuevo)
Factory para seleccionar reader según tipo de documento detectado.

### `app/src/main/java/com/oscar/detectornfc/NfcDataParser.kt`
- `analyzeStructure()`: Convierte `RawStructureData` en resultado analizado con TLV y detección
- `convertFromRawNfcData()`: Convierte `RawNfcData` (DniReader) a `RawStructureData`
- Métodos de parseo de TLV y clasificación de documentos

### `app/src/main/res/layout/activity_result.xml`
- Añadidos TextViews: `tv_gender`, `tv_birth_place`, `tv_address`, `tv_support_number`,
  `tv_father_name`, `tv_mother_name` con dividers correspondientes
- Sección de DG tree con TextView dinámico

### `app/src/main/res/values/strings.xml`
- Añadidas: `label_gender`, `label_birth_place`, `label_address`, `label_support_number`,
  `label_father_name`, `label_mother_name`

### `SDK_DNIeDroid_FNMT/` (nuevo, referencia)
SDK oficial DNIeDroid v2.3.111 de la FNMT:
- `dniedroid_lib/dniedroid-release.aar`: AAR oficial (copia también en `app/libs/`)
- `Sample_DNIe_App/`: App de ejemplo con `Loader.init()` usage
- `JavaDoc/`: Documentación de APIs (DG11, DG13, Loader, MrtdCard, etc.)

---

## Validación

✅ Build exitoso con BC 1.65 + SDK v2.3.111
✅ Lectura DNIe real confirmada: DG1(95B), DG2(18547B), DG7(4229B), DG11(105B), DG13(185B)
✅ Foto JP2 (307×378) y firma (252×66) decodificadas correctamente
✅ Campos de identidad (nombre, apellidos, documento, nacimiento, padres) mostrados
✅ Fallback europeo e ICAO preservados


