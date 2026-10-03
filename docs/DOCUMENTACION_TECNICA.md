# Documentación Técnica — DetectorNFC

> Versión del documento: 1.1 · Última actualización: 03/10/2026
> Esta referencia cubre la arquitectura, los protocolos de lectura, el informe PDF,
> el proceso de compilación/firma y las pruebas. Para una visión general y
> capturas, consultar [`README.md`](../README.md).

---

## 1. Resumen

DetectorNFC es una aplicación Android nativa (Kotlin, Views + XML) que lee datos
de documentos de identidad europeos por NFC y los presenta con fines de estudio
y análisis. Soporta dos modos de acceso al chip:

| Modo | Credenciales | Protocolo | Documentos típicos |
|------|--------------|-----------|--------------------|
| **CAN** | Código de 6 dígitos impreso en el documento | PACE-CAN | DNIe/TIE (ES), ID Países Bajos, Cartão de Cidadão 2024+ (PT), CIE (IT), eDO (PL) |
| **Datos del documento (MRZ)** | Nº documento + fecha nacimiento + fecha caducidad (ICAO 9303) | PACE-MRZ o BAC | Pasaportes, CNIe francesa, CNI (ES) |

La aplicación extrae identidad (DG1/DG11/DG13), fotografía (DG2), firma manuscrita
(DG7), análisis TLV/ASN.1 de cada Data Group, y genera un **informe PDF firmado
gráficamente** con aspecto de análisis forense.

## 2. Stack tecnológico

| Componente | Versión |
|---|---|
| Android Gradle Plugin | 9.4.1 |
| Gradle (wrapper) | 9.6.0 |
| Kotlin | 2.2.10 (embebido en AGP 9.x) |
| JDK (compilación) | 17+ (`compileOptions` = Java 11) |
| compileSdk / targetSdk | 36 |
| minSdk | 31 (Android 12) |
| SDK FNMT DNIeDroid | 2.3.111 (`app/libs/dniedroid-release.aar`, local) |
| jmrtd / scuba | 0.7.31 / 0.0.23 |
| BouncyCastle | 1.65 (`bcprov`, `bcpkix`, `bcmail`, `bctls` jdk15on) |
| Gson | 2.10.1 |
| OpenJPEG | 2.5.2 (compilado desde `app/src/main/cpp` vía CMake 3.22.1) |
| Tests | JUnit 4.13.2 |

ABIs compiladas: `arm64-v8a`, `armeabi-v7a`, `x86_64`.

## 3. Arquitectura

### 3.1 Paquete principal `com.oscar.detectornfc`

```
MainActivity ──credenciales──► NFCScanActivity ──JSON──► ResultActivity
                                                                    │
                        ┌───────────────────────────────────────────┤
                        ▼                                           ▼
                 lectores NFC                              report/ (informe PDF)
```

| Capa | Ficheros | Responsabilidad |
|---|---|---|
| UI / navegación | `MainActivity`, `NFCScanActivity`, `ResultActivity` | Credenciales, escaneo, presentación |
| Orquestación | `NFCScanActivity` | Cadena de fallback entre lectores en hilo propio |
| Lectores | `DniReader`, `EuropeanStructureReader`, `IcaoReader` | Establecer canal seguro y leer DGs |
| Parseo | `NfcDataParser`, `TLVStructureAnalyzer` | Conversión a modelos y análisis ASN.1/TLV |
| Modelo | `RawNfcData`, `RawStructureData`, `DniData`, `AccessCredentials`, `DataGroupInfo` | Contratos de datos inmutables |
| Imágenes | `ImageDecoder`, `ImageFormatDetector` | JP2 (OpenJPEG JNI) / JPEG / PNG → `Bitmap` |
| Clasificación | `DocumentClassifier`, `DocumentProfile`, `DocumentDiagnostics` | Tipo de documento, país, arquitectura LDS |
| Informe | `report/ReportData`, `report/ReportPdfGenerator` | Modelo y generación del PDF forense |

