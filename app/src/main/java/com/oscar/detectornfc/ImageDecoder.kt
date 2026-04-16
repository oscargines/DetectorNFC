package com.oscar.detectornfc

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Log

/**
 * Decodificador unificado de imágenes biométricas para documentos ICAO/MRTD.
 *
 * Selecciona automáticamente el motor de decodificación según el formato
 * detectado por [ImageFormatDetector]:
 *
 *  - **JPEG / PNG** → [BitmapFactory] (nativo Android, ruta rápida)
 *  - **JP2 / J2K**  → OpenJPEG 2.5.2 via JNI (`libjp2jni.so` compilada desde src/main/cpp)
 *  - **UNKNOWN**    → fallback a [BitmapFactory]
 *
 * La librería nativa `jp2jni` se carga automáticamente al primer acceso.
 * Si el dispositivo no soporta la ABI o falla el enlace, el decodificador JP2
 * retorna null sin lanzar excepciones.
 */
object ImageDecoder {

    private const val TAG = "ImageDecoder"

    /** true si libjp2jni.so se cargó correctamente en este proceso. */
    private val nativeLibLoaded: Boolean by lazy {
        try {
            System.loadLibrary("jp2jni")
            Log.i(TAG, "libjp2jni.so cargada OK (OpenJPEG 2.5.2 nativo)")
            true
        } catch (e: UnsatisfiedLinkError) {
            Log.w(TAG, "libjp2jni.so no disponible: ${e.message}")
            false
        }
    }

    /**
     * Función nativa expuesta por jp2_jni.cpp.
     * Decodifica bytes JP2/J2K y devuelve un [Bitmap] ARGB_8888, o null si falla.
     */
    private external fun nativeDecodeJp2(jp2Data: ByteArray): Bitmap?

    /**
     * Decodifica [bytes] a [Bitmap].
     * Devuelve un [DecodeResult] con el bitmap (o `null`) y el formato detectado.
     */
    fun decode(bytes: ByteArray): DecodeResult {
        if (bytes.isEmpty()) return DecodeResult(null, ImageFormat.UNKNOWN)
        val format = ImageFormatDetector.detect(bytes)
        Log.d(TAG, "decode() → formato=$format bytes=${bytes.size}")
        val bitmap = when (format) {
            ImageFormat.JP2 -> decodeJp2(bytes)
            else            -> decodeBitmapFactory(bytes, format)
        }
        return DecodeResult(bitmap, format)
    }

    // ──────────────────────────────────────────────────────────────────────
    //  OpenJPEG nativo (JP2 / J2K)
    // ──────────────────────────────────────────────────────────────────────

    private fun decodeJp2(bytes: ByteArray): Bitmap? {
        if (!nativeLibLoaded) {
            Log.w(TAG, "JP2: librería nativa no cargada, no se puede decodificar")
            return null
        }
        return try {
            Log.d(TAG, "JP2: llamando a OpenJPEG nativo (${bytes.size} bytes)")
            val bmp = nativeDecodeJp2(bytes)
            if (bmp != null)
                Log.i(TAG, "JP2 OK (OpenJPEG nativo): ${bmp.width}×${bmp.height}")
            else
                Log.w(TAG, "nativeDecodeJp2 devolvió null (datos inválidos o truncados)")
            bmp
        } catch (e: Exception) {
            Log.e(TAG, "Error en nativeDecodeJp2: ${e.javaClass.simpleName}: ${e.message}")
            null
        }
    }

    // ──────────────────────────────────────────────────────────────────────
    //  BitmapFactory estándar (JPEG / PNG / UNKNOWN)
    // ──────────────────────────────────────────────────────────────────────

    private fun decodeBitmapFactory(bytes: ByteArray, format: ImageFormat): Bitmap? {
        return try {
            val bmp = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
            if (bmp != null)
                Log.i(TAG, "BitmapFactory OK: formato=$format ${bmp.width}×${bmp.height}")
            else
                Log.w(TAG, "BitmapFactory devolvió null: formato=$format bytes=${bytes.size}")
            bmp
        } catch (e: Exception) {
            Log.e(TAG, "Error en BitmapFactory: ${e.message}")
            null
        }
    }

    // ──────────────────────────────────────────────────────────────────────
    //  Resultado tipado
    // ──────────────────────────────────────────────────────────────────────

    /**
     * @param bitmap  [Bitmap] decodificado, o `null` si la decodificación falló.
     * @param format  Formato detectado por cabecera ([ImageFormat]).
     */
    data class DecodeResult(
        val bitmap: Bitmap?,
        val format: ImageFormat
    ) {
        val success: Boolean get() = bitmap != null
    }
}
