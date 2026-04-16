# Código Adaptado - Reutilizable de Atestados

## 1. DniData.kt - COPIA DIRECTA ✅

Este archivo es idéntico. Puedes copiarlo tal cual:

```kotlin
package com.oscar.detectornfc

data class DniData(
    val genero: String?,
    val nacionalidad: String?,
    val tipoDocumento: String?,
    val numeroDocumento: String?,
    val numeroSoporte: String?,
    val nombre: String?,
    val apellidos: String?,
    val nombrePadre: String?,
    val nombreMadre: String?,
    val fechaNacimiento: String?,
    val lugarNacimiento: String?,
    val domicilio: String?,
    val uid: String?,
    val can: String?,
    val error: String?
)
```

**Compatibilidad**: 100% ✅

---

## 2. NfcDataParser.kt - ADAPTADO ⚠️

**Cambios necesarios para BC jdk18on 1.78.1:**

### Cambio 1: Reemplazar parseDG1 completo

**ANTES (Atestados - ASN1ApplicationSpecific):**
```kotlin
val content = (root as? ASN1ApplicationSpecific)?.contents ?: bytes
```

**DESPUÉS (DetectorNFG - ASN1TaggedObject):**
```kotlin
private fun parseDG1(bytes: ByteArray): Map<String, String?> {
    val result = mutableMapOf<String, String?>()
    try {
        Log.d(TAG, "Datos crudos DG1: ${bytes.joinToString("") { "%02x".format(it) }}")
        val asn1InputStream = ASN1InputStream(ByteArrayInputStream(bytes))
        val root = asn1InputStream.readObject()
        Log.d(TAG, "Objeto ASN1 raíz: $root")

        // Extract MRZ using modern ASN.1 API
        val mrzBytes = extractMrzBytes(root)
        val mrzString = String(mrzBytes, Charset.forName("UTF-8")).trim()
        Log.d(TAG, "MRZ extraído (longitud=${mrzString.length}): $mrzString")

        // Parse MRZ (TD3 format, 88 characters)
        if (mrzString.length == 88) {
            val line1 = mrzString.substring(0, 44)
            val line2 = mrzString.substring(44, 88)

            result["docType"] = line1.substring(0, 2)
            result["issuingState"] = line1.substring(2, 5)
            result["docNumber"] = line1.substring(5, 14).replace("<", "")

            result["birthDate"] = line2.substring(0, 6)
            result["sex"] = line2.substring(7, 8)
            result["expiryDate"] = line2.substring(8, 14)
            result["nationality"] = line2.substring(14, 17)
            val nameParts = line2.substring(20).split("<<")
            result["surname"] = nameParts[0].replace("<", " ").trim()
            result["name"] = nameParts.getOrNull(1)?.replace("<", " ")?.trim()
        } else {
            Log.w(TAG, "MRZ tiene longitud inválida: ${mrzString.length}")
            Log.d(TAG, "MRZ bytes: ${mrzBytes.joinToString("") { "%02x".format(it) }}")
        }
        asn1InputStream.close()
    } catch (e: Exception) {
        Log.e(TAG, "Error al parsear DG1: ${e.message}", e)
    }
    Log.d(TAG, "Resultado DG1 parseado: $result")
    return result
}

private fun extractMrzBytes(root: ASN1Primitive?): ByteArray {
    return when (root) {
        is ASN1TaggedObject -> {
            // [APPLICATION 1] containing [APPLICATION 31]
            if (root.hasTagClass(BERTags.APPLICATION) && root.tagNo == 1) {
                val baseObj = root.baseObject?.toASN1Primitive()
                if (baseObj is ASN1TaggedObject && baseObj.hasTagClass(BERTags.APPLICATION) && baseObj.tagNo == 31) {
                    val innerBase = baseObj.baseObject?.toASN1Primitive()
                    (innerBase as? DEROctetString)?.octets ?: innerBase?.encoded ?: byteArrayOf()
                } else {
                    baseObj?.encoded ?: byteArrayOf()
                }
            } else {
                root.encoded
            }
        }
        is DEROctetString -> root.octets
        else -> root?.encoded ?: byteArrayOf()
    }
}
```

### Cambio 2: Actualizar parseDG13

**El parsing de campos funciona igual, solo actualizar ASN.1 API:**