### 3.2 Flujo end-to-end

```
1. MainActivity
   ├─ Modo CAN  → AccessCredentials.withCan(can)          (valida 6 dígitos)
   └─ Modo MRZ  → AccessCredentials.withMrz(doc, dob, exp) (valida fechas yymmdd)
   └─ putExtra(EXTRA_CAN | EXTRA_MRZ_DOC_NUMBER / _DATE_OF_BIRTH / _DATE_OF_EXPIRY)

2. NFCScanActivity (hilo de lectura)
   ├─ buildCredentials(intent) → AccessCredentials (ambos métodos pueden coexistir)
   ├─ readDocumentStructure(tag, credentials):
   │    ① DniReader (solo si hay CAN y dependencias FNMT disponibles)
   │    ② EuropeanStructureReader.readAllStructures(credentials)   ← universal
   │    └─ si FAILED → ③ IcaoReader.readWithCredentials(credentials)
   ├─ NfcDataParser.analyzeStructure() → RawStructureData
   └─ Gson → cacheDir/scan_result_<ts>.json → Intent(EXTRA_JSON_PATH)

3. ResultActivity
   ├─ parseIdentity() (DG1_Dnie / DG11 / DG13) → tarjeta IDENTIDAD
   ├─ bindPhoto()  ← dgRawBytes[2]  (DG2 → JP2/JPEG → Bitmap)
   ├─ bindSignature() ← dgRawBytes[7] (DG7)
   ├─ bindDGTree() / bindDiagnostics() / bindChipSecurity() / bindHexDump()
   └─ Botón "Generar informe" → ReportPdfGenerator → PDF (§8)
```

## 4. Métodos de acceso al chip

### 4.1 Modelo de credenciales

`AccessCredentials(can, mrz, pin)` con:

- `hasCan()` — CAN válido (6 dígitos).
- `hasMrz()` — `MrzCredentials` **completo**: los tres campos no vacíos *y* fechas
  `yymmdd` válidas (`MrzCredentials.isValidMrzDate`: mes 01-12, día 01-31).
- `hasAnyAccessMethod()` — puerta de entrada de `NFCScanActivity`.
- `fromValues(...)` — construcción normalizada desde los extras del Intent
  (rellena, recorta, pasa el documento a mayúsculas).

El dígito de control del MRZ no es obligatorio: jmrtd lo deriva internamente
(`BACKey` / `PACEKeySpec.createMRZKey`).

### 4.2 Cascada de canal seguro

En `EuropeanStructureReader.establishSecureChannel()` e `IcaoReader.establishPace()`:

```
¿Hay EF.CardAccess con PACEInfo?
 ├─ SÍ → PACE:  CAN  →  MRZ  →  PIN       (el PIN solo se intenta una vez
 │                                          para no agotar su contador)
 └─ NO → BAC con clave derivada del MRZ    (pasaportes clásicos)
```

- `PACEKeySpec.createCANKey(can)`
- `PACEKeySpec.createMRZKey(BACKey(doc, dob, exp))`
- `BACKey` + `passportService.doBAC(...)` (BAC clásico)
- Si todo falla: `accessDeniedGuidance()` indica al usuario qué modo probar.

`DniReader` (SDK FNMT) solo acepta CAN (`Loader.init(arrayOf(can), tag)`); por eso
en modo MRZ **se omite** y se entra directamente por el lector universal.

### 4.3 Cadena de fallback

```
DniReader ──(falla + fallbackSuggested)──► EuropeanStructureReader ──(FAILED)──► IcaoReader
   │                                              │                                │
   └─ error fatal sin fallback → resultado final  └─ éxito → fin                   └─ éxito → fin
```

`notifyFallbackStart()` muestra el aviso en UI y pausa ~1,2 s antes del siguiente
intento. Errores fatales (`TagLostException`, `IOException`, "6a82", "6988"…)
cortan la cadena y se muestran en el diálogo de reintento.

