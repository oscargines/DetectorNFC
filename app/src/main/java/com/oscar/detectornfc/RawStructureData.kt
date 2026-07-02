package com.oscar.detectornfc

data class RawStructureData(
    val uid: String?,
    val can: String?,
    val sessionStatus: NfcSessionStatus,
    val sessionError: String? = null,
    val readerMethod: String = NfcReaderMethod.EUROPEAN_STRUCTURE.name,
    val fallbackUsed: Boolean = false,
    val documentDetection: DocumentDetection? = null,
    val dgRawBytes: Map<Int, ByteArray?> = emptyMap(),
    val dgAnalysis: Map<Int, DataGroupInfo> = emptyMap(),
    val dgTLV: Map<Int, DGTLVResult> = emptyMap(),
    val efCom: EFComData? = null,
    val efSod: SODData? = null,
    val efCardAccess: CardAccessData? = null,
    val efCardSecurity: CardSecurityData? = null,
    val scanTimestamp: Long = System.currentTimeMillis()
)

data class DGTLVResult(
    val dgNumber: Int,
    val rawSize: Int,
    val rootNodes: List<TLVNode>,
    val hasValidASN1: Boolean,
    val parseError: String? = null
)

data class TLVNode(
    val tagClass: String,
    val tagNumber: Int,
    val tagHex: String,
    val tagName: String,
    val isConstructed: Boolean,
    val length: Int,
    val offset: Int,
    val valueHex: String?,
    val valueAscii: String?,
    val valueDecoded: String?,
    val children: List<TLVNode> = emptyList()
)

data class DocumentDetection(
    val documentType: String,
    val countryCode: String,
    val countryName: String,
    val architecture: String,
    val mrzRawLines: List<String>? = null,
    val supportedProtocols: List<String> = emptyList()
)

data class EFComData(
    val ldsVersion: String?,
    val unicodeVersion: String?,
    val dataGroupsPresent: List<Int> = emptyList()
)

data class SODData(
    val rawHash: String?,
    val digestAlgorithm: String?,
    val signatureAlgorithm: String?,
    val certificateIssuer: String?,
    val dataGroupHashes: Map<Int, String>? = null
)

data class CardAccessData(
    val paceSupported: Boolean = false,
    val paceAlgorithm: List<String> = emptyList(),
    val chipAuthenticationSupported: Boolean = false,
    val terminalAuthenticationSupported: Boolean = false
)

data class CardSecurityData(
    val chipAuthenticationPublicKeySize: Int? = null,
    val terminalAuthenticationRequired: Boolean? = null
)
