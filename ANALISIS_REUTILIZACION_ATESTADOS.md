# Análisis de Reutilización - Proyecto Atestados → DetectorNFG

## 📋 Resumen Ejecutivo

| Aspecto | Compatible | Notas |
|--------|-----------|-------|
| **Estructura de datos** | ✅ 80% | Necesita adaptación menor |
| **NfcReader (lectura)** | ⚠️ 40% | Usa bibliotecas deprecated |
| **NfcDataParser (parsing)** | ⚠️ 30% | ASN1ApplicationSpecific deprecated |
| **Flujo general** | ✅ 100% | Conceptos aplicables |

## 🔍 Análisis Detallado

### 1. **DniData.kt** - ALTAMENTE REUTILIZABLE ✅

```kotlin
data class DniData(
    val genero: String?,
    val nacionalidad: String?,
    val tipoDocumento: String?,
    val numeroDocumento: String?,
    // ... 8 campos más
)
```

**Estado**: ✅ Perfectamente reutilizable  
**Por qué**: Es una estructura de datos agnóstica a las librerías

**Acción**: Copiar tal cual a DetectorNFG

---

### 2. **RawNfcData.kt** - PARCIALMENTE COMPATIBLE ⚠️

**Proyecto Atestados:**
```kotlin
data class RawNfcData(
    val uid: String?,
    val can: String?,
    val dg1Bytes: ByteArray?,
    val dg11Bytes: ByteArray?,
    val dg13Bytes: ByteArray?
)
```

**Proyecto DetectorNFG (actual):**
```kotlin
data class RawNfcData(
    val uid: String?,
    val can: String?,
    val dataGroups: Map<Int, ByteArray?>,  // Todos los DGs
    val sod: ByteArray?,
    val dgAnalysis: Map<Int, DataGroupInfo> = emptyMap()
)
```

**Diferencias**:
- Atestados: Solo DG1, DG11, DG13 (específico)
- DetectorNFG: Todos los DGs (DG1-DG16) + análisis forense

**Acción**: Mantener la estructura actual de DetectorNFG (es más general)

---

### 3. **NfcReader.kt** - INCOMPATIBLE ❌

**Problemas críticos:**

#### 3.1 Usa Bibliotecas Deprecated
```kotlin
import es.gob.fnmt.dniedroid.help.Loader  // ❌ Legacy
import de.tsenger.androsmex.mrtd.DG1_Dnie  // ❌ Legacy
import de.tsenger.androsmex.mrtd.DG11      // ❌ Legacy
import de.tsenger.androsmex.mrtd.DG13      // ❌ Legacy
```

**Por qué es problema**:
- **androsmex** es librería abandonada (~2015)
- **dniedroid** es deprecated por FNMT
- No soporta DNI 4.0, pasaportes, TIE internacionales
- No compatible con jmulticard 2.0

#### 3.2 API Diferente
```kotlin
// Atestados (androsmex):
val dg1: DG1_Dnie? = mrtdCard.getDataGroup1()

// DetectorNFG (jmulticard 2.0):
val bytes = card.getDg1()  // O reflection con 4 patrones
```

#### 3.3 Sin Control de Excepciones Granular
```kotlin
// Atestados:
try {
    val dg11 = mrtdCard.getDataGroup11()
    // ...
} catch (e: Exception) {
    Log.w(TAG, "DG11 no disponible")
    null
}

// DetectorNFG:
try {
    // Con unwrapException + logExceptionDetails
    // Distingue IOException vs UnsupportedOperationException, etc
}
```

**Acción**: ❌ NO reutilizar. Usar `DniReader.kt` de DetectorNFG

---

### 4. **NfcDataParser.kt** - PARCIALMENTE COMPATIBLE ⚠️

**Problemas:**

#### 4.1 ASN1ApplicationSpecific (CRÍTICO)
```kotlin
val content = (root as? ASN1ApplicationSpecific)?.contents ?: bytes
//                   ^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^
//                   ❌ Removido en BC jdk18on 1.72+
```