## 5. Data Groups y modelos

| DG | Contenido | Origen en `RawStructureData` |
|---|---|---|
| DG1 | MRZ + tipo/país/nº documento | `dgRawBytes[1]` |
| DG2 | Fotografía (normalmente JP2) | `dgRawBytes[2]` |
| DG7 | Firma manuscrita | `dgRawBytes[7]` |
| DG11 | Dirección, lugar de nacimiento | `dgRawBytes[11]` |
| DG13 | Nombre, apellidos, padres, domicilio actual (DNIe 4.0) | `dgRawBytes[13]` |
| DG15 | Datos biométricos (huellas) | `dgRawBytes[15]` |
| EF.COM / EF.SOD / EF.CardAccess / EF.CardSecurity | LDS, hashes, protocolos | `efCom`, `efSod`, `efCardAccess`, `efCardSecurity` |

- `NfcDataParser.analyzeStructure()` ejecuta `TLVStructureAnalyzer` por DG
  (estructura BER-TLV, validación ASN.1, SHA-256, previsualización ASCII/HEX).
- `ImageDecoder.decode(bytes)` elige motor: **JP2/J2K → OpenJPEG nativo**
  (`System.loadLibrary("jp2jni")`), JPEG/PNG → `BitmapFactory`.
- `parseIdentity(struct)` (en `report/ReportData.kt`) es la **única** fuente de
  identidad: la consumen tanto `ResultActivity.bindIdentity()` como el informe PDF.

## 6. Pantalla de resultados

`ResultActivity` recibe la **ruta** del JSON (`EXTRA_JSON_PATH`, no el texto) y
construye las tarjetas: Resumen · Avisos · Identidad · Foto/Firma · Data Groups ·
Diagnóstico · Chip Security · HEX Dump. Barra inferior: **Compartir JSON**,
**Guardar imagen** (DG2 a `Pictures/DetectorNFC` vía MediaStore) y
**Generar informe** (§8).

## 7. Dependencias y recursos nativos

- `app/libs/dniedroid-release.aar` — SDK oficial CNP-FNMT. **No modificar.**
  Requiere BouncyCastle 1.65 clásico en runtime (se excluye `bcprov` de jmrtd
  para evitar duplicados).
- `app/src/main/cpp/` — OpenJPEG 2.5.2 + wrapper JNI (`jp2_jni.cpp`), CMake 3.22.1.
- `app/src/main/assets/escudos/` — `esc_espana.png`, `esc_guardia_civil.png`
  (usados por el informe PDF).
- `packaging.resources.excludes` — elimina metadatos duplicados que trae el AAR
  de dniedroid (LGPL, NOTICE, `AndroidManifest.xml`…).

## 8. Informe PDF

### 8.1 Flujo

```
btn_generate_report (activity_result.xml, deshabilitado hasta parsear el JSON)
   → ResultActivity.generateReportPdf()
        ReportData(identity, uid, can, timestamp, dgs, photo, signature, sha256(json))
        hilo en background → ReportPdfGenerator(applicationContext).generate(report, file)
   → copia en MediaStore: Documents/DetectorNFC/informe_<DOC>_<fecha>.pdf
   → ACTION_SEND (application/pdf) vía FileProvider (cache-path)
```

Tecnología: `android.graphics.pdf.PdfDocument` + `Canvas` — **sin dependencias
externas**. El generador hace **dos pasadas**: la primera cuenta páginas
(`pageCount`) y la segunda dibuja con el total conocido para el pie
`Página X de Y`.

### 8.2 Especificación de layout (A4 = 595,28 × 841,89 pt · 1 cm = 28,3465 pt)

