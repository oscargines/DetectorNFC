package com.oscar.detectornfc

import android.util.Log
import org.bouncycastle.asn1.ASN1InputStream
import org.bouncycastle.asn1.ASN1ObjectIdentifier
import org.bouncycastle.asn1.ASN1Primitive
import org.bouncycastle.asn1.ASN1Sequence
import org.bouncycastle.asn1.ASN1Set
import org.bouncycastle.asn1.BERTaggedObject
import org.bouncycastle.asn1.DERApplicationSpecific
import org.bouncycastle.asn1.DEROctetString
import org.bouncycastle.asn1.DERUTF8String
import org.bouncycastle.asn1.DERPrintableString
import org.bouncycastle.asn1.DERIA5String
import org.bouncycastle.asn1.ASN1Integer
import org.bouncycastle.asn1.DERBitString
import java.io.ByteArrayInputStream
import java.io.IOException

object TLVStructureAnalyzer {

    private const val TAG = "TLVAnalyzer"

    private val ICAO_TAG_NAMES: Map<Int, String> = mapOf(
        0x01 to "DG1 Template (MRZ)",
        0x02 to "DG2 Template (Face)",
        0x03 to "DG3 Template (Fingerprint)",
        0x04 to "DG4 Template (Iris)",
        0x05 to "DG5 Template (Displayed Portrait)",
        0x06 to "DG6 Template (Reserved)",
        0x07 to "DG7 Template (Signature)",
        0x08 to "DG8 Template (Data of Others 1)",
        0x09 to "DG9 Template (Data of Others 2)",
        0x0A to "DG10 Template (Data of Others 3)",
        0x0B to "DG11 Template (Additional Personal Details)",
        0x0C to "DG12 Template (Additional Document Details)",
        0x0D to "DG13 Template (Optional Details)",
        0x0E to "DG14 Template (Security Options)",
        0x0F to "DG15 Template (Active Authentication)",
        0x10 to "DG16 Template (Persons to Notify)",
        0x61 to "Tag 0x61 (DG1 Container)",
        0x75 to "Tag 0x75 (DG2 Face Container)",
        0x63 to "Tag 0x63 (DG3 Fingerprint Container)",
        0x76 to "Tag 0x76 (DG4 Iris Container)",
        0x65 to "Tag 0x65 (DG5 Container)",
        0x66 to "Tag 0x66 (DG6 Container)",
        0x67 to "Tag 0x67 (DG7 Signature Container)",
        0x68 to "Tag 0x68 (DG8 Container)",
        0x69 to "Tag 0x69 (DG9 Container)",
        0x6A to "Tag 0x6A (DG10 Container)",
        0x6B to "Tag 0x6B (DG11 Container)",
        0x6C to "Tag 0x6C (DG12 Container)",
        0x6D to "Tag 0x6D (DG13 Container)",
        0x6E to "Tag 0x6E (DG14 Container)",
        0x6F to "Tag 0x6F (DG15 Container)",
        0x70 to "Tag 0x70 (DG16 Container)",
        0x5F1F to "MRZ Data",
        0x5F20 to "Surname / Primary Identifier",
        0x5F21 to "Given Names / Secondary Identifiers",
        0x5F22 to "Date of Birth",
        0x5F23 to "Date of Expiry",
        0x5F24 to "Document Number",
        0x5F25 to "Nationality",
        0x5F26 to "Sex / Gender",
        0x5F27 to "Place of Birth",
        0x5F28 to "Permanent Address",
        0x5F29 to "Professional Activity / Title",
        0x5F2A to "Personal Summary",
        0x5F2B to "Other Names",
        0x5F2C to "Other Valid TD Numbers",
        0x5F2D to "Custody Information",
        0x5F2E to "Proof of Citizenship",
        0x5F2F to "Telephone",
        0x5F30 to "Email",
        0x5F31 to "Municipality / City",
        0x5F32 to "Province / State",
        0x5F33 to "Postal Code",
        0x5F34 to "Country of Birth",
        0x5F35 to "Country of Residence",
        0x5F36 to "Father's Name",
        0x5F37 to "Mother's Name",
        0x5F38 to "Spouse's Name",
        0x5F40 to "Image Data (JPEG/JP2)",
        0x5F41 to "Biometric Data Template",
        0x5F42 to "Finger Minutiae Data",
        0x5F43 to "Iris Image Data",
        0x5F44 to "Signature / Usual Mark Image",
        0x7F60 to "Chip Authentication Public Key Info",
        0x7F61 to "Active Authentication Public Key Info",
        0x7F62 to "Chip Authentication Info",
        0x7F63 to "Terminal Authentication Info",
        0x7F49 to "PACE Info",
        0x7F4A to "PACE Domain Parameter Info"
    )

