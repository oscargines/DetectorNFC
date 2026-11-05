# Cambios: lectura de pasaportes ICAO 9303 y UX NFC

Fecha: 2026-05-11 (sesión de trabajo)

## Resumen
Se añade soporte de lectura de **pasaportes/eMRTD ICAO 9303** mediante un lector dedicado (JMRTD/SCUBA ya presentes), **aislado del flujo DNI español** (`DniReader`/dniedroid, sin modificaciones funcionales). Se implementa ruteo **(C) modo explícito + auto-ruteo**, persistencia de intentos accesible por ADB y mejoras de UX (flujo CAN→MRZ en pasaporte, teclado visible).

## Restricciones respetadas
- `DniReader.kt`: **sin cambios funcionales** (verificado por diff/hash durante la implementación). `dniedroid-release.aar` intacto.
- **Sin actualizar/sustituir librerías** ni cambiar exclusiones BC↔JMRTD (`jmrtd 0.7.31`, `scuba-sc-android 0.0.23`, BouncyCastle `1.65`).

## Nuevos componentes
- `app/src/main/java/com/oscar/detectornfc/passport/PassportIcaoReader.kt`
  - Lector ICAO 9303 dedicado (sin dniedroid). Acepta MRZ y/o CAN.
  - Autenticación: con MRZ intenta **PACE-MRZ → BAC** (PACE-CAN opcional); con solo CAN intenta **PACE-CAN**; sin PACE, BAC requiere MRZ.
  - Lee DG1, DG2, DG3, DG11, DG12, DG14, DG15, EF.COM/SOD; detecta documento por DG1; reintentos controlados.
  - Clasificación de errores APDU/SW (`6982/6985/6988/6A82/6A86/63Cx`), `TagLostException`/`IOException`; mensajes de causa raíz (p. ej. CAN no aceptado en pasaporte → sugerir MRZ).
- `app/src/main/java/com/oscar/detectornfc/nfc/NfcAttemptLogger.kt`
  - Persiste por intento `nfc_attempt_<ts>.json` + `scan_result_<ts>.json` en almacenamiento app-specific externo: `/sdcard/Android/data/com.oscar.detectornfc/files/nfc_logs/` (extraíble con ADB según permisos del dispositivo).
  - Registra modo, método de acceso, estado, error, análisis DG (resumen), detección de documento y excepciones (stack truncado); credenciales/MRZ **enmascaradas**.

## Cambios en componentes existentes
- `RawNfcData.kt`: añade `NfcReaderMethod.ICAO_PASSPORT`.
- `NfcDataParser.kt`: trata `ICAO_PASSPORT` como parseo ICAO (`parseIcaoRawData`).
- `AccessCredentials.kt` / `MrzCredentials`: añade `normalizedDocumentNumber()` (normaliza nº doc para BAC/PACE-MRZ: mayúsculas, sin espacios, sin relleno `<` final). (Incluye además compatibilidad `toMrzDate` con `yymmdd` presente en el árbol de trabajo.)
- `NFCScanActivity.kt`
  - Nuevos extras `EXTRA_SCAN_MODE` / `MODE_PASSPORT` / `MODE_DNI`.
  - Ruteo C: modo **PASAPORTE** (o auto MRZ-sin-CAN) → `PassportIcaoReader` (+ fallback ICAO genérico de dominio pasaporte), **nunca `DniReader`**. Modo DNI conserva el flujo/orden previo hacia `DniReader` y fallbacks.
  - Registra intentos con `NfcAttemptLogger`.
  - Si pasaporte falla con CAN (sin MRZ): modal con **Reintentar** (CAN), **Escanear con MRZ** (diálogo solicita nº doc/nacimiento/caducidad y reintenta NFC con MRZ) o **Cancelar**.
- `MainActivity.kt` / `app/src/main/res/layout/activity_main.xml`
  - Nuevo modo **Pasaporte (ICAO 9303, MRZ/BAC o PACE)**. En modo Pasaporte se solicita **solo CAN** (+ cámara) y “Continuar al escaneo”; si falla, se ofrece MRZ desde el flujo NFC.
  - Visibilidad CAN/MRZ ajustada por modo; envía `EXTRA_SCAN_MODE`.
  - Fix teclado/visibilidad CAN: escucha `ime()` en window insets (edge-to-edge), `ScrollView` `scroll_main`, y scroll al enfocar el campo CAN.
- `app/src/main/res/values/strings.xml`: textos de modo pasaporte, ayuda CAN/MRZ, y diálogo de reintento MRZ.
- `AndroidManifest.xml`: `MainActivity` con `android:windowSoftInputMode="adjustResize"`.

## Comportamiento esperado (pasaporte, p. ej. KGZ)
1. Seleccionar **Pasaporte** → introducir CAN (6 dígitos) → escanear NFC.
2. Si el chip rechaza CAN (habitual en pasaportes ICAO), se muestra modal para **reintentar con CAN** o **escanear con MRZ**.
3. Con MRZ correcto (`yymmdd`), el lector intenta **PACE-MRZ/BAC** y extrae DGs disponibles (DG1/DG2 prioritarios).
4. Revisar diagnóstico: `/sdcard/Android/data/com.oscar.detectornfc/files/nfc_logs/` y `adb logcat -s PassportIcaoReader NFCScanActivity NfcAttemptLogger`.

## Notas
- Muchos pasaportes **no aceptan PACE-CAN**; el camino principal es MRZ (BAC/PACE-MRZ).
- El acceso a `getExternalFilesDir` por ADB puede variar según dispositivo/políticas; los intentos también quedan en logcat.
- `compileDebugKotlin` verificado durante la implementación; `DniReader` sin diffs funcionales.
