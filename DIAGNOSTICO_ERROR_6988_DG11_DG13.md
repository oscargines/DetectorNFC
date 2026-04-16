# SOLUCIÓN: DG11 y DG13 Error 6988

## Problema Identificado

**Error 6988**: "Objetos de datos incorrectos para el mensaje seguro"

Esto significa que jmulticard está intentando acceder a DG11/DG13 con la ruta incorrecta.

```
Ruta incorrecta (intentada):
00A404000B4D61737465722E46696C65  → "Master.File"

Resultado:
Error 6988 - Datos incorrectos
```

---

## Causa Técnica

En DNIe, DG11 y DG13 son **archivos especiales** que requieren acceso directo, no a través del Master.File estándar.

- **DG1-DG2**: Acceso estándar ✅
- **DG11-DG13**: Requieren acceso **directo con EF específico** ❌

---

## Solución

Necesitamos **acceder a los archivos con sus IDs de archivo específicos**:

### DG11 - Dirección Personal
```
Ruta correcta: 3F00 (MF) → 016B (DG11 - Dirección personal)
ID de archivo: 016B
Comando: A4-04-00-02-01-6B
```

### DG13 - Detalles Opcionales
```
Ruta correcta: 3F00 (MF) → 016D (DG13 - Detalles opcionales)
ID de archivo: 016D
Comando: A4-04-00-02-01-6D
```

---

## Implementación

Para leer correctamente DG11 y DG13, debemos:

1. **Cambiar la ruta de acceso**
2. **Usar los IDs de archivo específicos**
3. **Seleccionar el archivo correcto antes de leer**

### Código de Fix

En `DniReader.kt`, agregamos un método especial para DG11/DG13:

```kotlin
private fun tryReadDg11Or13DirectlyByFileId(card: Any, index: Int): ByteArray? {
    // DG11 usa EF ID: 01 6B
    // DG13 usa EF ID: 01 6D
    
    val fileId = when (index) {
        11 -> byteArrayOf(0x01, 0x6B)  // DG11
        13 -> byteArrayOf(0x01, 0x6D)  // DG13
        else -> return null
    }
    
    return try {
        // Intentar acceso directo por ID de archivo
        // Este es un workaround para el error 6988
        
        // Nota: Esto requiere acceso de bajo nivel a la tarjeta
        // que podría no estar disponible via jmulticard
        
        Log.d(TAG, "DG$index: Intentando acceso directo por fileId=${fileId.joinToString(",")}")
        null  // Por ahora retorna null
    } catch (e: Exception) {
        Log.e(TAG, "DG$index: Error en acceso directo: ${e.message}")
        null
    }
}
```

---

## Alternativas Posibles

### Opción 1: Usar métodos específicos de jmulticard

Verificar si jmulticard tiene métodos específicos para DG11/DG13:

```kotlin
// Alternativa: buscar métodos específicos
try {
    val method = card.javaClass.getMethod("getDg11Address") // Si existe
    method.invoke(card) as? ByteArray
} catch (e: Exception) {
    // No existe
}
```

### Opción 2: Acceso de bajo nivel a la tarjeta

Usar el objeto `PaceConnection` o `AbstractSmartCard` directamente con APDU personalizado.

### Opción 3: Usar otra librería

Si jmulticard no expone estos métodos, considerar:
- **jmrtd** - Librería MRTD estándar
- Acceso directo al canal PACE

---

## Estado Actual

```
✅ DG1: Funciona
✅ DG2: Funciona
❌ DG11: Error 6988 (Ruta incorrecta)
❌ DG13: Error 6988 (Ruta incorrecta)
❌ DG3-10, 14-16: UnsupportedOperationException (No existen en DNIe 3.0)
```

---

## Verificación Necesaria

¿Podemos hacer lo siguiente?

1. **Revisar qué versión de jmulticard usamos** (ya vimos 2.0)
2. **Verificar la documentación de jmulticard** para DG11/DG13
3. **Comprobar si existen métodos específicos** en la clase DnieNfc

Voy a investigar el código de jmulticard en `app/libs/jmulticard-2.0.jar` para encontrar cómo acceder correctamente a estos archivos.

---

## Conclusión

El problema es **específico del protocolo DNIe**: DG11 y DG13 requieren acceso especial que jmulticard podría no estar exponiendo correctamente a través del método estándar `getDgN()`.

**Necesitamos:**
1. Revisar si jmulticard tiene métodos específicos
2. O usar acceso de bajo nivel APDU
3. O considerar otra librería

¿Continuamos investigando dentro del JAR de jmulticard?

