# DetectorNFC

Una aplicación Android avanzada para leer y procesar datos de documentos de identidad (DNI electrónico, pasaportes) a través de NFC, con soporte para estándares españoles (PACE-CAN) e internacionales (ICAO/JMRTD).

## 🎯 Características

- **Lectura de DNI Electrónico Español**: Soporte completo para DNI-e mediante PACE-CAN con la librería oficial DNIedroid (CNP-FNMT)
- **Fallback ICAO/JMRTD**: Compatibilidad con pasaportes y documentos de identidad internacionales
- **Análisis de DataGroups**: Detección y análisis automático de todos los grupos de datos (DG1-DG16)
- **Decodificación de Imágenes**: Soporte nativo para imágenes JPEG-2000 (JP2) usando OpenJPEG 2.5.2
- **Cryptografía Robusta**: Integración con BouncyCastle para validación de certificados y firma digital
- **Manejo de Errores Avanzado**: Análisis detallado de fallos de lectura con propagación de metadatos

## 🛠️ Requisitos

- **Android**: API 31+ (Android 12+)
- **Compilación**: Android Gradle Plugin 8.x
- **Java**: JDK 11+
- **Kotlin**: Soporte completo

## 📦 Compilación

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

## 📥 Descarga

### APK Release
Descarga la última versión compilada de la aplicación:
- **[DetectorNFC v1.0 - Release APK](../../releases/v1.0)** (Última versión)

> **Nota**: Los APK compilados están disponibles en la sección [Releases](../../releases) del repositorio.

## 🏗️ Arquitectura

```
DetectorNFC/
├── app/
│   ├── src/
│   │   ├── main/
│   │   │   ├── java/com/oscar/detectornfc/
│   │   │   │   ├── NFCScanActivity          # Actividad principal
│   │   │   │   ├── DniReader                # Lectura de DNI-e
│   │   │   │   ├── NfcDataParser            # Parseo de datos
│   │   │   │   └── ...
│   │   │   ├── cpp/                         # Código nativo OpenJPEG
│   │   │   └── AndroidManifest.xml
│   │   ├── test/                            # Unit tests
│   │   └── androidTest/                     # Instrumented tests
│   ├── libs/
│   │   ├── dniedroid-release.aar            # CNP-FNMT DNI-e oficial
│   │   ├── jmulticard-2.0.jar
│   │   └── [BouncyCastle & OpenJPEG]
│   └── build.gradle.kts
└── gradle/
    ├── libs.versions.toml                   # Gestión de dependencias
    └── wrapper/
```

### Flujo de Lectura NFC

1. **Detección**: `NFCScanActivity.onNewIntent()` captura el tag NFC
2. **Lectura**: `DniReader.readDniSync()` abre conexión PACE-CAN
3. **Análisis**: `readDataGroupsFromCard()` itera sobre DG1..DG16
4. **Parsing**: `NfcDataParser.parseRawData()` procesa y formatea datos
5. **Presentación**: `ResultsActivity` muestra resultados al usuario

## 📋 Dependencias Principales

| Dependencia | Versión | Propósito |
|---|---|---|
| `dniedroid` | Release AAR | Lectura oficial DNI-e (PACE-CAN, DG1, DG11, DG13) |
| `jmrtd` | 0.7.31 | Fallback ICAO/JMRTD para documentos internacionales |
| `bouncycastle-prov` | 1.58.0 | Criptografía y validación de certificados |
| `openjpeg` | 2.5.2 | Decodificación de imágenes JPEG-2000 (compilado nativamente) |

## 🧪 Tests

La aplicación incluye suite completa de tests:

```bash
# Ejecutar todos los tests unitarios
./gradlew test

# Ejecutar tests con reporte detallado
./gradlew test --info
```

### Cobertura de Tests
- **NfcDataParserTest**: Validación de parseo de datos, manejo de errores y formatos de fecha
- **Casos de Uso**: Sesiones fallidas, lecturas parciales, fallback ICAO

## 🔧 Configuración

### Habilitar NFC en AndroidManifest.xml
```xml
<uses-permission android:name="android.permission.NFC" />
<uses-feature android:name="android.hardware.nfc" android:required="true" />
```

### Variables de Entorno (gradle.properties)
```properties
# Usar valores por defecto en local.properties
org.gradle.jvmargs=-Xmx4096m
```

## 🐛 Troubleshooting

### Error 6988 (DG11/DG13)
Si se produce el error 6988 al leer DG11/DG13:
1. Verificar que CAN es correcto (6 dígitos)
2. Usar el fallback ICAO si DNI-e falla
3. Revisar `DIAGNOSTICO_ERROR_6988_DG11_DG13.md`

### Fallo de Lectura de Imagen
- Validar formato JPEG-2000 (JP2)
- Verificar que OpenJPEG está compilado correctamente (CMake)

## 📄 Licencias

- **dniedroid**: © CNP-FNMT (Licencia específica incluida)
- **jmulticard**: © Dirección General de la Policía Nacional (LGPL 2.1)
- **BouncyCastle**: Licencia MIT
- **OpenJPEG**: Licencia BSD

## 🤝 Contribuciones

Las contribuciones son bienvenidas. Por favor:

1. Fork el repositorio
2. Crea una rama para tu feature (`git checkout -b feature/AmazingFeature`)
3. Commit tus cambios (`git commit -m 'Add some AmazingFeature'`)
4. Push a la rama (`git push origin feature/AmazingFeature`)
5. Abre un Pull Request

## 📞 Soporte

Para reportar issues o sugerencias, abre un [issue en GitHub](../../issues).

## 📝 Historial de Cambios

Ver [CAMBIOS_REALIZADOS.md](CAMBIOS_REALIZADOS.md) para detalles de todas las modificaciones.

---

**Versión**: 1.0  
**Última actualización**: Abril 2026  
**Autor**: Oscar

