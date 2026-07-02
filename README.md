# DetectorNFC

Una aplicación Android para leer y procesar datos de documentos de identidad (DNI electrónico, TIE, pasaportes) a través de NFC, con soporte para estándares españoles (PACE-CAN mediante SDK oficial FNMT) e internacionales (ICAO/JMRTD).

Diseñada como **herramienta de estudio** para comprender dónde y cómo se almacenan los datos en los chips de documentos de identidad europeos.

## Características

- **Lectura de DNI Electrónico Español**: Soporte completo para DNI-e 3.0/4.0 y TIE mediante PACE-CAN con el SDK oficial DNIeDroid v2.3.111 (CNP-FNMT)
- **Lector universal europeo**: `EuropeanStructureReader` usa jmrtd para leer cualquier documento ICAO europeo
- **Fallback ICAO/JMRTD**: Compatibilidad con pasaportes y documentos internacionales
- **Datos de identidad completos**: Nombre, apellidos, número de documento, fecha de nacimiento, nacionalidad, sexo, lugar de nacimiento, domicilio, **nombre del padre**, **nombre de la madre**, número de soporte
- **Análisis de DataGroups**: Detección y análisis automático de todos los grupos de datos (DG1-DG16) con estructura TLV/BER-TLV parseada
- **Decodificación de Imágenes**: Soporte nativo para imágenes JPEG-2000 (JP2) usando OpenJPEG 2.5.2 (foto DG2 y firma DG7)
- **Volcado HEX y árbol TLV**: Visualización de datos crudos por DG para estudio del formato ASN.1/TLV
- **Criptografía**: BouncyCastle 1.65 para validación de certificados, firma digital y PACE
- **Manejo de errores avanzado**: Detección de errores fatales (tag perdido), fallback inteligente entre readers

## Requisitos

- **Android**: API 31+ (Android 12+)
- **Compilación**: Android Gradle Plugin 9.2.1, Gradle 9.4.1
- **Java**: JDK 17+
- **Kotlin**: Soporte completo (KGP embebido en AGP 9.x)

## Compilación

```bash
# Clonar el repositorio
git clone https://github.com/tu-usuario/DetectorNFC.git
cd DetectorNFC

# Compilar la aplicación
./gradlew build

# Compilar APK de release
./gradlew assembleRelease

# Ejecutar tests
./gradlew test
```

## Descarga

Los APK compilados están disponibles en la sección [Releases](../../releases) del repositorio.

## Arquitectura

```
DetectorNFC/
├── app/
│   ├── src/main/java/com/oscar/detectornfc/
│   │   ├── MainActivity.kt               # Entrada: CAN + NFC check
│   │   ├── NFCScanActivity.kt            # Escaneo NFC + cadena de fallback
│   │   ├── ResultActivity.kt             # Presentación de resultados
│   │   ├── DniReader.kt                  # Lector DNIe (SDK FNMT v2.3.111)
│   │   ├── EuropeanStructureReader.kt    # Lector universal europeo (jmrtd)
│   │   ├── IcaoReader.kt                 # Fallback ICAO estándar (jmrtd)
│   │   ├── NfcDataParser.kt             # Parseo y análisis de resultados
│   │   ├── RawStructureData.kt           # Modelo de datos unificado
│   │   ├── TLVStructureAnalyzer.kt       # Parser TLV/BER-TLV recursivo
│   │   ├── DocumentReaderFactory.kt      # Factory de readers por tipo
│   │   ├── DocumentClassifier.kt         # Clasificación de documentos
│   │   ├── DocumentProfile.kt            # Perfiles de documento
│   │   ├── DataGroupInfo.kt              # Metadata de DGs (status, errores)
│   │   └── RawNfcData.kt                 # DTO para DniReader
│   ├── src/main/cpp/                     # OpenJPEG nativo (JP2 decode)
│   ├── libs/
│   │   ├── dniedroid-release.aar         # SDK oficial FNMT v2.3.111
│   │   ├── jmrtd-0.7.31.jar
│   │   └── [BouncyCastle 1.65 & OpenJPEG]
│   └── build.gradle.kts
├── SDK_DNIeDroid_FNMT/                   # SDK oficial de referencia (JavaDoc, sample)
├── gradle/libs.versions.toml             # Dependencias centralizadas
└── gradle/wrapper/
```

### Cadena de Lectura NFC

```
NFCScanActivity.onTagDetected(can)
  │
  ├─ 1. DniReader (SDK español FNMT v2.3.111)
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
  │     └─ PassportService + PACE-CAN
  │     └─ Lee DG1, DG2, DG7, DG11, DG13
  │
  └─ 3. IcaoReader (fallback ICAO estándar)
        └─ PassportService + BAC/PACE

Resultado → NfcDataParser.analyzeStructure() → RawStructureData (JSON)
         → ResultActivity: identidad, foto, firma, árbol TLV, HEX dump
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
| `jmrtd` | 0.7.31 | Fallback ICAO para documentos europeos e internacionales |
| `bcprov-jdk15on` | 1.65 | Criptografía ASN.1, certificados, PACE |
| `bcpkix-jdk15on` | 1.65 | PKI, CMS/SignedData |
| `bcmail-jdk15on` | 1.65 | CMS mail API (requerido por SDK) |
| `bctls-jdk15on` | 1.65 | TLS (requerido por SDK) |
| `openjpeg` | 2.5.2 | Decodificación JPEG-2000 nativa (foto y firma) |

## Tests

La aplicación incluye suite de tests:

```bash
./gradlew test          # Tests unitarios
./gradlew test --info   # Con reporte detallado
```

### Cobertura
- **DocumentClassifierTest**: Clasificación de documentos por país/código
- **NfcDataParserTest**: Parseo de datos, manejo de errores, formatos de fecha

## Configuración

### Habilitar NFC en AndroidManifest.xml
```xml
<uses-permission android:name="android.permission.NFC" />
<uses-feature android:name="android.hardware.nfc" android:required="true" />
```

### gradle.properties
```properties
org.gradle.jvmargs=-Xmx4096m
```

## Troubleshooting

### "Tag was lost" / "Se ha perdido la conexión"
El DNIe se separó del móvil durante la lectura. Mantén el documento inmóvil sobre la zona NFC hasta que termine. Si el error persiste, puede ser necesario reintentar.

### Error 6988 / 6A82 (CAN incorrecto)
- Verificar que el CAN es correcto (6 dígitos impresos en el documento)
- Si el documento está bloqueado por demasiados intentos, usar fallback ICAO

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

Para reportar issues o sugerencias, abre un [issue en GitHub](../../issues).

## Historial de Cambios

Ver [CAMBIOS_REALIZADOS.md](CAMBIOS_REALIZADOS.md) para detalles de todas las modificaciones.

---

**Versión**: 2.0  
**Última actualización**: Julio 2026  
**Autor**: Oscar

