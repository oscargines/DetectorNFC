/**
 * jp2_jni.cpp — Capa JNI que conecta Android con OpenJPEG 2.5.2
 *
 * Función expuesta:
 *   com.oscar.detectornfc.ImageDecoder.nativeDecodeJp2(byte[]) → Bitmap
 *
 * Flujo:
 *   1. Recibe el array de bytes JP2/J2K desde Kotlin.
 *   2. Decodifica con openjpeg (opj_decode) a RGBA.
 *   3. Crea un android.graphics.Bitmap con los píxeles resultantes.
 *   4. Devuelve el Bitmap al lado Java/Kotlin.
 */

#include <jni.h>
#include <android/log.h>
#include <android/bitmap.h>
#include <cstdlib>
#include <cstring>

#include "openjpeg/openjpeg.h"

#define LOG_TAG "JP2_JNI"
#define LOGI(...)  __android_log_print(ANDROID_LOG_INFO,  LOG_TAG, __VA_ARGS__)
#define LOGW(...)  __android_log_print(ANDROID_LOG_WARN,  LOG_TAG, __VA_ARGS__)
#define LOGE(...)  __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

/* ── Callbacks de memoria para leer el buffer JP2 ─────────────────────── */

struct MemStream {
    const OPJ_BYTE* data;
    OPJ_SIZE_T      size;
    OPJ_SIZE_T      pos;
};

static OPJ_SIZE_T mem_read(void* buf, OPJ_SIZE_T len, void* ud) {
    MemStream* ms = (MemStream*)ud;
    OPJ_SIZE_T avail = ms->size - ms->pos;
    if (avail == 0) return (OPJ_SIZE_T)-1;
    if (len > avail) len = avail;
    memcpy(buf, ms->data + ms->pos, len);
    ms->pos += len;
    return len;
}

static OPJ_BOOL mem_seek(OPJ_OFF_T off, void* ud) {
    MemStream* ms = (MemStream*)ud;
    if (off < 0 || (OPJ_SIZE_T)off > ms->size) return OPJ_FALSE;
    ms->pos = (OPJ_SIZE_T)off;
    return OPJ_TRUE;
}

static OPJ_OFF_T mem_skip(OPJ_OFF_T n, void* ud) {
    MemStream* ms = (MemStream*)ud;
    OPJ_SIZE_T remaining = ms->size - ms->pos;
    OPJ_SIZE_T skip = (n < 0) ? 0 : (OPJ_SIZE_T)n;
    if (skip > remaining) skip = remaining;
    ms->pos += skip;
    return (OPJ_OFF_T)skip;
}

/* ── Callbacks de log de OpenJPEG → Logcat ───────────────────────────── */

static void ojp_info (const char* msg, void*) { LOGI("%s", msg); }
static void ojp_warn (const char* msg, void*) { LOGW("%s", msg); }
static void ojp_error(const char* msg, void*) { LOGE("%s", msg); }

/* ── Punto de entrada JNI ─────────────────────────────────────────────── */

