package com.oscar.detectornfc

/**
 * Formatos de imagen detectados a partir de cabecera (magic bytes).
 *
 * @param mimeType   MIME type correcto para MediaStore / compartición.
 * @param extension  Extensión de archivo sin punto.
 * @param isAndroidRenderable  true si BitmapFactory de Android puede decodificarlo nativamente.
 */
enum class ImageFormat(
    val mimeType: String,
    val extension: String,
    val isAndroidRenderable: Boolean
) {
    /** JPEG estándar — cabecera FF D8 FF */
    JPEG("image/jpeg", "jpg", true),

    /** PNG — cabecera 89 50 4E 47 0D 0A 1A 0A */
    PNG("image/png", "png", true),

    /**
     * JPEG 2000 (JP2/J2K) — NO soportado de forma nativa por BitmapFactory en Android.
     * Cabeceras reconocidas:
     *   - JP2 file box : 00 00 00 0C 6A 50 20 20
     *   - J2K codestream: FF 4F FF 51
     */
    JP2("image/jp2", "jp2", false),

    /** Formato desconocido o datos insuficientes para determinar tipo. */
    UNKNOWN("application/octet-stream", "bin", false)
}

/**
 * Detecta el formato de imagen a partir de los primeros bytes del buffer (magic bytes).
 * No realiza decodificación completa; sólo examina la cabecera.
 */
object ImageFormatDetector {

    /**
     * Analiza [bytes] y devuelve el [ImageFormat] correspondiente.
     * Requiere al menos 4 bytes; con menos devuelve [ImageFormat.UNKNOWN].
     */
    fun detect(bytes: ByteArray): ImageFormat {
        if (bytes.size < 4) return ImageFormat.UNKNOWN

        // ── JPEG: FF D8 FF ──────────────────────────────────────────────
        if (bytes[0] == 0xFF.toByte() &&
            bytes[1] == 0xD8.toByte() &&
            bytes[2] == 0xFF.toByte()
        ) return ImageFormat.JPEG

        // ── PNG: 89 50 4E 47 0D 0A 1A 0A ─────────────────────────────
        if (bytes.size >= 8 &&
            bytes[0] == 0x89.toByte() &&
            bytes[1] == 0x50.toByte() && // 'P'
            bytes[2] == 0x4E.toByte() && // 'N'
            bytes[3] == 0x47.toByte()    // 'G'
        ) return ImageFormat.PNG

        // ── JP2 file box: 00 00 00 0C 6A 50 20 20 ────────────────────
        if (bytes.size >= 6 &&
            bytes[0] == 0x00.toByte() &&
            bytes[1] == 0x00.toByte() &&
            bytes[2] == 0x00.toByte() &&
            bytes[3] == 0x0C.toByte() &&
            bytes[4] == 0x6A.toByte() && // 'j'
            bytes[5] == 0x50.toByte()    // 'P'
        ) return ImageFormat.JP2

        // ── J2K codestream: FF 4F FF 51 ───────────────────────────────
        if (bytes[0] == 0xFF.toByte() &&
            bytes[1] == 0x4F.toByte() &&
            bytes[2] == 0xFF.toByte() &&
            bytes[3] == 0x51.toByte()
        ) return ImageFormat.JP2

        return ImageFormat.UNKNOWN
    }
}

