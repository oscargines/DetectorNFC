# DetectorNFC

Una aplicación Android para leer y procesar datos de documentos de identidad (DNI electrónico, TIE, pasaportes, CNI y demás documentos ICAO europeos) a través de NFC, con dos modos de acceso: **PACE-CAN** (SDK oficial FNMT) y **datos del documento / MRZ** (PACE-MRZ y BAC).

Diseñada como **herramienta de estudio** para comprender dónde y cómo se almacenan los datos en los chips de documentos de identidad europeos.

> **Documentación técnica**: [docs/DOCUMENTACION_TECNICA.md](docs/DOCUMENTACION_TECNICA.md) (arquitectura, protocolos, informe PDF, firma y tests).

## Características

- **Lectura de DNI Electrónico Español**: Soporte completo para DNI-e 3.0/4.0 y TIE mediante PACE-CAN con el SDK oficial DNIeDroid v2.3.111 (CNP-FNMT)
- **Modo MRZ (datos del documento)**: lectura con número de documento + fecha de nacimiento + fecha de caducidad vía `AccessCredentials`, intentando **PACE-MRZ** y cayendo a **BAC** — necesario para pasaportes, CNIe francesa y CNI española
- **Lector universal europeo**: `EuropeanStructureReader` usa jmrtd para leer cualquier documento ICAO europeo con CAN o MRZ
- **Fallback ICAO/JMRTD**: `IcaoReader` como último eslabón de la cadena de lectura
- **Datos de identidad completos**: Nombre, apellidos, número de documento, fecha de nacimiento, nacionalidad, sexo, lugar de nacimiento, domicilio, **nombre del padre**, **nombre de la madre**, número de soporte
- **Informe PDF forense**: botón "Generar informe" en la pantalla de resultados → PDF con escudos oficiales, recuadro CONFIDENCIAL, foto (DG2), firma (DG7), tabla de datos, huella SHA-256 del JSON y paginación; se guarda en `Documentos/DetectorNFC` y se puede compartir
- **Análisis de DataGroups**: Detección y análisis automático de todos los grupos de datos (DG1-DG16) con estructura TLV/BER-TLV parseada
- **Decodificación de Imágenes**: Soporte nativo para imágenes JPEG-2000 (JP2) usando OpenJPEG 2.5.2 (foto DG2 y firma DG7)
- **Volcado HEX y árbol TLV**: Visualización de datos crudos por DG para estudio del formato ASN.1/TLV
- **Criptografía**: BouncyCastle 1.65 para validación de certificados, firma digital y PACE
- **Manejo de errores avanzado**: Detección de errores fatales (tag perdido), fallback inteligente entre readers

## Requisitos

- **Android**: API 31+ (Android 12+)
- **Compilación**: Android Gradle Plugin 9.4.1, Gradle 9.6.0 (wrapper)
- **Java**: JDK 17+
- **Kotlin**: 2.2.10 (embebido en AGP 9.x)

## Compilación

```bash
# Clonar el repositorio
git clone https://github.com/oscargines/DetectorNFC.git
cd DetectorNFC

# Compilar la aplicación
./gradlew build          # en Windows: .\gradlew.bat build

# Compilar APK de release (firmado si existe keystore.properties, ver abajo)
./gradlew assembleRelease

# Ejecutar tests
./gradlew test
```

### Firma de release

El keystore de release está en `app/release.keystore` (alias `detectornfc`).
Las credenciales viven en `keystore.properties` en la raíz del repo, **no se
commitean** (está en `.gitignore`); sin ese fichero, `assembleRelease` genera un
APK **sin firmar**. Ver [docs/DOCUMENTACION_TECNICA.md §9](docs/DOCUMENTACION_TECNICA.md).

## Descarga