    private val OID_NAMES: Map<String, String> = mapOf(
        "0.4.0.127.0.7.2.2.1" to "PACE-CAN",
        "0.4.0.127.0.7.2.2.2" to "PACE-MRZ",
        "0.4.0.127.0.7.2.2.3" to "PACE-PIN",
        "0.4.0.127.0.7.2.2.4" to "PACE-PUK",
        "0.4.0.127.0.7.2.2.5" to "PACE-CAM",
        "1.2.840.113549.1.1.1" to "RSA (PKCS#1)",
        "1.2.840.113549.1.1.11" to "SHA-256 with RSA",
        "1.3.6.1.4.1.311.10.3.12" to "SHA-256 (CE)",
        "1.3.14.3.2.26" to "SHA-1",
        "2.16.840.1.101.3.4.2.1" to "SHA-256"
    )

    fun analyze(bytes: ByteArray, dgNumber: Int): DGTLVResult {
        return try {
            val nodes = parseTLV(bytes)
            DGTLVResult(
                dgNumber = dgNumber,
                rawSize = bytes.size,
                rootNodes = nodes,
                hasValidASN1 = true
            )
        } catch (e: Exception) {
            Log.w(TAG, "DG$dgNumber: falló análisis ASN.1 — ${e.message}")
            DGTLVResult(
                dgNumber = dgNumber,
                rawSize = bytes.size,
                rootNodes = emptyList(),
                hasValidASN1 = false,
                parseError = "${e.javaClass.simpleName}: ${e.message}"
            )
        }
    }

    private fun parseTLV(bytes: ByteArray): List<TLVNode> {
        if (bytes.isEmpty()) return emptyList()
        val nodes = mutableListOf<TLVNode>()
        val input = ASN1InputStream(ByteArrayInputStream(bytes))
        try {
            var obj = input.readObject()
            while (obj != null) {
                val node = parseTag(obj, 0)
                if (node != null) nodes.add(node)
                obj = input.readObject()
            }
        } catch (e: IOException) {
            return listOf(tryBruteForceFallback(bytes))
        }
        return nodes
    }