```kotlin
private fun parseDG13(bytes: ByteArray): Map<String, String?> {
    val result = mutableMapOf<String, String?>()
    try {
        Log.d(TAG, "Datos crudos DG13: ${bytes.joinToString("") { "%02x".format(it) }}")
        val asn1InputStream = ASN1InputStream(ByteArrayInputStream(bytes))
        val root = asn1InputStream.readObject()
        Log.d(TAG, "Objeto ASN1 raíz DG13: $root")

        // Extract content using modern API
        val content = when {
            root is ASN1TaggedObject && root.hasTagClass(BERTags.APPLICATION) -> 
                root.baseObject?.toASN1Primitive()?.encoded ?: root.encoded
            else -> bytes
        }

        val contentStream = ASN1InputStream(ByteArrayInputStream(content))
        val sequence = contentStream.readObject() as? ASN1Sequence
            ?: throw IllegalStateException("DG13 root is not a SEQUENCE")

        sequence.objects.toList().forEachIndexed { index, obj ->
            val value = getStringFromASN1(obj as? ASN1Primitive)
            Log.d(TAG, "Elemento $index: $value")
            when (index) {
                0 -> result["surname1"] = value
                1 -> result["surname2"] = value
                2 -> result["name"] = value
                3 -> result["personalNumber"] = value
                4 -> result["birthDate"] = value
                5 -> result["nationality"] = value
                6 -> result["expirationDate"] = value
                7 -> result["docNumber"] = value
                8 -> result["sex"] = value
                9 -> result["birthPopulation"] = value
                10 -> result["birthProvince"] = value
                11 -> {
                    val parents = value?.split(" / ")
                    result["fatherName"] = parents?.getOrNull(0)
                    result["motherName"] = parents?.getOrNull(1)
                }
                12 -> result["streetAddress"] = value
                13 -> result["cityAddress"] = value
                14 -> result["cityAddress2"] = value
                15 -> result["provinceAddress"] = value
            }
        }

        result["birthPlace"] = listOfNotNull(
            result["birthPopulation"],
            result["birthProvince"]
        ).joinToString(", ")
        result["actualAddress"] = listOfNotNull(
            result["streetAddress"],
            result["cityAddress"],
            result["cityAddress2"],
            result["provinceAddress"]
        ).joinToString(", ")

        contentStream.close()
        asn1InputStream.close()
    } catch (e: Exception) {
        Log.e(TAG, "Error al parsear DG13: ${e.message}", e)
    }
    Log.d(TAG, "Resultado DG13 parseado: $result")
    return result
}

private fun getStringFromASN1(element: ASN1Primitive?): String? {
    return when (element) {
        is DEROctetString -> String(element.octets, Charset.forName("UTF-8")).trim()
        is ASN1Sequence -> element.toString().trim()
        is ASN1TaggedObject -> getStringFromASN1(element.baseObject?.toASN1Primitive())
        else -> element?.toString()?.trim()?.takeIf { it.isNotEmpty() }
    }.also { Log.d(TAG, "Extraído de ASN1 ($element): $it") }
}
```

### Cambio 3: Mantener formatDate igual

La función de formateo de fecha NO depende de BC, así que se copia tal cual (líneas 359-433 de Atestados).

---

## 3. Resumen de Cambios Necesarios

| Componente | Atestados | DetectorNFG | Cambio |
|------------|-----------|------------|--------|
| DniData.kt | ✅ Compatible | Copiar | Ninguno |
| parseDG1 | ❌ ASN1ApplicationSpecific | ✅ ASN1TaggedObject | Major |
| parseDG13 | ❌ ASN1ApplicationSpecific | ✅ ASN1TaggedObject | Major |
| getStringFromASN1 | Parcial | ✅ Actualizado | Minor |
| formatDate | ✅ Compatible | Copiar igual | Ninguno |
| parseRawData | ✅ Compatible | Copiar igual | Ninguno |

---

## 4. Imports Necesarios en DetectorNFG

```kotlin
import android.util.Log
import com.oscar.detectornfc.data.DniData
import com.oscar.detectornfc.data.RawNfcData
import org.bouncycastle.asn1.ASN1InputStream
import org.bouncycastle.asn1.ASN1Primitive
import org.bouncycastle.asn1.ASN1Sequence
import org.bouncycastle.asn1.ASN1TaggedObject
import org.bouncycastle.asn1.BERTags  // ⭐ NUEVO para BC moderno
import org.bouncycastle.asn1.DEROctetString
import java.io.ByteArrayInputStream
import java.nio.charset.Charset
```

---

## 5. Lo Que NO Se Puede Reutilizar

❌ **NfcReader.kt completo**
- Usa `es.gob.fnmt.dniedroid.help.Loader`
- Usa `de.tsenger.androsmex`
- Estas librerías no funcionan con jmulticard 2.0

✅ **Pero**: La estructura de lectura (try-catch, logging) es parecida

---

## Conclusión

**Reutilizable**: ~40% del código
- DniData.kt (100%)
- Lógica de parseo (70%, necesita actualizar ASN.1)
- Flujo general (80%)

**No reutilizable**: 60%
- NfcReader.kt (0%, librerías incompatibles)
- ASN.1 API antigua (0%, deprecated)

**Recomendación**: Integrar DniData + parsing actualizado en DetectorNFG

