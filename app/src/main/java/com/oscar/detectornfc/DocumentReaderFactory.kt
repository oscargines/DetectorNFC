package com.oscar.detectornfc

import android.nfc.Tag
import android.util.Log

/**
 * Factory para crear el lector de documentos apropiado según el tipo de documento.
 *
 * Arquitectura:
 * - EuropeanStructureReader: Lector universal PACE-CAN para documentos ICAO europeos
 * - DniReader: Lector específico para DNI español (requiere dniedroid)
 * - IcaoReader: Fallback ICAO genérico
 */
object DocumentReaderFactory {

    private const val TAG = "DocReaderFactory"

    sealed class ReaderResult {
        data class Success(val data: RawStructureData) : ReaderResult()
        data class FallbackNeeded(val reason: String, val suggestedReader: ReaderType) : ReaderResult()
        data class Error(val message: String, val recoverable: Boolean = true) : ReaderResult()
    }

    enum class ReaderType {
        EUROPEAN_UNIVERSAL,
        SPANISH_DNIE,
        ICAO_FALLBACK
    }

    data class ReaderConfig(
        val preferSpanishDni: Boolean = false,
        val allowFallback: Boolean = true,
        val maxRetries: Int = 3
    )

    fun createReader(
        tag: Tag,
        documentType: ClassificationResult? = null,
        config: ReaderConfig = ReaderConfig()
    ): DocumentReader {
        val readerType = determineBestReader(documentType, config)
        Log.i(TAG, "Creando lector de tipo: $readerType")

        return when (readerType) {
            ReaderType.SPANISH_DNIE -> SpanishDniDocumentReader(tag)
            ReaderType.ICAO_FALLBACK -> IcaoDocumentReader(tag)
            ReaderType.EUROPEAN_UNIVERSAL -> EuropeanUniversalDocumentReader(tag)
        }
    }

    private fun determineBestReader(
        documentType: ClassificationResult?,
        config: ReaderConfig
    ): ReaderType {
        if (documentType == null) {
            return if (config.preferSpanishDni && DniReader.areDependenciesAvailable()) {
                ReaderType.SPANISH_DNIE
            } else {
                ReaderType.EUROPEAN_UNIVERSAL
            }
        }

        return when {
            DocumentClassifier.isSpanishDni(documentType) && DniReader.areDependenciesAvailable() ->
                ReaderType.SPANISH_DNIE

            DocumentClassifier.isSpanishTie(documentType) ->
                ReaderType.EUROPEAN_UNIVERSAL

            DocumentClassifier.isEuropeanIdCard(documentType) ->
                ReaderType.EUROPEAN_UNIVERSAL

            documentType.documentType == DocumentType.PASSPORT ->
                ReaderType.EUROPEAN_UNIVERSAL

            documentType.documentType == DocumentType.GERMAN_EID ->
                ReaderType.EUROPEAN_UNIVERSAL

            else -> ReaderType.EUROPEAN_UNIVERSAL
        }
    }

    fun getFallbackChain(documentType: ClassificationResult?): List<ReaderType> {
        return when {
            documentType == null -> listOf(
                ReaderType.EUROPEAN_UNIVERSAL,
                ReaderType.ICAO_FALLBACK
            )

            DocumentClassifier.isSpanishDni(documentType) -> listOf(
                ReaderType.SPANISH_DNIE,
                ReaderType.EUROPEAN_UNIVERSAL,
                ReaderType.ICAO_FALLBACK
            )

            DocumentClassifier.isSpanishTie(documentType) -> listOf(
                ReaderType.EUROPEAN_UNIVERSAL,
                ReaderType.ICAO_FALLBACK
            )

            documentType.documentType == DocumentType.GERMAN_EID -> listOf(
                ReaderType.EUROPEAN_UNIVERSAL
            )

            else -> listOf(
                ReaderType.EUROPEAN_UNIVERSAL,
                ReaderType.ICAO_FALLBACK
            )
        }
    }
}

interface DocumentReader {
    fun read(can: String): RawStructureData
    val readerType: DocumentReaderFactory.ReaderType
}

class EuropeanUniversalDocumentReader(private val tag: Tag) : DocumentReader {
    override val readerType = DocumentReaderFactory.ReaderType.EUROPEAN_UNIVERSAL
    private val delegate = EuropeanStructureReader(tag)

    override fun read(can: String): RawStructureData {
        return delegate.readAllStructures(can)
    }
}

class SpanishDniDocumentReader(private val tag: Tag) : DocumentReader {
    override val readerType = DocumentReaderFactory.ReaderType.SPANISH_DNIE
    private val delegate = DniReader(tag)

    override fun read(can: String): RawStructureData {
        val parser = NfcDataParser()
        val rawNfcData = delegate.readDniSync(can)
        return parser.convertFromRawNfcData(rawNfcData)
    }
}

class IcaoDocumentReader(private val tag: Tag) : DocumentReader {
    override val readerType = DocumentReaderFactory.ReaderType.ICAO_FALLBACK
    private val delegate = IcaoReader(tag)

    override fun read(can: String): RawStructureData {
        val parser = NfcDataParser()
        val rawNfcData = delegate.readWithCan(can)
        return parser.convertFromRawNfcData(rawNfcData)
    }
}