    private fun parseTag(obj: Any, offset: Int): TLVNode? {
        return when (obj) {
            is BERTaggedObject -> parseTagged(obj, offset)
            is DERApplicationSpecific -> {
                val tagNo = obj.applicationTag.coerceAtMost(0xFF)
                val tagHex = "%02X".format(tagNo)
                val name = ICAO_TAG_NAMES[tagNo] ?: "Tag 0x$tagHex (Application)"
                val contents = obj.contents
                val children = tryParseChildren(contents)
                TLVNode(
                    tagClass = "Application",
                    tagNumber = tagNo,
                    tagHex = tagHex,
                    tagName = name,
                    isConstructed = obj.isConstructed,
                    length = contents.size,
                    offset = offset,
                    valueHex = null,
                    valueAscii = null,
                    valueDecoded = null,
                    children = children
                )
            }
            is ASN1Sequence -> {
                val children = mutableListOf<TLVNode>()
                for ((i, item) in obj.toArray().withIndex()) {
                    val child = parseTag(item, offset + i * 8)
                    if (child != null) children.add(child)
                }
                TLVNode(
                    tagClass = "Universal",
                    tagNumber = 0x30,
                    tagHex = "30",
                    tagName = "SEQUENCE",
                    isConstructed = true,
                    length = obj.encoded.size,
                    offset = offset,
                    valueHex = null,
                    valueAscii = null,
                    valueDecoded = null,
                    children = children
                )
            }
            is ASN1Set -> {
                val children = mutableListOf<TLVNode>()
                for ((i, item) in obj.toArray().withIndex()) {
                    val child = parseTag(item, offset + i * 8)
                    if (child != null) children.add(child)
                }
                TLVNode(
                    tagClass = "Universal",
                    tagNumber = 0x31,
                    tagHex = "31",
                    tagName = "SET",
                    isConstructed = true,
                    length = obj.encoded.size,
                    offset = offset,
                    valueHex = null,
                    valueAscii = null,
                    valueDecoded = null,
                    children = children
                )
            }
            is DEROctetString -> {
                val valBytes = obj.octets
                val children = tryParseChildren(valBytes)
                TLVNode(
                    tagClass = "Universal",
                    tagNumber = 0x04,
                    tagHex = "04",
                    tagName = "OCTET STRING",
                    isConstructed = children.isNotEmpty(),
                    length = valBytes.size,
                    offset = offset,
                    valueHex = if (children.isEmpty()) toHexPreview(valBytes) else null,
                    valueAscii = if (children.isEmpty()) toAsciiPreview(valBytes) else null,
                    valueDecoded = null,
                    children = children
                )
            }
            is DERUTF8String -> {
                TLVNode(
                    tagClass = "Universal",
                    tagNumber = 0x0C,
                    tagHex = "0C",
                    tagName = "UTF8String",
                    isConstructed = false,
                    length = obj.string.length,
                    offset = offset,
                    valueHex = null,
                    valueAscii = obj.string,
                    valueDecoded = obj.string
                )
            }
            is DERPrintableString -> {
                TLVNode(
                    tagClass = "Universal",
                    tagNumber = 0x13,
                    tagHex = "13",
                    tagName = "PrintableString",
                    isConstructed = false,
                    length = obj.string.length,
                    offset = offset,
                    valueHex = null,
                    valueAscii = obj.string,
                    valueDecoded = obj.string
                )
            }
            is DERIA5String -> {
                TLVNode(
                    tagClass = "Universal",
                    tagNumber = 0x16,
                    tagHex = "16",
                    tagName = "IA5String",
                    isConstructed = false,
                    length = obj.string.length,
                    offset = offset,
                    valueHex = null,
                    valueAscii = obj.string,
                    valueDecoded = obj.string
                )
            }
            is ASN1Integer -> {
                val value = obj.value.toString(10)
                TLVNode(
                    tagClass = "Universal",
                    tagNumber = 0x02,
                    tagHex = "02",
                    tagName = "INTEGER",
                    isConstructed = false,
                    length = obj.encoded.size,
                    offset = offset,
                    valueHex = obj.value.toString(16),
                    valueAscii = value,
                    valueDecoded = value
                )
            }
            is ASN1ObjectIdentifier -> {
                val oid = obj.id
                val name = OID_NAMES[oid] ?: oid
                TLVNode(
                    tagClass = "Universal",
                    tagNumber = 0x06,
                    tagHex = "06",
                    tagName = "OID",
                    isConstructed = false,
                    length = oid.length,
                    offset = offset,
                    valueHex = null,
                    valueAscii = oid,
                    valueDecoded = name
                )
            }
            is DERBitString -> {
                TLVNode(
                    tagClass = "Universal",
                    tagNumber = 0x03,
                    tagHex = "03",
                    tagName = "BIT STRING",
                    isConstructed = false,
                    length = obj.bytes.size,
                    offset = offset,
                    valueHex = toHexPreview(obj.bytes),
                    valueAscii = null,
                    valueDecoded = null
                )
            }
            else -> {
                val enc = try {
                    (obj as? ASN1Primitive)?.encoded
                } catch (e: IOException) {
                    null
                } ?: ByteArray(0)
                TLVNode(
                    tagClass = "Universal",
                    tagNumber = 0,
                    tagHex = "??",
                    tagName = "${obj.javaClass.simpleName}",
                    isConstructed = false,
                    length = enc.size,
                    offset = offset,
                    valueHex = toHexPreview(enc),
                    valueAscii = null,
                    valueDecoded = null
                )
            }
        }
    }

    private fun parseTagged(tagged: BERTaggedObject, offset: Int): TLVNode? {
        val tagNo = tagged.tagNo
        val tagHex = "%02X".format(tagNo)
        val tClass = when {
            tagged.tagNo < 0x1F && tagged.encoded[0].toInt() and 0xC0 == 0x80 -> "Context"
            tagged.tagNo < 0x1F && tagged.encoded[0].toInt() and 0xC0 == 0x40 -> "Application"
            else -> "Context"
        }
        val name = ICAO_TAG_NAMES[tagNo] ?: "Tag 0x$tagHex"
        val obj = tagged.getObject()
        val children = mutableListOf<TLVNode>()
        var valueHex: String? = null
        var valueAscii: String? = null
        var valueDecoded: String? = null
        var isCons = true

        if (obj != null) {
            when (obj) {
                is ASN1Sequence, is ASN1Set -> {
                    val array = (obj as? ASN1Sequence)?.toArray()
                        ?: (obj as? ASN1Set)?.toArray() ?: emptyArray()
                    for ((i, item) in array.withIndex()) {
                        val c = parseTag(item, offset + (i + 1) * 8)
                        if (c != null) children.add(c)
                    }
                }
                is DEROctetString -> {
                    isCons = false
                    val octets = obj.octets
                    valueHex = toHexPreview(octets)
                    valueAscii = toAsciiPreview(octets)
                    val subChildren = tryParseChildren(octets)
                    if (subChildren.isNotEmpty()) {
                        children.addAll(subChildren)
                    } else {
                        valueDecoded = detectContent(octets, tagNo)
                    }
                }
                is DERUTF8String -> {
                    isCons = false
                    valueAscii = obj.string
                    valueDecoded = obj.string
                }
                is DERPrintableString -> {
                    isCons = false
                    valueAscii = obj.string
                    valueDecoded = obj.string
                }
                is DERIA5String -> {
                    isCons = false
                    valueAscii = obj.string
                    valueDecoded = obj.string
                }
                is ASN1Integer -> {
                    isCons = false
                    valueDecoded = obj.value.toString(10)
                    valueHex = obj.value.toString(16)
                    valueAscii = valueDecoded
                }
                is ASN1ObjectIdentifier -> {
                    isCons = false
                    val oid = obj.id
                    valueDecoded = OID_NAMES[oid] ?: oid
                    valueAscii = oid
                }
                is DERBitString -> {
                    isCons = false
                    valueHex = toHexPreview(obj.bytes)
                }
                else -> {
                    val enc = obj.encoded
                    isCons = false
                    valueHex = toHexPreview(enc)
                    valueAscii = toAsciiPreview(enc)
                }
            }
        }

        return TLVNode(
            tagClass = tClass,
            tagNumber = tagNo,
            tagHex = tagHex,
            tagName = name,
            isConstructed = isCons || children.isNotEmpty(),
            length = tagged.encoded.size,
            offset = offset,
            valueHex = valueHex,
            valueAscii = valueAscii,
            valueDecoded = valueDecoded,
            children = children
        )
    }

