// etcpack_jni.cpp — byte-exact ETC2 RGBA8 encoder replicating Wallpaper Engine's
// resourcecompiler64.exe pipeline (AMD Compressonator v3.x + Ericsson etcpack,
// m_fast = true).
//
// The exact chain, decoded from resourcecompiler64.exe:
//   FUN_1400598c0  pad-to-4 (column pads repeat the row's last pixel with
//                  alpha zeroed; pad rows duplicate the last row; align = 4
//                  for single-mip output)
//   FUN_140059720  R<->B byte swap (cancels with the BGRA staging below)
//   FUN_140115ce0  CompressRGBABlock:
//                    alphaBlock[16]  = alphas, row-major 4x4
//                    rgb[48]         = per pixel [byte2, byte1, byte0] of the
//                                      (already R<->B swapped) buffer = [R,G,B]
//                    compressBlockAlphaFast(alpha, 0,0, 4,4, out8)   (etcpack)
//                    compressBlockETC2FastPerceptual(rgb, tmp, 4,4, 0,0, w1, w2)
//                    block = [out8 (2 LE words)][bswap(w1)][bswap(w2)]
//   FUN_1400da510  the per-block loop over the padded image
//
// The net effect of the in-place R<->B swap plus the [byte2, byte1, byte0]
// staging is that the etcpack functions are fed plain [R,G,B] row-major data
// from the ORIGINAL RGBA buffer — which is exactly what vanilla etcpack's
// RED/GREEN/BLUE macros expect. So we stage directly from the input.

#include <jni.h>
#include <cstdint>
#include <cstring>
#include <cstdlib>
#include <mutex>
#include "etcpack_lib.h"

// C++ linkage (etcpack.cxx defines this without extern "C"; the NDK linker
// resolves it eagerly, so the declaration must match exactly).
void compressBlockAlphaFast(uint8 *data, int ix, int iy, int width, int height, uint8 *returnData);

namespace {

std::once_flag g_init_once;

void initEtcpack() {
    // Compressonator's CCodec_ETC2 constructor does exactly this before any
    // block is compressed (readCompressParams + setupAlphaTableAndValtab for
    // the RGBA codecs).
    readCompressParams();
    setupAlphaTableAndValtab();
}

inline uint32_t bswap32(uint32_t v) {
    return ((v & 0xff000000u) >> 24) | ((v & 0x00ff0000u) >> 8) |
           ((v & 0x0000ff00u) << 8) | ((v & 0x000000ffu) << 24);
}

inline uint32_t roundUpTo4(uint32_t v) {
    uint32_t r = v % 4;
    return r == 0 ? v : v + (4 - r);
}

} // namespace

