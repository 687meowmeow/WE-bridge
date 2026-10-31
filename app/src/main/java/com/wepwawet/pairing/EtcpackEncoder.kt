package com.wepwawet.pairing

import com.wepwawet.log.WepWawetLogger

/**
 * Native ETC2 RGBA8 encoder using **Ericsson's etcpack** — the exact encoder
 * Wallpaper Engine's resourcecompiler64.exe embeds (via AMD Compressonator
 * v3.x, m_fast = true).
 *
 * v0.7.18 (PC-exact): every etc2comp-based encoder produced DIFFERENT block
 * bytes than the PC because the PC never used etc2comp. The PC's chain,
 * decoded instruction-by-instruction from resourcecompiler64.exe:
 *
 *   FUN_1400598c0  pad-to-4 (column pads repeat the row's last pixel with
 *                  alpha zeroed; pad rows duplicate the last row)
 *   FUN_140059720  in-place R<->B byte swap
 *   FUN_140115ce0  CompressRGBABlock: 16 alphas (row-major) through
 *                  compressBlockAlphaFast, 4x4x3 [R,G,B] (row-major) through
 *                  compressBlockETC2FastPerceptual; block = [8 alpha bytes]
 *                  [bswap(word1)][bswap(word2)]
 *   FUN_1400da510  the per-block loop over the padded image
 *
 * The in-place R<->B swap cancels against the [byte2,byte1,byte0] staging,
 * so the vendored (unmodified) etcpack is fed plain [R,G,B] row-major data
 * from the original RGBA buffer — verified by decoding our output with
 * etcpack's own decoder (roundtrip_test.cpp: orientation, channels, alpha
 * and the pad-4 semantics all verified on asymmetric sizes).
 *
 * Output size: ceil(w/4)*ceil(h/4) * 16 bytes.
 */
object EtcpackEncoder {
    private const val TAG = "EtcpackEncoder"

    private val isLoaded: Boolean by lazy {
        try {
            // Same .so as Etc2Encoder/DxtDecoder (libwepwawet_etc2.so) — the
            // load here is idempotent; it also covers the case where this
            // object is used before any Etc2Encoder call.
            System.loadLibrary("wepwawet_etc2")
            WepWawetLogger.proxy("EtcpackEncoder: native library loaded ✓")
            true
        } catch (e: UnsatisfiedLinkError) {
            WepWawetLogger.proxyError("EtcpackEncoder: FAILED to load libwepwawet_etc2.so",
                "exception" to e.javaClass.name,
                "message" to (e.message ?: ""))
            false
        } catch (e: Exception) {
            WepWawetLogger.proxyError("EtcpackEncoder: exception loading libwepwawet_etc2.so",
                "exception" to e.javaClass.name,
                "message" to (e.message ?: ""))
            false
        }
    }

    /**
     * Encode RGBA8888 pixel data as ETC2 RGBA8 blocks, byte-exact with the
     * PC's resourcecompiler64.exe.
     *
     * @param rgbaBytes Input pixels, RGBA8888 (R,G,B,A per pixel, row-major)
     * @param width Image width in pixels (need NOT be a multiple of 4 — the
     *              encoder pads exactly like the PC: column pads repeat the
     *              row's last pixel with alpha zeroed, pad rows duplicate the
     *              last row)
     * @param height Image height in pixels
     * @return ETC2 RGBA8 block data, 16 bytes per 4x4 block
     */
    fun encodeRgba8888(rgbaBytes: ByteArray, width: Int, height: Int): ByteArray {
        if (!isLoaded) {
            throw IllegalStateException("etcpack native library not loaded")
        }
        val expectedSize = width * height * 4
        if (rgbaBytes.size < expectedSize) {
            throw IllegalArgumentException(
                "RGBA bytes too small: ${rgbaBytes.size} < $expectedSize (width=$width, height=$height)")
        }
        val result = nativeEncodeEtc2Rgba8(rgbaBytes, width, height)
        if (result == null) {
            throw RuntimeException("etcpack ETC2 encoding returned null (see logs)")
        }
        return result
    }

    /** Whether the etcpack encoder is usable on this device. */
    fun isAvailable(): Boolean = isLoaded

    @JvmStatic
    private external fun nativeEncodeEtc2Rgba8(
        rgbaBytes: ByteArray,
        width: Int,
        height: Int,
    ): ByteArray?
}