| Elemento | Posición / tamaño |
|---|---|
| Escudo España | x = **1 cm**, y = **2 cm**, alto **3 cm** (ratio conservado) |
| Escudo Guardia Civil | y = 2 cm, margen derecho **1 cm**, alto **3 cm** |
| Recuadro central | **9 × 2,5 cm**, centrado en X, alineado verticalmente con los escudos |
| Doble borde del recuadro | 2 rectángulos concéntricos (inset 3,5 pt), `strokeWidth = 1 pt`, rojo |
| Texto interior | `C O N F I D E N C I A L`, centrado, rojo, auto-ajuste 18 → 6 pt |
| Bajo el recuadro | `SÓLO USO INTERNO, PROHIBIDO DIFUSIÓN` — **6 pt, rojo** |
| Título | `INFORME DATOS INTERNOS DOCUMENTO: <ID>` — serif bold, auto-ajuste 13 → 7,5 pt |
| Subtítulo | `Análisis forense de datos · Lectura de chip NFC · Generado el <fecha>` — 7,5 pt gris |
| Filete | doble línea (1,2 pt + 0,5 pt) navy `#1F2A44` |
| Tabla foto/firma | **7 cm** de alto, 2 columnas con margen 1,5 cm; banda de cabecera navy con `FOTOGRAFÍA (DG2)` / `FIRMA (DG7)`; placeholders *"no se ha obtenido foto"* / *"no se ha obtenido firma"* |
| Cabecera de tabla de datos | banda navy con `NOMBRE APELLIDOS · Nº DOC` (fallback doc → UID → CAN) |
| Filas | etiqueta 38 % / valor 62 %, fondo alternado, rejilla 0,7 pt, bandas de sección `#DFE5F0` |
| Secciones | IDENTIFICACIÓN · NACIMIENTO · DOMICILIO · FAMILIA · LECTURA DEL CHIPO |
| Pie | `Generado el <fecha>` · `SHA-256 JSON: <24 hex>…` · `Página X de Y` |
| Paginación | cabecera de continuación al rebasar el margen inferior (1,3 pt de reserva) |

Cadenas literales del PDF en `ReportPdfGenerator.kt` (documento fijo en español);
cadenas de UI en `res/values/strings.xml`.

### 8.3 Código

| Fichero | Contenido |
|---|---|
| `report/ReportData.kt` | `IdentityInfo`, `ReportData`, `parseIdentity()`, `titleIdentifier`, `tableTitle`, `maskCAN()` |
| `report/ReportPdfGenerator.kt` | Clase `ReportPdfGenerator` + clase interna `Renderer` (cabecera, título, tabla media, tabla de datos, paginación, pie) |
| `report/ReportDataTest.kt` | Tests de títulos, fallbacks y enmascorado del CAN |

## 9. Compilación, firma y release

### 9.1 Comandos

```bash
./gradlew assembleDebug      # APK de desarrollo (debuggable)
./gradlew compileDebugKotlin # typecheck rápido (usado como lint)
./gradlew test               # tests unitarios
./gradlew assembleRelease    # APK de release FIRMADO
```

Salida de release: `app/build/outputs/apk/release/app-release.apk`.

### 9.2 Firma de release

1. **Keystore** (trackeado): `app/release.keystore`
   - Alias `detectornfc`, RSA 4096, SHA384withRSA, validez 10.000 días,
     DN `CN=DetectorNFC, OU=DetectorNFC, O=Oscar Gines, C=ES`.
   - Huella SHA-256 del certificado:
     `71f3c00d542f3530cb09a3487b42069d9ad7c1284f84aedaf76c73378efc26e8`
2. **Credenciales** (NO trackeadas): `keystore.properties` en la raíz —

   ```properties
   storeFile=release.keystore
   storePassword=<privado>
   keyAlias=detectornfc
   keyPassword=<privado>
   ```

   La contraseña se guarda **solo en local**; `.gitignore` excluye el fichero.
3. **Integración en Gradle** (`app/build.gradle.kts`): carga
   `keystore.properties` si existe y asigna `signingConfig` al build type
   `release`; **si no existe, el release se compila sin firmar** (no rompe CI ni
   clones nuevos).