    private fun tryParseChildren(bytes: ByteArray): List<TLVNode> {
        return try {
            parseTLV(bytes)
        } catch (e: Exception) {
            emptyList()
        }
    }

    private fun tryBruteForceFallback(bytes: ByteArray): TLVNode {
        return TLVNode(
            tagClass = "???",
            tagNumber = 0,
            tagHex = "??",
            tagName = "(ASN.1 parse failed — raw dump)",
            isConstructed = false,
            length = bytes.size,
            offset = 0,
            valueHex = toHexPreview(bytes),
            valueAscii = toAsciiPreview(bytes),
            valueDecoded = null
        )
    }

    private fun detectContent(bytes: ByteArray, tagNo: Int): String? {
        return when {
            bytes.size in 6..8 && isDigitOrHex(bytes) -> parseMRZDate(bytes)
            tagNo == 0x5F22 || tagNo == 0x5F23 -> parseMRZDate(bytes)
            tagNo == 0x5F26 -> when (bytes[0].toInt() and 0xFF) {
                'M'.code -> "Masculino"
                'F'.code -> "Femenino"
                else -> null
            }
            else -> null
        }
    }

    private fun isDigitOrHex(bytes: ByteArray): Boolean {
        return bytes.all { b ->
            val c = b.toInt() and 0xFF
            (c in 48..57) || (c in 65..70) || (c in 97..102) || c == '<'.code
        }
    }

    private fun parseMRZDate(bytes: ByteArray): String? {
        val raw = bytes.decodeToString()
        return if (raw.length >= 6 && raw.take(6).all { it.isDigit() || it == '-' }) {
            val digits = raw.take(6)
            if (digits.length == 6) {
                val yy = digits.substring(0, 2)
                val mm = digits.substring(2, 4)
                val dd = digits.substring(4, 6)
                try {
                    val year = if (yy.toInt() > 30) "19$yy" else "20$yy"
                    val month = monthName(mm.toInt())
                    "$dd de $month de $year"
                } catch (e: NumberFormatException) { null }
            } else null
        } else null
    }

    private fun monthName(m: Int) = when (m) {
        1 -> "enero"; 2 -> "febrero"; 3 -> "marzo"; 4 -> "abril"
        5 -> "mayo"; 6 -> "junio"; 7 -> "julio"; 8 -> "agosto"
        9 -> "septiembre"; 10 -> "octubre"; 11 -> "noviembre"; 12 -> "diciembre"
        else -> "?"
    }

    private fun toHexPreview(bytes: ByteArray, max: Int = 80): String {
        val count = minOf(bytes.size, max)
        val preview = bytes.take(count).joinToString(" ") { "%02X".format(it) }
        return if (bytes.size > max) "$preview ..." else preview
    }

    private fun toAsciiPreview(bytes: ByteArray, max: Int = 200): String? {
        val sb = StringBuilder()
        val count = minOf(bytes.size, max)
        for (i in 0 until count) {
            val c = bytes[i].toInt() and 0xFF
            sb.append(if (c in 32..126) c.toChar() else '.')
        }
        val result = sb.toString().replace(Regex("\\.+"), ".").trim('.')
        return if (result.any { it.isLetterOrDigit() }) result else null
    }
}
