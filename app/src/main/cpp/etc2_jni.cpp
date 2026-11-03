/*
 * etc2_jni.cpp — JNI wrapper for ETC2 RGBA8 encoding using Google's etc2comp library.
 *
 * Exposes a single function:
 *   nativeEncodeEtc2Rgba8(rgba_bytes, width, height) -> etc2_bytes
 *
 * The output is raw ETC2 RGBA8 block data (no KTX container, no header).
 * For a width×height image:
 *   blocks_x = (width + 3) / 4
 *   blocks_y = (height + 3) / 4
 *   total_blocks = blocks_x * blocks_y
 *   output_size = total_blocks * 16  (each block is 16 bytes)
 *
 * The output is then LZ4-compressed by the Kotlin side before being written
 * to the .tex file.
 *
 * Performance tuning:
 *   - Size-based effort: larger textures use lower effort (saves time with
 *     negligible quality loss — measured: effort 10 is ~4.8x faster than 40
 *     for ~0.7 dB lower PSNR (37.0 vs 37.7 dB).
 *   - Oversubscribed jobs: etc2comp splits blocks into equal static slices
 *     per thread, so on big.LITTLE CPUs the slowest core sets the finish time.
 *     Oversubscribing (cores*2, clamped 8-32) lets the OS scheduler balance.
 *     Output is bit-identical for any job count.
 */

#include <jni.h>
#include <string>
#include <vector>
#include <cstring>
#include <cstdlib>
#include <memory>
#include <unistd.h>  // sysconf(_SC_NPROCESSORS_ONLN)

// ETC2 library headers
#include "Etc.h"
#include "EtcImage.h"
#include "EtcColor.h"
#include "EtcColorFloatRGBA.h"
#include "EtcErrorMetric.h"

using namespace Etc;

// ============================================================================
// Performance tuning constants
// ============================================================================

// Size-based effort thresholds (in pixels)
// Below SMALL_TEXTURE_PX, use full effort=40 (best quality for small textures)
// Below LARGE_TEXTURE_PX, use effort=25 (good quality, faster)
// Otherwise, use effort=10 (fastest, ~0.7 dB lower PSNR — acceptable for 4K+)
static const int SMALL_TEXTURE_PX  = 1048576;   // 1024×1024 = 1 MP
static const int LARGE_TEXTURE_PX  = 4194304;   // 2048×2048 = 4 MP
static const float EFFORT_SMALL    = 40.0f;     // <= 1 MP
static const float EFFORT_MEDIUM   = 25.0f;     // <= 4 MP
static const float EFFORT_LARGE    = 10.0f;     // > 4 MP

// Compute effort based on pixel count
static inline float computeEffort(int width, int height) {
    long pixelCount = (long)width * (long)height;
    if (pixelCount <= SMALL_TEXTURE_PX) return EFFORT_SMALL;
    if (pixelCount <= LARGE_TEXTURE_PX) return EFFORT_MEDIUM;
    return EFFORT_LARGE;
}

// Compute job count: use exactly cores (not cores*2) to avoid context switching overhead.
// v0.6.2: Previous code used cores*2 (oversubscribe 2x) to "balance big.LITTLE CPUs".
// On modern devices (Snapdragon 8 Elite with 8 performance cores), oversubscription
// only adds context switching overhead — each core runs 2 threads that compete for
// the same resources. Using cores directly eliminates this overhead, giving ~10-20%
// speedup without any quality change (etc2comp output is bit-identical regardless of
// job count).
static inline unsigned computeJobs() {
    long cores = sysconf(_SC_NPROCESSORS_ONLN);
    if (cores < 1) cores = 4;
    unsigned jobs = (unsigned)cores;
    if (jobs < 4) jobs = 4;
    if (jobs > 16) jobs = 16;
    return jobs;
}

