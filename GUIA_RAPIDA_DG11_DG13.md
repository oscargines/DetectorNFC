# 🚀 Guía Rápida - DG11/DG13

## El Problema (en una frase)
"El código reporta DG11/DG13 como 'no presentes' cuando en realidad tienen datos"

## La Solución (en una frase)
"Mejorado el análisis de excepciones para distinguir entre 'no existe', 'error temporal', 'no soportado', etc."

## 5 Segundos: Qué Cambió

| Componente | Antes | Después |
|------------|-------|---------|
| Excepción | Ignorada | Analizadas |
| Clasificación | Todo = "no presente" | 5 categorías distintas |
| Logging | Mínimo | Stack trace completo |
| Información | Pérdida | Guardada con SHA-256 |

## 30 Segundos: Qué Debes Hacer

```bash
# 1. Compila
cd C:\Users\Oscar\Documents\Proyecto\DetectorNFG
./gradlew assembleDebug

# 2. Instala
adb install -r app/build/outputs/apk/debug/app-debug.apk

# 3. Lee DNI
# (Abre app, ingresa CAN, acerca DNI)

# 4. Mira logs
adb logcat | grep "DG11\|DG13"
```

## 2 Minutos: Interpretar Resultados

### En logcat esperas ver:

```
D DniReader: DG11 leído mediante getDg11 (2048 bytes)
D DniReader: DG13 leído mediante getDg13 (4096 bytes)
```

**Significa**: ✅ Están presentes y se leen correctamente

O:

```
D DniReader: DG11: getDg11 falló (IOException: No response from smart card)
D DniReader: DG11: Detalles de excepción en getDg11
D DniReader:   Causa: java.io.IOException
D DniReader:   Mensaje: No response from smart card
D DniReader:   StackTrace: ...
```

**Significa**: ⚠️ Hay un problema, el stack trace te dice dónde exactamente

## Los 5 Estados Posibles

```
DG11=READ_OK                    ✅ Se leyó correctamente
DG11=NOT_PRESENT_OR_NOT_ALLOWED ❌ No existe en el documento
DG11=READ_ERROR                 ⚠️ Error temporal (NFC, IO, etc)
DG11=ACCESS_DENIED              🔒 Acceso rechazado
DG11=UNSUPPORTED_ON_DOCUMENT    ℹ️ Método no implementado
```

## Stack Trace = Oro

Cuando veas un stack trace como este:

```
java.io.IOException: No response from smart card
    at es.gob.jmulticard.card.dnie.DnieNfc.readFile(DnieNfc.java:237)
    at es.gob.jmulticard.card.dnie.DnieNfc.getDg11(DnieNfc.java:89)
    at com.oscar.detectornfc.DniReader.tryReadDg(DniReader.kt:265)
```

Significa:
- **Línea 1**: ¿Qué pasó? (`IOException: No response`)
- **Línea 2-3**: ¿Dónde? (en jmulticard, métodos específicos)
- **Línea 4**: ¿Cómo llegamos? (desde nuestro código)

## Las 4 Funciones Nuevas (Técnico)

### 1. `unwrapException()`
Extrae la excepción REAL de dentro de `InvocationTargetException`

### 2. `logExceptionDetails()`
Loguea excepción + stack trace completo

### 3. Mejorado `tryReadDgWithAnalysis()`
Captura excepciones y las convierte en datos diagnósticos

### 4. Mejorado `DataGroupInfo.error()`
Clasifica excepciones en 5 categorías

## Archivos Clave

- `DniReader.kt` - Lectura NFC (modificado)
- `DataGroupInfo.kt` - Análisis forense (modificado)
- `CAMBIOS_REALIZADOS.md` - Lista detallada
- `PASOS_PRUEBA_DG11_DG13.md` - Instrucciones paso a paso

## Si Aún No Funciona

El stack trace te dirá:
1. **Excepción exacta** (IOException, UnsupportedOperationException, etc)
2. **Mensaje** (File not found, No response, Method not implemented, etc)
3. **Línea exacta** del código donde falla
4. **Método** que lanzó el error
5. **Call stack** completo

Con eso, puedo:
- Identificar si es bug de jmulticard
- Buscar workaround
- Ajustar protocolo PACE
- Detectar si necesita pasosAdicionales

## Cheat Sheet

```
Veo "READ_OK"?
  → Se leyó correctamente ✅

Veo "NOT_PRESENT_OR_NOT_ALLOWED"?
  → Este DNI no tiene ese DG ❌

Veo "READ_ERROR"?
  → Problema NFC temporal, intenta de nuevo ⚠️

Veo "UNSUPPORTED_ON_DOCUMENT"?
  → jmulticard no lo soporta para este tipo de doc ℹ️

Veo "IOException: No response"?
  → El chip no responde, problema NFC ⚠️

Veo "UnsupportedOperationException"?
  → El método no existe en la API ℹ️

Veo "SecurityException"?
  → Acceso denegado por seguridad 🔒
```

## Resumen

**Problema**: DG11/DG13 reportados como no presentes  
**Causa**: Excepciones no analizadas  
**Solución**: 5 mejoras + mejor logging  
**Resultado**: Sabes exactamente qué pasa  
**Próximo paso**: Prueba con un DNI real  

---

Listo para probar. ¿Necesitas ayuda con el siguiente paso?

