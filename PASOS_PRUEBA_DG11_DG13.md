# Pasos para Probar Lectura de DG11 y DG13

## 1. Instalar APK Actualizada

```bash
cd C:\Users\Oscar\Documents\Proyecto\DetectorNFG
./gradlew assembleDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

## 2. Limpiar Logcat

```bash
adb logcat -c
```

## 3. Abrir la Aplicación

- Abre DetectorNFC en el dispositivo
- Asegúrate de que NFC esté activado
- Acepta permisos si se piden

## 4. Iniciar Lectura

- Presiona el botón de lectura NFC
- Acerca el DNI al dispositivo
- **Ingresa el CAN (6 dígitos del DNI frente)**
- Espera a que termine la lectura

## 5. Capturar Logcat Completo

```bash
adb logcat -d > dg_logcat.txt
```

O en tiempo real:

```bash
adb logcat | grep "DniReader"
```

## 6. Qué Buscar en los Logs

### Para DG1 y DG2 (deberían estar presentes):
```
D DniReader: DG1 leído mediante getDg1 (95 bytes)
D DniReader: DG2 leído mediante getDg2 (18547 bytes)
```

### Para DG11 y DG13 (es lo que queremos diagnosticar):

**Opción A - Se leen correctamente:**
```
D DniReader: DG11 leído mediante getDg11 (XXXX bytes)
D DniReader: DG13 leído mediante getDg13 (XXXX bytes)
```
→ Significa que el DNI SÍ tiene estos DGs

**Opción B - Error de lectura:**
```
D DniReader: DG11: getDg11 falló (IOException: No response from smart card)
D DniReader: DG11: Detalles de excepción en getDg11
D DniReader:   Causa: java.io.IOException
D DniReader:   Mensaje: No response from smart card
D DniReader:   StackTrace: ...
```
→ Hay un problema de comunicación NFC o el DNI no tiene DG11

**Opción C - Método no soportado:**
```
D DniReader: DG11: getDg11 falló (UnsupportedOperationException: ...)
D DniReader: DG11: Detalles de excepción en getDg11
D DniReader:   Causa: java.lang.UnsupportedOperationException
...
```
→ jmulticard no implementa getDg11() para este tipo de documento

### Línea importante (al final):
```
I DniReader: DG Analysis: DG1=READ_OK, DG2=READ_OK, DG11=???, DG13=???, ...
```

Donde `???` puede ser:
- `READ_OK` - Se leyó correctamente
- `NOT_PRESENT_OR_NOT_ALLOWED` - No existe en el documento
- `READ_ERROR` - Error temporal
- `ACCESS_DENIED` - Acceso rechazado
- `UNSUPPORTED_ON_DOCUMENT` - No soportado por jmulticard

## 7. Envíame

Por favor, comparte:
1. **El logcat completo** (`dg_logcat.txt`)
2. **Particular mente las líneas con DG11 y DG13**
3. **La línea del `DG Analysis` final**
4. **Cualquier error visible en pantalla**

Ejemplo perfecto:
```
D DniReader: DG1 leído mediante getDg1 (95 bytes)
D DniReader: DG2 leído mediante getDg2 (18547 bytes)
D DniReader: DG3: getDg3 falló (IOException: File not found)
D DniReader: DG3: Detalles de excepción en getDg3
D DniReader:   Causa: java.io.IOException
D DniReader:   Mensaje: File not found
D DniReader:   StackTrace: [primeros 500 caracteres del stack trace]
...
D DniReader: DG11 leído mediante getDg11 (2048 bytes)
D DniReader: DG12: getDg12 falló (UnsupportedOperationException: Method not implemented)
D DniReader: DG13 leído mediante getDg13 (4096 bytes)
...
I DniReader: DG Analysis: DG1=READ_OK, DG2=READ_OK, DG3=READ_ERROR, ..., DG11=READ_OK, DG12=UNSUPPORTED_ON_DOCUMENT, DG13=READ_OK, ...
```

## Troubleshooting

### Si la app no instala:
```bash
adb uninstall com.oscar.detectornfc
adb install app/build/outputs/apk/debug/app-debug.apk
```

### Si no ves logs:
```bash
# Filtrar solo por el tag DniReader
adb logcat DniReader:D *:S

# O buscar solo líneas con "DG"
adb logcat | grep "DG"
```

### Si el DNI no es detectado:
- Asegúrate de que NFC esté activado en el dispositivo
- Prueba de nuevo colocando el DNI diferente angle/distancia
- Intenta con otro dispositivo si es posible