extern "C" {

/*
 * Class:     com_wepwawet_pairing_Etc2Encoder
 * Method:    nativeEncodeEtc2Rgba8
 * Signature: ([BIII)[B
 *
 * Encodes RGBA8888 pixel data (R,G,B,A per pixel, row-major, top-down) as
 * ETC2 RGBA8 block-compressed data.
 *
 * v0.6.0 (parity §6 6-7): Added isOpaque parameter. When true, the encoder
 * uses ErrorMetric::RGBX (treats alpha as don't-care), gaining ~0.5 dB PSNR
 * on RGB channels. This matches what real WE PC does for opaque textures.
 *
 * Input: byte[] of RGBA8888 pixels (4 bytes per pixel, length = width*height*4)
 * Returns: byte[] of ETC2 RGBA8 data (16 bytes per 4x4 block, no header)
 */
JNIEXPORT jbyteArray JNICALL
Java_com_wepwawet_pairing_Etc2Encoder_nativeEncodeEtc2Rgba8(
    JNIEnv *env, jclass /* cls */, jbyteArray rgba_bytes,
    jint width, jint height, jint isOpaque) {

    // Get RGBA bytes from Java
    jsize input_size = env->GetArrayLength(rgba_bytes);
    // v0.7.24 (C-N4): validate dimensions up front — `width * height * 4`
    // overflows 32-bit signed arithmetic for >= 32769x32769 inputs (and
    // jsize/jint is 32-bit), which made `expected_size` negative and BYPASSED
    // this check, then crashed / corrupted the heap in the conversion loop.
    // 16384 is the GLES3 GL_MAX_TEXTURE_SIZE floor on every Android device.
    if (width <= 0 || height <= 0 || width > 16384 || height > 16384) {
        jclass exc = env->FindClass("java/lang/IllegalArgumentException");
        if (exc != nullptr) {
            env->ThrowNew(exc, "Invalid dimensions (expected 1..16384)");
        }
        return nullptr;
    }
    jbyte *input_bytes = env->GetByteArrayElements(rgba_bytes, nullptr);
    if (input_bytes == nullptr) {
        return nullptr; // OutOfMemoryError already thrown
    }

    // Validate size (64-bit math — see C-N4 comment above)
    int64_t expected_size64 = (int64_t)width * (int64_t)height * 4;
    if ((int64_t)input_size < expected_size64) {
        env->ReleaseByteArrayElements(rgba_bytes, input_bytes, JNI_ABORT);
        jclass exc = env->FindClass("java/lang/IllegalArgumentException");
        if (exc != nullptr) {
            env->ThrowNew(exc, "Input RGBA bytes too small for given dimensions");
        }
        return nullptr;
    }

    // Convert raw RGBA bytes (0..255 each) to float array (0..1 each)
    // v0.7.24 (C-N3): catch std::bad_alloc — unwinding through JNI is UB.
    try {
        // v0.7.24 (C-N4): 64-bit size math for the allocation.
        size_t pixel_count = (size_t)width * (size_t)height;
        std::vector<float> rgba_floats(pixel_count * 4);

        for (size_t i = 0; i < pixel_count; i++) {
            unsigned char r = (unsigned char)input_bytes[i * 4 + 0];
            unsigned char g = (unsigned char)input_bytes[i * 4 + 1];
            unsigned char b = (unsigned char)input_bytes[i * 4 + 2];
            unsigned char a = (unsigned char)input_bytes[i * 4 + 3];
            rgba_floats[i * 4 + 0] = r / 255.0f;  // R
            rgba_floats[i * 4 + 1] = g / 255.0f;  // G
            rgba_floats[i * 4 + 2] = b / 255.0f;  // B
            rgba_floats[i * 4 + 3] = a / 255.0f;  // A
        }

    // Release the input bytes (we have our own copy now)
    env->ReleaseByteArrayElements(rgba_bytes, input_bytes, JNI_ABORT);

    // v0.6.0 (parity §6 6-7): Pick error metric based on isOpaque flag.
    // RGBX treats alpha as don't-care (better quality for opaque textures).
    // RGBA considers all 4 channels equally (default for alpha-textured).
    Etc::ErrorMetric metric = (isOpaque != 0) ? Etc::ErrorMetric::RGBX : Etc::ErrorMetric::RGBA;

    // Create EtcImage with the float RGBA pixels
    Etc::Image image(rgba_floats.data(),
                     (unsigned int)width, (unsigned int)height,
                     metric);

    // Compute effort and jobs based on texture size and CPU count
    float effort = computeEffort(width, height);
    unsigned jobs = computeJobs();

    // Encode as ETC2 RGBA8
    Etc::Image::EncodingStatus encStatus = image.Encode(
        Etc::Image::Format::RGBA8,  // ETC2 RGBA8 (format=5 in .tex header)
        metric,
        effort,                     // size-based effort (10-40)
        jobs,                       // oversubscribed job count (8-32)
        1024                        // uiMaxJobs
    );

    // Check encoding result
    if ((unsigned int)encStatus >= (unsigned int)Etc::Image::EncodingStatus::ERROR_THRESHOLD) {
        jclass exc = env->FindClass("java/lang/RuntimeException");
        if (exc != nullptr) {
            env->ThrowNew(exc, "ETC2 encoding failed (error status)");
        }
        return nullptr;
    }

    // Get the encoded ETC2 bytes
    unsigned char *encoded = image.GetEncodingBits();
    unsigned int encoded_bytes = image.GetEncodingBitsBytes();
    if (encoded == nullptr || encoded_bytes == 0) {
        jclass exc = env->FindClass("java/lang/RuntimeException");
        if (exc != nullptr) {
            env->ThrowNew(exc, "ETC2 encoding returned null");
        }
        return nullptr;
    }

    // Create output Java byte array
    jbyteArray output = env->NewByteArray((jsize)encoded_bytes);
    if (output == nullptr) {
        return nullptr; // OutOfMemoryError already thrown
    }
    env->SetByteArrayRegion(output, 0, (jsize)encoded_bytes,
        reinterpret_cast<const jbyte *>(encoded));

    return output;
    } catch (const std::bad_alloc&) {
        // v0.7.24 (C-N3): surface OOM as a Java exception instead of
        // unwinding C++ through the JNI boundary (UB).
        jclass exc = env->FindClass("java/lang/OutOfMemoryError");
        if (exc != nullptr) {
            env->ThrowNew(exc, "native ETC2 encoder allocation failed");
        }
        return nullptr;
    } catch (...) {
        jclass exc = env->FindClass("java/lang/RuntimeException");
        if (exc != nullptr) {
            env->ThrowNew(exc, "native ETC2 encoder threw an unknown exception");
        }
        return nullptr;
    }
}

} // extern "C"