extern "C"
JNIEXPORT jobject JNICALL
Java_com_oscar_detectornfc_ImageDecoder_nativeDecodeJp2(
        JNIEnv* env, jobject /*thiz*/, jbyteArray jp2Data)
{
    jsize   len   = env->GetArrayLength(jp2Data);
    jbyte*  bytes = env->GetByteArrayElements(jp2Data, nullptr);
    if (!bytes || len <= 0) {
        LOGE("nativeDecodeJp2: datos nulos o vacíos");
        return nullptr;
    }

    /* Detectar formato JP2 vs J2K codestream */
    OPJ_CODEC_FORMAT fmt = OPJ_CODEC_JP2;
    const unsigned char* b = (const unsigned char*)bytes;
    if (len >= 4 && b[0]==0xFF && b[1]==0x4F && b[2]==0xFF && b[3]==0x51) {
        fmt = OPJ_CODEC_J2K;   /* J2K bare codestream */
    }

    /* Crear decodificador */
    opj_codec_t* codec = opj_create_decompress(fmt);
    if (!codec) {
        LOGE("nativeDecodeJp2: opj_create_decompress falló");
        env->ReleaseByteArrayElements(jp2Data, bytes, JNI_ABORT);
        return nullptr;
    }

    opj_set_info_handler (codec, ojp_info,  nullptr);
    opj_set_warning_handler(codec, ojp_warn, nullptr);
    opj_set_error_handler(codec, ojp_error, nullptr);

    /* Parámetros por defecto */
    opj_dparameters_t params;
    opj_set_default_decoder_parameters(&params);
    if (!opj_setup_decoder(codec, &params)) {
        LOGE("nativeDecodeJp2: opj_setup_decoder falló");
        opj_destroy_codec(codec);
        env->ReleaseByteArrayElements(jp2Data, bytes, JNI_ABORT);
        return nullptr;
    }

    /* Crear stream desde el buffer en memoria */
    MemStream ms { (const OPJ_BYTE*)bytes, (OPJ_SIZE_T)len, 0 };
    opj_stream_t* stream = opj_stream_create((OPJ_SIZE_T)len, OPJ_TRUE);
    if (!stream) {
        LOGE("nativeDecodeJp2: opj_stream_create falló");
        opj_destroy_codec(codec);
        env->ReleaseByteArrayElements(jp2Data, bytes, JNI_ABORT);
        return nullptr;
    }
    opj_stream_set_read_function (stream, mem_read);
    opj_stream_set_seek_function (stream, mem_seek);
    opj_stream_set_skip_function (stream, mem_skip);
    opj_stream_set_user_data     (stream, &ms, nullptr);
    opj_stream_set_user_data_length(stream, (OPJ_UINT64)len);

    /* Leer cabecera */
    opj_image_t* image = nullptr;
    if (!opj_read_header(stream, codec, &image)) {
        LOGE("nativeDecodeJp2: opj_read_header falló");
        opj_stream_destroy(stream);
        opj_destroy_codec(codec);
        env->ReleaseByteArrayElements(jp2Data, bytes, JNI_ABORT);
        return nullptr;
    }

    /* Decodificar */
    if (!opj_decode(codec, stream, image) || !opj_end_decompress(codec, stream)) {
        LOGE("nativeDecodeJp2: opj_decode / opj_end_decompress falló");
        opj_image_destroy(image);
        opj_stream_destroy(stream);
        opj_destroy_codec(codec);
        env->ReleaseByteArrayElements(jp2Data, bytes, JNI_ABORT);
        return nullptr;
    }

    opj_stream_destroy(stream);
    opj_destroy_codec(codec);
    env->ReleaseByteArrayElements(jp2Data, bytes, JNI_ABORT);

    /* ── Convertir opj_image_t → android.graphics.Bitmap ────────────── */
    int w = (int)(image->x1 - image->x0);
    int h = (int)(image->y1 - image->y0);
    int ncomps = (int)image->numcomps;
    LOGI("nativeDecodeJp2: decoded %dx%d, %d componentes", w, h, ncomps);

    /* Crear Bitmap ARGB_8888 */
    jclass   bmpCls  = env->FindClass("android/graphics/Bitmap");
    jclass   cfgCls  = env->FindClass("android/graphics/Bitmap$Config");
    jfieldID argb8   = env->GetStaticFieldID(cfgCls, "ARGB_8888", "Landroid/graphics/Bitmap$Config;");
    jobject  cfgObj  = env->GetStaticObjectField(cfgCls, argb8);
    jmethodID create = env->GetStaticMethodID(bmpCls, "createBitmap",
                        "(IILandroid/graphics/Bitmap$Config;)Landroid/graphics/Bitmap;");
    jobject  bitmap  = env->CallStaticObjectMethod(bmpCls, create, w, h, cfgObj);
    if (!bitmap) {
        LOGE("nativeDecodeJp2: no se pudo crear Bitmap");
        opj_image_destroy(image);
        return nullptr;
    }

    /* Acceder a los píxeles del Bitmap vía AndroidBitmap API */
    void* pixels = nullptr;
    if (AndroidBitmap_lockPixels(env, bitmap, &pixels) != ANDROID_BITMAP_RESULT_SUCCESS) {
        LOGE("nativeDecodeJp2: AndroidBitmap_lockPixels falló");
        opj_image_destroy(image);
        return bitmap;
    }

    /* Normalización de muestra: ajuste de prec a 8-bit */
    int adjust = image->comps[0].prec > 8 ? (int)(image->comps[0].prec - 8) : 0;

    auto* dst = (uint32_t*)pixels;
    for (int y = 0; y < h; y++) {
        for (int x = 0; x < w; x++) {
            int idx = y * w + x;
            uint8_t r, g, B_ch, a = 255;

            if (ncomps >= 3) {
                r    = (uint8_t)((image->comps[0].data[idx] + image->comps[0].sgnd * 128) >> adjust);
                g    = (uint8_t)((image->comps[1].data[idx] + image->comps[1].sgnd * 128) >> adjust);
                B_ch = (uint8_t)((image->comps[2].data[idx] + image->comps[2].sgnd * 128) >> adjust);
                if (ncomps >= 4)
                    a = (uint8_t)((image->comps[3].data[idx] + image->comps[3].sgnd * 128) >> adjust);
            } else {
                /* Escala de grises */
                r = g = B_ch = (uint8_t)((image->comps[0].data[idx] + image->comps[0].sgnd * 128) >> adjust);
            }
            /* Android Bitmap ARGB_8888: byte order A R G B en memoria little-endian */
            dst[idx] = ((uint32_t)a << 24) | ((uint32_t)r << 16) |
                       ((uint32_t)g << 8)  |  (uint32_t)B_ch;
        }
    }

    AndroidBitmap_unlockPixels(env, bitmap);
    opj_image_destroy(image);

    LOGI("nativeDecodeJp2: Bitmap ARGB_8888 %dx%d creado OK", w, h);
    return bitmap;
}