Los APK firmados de release se publican en la sección [Releases](https://github.com/oscargines/DetectorNFC/releases) del repositorio.

## Arquitectura

```
DetectorNFC/
├── app/
│   ├── src/main/java/com/oscar/detectornfc/
│   │   ├── MainActivity.kt               # Entrada: credenciales CAN/MRZ + check NFC
│   │   ├── NFCScanActivity.kt            # Escaneo NFC + cadena de fallback
│   │   ├── ResultActivity.kt             # Resultados + informe PDF
│   │   ├── AccessCredentials.kt          # Credenciales (CAN, MRZ, PIN)
│   │   ├── DniReader.kt                  # Lector DNIe (SDK FNMT v2.3.111)
│   │   ├── EuropeanStructureReader.kt    # Lector universal europeo (jmrtd)
│   │   ├── IcaoReader.kt                 # Fallback ICAO (PACE-MRZ/BAC)
│   │   ├── NfcDataParser.kt              # Parseo y análisis de resultados
│   │   ├── RawStructureData.kt           # Modelo de datos unificado
│   │   ├── RawNfcData.kt                 # DTO de lectura
│   │   ├── TLVStructureAnalyzer.kt       # Parser TLV/BER-TLV recursivo
│   │   ├── ImageDecoder.kt               # JP2 (OpenJPEG) / JPEG / PNG → Bitmap
│   │   ├── ImageFormatDetector.kt        # Detección de formato de imagen
│   │   ├── DocumentReaderFactory.kt      # Factory de readers por tipo
│   │   ├── DocumentClassifier.kt         # Clasificación de documentos
│   │   ├── DocumentProfile.kt            # Perfiles de documento
│   │   ├── DocumentDiagnostics.kt        # DG esperados/leídos por arquitectura
│   │   ├── DataGroupInfo.kt              # Metadata de DGs (status, errores)
│   │   └── DniData.kt                    # Modelo de identidad DNIe
│   │   └── report/
│   │       ├── ReportData.kt             # Modelo del informe + parseIdentity()
│   │       └── ReportPdfGenerator.kt     # Generación del PDF forense
│   ├── src/main/assets/escudos/          # Escudos para el PDF
│   ├── src/main/cpp/                     # OpenJPEG nativo (JP2 decode, CMake)
│   ├── src/test/java/                    # Tests unitarios (6 suites)
│   ├── libs/
│   │   └── dniedroid-release.aar         # SDK oficial FNMT v2.3.111
│   ├── release.keystore                  # Firma de release (alias detectornfc)
│   └── build.gradle.kts
├── docs/DOCUMENTACION_TECNICA.md         # Documentación técnica
├── SDK_DNIeDroid_FNMT/                   # SDK oficial de referencia (JavaDoc, sample)
├── gradle/libs.versions.toml             # Dependencias centralizadas
├── gradle/wrapper/
└── keystore.properties                   # Credenciales de firma (NO commiteado)
```

### Cadena de Lectura NFC

```
MainActivity → NFCScanActivity.buildCredentials() → AccessCredentials (CAN y/o MRZ)
  │
  NFCScanActivity.readDocumentStructure(tag, credentials)
  │
  ├─ 1. DniReader (solo con CAN; SDK español FNMT v2.3.111)
  │     └─ Loader.init(can, tag) → MrtdCard
  │        ├─ DG1_Dnie  → documento, nacionalidad, sexo
  │        ├─ DG11      → domicilio, lugar nacimiento
  │        ├─ DG13      → nombre, apellidos, padres, fechas
  │        ├─ DG2       → foto (JP2)
  │        └─ DG7       → firma (JP2)
  │     └─ Si FALLA con fallback sugerido → continúa
  │     └─ Si FALLA sin fallback (tag perdido) → retorna error
  │
  ├─ 2. EuropeanStructureReader (jmrtd universal)
  │     └─ Canal seguro: PACE (CAN → MRZ → PIN) o BAC con MRZ
  │     └─ Lee DG1, DG2, DG7, DG11, DG13…
  │
  └─ 3. IcaoReader (fallback ICAO estándar)
        └─ PassportService + PACE-MRZ / BAC

Resultado → NfcDataParser.analyzeStructure() → RawStructureData (JSON)
         → ResultActivity: identidad, foto, firma, árbol TLV, HEX dump, informe PDF
```

### Data Groups del DNIe 3.0

| DG | Contenido | Clase SDK |
|----|-----------|-----------|
| DG1 | MRZ: documento, nacionalidad, sexo, fecha nacimiento | `DG1_Dnie` |
| DG2 | Fotografía (JPEG-2000) | `DG2` |
| DG7 | Firma manuscrita (JPEG-2000) | `DG7` |
| DG11 | Domicilio, lugar de nacimiento, profesión | `DG11` |
| DG13 | Nombre, apellidos, padres, fechas, sexo, domicilio | `DG13` |

## Dependencias Principales

| Dependencia | Versión | Propósito |
|---|---|---|
| `dniedroid-release.aar` | v2.3.111 (FNMT, 2023) | Lectura oficial DNI-e: PACE-CAN, Loader.init(), DG1-DG13 |
| `jmrtd` (Maven) | 0.7.31 | Lectura universal e ICAO: PACE-MRZ, BAC |
| `scuba-sc-android` (Maven) | 0.0.23 | Soporte smartcard para jmrtd en Android |
| `bcprov`/`bcpkix`/`bcmail`/`bctls-jdk15on` | 1.65 | Criptografía ASN.1, certificados, PACE/TLS |
| `openjpeg` | 2.5.2 | Decodificación JPEG-2000 nativa (foto y firma), compilada en `src/main/cpp` |
| `gson` (Maven) | 2.10.1 | Serialización del JSON de resultados e informe |

> jmrtd, BouncyCastle y Gson vienen de Maven Central (`gradle/libs.versions.toml`);
> en `app/libs/` solo se distribuye el AAR oficial de la FNMT.

## Tests

```bash
./gradlew test          # Tests unitarios
./gradlew test --info   # Con reporte detallado
```

### Cobertura (6 suites, 37 tests)

- **DocumentClassifierTest** (13): Clasificación de documentos por país/código
- **NfcDataParserTest** (6): Parseo de datos, manejo de errores, formatos de fecha
- **DocumentDiagnosticsTest** (4): DG esperados/leídos por arquitectura
- **AccessCredentialsTest** (7): CAN, MRZ completo/inválido, normalización
- **ReportDataTest** (6): Títulos y fallbacks del informe PDF, enmascorado del CAN
- **ExampleUnitTest** (1): Plantilla

## Configuración

### Habilitar NFC en AndroidManifest.xml
```xml
<uses-permission android:name="android.permission.NFC" />
<uses-feature android:name="android.hardware.nfc" android:required="true" />
```

### gradle.properties
```properties
org.gradle.jvmargs=-Xmx2048m -Dfile.encoding=UTF-8
```

## Troubleshooting

### "Tag was lost" / "Se ha perdido la conexión"
El documento se separó del móvil durante la lectura. Mantén el documento inmóvil sobre la zona NFC hasta que termine. Si el error persiste, puede ser necesario reintentar.

### Error 6988 / 6A82 (CAN incorrecto)
- Verificar que el CAN es correcto (6 dígitos impresos en el documento)
- Si el documento está bloqueado por demasiados intentos, probar el modo MRZ (datos del documento)

### ClassCastException con DERObjectIdentifier
Este error ocurría con el AAR antiguo (2019) compilado contra BC 1.50. La solución fue reemplazarlo con el SDK oficial v2.3.111 (2023) compatible con BC 1.65. Ver `CAMBIOS_REALIZADOS.md` para detalles.

## Licencias

- **dniedroid SDK v2.3.111**: (c) CNP-FNMT - Distribuido con el SDK oficial
- **jmrtd**: LGPL 2.1
- **BouncyCastle**: Licencia estilo MIT
- **OpenJPEG**: Licencia BSD

## Contribuciones

Las contribuciones son bienvenidas. Por favor:

1. Fork el repositorio
2. Crea una rama para tu feature (`git checkout -b feature/AmazingFeature`)
3. Commit tus cambios (`git commit -m 'Add some AmazingFeature'`)
4. Push a la rama (`git push origin feature/AmazingFeature`)
5. Abre un Pull Request

## Soporte

Para reportar issues o sugerencias, abre un [issue en GitHub](https://github.com/oscargines/DetectorNFC/issues).

## Historial de Cambios

Ver [CAMBIOS_REALIZADOS.md](CAMBIOS_REALIZADOS.md) para detalles de todas las modificaciones.

---

**Versión**: 1.1 (versionCode 2)
**Última actualización**: Octubre 2026
**Autor**: Oscar
