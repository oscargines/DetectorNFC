# Arquitectura de Detección de DataGroups (DGs)

## Flujo General de Lectura

```
┌─────────────────────────────────────────────────────────────────┐
│ NFCScanActivity.onNewIntent() - Detecta tag NFC                │
└──────────────────────┬──────────────────────────────────────────┘
                       │
                       ▼
┌─────────────────────────────────────────────────────────────────┐
│ DniReader.readDniSync()                                         │
│ - Abre conexión PACE con CAN                                   │
│ - Llama readDataGroupsFromCard()                               │
└──────────────────────┬──────────────────────────────────────────┘
                       │
                       ▼
┌─────────────────────────────────────────────────────────────────┐
│ readDataGroupsFromCard() - Bucle sobre DG1..DG16               │
│ for (i in 1..16) {                                             │
│   result = tryReadDgWithAnalysis(card, i)                      │
│   dgMap[i] = result.first (bytes)                              │
│   dgAnalysis[i] = result.second (metadata)                     │
│ }                                                              │
└──────────────────────┬──────────────────────────────────────────┘
                       │
                       ▼
┌─────────────────────────────────────────────────────────────────┐
│ tryReadDgWithAnalysis(card, index)                              │
│ - Intenta tryReadDg(card, index)                               │
│ - Si bytes ≠ null → DataGroupInfo.read() [READ_OK]             │
│ - Si bytes = null → DataGroupInfo.notPresent()                │
│ - Si excepción    → DataGroupInfo.error() [ANALYZE]            │
└──────────────────────┬──────────────────────────────────────────┘
                       │
                       ▼
┌─────────────────────────────────────────────────────────────────┐
│ tryReadDg(card, index) - Prueba 4 patrones de nombre           │
│                                                                 │
│ Patrón 1: getDataGroupN()  [ej: getDataGroup1()]               │
│ Patrón 2: getDgNBytes()     [ej: getDg1Bytes()]                │
│ Patrón 3: getDgN()          [ej: getDg1()]          ← JMULTICARD 2.0
│ Patrón 4: getDg(N)          [ej: getDg(1)]                     │
│                                                                 │
│ Si alguno retorna bytes ≠ null → retorna bytes                 │
│ Si alguno lanza excepción → desenvuelve cause y continúa       │
│ Si ninguno funciona → retorna null                             │
└──────────────────────┬──────────────────────────────────────────┘
                       │
                       ▼
┌─────────────────────────────────────────────────────────────────┐
│ Resultado: RawNfcData                                           │
│ - uid: String                   [UID del chip NFC]             │
│ - can: String                   [CAN de 6 dígitos]             │
│ - dataGroups: Map<Int, ByteArray?>  [Bytes leídos]             │
│ - sod: ByteArray?               [Security Object Document]    │
│ - dgAnalysis: Map<Int, DataGroupInfo>  [FORENSIC DATA]         │
└──────────────────────┬──────────────────────────────────────────┘
                       │
                       ▼
┌─────────────────────────────────────────────────────────────────┐
│ Serializar a JSON y guardar en cacheDir                        │
│ {"uid": "...", "can": "...", "dgAnalysis": [...]}               │
└──────────────────────┬──────────────────────────────────────────┘
                       │
                       ▼
┌─────────────────────────────────────────────────────────────────┐
│ ResultActivity - Renderizar tabla forense                       │
│ Mostrar para cada DG:                                           │
│ - Índice (1-16)                                                │
│ - Status (READ_OK, NOT_PRESENT, READ_ERROR, etc)               │
│ - SHA-256 (si fue leído)                                       │
│ - Tamaño en bytes                                              │
└─────────────────────────────────────────────────────────────────┘
```

## Patrones de Método (Retrocompatibilidad)

La API de jmulticard ha evolucionado. Soportamos 4 patrones:

### Patrón 1: `getDataGroupN()` (Legacy, muy antiguo)
```kotlin
// Retorna un objeto con método .getBytes()
val dgObj = card.getDataGroup1()
val bytes = dgObj.getBytes()
```

**Cuándo se usa**: Versiones muy antiguas de jmulticard (pre-2.0)

### Patrón 2: `getDgNBytes()` (Legacy, antiguo)
```kotlin
// Retorna ByteArray directamente
val bytes = card.getDg1Bytes()  // DirectByte array
```

**Cuándo se usa**: Versiones antiguas de jmulticard

### Patrón 3: `getDgN()` (Moderno, jmulticard 2.0+) ⭐ ACTUAL
```kotlin
// Retorna ByteArray o un objeto con .getBytes()
val bytes = card.getDg1()      // jmulticard 2.0.jar
val bytes = card.getDg13()     // Para DG13
```

**Cuándo se usa**: jmulticard 2.0 (en `app/libs/jmulticard-2.0.jar`)