extern "C" JNIEXPORT jbyteArray JNICALL
Java_com_wepwawet_pairing_EtcpackEncoder_nativeEncodeEtc2Rgba8(
        JNIEnv *env, jclass /*clazz*/, jbyteArray rgbaBytes, jint width, jint height) {

    std::call_once(g_init_once, initEtcpack);

    // v0.7.24 (M-N1): every early-return error path now throws a proper Java
    // exception — returning nullptr alone left the Kotlin caller unable to
    // distinguish OOM from an encoder bug.
    auto throwIllegalArg = [env](const char* msg) {
        jclass exc = env->FindClass("java/lang/IllegalArgumentException");
        if (exc != nullptr) env->ThrowNew(exc, msg);
    };
    auto throwOom = [env](const char* msg) {
        jclass exc = env->FindClass("java/lang/OutOfMemoryError");
        if (exc != nullptr) env->ThrowNew(exc, msg);
    };
    // v0.7.24 (C-N5): dimension guard — every size product below is computed
    // in 32-bit arithmetic; >= 32769x32769 inputs overflowed the input-size
    // check to 0 (bypassing it) and wrapped the malloc size to 0 (heap
    // overflow in the row-copy loops). 16384 is the GLES3 texture-size floor.
    if (width <= 0 || height <= 0 || width > 16384 || height > 16384) {
        throwIllegalArg("Invalid dimensions (expected 1..16384)");
        return nullptr;
    }
    const jsize srcLen = env->GetArrayLength(rgbaBytes);
    // v0.7.24 (C-N5): 64-bit input-size check (was 32-bit jint multiply).
    if ((int64_t) srcLen < (int64_t) width * (int64_t) height * 4) {
        throwIllegalArg("Input RGBA bytes too small for given dimensions");
        return nullptr;
    }
    jbyte *src = env->GetByteArrayElements(rgbaBytes, nullptr);
    if (src == nullptr) {
        return nullptr; // OOM already thrown by the JVM
    }

    // ── pad to multiples of 4 (FUN_1400598c0, align 4) ──
    const uint32_t w = (uint32_t) width;
    const uint32_t h = (uint32_t) height;
    const uint32_t pw = roundUpTo4(w);
    const uint32_t ph = roundUpTo4(h);
    const uint32_t srcStride = w * 4;
    const uint32_t padStride = pw * 4;

    // v0.7.24 (C-N5): size_t multiply — `(size_t) padStride * ph` (both were
    // 32-bit uint32_t; the product wrapped to 0 for pw=ph=32768).
    uint8_t *padded = (uint8_t *) malloc((size_t) padStride * (size_t) ph);
    if (padded == nullptr) {
        env->ReleaseByteArrayElements(rgbaBytes, src, JNI_ABORT);
        throwOom("native etcpack padded-buffer allocation failed");
        return nullptr;
    }
    for (uint32_t y = 0; y < h; y++) {
        const uint8_t *srcRow = (const uint8_t *) src + (size_t) y * srcStride;
        uint8_t *dstRow = padded + (size_t) y * padStride;
        memcpy(dstRow, srcRow, srcStride);
        if (pw > w) {
            // column pad: the row's last pixel with alpha zeroed
            const uint32_t last = *((const uint32_t *) (srcRow + srcStride - 4));
            const uint32_t padPix = last & 0x00ffffffu;
            for (uint32_t x = w; x < pw; x++) {
                *((uint32_t *) (dstRow + (size_t) x * 4)) = padPix;
            }
        }
    }
    for (uint32_t y = h; y < ph; y++) {
        // row pad: duplicate the last row
        memcpy(padded + (size_t) y * padStride, padded + (size_t) (h - 1) * padStride, padStride);
    }
    env->ReleaseByteArrayElements(rgbaBytes, src, JNI_ABORT);

    // ── encode every 4x4 block (FUN_1400da510 + FUN_140115ce0) ──
    const uint32_t blocksX = pw >> 2;
    const uint32_t blocksY = ph >> 2;
    // v0.7.24 (C-N5): 64-bit output-size math (16384^2/16 = 64M blocks * 16
    // bytes = 1 GiB — fits jint, but compute safely anyway).
    const int64_t outLen64 = (int64_t) blocksX * (int64_t) blocksY * 16;
    if (outLen64 > 0x7FFFFFFFLL) {
        free(padded);
        throwIllegalArg("Output too large for a Java array");
        return nullptr;
    }
    const jsize outLen = (jsize) outLen64;
    jbyteArray result = env->NewByteArray(outLen);
    if (result == nullptr) {
        free(padded);
        return nullptr; // OOM already thrown by the JVM
    }
    jbyte *out = env->GetByteArrayElements(result, nullptr);
    if (out == nullptr) {
        free(padded);
        // v0.7.24 (M-N1): release the (empty) array reference path — the JVM
        // already threw; nothing more to do.
        return nullptr;
    }

    uint8_t alphaBlock[16];
    uint8_t rgbBlock[4 * 4 * 3];
    uint8_t scratch[4 * 4 * 3]; // required by the "fast" ETC2 function
    uint8_t alphaOut[8];

    for (uint32_t by = 0; by < blocksY; by++) {
        for (uint32_t bx = 0; bx < blocksX; bx++) {
            const uint8_t *block = padded + (size_t) (by * 4) * padStride + (size_t) (bx * 4) * 4;
            // alpha staging: 16 alphas, row-major
            for (uint32_t y = 0; y < 4; y++) {
                const uint8_t *row = block + (size_t) y * padStride;
                alphaBlock[y * 4 + 0] = row[3];
                alphaBlock[y * 4 + 1] = row[7];
                alphaBlock[y * 4 + 2] = row[11];
                alphaBlock[y * 4 + 3] = row[15];
            }
            // rgb staging: per pixel [R,G,B], row-major (see header comment)
            for (uint32_t y = 0; y < 4; y++) {
                const uint8_t *row = block + (size_t) y * padStride;
                uint8_t *dst = rgbBlock + (size_t) y * 12;
                for (uint32_t x = 0; x < 4; x++) {
                    dst[x * 3 + 0] = row[x * 4 + 0]; // R
                    dst[x * 3 + 1] = row[x * 4 + 1]; // G
                    dst[x * 3 + 2] = row[x * 4 + 2]; // B
                }
            }

            compressBlockAlphaFast(alphaBlock, 0, 0, 4, 4, alphaOut);

            unsigned int w1, w2;
            compressBlockETC2FastPerceptual(rgbBlock, scratch, 4, 4, 0, 0, w1, w2);

            uint8_t *dst = (uint8_t *) out + (size_t) ((by * blocksX + bx) * 16);
            memcpy(dst, alphaOut, 8); // two little-endian alpha words
            uint32_t hi = bswap32(w1);
            uint32_t lo = bswap32(w2);
            memcpy(dst + 8, &hi, 4);
            memcpy(dst + 12, &lo, 4);
        }
    }

    env->ReleaseByteArrayElements(result, out, 0);
    free(padded);
    return result;
}