4. **Verificación**:

   ```bash
   "$ANDROID_HOME/build-tools/36.0.0/apksigner.bat" verify --print-certs \
       app/build/outputs/apk/release/app-release.apk
   ```

⚠️ Perder el keystore o la contraseña implica cambiar de firma: Android no
permite actualizar una app instalada con otro certificado. Mantener copia de
seguridad de `app/release.keystore` + `keystore.properties` fuera del repo.

### 9.3 Versionado

`versionCode = 2`, `versionName = "1.1"` en `app/build.gradle.kts`. Incrementar
`versionCode` en cada entrega a Play Store/dispositivos.

## 10. Tests

| Suite | Nº | Cubre |
|---|---|---|
| `DocumentClassifierTest` | 13 | Clasificación de tipo/país |
| `NfcDataParserTest` | 6 | Parseo de MRZ y estructuras |
| `DocumentDiagnosticsTest` | 4 | Diagnóstico DG esperados/leídos |
| `AccessCredentialsTest` | 7 | CAN, MRZ completo/incompleto/inválido, normalización |
| `ReportDataTest` | 6 | Títulos del informe y fallbacks (doc → UID → CAN), `maskCAN` |
| `ExampleUnitTest` | 1 | Plantilla |

```bash
./gradlew test
# Informes: app/build/test-results/testDebugUnitTest/*.xml
```

Las clases Android se configuran con `unitTests.isReturnDefaultValues = true`.

## 11. Consideraciones de seguridad y privacidad

- Los datos leídos (identidad, foto, firma) salen del chip NFC cifrado (PACE/BAC)
  y se persisten **solo** en `cacheDir` (JSON) y `Documents/DetectorNFC` (PDF),
  a petición explícita del usuario.
- El CAN se muestra enmascarado en UI (`maskCAN`: primer y último dígito) y el
  PDF incluye el **SHA-256 del JSON** como huella de integridad de la lectura.
- El informe se marca `CONFIDENCIAL` / `SÓLO USO INTERNO, PROHIBIDO DIFUSIÓN`.
- `FileProvider` solo expone `cache-path` vía `Intent.FLAG_GRANT_READ_URI_PERMISSION`.
- No hay telemetría ni envío de datos a servidores: la app funciona 100 % offline.
- Uso previsto: **herramienta de estudio**. La lectura no valida la autenticidad
  del documento (la verificación de firmas/SOD no bloquea la lectura).

## 12. Mapa de ficheros

```
DetectorNFC/
├── app/
│   ├── build.gradle.kts               # signingConfig, versionCode/Name, dependencias
│   ├── release.keystore               # firma de release (trackeado)
│   ├── libs/dniedroid-release.aar     # SDK FNMT (no modificar)
│   └── src/
│       ├── main/
│       │   ├── assets/escudos/        # escudos para el PDF
│       │   ├── cpp/                   # OpenJPEG + JNI (CMakeLists.txt)
│       │   ├── java/com/oscar/detectornfc/
│       │   │   ├── MainActivity.kt · NFCScanActivity.kt · ResultActivity.kt
│       │   │   ├── DniReader.kt · EuropeanStructureReader.kt · IcaoReader.kt
│       │   │   ├── NfcDataParser.kt · TLVStructureAnalyzer.kt
│       │   │   ├── AccessCredentials.kt · RawNfcData.kt · RawStructureData.kt
│       │   │   ├── ImageDecoder.kt · ImageFormatDetector.kt
│       │   │   ├── DocumentClassifier.kt · DocumentProfile.kt · DocumentDiagnostics.kt
│       │   │   └── report/ReportData.kt · report/ReportPdfGenerator.kt
│       │   └── res/layout/ · res/values/
│       └── test/java/...              # 6 suites JUnit
├── docs/DOCUMENTACION_TECNICA.md      # este documento
├── README.md
├── gradle/libs.versions.toml
└── keystore.properties                # credenciales de firma (gitignorado)
```
