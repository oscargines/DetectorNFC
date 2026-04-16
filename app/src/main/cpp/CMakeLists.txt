cmake_minimum_required(VERSION 3.22.1)
project(openjpeg_android C CXX)

# ── Fuentes OpenJPEG 2.5.2 ─────────────────────────────────────────────────
set(OPJ_SRC_DIR ${CMAKE_CURRENT_SOURCE_DIR}/openjpeg)

# Recoger todos los .c del directorio openjpeg/
# Se excluyen:
#   - Herramientas/tests con main()
#   - Archivos JPIP (requieren USE_JPIP + indexbox_manager.h con constantes no exportadas)
file(GLOB OPJ_SOURCES "${OPJ_SRC_DIR}/*.c")
list(REMOVE_ITEM OPJ_SOURCES
    # Utilidades con main()
    "${OPJ_SRC_DIR}/bench_dwt.c"
    "${OPJ_SRC_DIR}/t1_generate_luts.c"
    "${OPJ_SRC_DIR}/t1_ht_generate_luts.c"
    "${OPJ_SRC_DIR}/test_sparse_array.c"
    # Módulo JPIP (solo para streaming; no necesario para decodificar JP2)
    "${OPJ_SRC_DIR}/cidx_manager.c"
    "${OPJ_SRC_DIR}/tpix_manager.c"
    "${OPJ_SRC_DIR}/thix_manager.c"
    "${OPJ_SRC_DIR}/ppix_manager.c"
    "${OPJ_SRC_DIR}/phix_manager.c"
)

# ── Librería estática OpenJPEG ──────────────────────────────────────────────
add_library(openjpeg_static STATIC ${OPJ_SOURCES})

target_include_directories(openjpeg_static PUBLIC ${OPJ_SRC_DIR})

target_compile_definitions(openjpeg_static PRIVATE
    OPJ_STATIC                        # no __declspec(dllexport)
    _FILE_OFFSET_BITS=64
)

# ── Librería JNI compartida (.so) ───────────────────────────────────────────
add_library(jp2jni SHARED
    jp2_jni.cpp
)

target_include_directories(jp2jni PRIVATE ${OPJ_SRC_DIR})

target_link_libraries(jp2jni
    openjpeg_static
    jnigraphics          # AndroidBitmap_lockPixels
    android
    log
)

target_compile_options(jp2jni PRIVATE -O2 -Wall)