**Estado en diferentes versiones**:
- BC jdk15on 1.70: ✅ Existe
- BC jdk18on 1.78.1: ❌ Removido (deprecated desde 1.72)

#### 4.2 Parsing MRZ en DG1
```kotlin
// Línea 73-96 - Lógica de parseo MRZ
// ✅ Esta lógica SÍ es reutilizable
if (mrzString.length == 88) {
    val line1 = mrzString.substring(0, 44)
    val line2 = mrzString.substring(44, 88)
    result["docType"] = line1.substring(0, 2)
    result["docNumber"] = line1.substring(5, 14)
    // ...
}
```

✅ La **lógica de parseo MRZ** es independiente de BC

#### 4.3 Parsing DG13
```kotlin
// Línea 169-223
// Estructura SEQUENCE con campos en orden
// ✅ Lógica aplicable, pero necesita actualizar ASN.1 API
```

**Acción**: Reutilizar LÓGICA de parseo, pero con ASN.1 API moderna

---

## 🎯 Plan de Reutilización

### ✅ COPIAR TAL CUAL

1. **DniData.kt**
   - Copiar completo a DetectorNFG
   - Compatible 100%

### ⚠️ ADAPTAR

2. **Parseo MRZ de DG1**
   ```kotlin
   // Copiar función parseDG1 pero:
   // - Reemplazar ASN1ApplicationSpecific por ASN1TaggedObject
   // - Usar BERTags.APPLICATION + baseObject
   ```

3. **Parseo DG13**
   ```kotlin
   // Copiar lógica pero:
   // - Actualizar acceso a ASN.1 con API moderna
   ```

### ❌ NO USAR

4. **NfcReader.kt**
   - Librerías incompatibles
   - Usar DniReader.kt de DetectorNFG

---

## 📊 Comparativa Técnica

| Aspecto | Atestados | DetectorNFG |
|---------|-----------|------------|
| **Librería NFC** | androsmex | jmulticard 2.0 |
| **BouncyCastle** | jdk15on 1.70 | jdk18on 1.78.1 |
| **ASN.1 API** | ApplicationSpecific | TaggedObject + BERTags |
| **DGs soportados** | DG1, DG11, DG13 | DG1-DG16 |
| **Documentos** | Solo DNI 3.0 | DNI, pasaportes, TIE |
| **Análisis forense** | ❌ No | ✅ Sí (dgAnalysis) |
| **Excepciones** | Básico | Detallado (5 categorías) |

---

## 🔧 Archivos Necesarios para Adaptar

Para reutilizar parsing code, necesitaría que me muestres:

1. ❓ `build.gradle.kts` de Atestados (qué librerías usa)
2. ❓ Estructura completa de `androsmex` (si tienes JAR local)
3. ❓ `NfcDataParser.kt` versión más reciente (si existe)

---

## 💡 Recomendación Final

### Opción A: Migración Selectiva (RECOMENDADO)
```
✅ Reutilizar:
  - DniData.kt (estructura de datos)
  - Lógica de parsing MRZ (algoritmo, no código)
  
⚠️ Adaptar:
  - Lógica de DG13 parsing (actualizar ASN.1 API)
  
❌ Descartar:
  - NfcReader.kt completo (bibliotecas legacy)
  - NfcDataParser.kt completo (ASN.1 deprecated)
```

### Opción B: Usar como Referencia
```
Estudiar Atestados para:
  - Orden de campos en DG13
  - Formato de MRZ esperado
  - Manejo de excepciones
  
Pero reimplementar todo con APIs modernas
```

---

## 🚀 Próximos Pasos

Si quieres **reutilizar máximo código**:

1. Necesito ver `build.gradle.kts` de Atestados
2. Me dices qué versión de BouncyCastle usaba
3. Te preparo versión actualizada de NfcDataParser.kt compatible con BC 1.78.1

¿Me pasas el `build.gradle.kts` de Atestados para análisis más detallado?