### Patrón 4: `getDg(int)` (Generic)
```kotlin
// Retorna ByteArray o un objeto con .getBytes()
val bytes = card.getDg(1)      // Fallback genérico
val bytes = card.getDg(13)
```

**Cuándo se usa**: Fallback si ninguno de los anteriores existe

## DataGroupInfo - Estructura Forense

```kotlin
data class DataGroupInfo(
    val index: Int,              // 1-16
    val status: DGStatus,        // READ_OK | NOT_PRESENT | READ_ERROR | etc
    val sizeBytes: Int?,         // Null si no se leyó
    val sha256: String?,         // Hash forense para comparación
    val exceptionType: String?,  // Clase de excepción (si hubo error)
    val exceptionMessage: String?  // Mensaje de error (si hubo error)
)

enum class DGStatus {
    READ_OK,                        // ✅ Leído correctamente
    NOT_PRESENT_OR_NOT_ALLOWED,    // ❌ DG no existe en documento
    ACCESS_DENIED,                  // ❌ Acceso rechazado por seguridad
    UNSUPPORTED_ON_DOCUMENT,        // ❌ Método no implementado para este doc
    READ_ERROR                      // ⚠️ Error temporal (NFC, IO, etc)
}
```

## Clasificación de Excepciones

El método `DataGroupInfo.error()` analiza la causa subyacente:

### Mapeado de Excepción → Status

| Excepción | Causa | Status |
|-----------|-------|--------|
| `IOException` con "not present" | DG no existe | `NOT_PRESENT_OR_NOT_ALLOWED` |
| `IOException` con "not supported" | No soportado | `NOT_PRESENT_OR_NOT_ALLOWED` |
| `IOException` general | Problema NFC/lectura | `READ_ERROR` |
| `SecurityException` | Acceso denegado | `ACCESS_DENIED` |
| `UnsupportedOperationException` | Método no implementado | `UNSUPPORTED_ON_DOCUMENT` |
| `NullPointerException` | Error de programación | `READ_ERROR` |
| `Exception` general | Desconocido | `READ_ERROR` |

## Casos Especiales

### DG11 en DNI 3.0 Español
- **Expected**: Debería estar presente en algunos DNIs
- **Qué contiene**: Certificados especiales
- **Cómo se identifica**:
  - Si `getDg11()` retorna bytes → `READ_OK`
  - Si lanza `UnsupportedOperationException` → DNI 3.0 no lo soporta
  - Si lanza `IOException` → Problema de lectura o no existe

### DG13 en DNI 3.0 Español
- **Expected**: Debería estar presente
- **Qué contiene**: Detalles opcionales del documento
- **Cómo se identifica**:
  - Si `getDg13()` retorna bytes → `READ_OK`
  - Si lanza `IOException` → Problema o no existe
  - Si lanza `UnsupportedOperationException` → No implementado

### Documentos Internacionales (Pasaportes, TIE, etc)
- Pueden usar diferente implementación de jmulticard
- Pueden tener diferentes DGs soportados
- Por eso usamos 4 patrones de reflexión

## Ejemplo de JSON Generado

```json
{
  "uid": "04:1A:2B:3C:4D:5E",
  "can": "123456",
  "dataGroups": {
    "1": [95, 0, ...],  // Bytes en hexadecimal (como List<Number>)
    "2": [18547, ...],
    "11": null,
    "13": null
  },
  "sod": [256, 0, ...],
  "dgAnalysis": {
    "1": {
      "index": 1,
      "status": "READ_OK",
      "sizeBytes": 95,
      "sha256": "a1b2c3d4e5f6...",
      "exceptionType": null,
      "exceptionMessage": null
    },
    "2": {
      "index": 2,
      "status": "READ_OK",
      "sizeBytes": 18547,
      "sha256": "f6e5d4c3b2a1...",
      "exceptionType": null,
      "exceptionMessage": null
    },
    "11": {
      "index": 11,
      "status": "NOT_PRESENT_OR_NOT_ALLOWED",
      "sizeBytes": null,
      "sha256": null,
      "exceptionType": null,
      "exceptionMessage": null
    },
    "13": {
      "index": 13,
      "status": "READ_ERROR",
      "sizeBytes": null,
      "sha256": null,
      "exceptionType": "IOException",
      "exceptionMessage": "No response from smart card"
    }
  }
}
```

## Ventaja Forense

Este diseño permite:
1. **Comparar documentos**: ¿Qué DGs tiene DNI español vs pasaporte canadiense?
2. **Detectar problemas**: ¿Es un error de lectura o el documento simplemente no tiene ese DG?
3. **Auditar**: Hash SHA-256 permite verificar que los datos no se modificaron
4. **Compatibilidad**: 4 patrones de reflexión soportan múltiples versiones de jmulticard
5. **Retrocompatibilidad**: Si cambia la API, solo necesita agregar nuevo patrón en `tryReadDg()`

