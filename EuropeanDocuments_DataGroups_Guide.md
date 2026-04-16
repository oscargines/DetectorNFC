# Guía de Data Groups en Documentos Europeos - ICAO 9303
## Lectura NFC para Pasaportes y DNI Europeos

---

## 📋 Resumen Ejecutivo

Tu implementación DNIe lee **DG1, DG2, DG11, DG13**. Esto es específico del DNI español. 

**La buena noticia**: Los documentos europeos siguen ICAO 9303, pero la **granularidad** de los Data Groups varía significativamente según el tipo de documento:

- **Pasaportes electrónicos** (TD3): Estructura estándar global
- **Documentos de Identidad** (ID cards - DV1/DV2): Estructura más granular
- **Permisos de conducir electrónicos** (eDL): Estructura completamente diferente

---

## 🏗️ Estructura ICAO 9303 - Dos Niveles

### LDS1 (Mandatory - Todos los documentos)
Estructura de datos obligatoria en TODOS los e-documentos:
- **EF_COM**: Fichero de información sobre versión LDS
- **EF_SOD**: Documento de seguridad (firma digital CMS/PKCS#7)
- **Data Groups (DG)**: Datos organizados por categoría

### LDS2 (Optional - Próxima generación)
Estructura extendida para:
- Registros de viajes
- Registros de visados
- Datos biométricos adicionales

**Tu enfoque**: Por ahora, trabaja con **LDS1** que es universal en Europa.

---

## 📊 Data Groups Estándar (ICAO 9303 - Parte 10)

### Categoría 1: Datos Biográficos y Biométricos (FOUNDATION)

| DG | Contenido | Obligatorio | Pasaporte | DNI | eDL |
|---|---|---|---|---|---|
| **DG1** | Datos biográficos MRZ (nombre, fecha nac., etc.) | ✅ SÍ | ✅ | ✅ | ✅ |
| **DG2** | Imagen facial (foto) | ✅ SÍ | ✅ | ✅ | ✅ |
| **DG3** | Datos de huellas dactilares | ⚠️ Restringido | Raro | No | No |
| **DG4** | Iris/Datos biométricos adicionales | ❌ NO | No | No | No |
| **DG5** | Datos de reconocimiento facial extendido | ❌ NO | No | No | No |

### Categoría 2: Información del Documento (ADDITIONAL)

| DG | Contenido | Obligatorio | Pasaporte | DNI | eDL |
|---|---|---|---|---|---|
| **DG6** | MRZ sin información cifrada | ❌ NO | No | No | No |
| **DG7** | Datos de firma manuscrita (imagen) | ❌ NO | Raro | ✅ **DNI SÍ** | No |
| **DG8** | Datos de seguridad del documento | ❌ NO | No | No | No |
| **DG9** | Datos de acceso de seguridad | ❌ NO | No | No | No |
| **DG10** | Patrón de seguridad | ❌ NO | No | No | No |

### Categoría 3: Información Adicional (DNI/ID Cards - Específica)

| DG | Contenido | Obligatorio | Pasaporte | DNI | eDL |
|---|---|---|---|---|---|
| **DG11** | Datos biográficos adicionales (dirección, etc.) | ❌ NO | No | ✅ **DNI SÍ** | No |
| **DG12** | Datos de documento adicionales | ❌ NO | No | Posible | No |
| **DG13** | Información de deltas de huellas dactilares | ❌ NO | No | ✅ **DNI SÍ** | No |
| **DG14** | Control de acceso de seguridad | ❌ NO | No | No | No |

### Categoría 4: Autenticación y Seguridad (PILLARS OF TRUST)

| DG | Contenido | Obligatorio | Pasaporte | DNI | eDL |
|---|---|---|---|---|---|
| **DG15** | Autenticación activa (ECDSA) | ⚠️ Opcional | Muchos | Posible | Posible |
| **DG16** | Claves de seguridad de documento | ❌ NO | Raro | No | No |

---

## 🌍 Variaciones por País Europeo

### **ESPAÑA - DNI v3.0/v4.0**
**Tu implementación ya cubre esto**

```
DG1     → Datos biográficos MRZ
DG2     → Fotografía facial
DG7     → Firma manuscrita (ESPECÍFICO DNI)
DG11    → Dirección, municipio, provincia (ESPECÍFICO DNI)
DG13    → Deltas de huellas (ESPECÍFICO DNI)
EF_COM  → Metadatos LDS
EF_SOD  → Firma digital del país
```

**Particularidad**: El DNIe español es **muy granular** - separa mucha información en DG11 que otros países guardan en DG1.

---

### **PASAPORTES ESPAÑOLES (ePasaporte)**
Sigue la estructura estándar global:

```
DG1     → Datos biográficos MRZ
DG2     → Fotografía facial
DG15    → Autenticación activa (ECDSA)
EF_COM  → Metadatos LDS
EF_SOD  → Firma digital
```

**Diferencia con DNI**: 
- ❌ No incluye DG7, DG11, DG13
- ✅ Puede incluir DG15 (autenticación activa)
- Menos datos adicionales

---

### **FRANCIA - Carte Nationale d'Identité Électronique (CNIE)**
Formato reciente (versión 2023+):

```
DG1     → Datos biográficos MRZ
DG2     → Fotografía facial
DG7     → Firma manuscrita (como DNI español)
DG11    → Información adicional (como DNI español)
(DG13)  → Posiblemente huellas dactilares
EF_COM  → Metadatos LDS
EF_SOD  → Firma digital
```

**Similar al DNI español**, pero con algunas variaciones en codificación de datos.

---

### **ALEMANIA - Personalausweis (PA) / eID**
⚠️ **EXCEPCIÓN IMPORTANTE** - NO sigue ICAO 9303 estrictamente

```
❌ PROBLEMA: Usa estándar distinto (eID con TR-03130)
- No es MRTD compatible
- Estructura de datos diferente
- Requiere biblioteca especial (eIDAS plug-in)
```

**Solución**: Detectar por MRZ y derivar a controlador especial.

---

### **ITALIA - Documento d'Identità Elettronico (CIE)**

```
DG1     → Datos biográficos MRZ
DG2     → Fotografía facial
DG7     → Firma (posible)
DG11    → Información adicional
EF_COM  → Metadatos LDS
EF_SOD  → Firma digital
```

Estructura similar a Francia/España.

---

### **PORTUGAL - Cartão de Cidadão (CC)**
```
DG1     → Datos biográficos MRZ
DG2     → Fotografía facial
DG7     → Firma manuscrita
DG11    → Información adicional
DG13    → Huellas dactilares
EF_COM  → Metadatos LDS
EF_SOD  → Firma digital
```

Muy similar al DNI español.

---

### **BÉLGICA - Carte d'Identité Électronique**
```
DG1     → Datos biográficos MRZ
DG2     → Fotografía facial
DG7     → Firma (posible)
DG11    → Información adicional
EF_COM  → Metadatos LDS
EF_SOD  → Firma digital
```

---

### **SUECIA, DINAMARCA, FINLANDIA, PAÍSES BAJOS**
Generalmente siguen estructura similar a Alemania o Francia, pero con variaciones locales en DG11.

---

## 🔄 Patrón Emergente: Dos Arquitecturas Principales

### Arquitectura A: "Granular" (España, Portugal, Francia, Italia)
```
DG1  = Datos MRZ básicos
DG2  = Foto
DG7  = Firma (opcional)
DG11 = Datos adicionales: dirección, provincia, etc.
DG13 = Huellas dactilares (opcional)
```

**Ventaja**: Más datos, más privacidad (lees solo lo que necesitas)
**Desafío**: Más DGs para parsear

### Arquitectura B: "Compacta" (Pasaportes globales)
```
DG1  = Datos MRZ + info adicional
DG2  = Foto
DG15 = Autenticación activa (opcional)
```

**Ventaja**: Menos complejidad
**Desafío**: Menos granularidad

---

## 🎯 Estrategia de Implementación Recomendada

### Fase 1: Detección del Tipo de Documento
```kotlin
// Pseudocódigo
fun detectDocumentType(mrz: String): DocumentType {
    val docType = mrz.substring(0, 1)  // P=Pasaporte, C=ID Card
    val countryCode = mrz.substring(2, 5)
    
    return when {
        docType == "P" -> DocumentType.PASSPORT
        docType == "C" && countryCode == "DEU" -> DocumentType.GERMAN_EID // Excepción
        docType == "C" -> DocumentType.ID_CARD
        docType == "D" -> DocumentType.DRIVING_LICENSE
        else -> DocumentType.UNKNOWN
    }
}
```

### Fase 2: Mapper de Data Groups
```kotlin
// Reutilizar tu código DNI para pasaportes españoles
// Crear nuevos parsers para variantes de arquitectura A
// Fallback a estructura pasaporte estándar

interface DocumentDataGroupParser {
    fun parseDG1(bytes: ByteArray): BiographicData
    fun parseDG2(bytes: ByteArray): FacialImage
    fun parseDG7(bytes: ByteArray): SignatureImage?  // Opcional
    fun parseDG11(bytes: ByteArray): AdditionalData?  // Opcional
    fun parseDG13(bytes: ByteArray): BiometricData?  // Opcional
    fun parseDG15(bytes: ByteArray): ActiveAuth?  // Opcional
}
```

### Fase 3: Fallback Mechanism
```kotlin
// Si documentType = PASSPORT o desconocido
// → Asumir LDS estándar: solo DG1, DG2, posible DG15

// Si documentType = ID_CARD
// → Intentar: DG1, DG2, DG7, DG11, DG13
// → Con manejo de excepciones para cada falta

// Si documentType = GERMAN_EID
// → Usar biblioteca especial de eIDAS
```

---

## 📝 Data Group Detallado por Tipo

### DG1: Biographic Data
**SIEMPRE obligatorio**, pero estructura varía:

```
Pasaporte (TD3):
- Document Type (1 char)
- Issuing Country (3 chars)
- Surname (39 chars)
- Given Names (39 chars)
- Passport Number (9 chars)
- Check Digit (1 char)
- Nationality (3 chars)
- Date of Birth (6 chars: YYMMDD)
- Check Digit (1 char)
- Sex (1 char: M/F/<)
- Date of Expiry (6 chars: YYMMDD)
- Check Digit (1 char)
- Optional Data (14 chars, puede incluir código dirección)

DNI Card (DV1):
- Similar a pasaporte pero MRZ truncado
- Información adicional en DG11
```

### DG2: Facial Image
**OBLIGATORIO**

```
Formato: JPEG o JPEG2000
Resolución: Típicamente 352x440 píxeles
Codificación: ISO/IEC 19794-5 (obsoleto) o ISO/IEC 39794-5 (nuevo desde 2023)

⚠️ IMPORTANTE: 
Desde agosto 2023, nuevos pasaportes usan ISO/IEC 39794-5
Necesitarás soportar AMBOS hasta 2026
```

### DG7: Signature/Usual Mark
**OPCIONAL - Presente en algunos DNI/ID Cards**

```
Países que lo incluyen:
- España (DNI)
- Portugal (CC)
- Francia (CNIE)
- Italia (CIE)

Formato: Imagen de firma manuscrita
Codificación: WSQ (Wavelet Scalar Quantization) o JPEG
```

### DG11: Additional Personal Data
**OPCIONAL - Presente en ID Cards europeos**

```
Contenido típico (varía por país):
- Nombre completo (formato local)
- Dirección
- Municipio
- Provincia
- Teléfono (algunas versiones)
- Email (algunas versiones)
- Profesión/Ocupación

España (DNI):
- Apellidos y nombres (posible variante)
- Domicilio completo
- Provincia

Francia (CNIE):
- Lieu de naissance (lugar de nacimiento)
- Adresse (dirección)
- Altre dati specifici francesi
```

### DG13: Biometric Security Object
**OPCIONAL - Deltas de huellas dactilares**

```
Contenido:
- Deltas de huellas dactilares (no las huellas completas)
- Información de posición de dedos
- Características de calidad

Países:
- España (DNI)
- Portugal (CC)
- Italia (CIE)
- Algunos pasaportes
```

### DG15: Active Authentication
**OPCIONAL - Mecanismo de seguridad avanzado**

```
Contenido:
- Clave pública ECDSA del documento
- Algoritmo de firma

Permite:
- Verificar que el chip es original
- Proteger contra clonación
- Realizar "challenging" criptográfico

Países con DG15:
- Muchos pasaportes europeos modernos
- Algunos ID Cards nuevos
```

---

## ⚙️ Implementación Práctica: Estructura de Carpetas Sugerida

```
src/main/kotlin/com/ejemplo/nfc/

├── core/
│   ├── MRTDProtocol.kt          (BAC, NFC communication)
│   ├── LDS1Parser.kt            (Parser base LDS1)
│   └── DataGroupFactory.kt      (Factory pattern para DGs)
│
├── data_groups/
│   ├── DG1.kt                   (Biographic - UNIVERSAL)
│   ├── DG2.kt                   (Facial Image - UNIVERSAL)
│   ├── DG7.kt                   (Signature - OPTIONAL)
│   ├── DG11.kt                  (Additional Data - OPTIONAL)
│   ├── DG13.kt                  (Biometric Deltas - OPTIONAL)
│   ├── DG14.kt                  (Security Info - OPTIONAL)
│   ├── DG15.kt                  (Active Auth - OPTIONAL)
│   └── EF_COM.kt               (LDS Metadata)
│
├── document_types/
│   ├── DocumentType.kt          (Enum)
│   ├── PassportParser.kt        (TD3 - Arquitectura B)
│   ├── IDCardParser.kt          (DV1 - Arquitectura A)
│   ├── DrivingLicenseParser.kt  (ISO 18013)
│   ├── GermanEIDHandler.kt      (⚠️ EXCEPCIÓN)
│   └── DocumentFactory.kt       (Factory pattern)
│
├── countries/
│   ├── Spain/
│   │   ├── SpanishDNIParser.kt  (Tu código existente)
│   │   └── SpanishPassportParser.kt
│   ├── France/
│   │   └── FrenchIDParser.kt
│   ├── Germany/
│   │   └── GermanEIDParser.kt   (Especial)
│   ├── Italy/
│   │   └── ItalianIDParser.kt
│   └── ...otros países
│
└── models/
    ├── BiographicData.kt
    ├── FacialImage.kt
    ├── Signature.kt
    ├── AdditionalData.kt
    └── DocumentVerification.kt
```

---

## 🚀 Estrategia de Implementación por Fases

### FASE 1: MVP (Pasaportes + DNI España/Portugal)
```
✅ DG1, DG2 - Datos básicos foto
✅ BAC encryption
✅ EF_SOD signature verification
✅ Soporte: Pasaportes + DNI españoles/portugueses
```

### FASE 2: Expansión (Francia, Italia, Bélgica)
```
✅ Agregar soporte DG7 (Firma)
✅ Agregar soporte DG11 (Datos adicionales)
✅ Agregar soporte DG13 (Huellas dactilares)
✅ Parsers específicos por país
```

### FASE 3: Casos Especiales
```
⚠️ Alemania (eID - Requerirá trabajo significativo)
✅ Permisos de conducir electrónicos (ISO 18013)
✅ Terminal Authentication (DG14/DG15)
```

---

## 📚 Referencias Técnicas

### Especificaciones Oficiales
- **ICAO Doc 9303-10**: LDS para eMRTD (obligatorio leer)
- **ISO/IEC 19794-5**: Codificación de datos biométricos (obsoleto)
- **ISO/IEC 39794-5**: Nueva codificación biométrica (desde 2023)
- **ETSI TS 102 778**: Firma digital en documentos de identidad

### Librerías de Referencia (Java/Kotlin)
- **jMRTD**: Lectura de MRTD
- **Bouncycastle**: Criptografía (3DES, ECDSA)
- **ZXing/ML Kit**: OCR para MRZ

### Bases de Datos Públicas
- **ICAO PKD** (Public Key Directory): Certificados de países
- **PKI Repositorio de España** (DGP)
- **OpenJDK cryptography**: Para validar certificados

---

## ✅ Checklist de Implementación

- [ ] Detectar tipo de documento (Pasaporte vs ID Card)
- [ ] Detectar país emisor
- [ ] Implementar BAC genérico (reutilizar tu código DNI)
- [ ] Parser DG1 genérico con manejo de variantes
- [ ] Parser DG2 con soporte JPEG + JPEG2000
- [ ] Parsers opcionales: DG7, DG11, DG13, DG15
- [ ] Validación de EF_SOD (firma digital)
- [ ] Manejo especial de Alemania
- [ ] Logging/debugging de datos raw
- [ ] Pruebas con documentos reales
- [ ] Fallback mechanism para documentos desconocidos

---

## 🔧 Ejemplo de Lógica de Detección

```kotlin
fun parseDocument(docType: String, countryCode: String, dataBlob: Map<Int, ByteArray>): DocumentData {
    return when {
        // Casos especiales
        countryCode == "DEU" && docType == "C" -> parseGermanEID(dataBlob)
        
        // Arquitectura A: Granular (ID Cards europeos)
        docType == "C" && listOf("ESP", "PRT", "FRA", "ITA", "BEL").contains(countryCode) -> {
            parseIDCardArchitectureA(
                dg1 = dataBlob[1],
                dg2 = dataBlob[2],
                dg7 = dataBlob[7],
                dg11 = dataBlob[11],
                dg13 = dataBlob[13]
            )
        }
        
        // Arquitectura B: Compacta (Pasaportes globales)
        docType == "P" -> {
            parsePassportArchitectureB(
                dg1 = dataBlob[1],
                dg2 = dataBlob[2],
                dg15 = dataBlob[15]
            )
        }
        
        else -> parseGenericMRTD(dataBlob)
    }
}
```

---

## 🎓 Conclusión

**Respuesta a tu pregunta inicial**: 

El resto de documentos europeos **NO todos usan exactamente DG1+DG2+DG11+DG13** como el DNI español.

**Distribución típica**:
- **Pasaportes europeos**: DG1, DG2, (DG15)
- **ID Cards granulares** (España, Portugal, Francia, Italia): DG1, DG2, DG7, DG11, (DG13)
- **Alemania**: COMPLETAMENTE DIFERENTE (no usar ICAO 9303)
- **Nuevos formato** (Dinamarca, Suecia): Varía

**Tu mejor enfoque**: 
1. Refactorizar tu código DNI para extraer lógica genérica (BAC, DG parsers)
2. Crear una capa de abstracción que soportar múltiples "arquitecturas"
3. Usar factory pattern para crear el parser correcto según documento detectado
4. Implementar un mecanismo de fallback para documentos desconocidos
