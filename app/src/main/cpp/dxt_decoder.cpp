/*
 * dxt_decoder.cpp — DXT (S3TC) / BCn block-compressed texture decoder.
 *
 * See dxt_decoder.h for the format documentation and design notes.
 *
 * Implementation notes:
 *  - All reads are little-endian (block data is LE on disk).
 *  - Output is RGBA8888, row-major, top-down (pixel 0 = top-left).
 *  - Blocks are arranged in row-major top-down order: block 0 is top-left,
 *    block (blocks_x * blocks_y - 1) is bottom-right.
 *  - Each 4x4 block is laid out as 4 rows of 4 pixels each, top to bottom.
 *  - The 2-bit/4-bit lookup indices are arranged such that the LSB is the
 *    left-most pixel and the MSB is the right-most pixel of a row.
 *  - The lookup index for row r, column c is:
 *      (lookup_word >> ((r*4 + c) * bits_per_index)) & ((1 << bits_per_index) - 1)
 *
 *  For DXT1/BC4, the lookup word is 32 bits (LE) and bits_per_index=2.
 *  For DXT3 alpha, the lookup is 8 bytes (64 bits) packed 2 pixels per byte.
 *  For DXT5 alpha + BC4/BC5, the lookup is 6 bytes (48 bits) = 16 x 3-bit indices.
 */

#include "dxt_decoder.h"
#include <cstring>
#include <cmath>

namespace wepwawet {

// --- helpers for reading little-endian data ---
static inline uint16_t readU16LE(const uint8_t* p) {
    return (uint16_t)(p[0] | (p[1] << 8));
}

static inline uint32_t readU32LE(const uint8_t* p) {
    return (uint32_t)(p[0] | (p[1] << 8) | (p[2] << 16) | (p[3] << 24));
}

// --- color conversion: RGB565 -> RGBA8888 (alpha = 255) ---
static inline void rgb565ToRgba8888(uint16_t c, uint8_t* outRgba) {
    // R: bits 11..15 (5 bits), G: bits 5..10 (6 bits), B: bits 0..4 (5 bits)
    uint8_t r = (uint8_t)((c >> 11) & 0x1F);
    uint8_t g = (uint8_t)((c >> 5) & 0x3F);
    uint8_t b = (uint8_t)(c & 0x1F);
    // Expand 5-bit to 8-bit by replicating the top 3 bits
    outRgba[0] = (uint8_t)((r << 3) | (r >> 2));
    outRgba[1] = (uint8_t)((g << 2) | (g >> 4));
    outRgba[2] = (uint8_t)((b << 3) | (b >> 2));
    outRgba[3] = 255;
}

// --- decode a single DXT1 color block (8 bytes) into a 4x4 RGBA tile ---
// Sets pixel alpha to 0 for transparent-black pixels when use1BitAlpha=true
// and color0 <= color1 and lookup = 3.
static void decodeDxt1ColorBlock(const uint8_t* block,
                                  uint8_t* outRgbaTile, // 16*4 bytes
                                  bool use1BitAlpha,
                                  bool forceFourColor = false) {
    uint16_t color0 = readU16LE(block);
    uint16_t color1 = readU16LE(block + 2);
    uint32_t lookup = readU32LE(block + 4);

    // Decode the 4-color palette
    uint8_t palette[4][4]; // [palette_index][RGBA]
    rgb565ToRgba8888(color0, palette[0]);
    rgb565ToRgba8888(color1, palette[1]);

    // Determine which mode (4-color or 3-color + transparent)
    // BC2/BC3 (DXT3/DXT5) reuse the BC1 colour block but ALWAYS decode it in
    // 4-colour mode; the colour0 <= colour1 3-colour+transparent switch is BC1-only.
    bool useTransparent = (color0 <= color1) && !forceFourColor;

    if (!useTransparent) {
        // 4-color mode: c2 = (2*c0 + c1) / 3, c3 = (c0 + 2*c1) / 3
        for (int i = 0; i < 3; i++) {
            // 8-bit channel interpolation, exact (matches Direct3D spec)
            // Use the rounded value: (2*color0 + color1 + 1) / 3 for round-to-nearest.
            palette[2][i] = (uint8_t)((2 * palette[0][i] + palette[1][i] + 1) / 3);
            palette[3][i] = (uint8_t)((palette[0][i] + 2 * palette[1][i] + 1) / 3);
        }
        palette[2][3] = 255;
        palette[3][3] = 255;
    } else {
        // 3-color + transparent mode
        // c2 = (2*c0 + c1) / 3, c3 = transparent black (r=g=b=a=0)
        for (int i = 0; i < 3; i++) {
            palette[2][i] = (uint8_t)((2 * palette[0][i] + palette[1][i] + 1) / 3);
        }
        palette[2][3] = 255;
        palette[3][0] = palette[3][1] = palette[3][2] = 0;
        palette[3][3] = use1BitAlpha ? 0 : 255; // DXT1: 0 for transparent; BC1 without alpha: 255
    }

    // Apply lookup
    for (int i = 0; i < 16; i++) {
        uint32_t idx = (lookup >> (i * 2)) & 0x3;
        // pixel i is at row (i/4), column (i%4)
        // output is laid out row-major: tile[(i/4)*4 + (i%4)]
        memcpy(outRgbaTile + i * 4, palette[idx], 4);
    }
}

// --- decode DXT3 explicit alpha block (8 bytes of 4-bit alphas) ---
// 8 bytes = 64 bits, packed 2 alphas per byte (each nibble is one alpha).
// Layout: byte 0 = (alpha[0], alpha[1]), byte 1 = (alpha[2], alpha[3]), ...
// Each alpha is 4 bits (0..15), expanded to 8 bits by (a | (a << 4)).
static void decodeDxt3AlphaBlock(const uint8_t* alphaBlock,
                                  uint8_t* outRgbaTile) { // modifies alpha channel
    for (int i = 0; i < 16; i++) {
        // byte index: i/2, nibble: i%2 (LSB first)
        uint8_t byte = alphaBlock[i / 2];
        uint8_t a4 = (uint8_t)((byte >> ((i % 2) * 4)) & 0xF);
        // Expand 4-bit to 8-bit: a8 = a4 * 17 (a4 | (a4 << 4))
        uint8_t a8 = (uint8_t)(a4 | (a4 << 4));
        outRgbaTile[i * 4 + 3] = a8;
    }
}

// --- decode DXT5 interpolated alpha block (8 bytes) ---
// 8 bytes = alpha0 (1) + alpha1 (1) + 6 bytes (48 bits) of 3-bit indices (16 pixels * 3 bits).
// The 6 bytes form a 48-bit LE value. Index for pixel i = (val48 >> (i*3)) & 0x7.
//
// Palette (when alpha0 > alpha1):
//   a0 = alpha0, a1 = alpha1
//   a2 = (6*a0 + 1*a1) / 7
//   a3 = (5*a0 + 2*a1) / 7
//   a4 = (4*a0 + 3*a1) / 7
//   a5 = (3*a0 + 4*a1) / 7
//   a6 = (2*a0 + 5*a1) / 7
//   a7 = (1*a0 + 6*a1) / 7
//
// Palette (when alpha0 <= alpha1):
//   a0 = alpha0, a1 = alpha1
//   a2 = (4*a0 + 1*a1) / 5
//   a3 = (3*a0 + 2*a1) / 5
//   a4 = (2*a0 + 3*a1) / 5
//   a5 = (1*a0 + 4*a1) / 5
//   a6 = 0
//   a7 = 255
static void decodeDxt5AlphaBlock(const uint8_t* alphaBlock,
                                  uint8_t* outRgbaTile) { // modifies alpha channel
    uint8_t a0 = alphaBlock[0];
    uint8_t a1 = alphaBlock[1];

    // Read the 6-byte (48-bit) lookup value as a single LE integer.
    // Each byte is just appended.
    uint64_t lookup = 0;
    for (int i = 0; i < 6; i++) {
        lookup |= ((uint64_t)alphaBlock[2 + i]) << (i * 8);
    }

    uint8_t palette[8];
    palette[0] = a0;
    palette[1] = a1;
    if (a0 > a1) {
        // 8-alpha interpolated
        palette[2] = (uint8_t)((6 * a0 + a1) / 7);
        palette[3] = (uint8_t)((5 * a0 + 2 * a1) / 7);
        palette[4] = (uint8_t)((4 * a0 + 3 * a1) / 7);
        palette[5] = (uint8_t)((3 * a0 + 4 * a1) / 7);
        palette[6] = (uint8_t)((2 * a0 + 5 * a1) / 7);
        palette[7] = (uint8_t)((a0 + 6 * a1) / 7);
    } else {
        // 6-alpha interpolated + 2 special
        palette[2] = (uint8_t)((4 * a0 + a1) / 5);
        palette[3] = (uint8_t)((3 * a0 + 2 * a1) / 5);
        palette[4] = (uint8_t)((2 * a0 + 3 * a1) / 5);
        palette[5] = (uint8_t)((a0 + 4 * a1) / 5);
        palette[6] = 0;
        palette[7] = 255;
    }

    // Apply alpha lookup to each pixel
    for (int i = 0; i < 16; i++) {
        uint8_t idx = (uint8_t)((lookup >> (i * 3)) & 0x7);
        outRgbaTile[i * 4 + 3] = palette[idx];
    }
}

// --- decode BC4 single-channel block (8 bytes, R only) ---
// Same layout as DXT5 alpha, but the decoded value goes into R, G=0, B=0, A=255.
// (Some apps use BC4 for grayscale — we set R=G=B=value so the result is visible.)
static void decodeBc4Block(const uint8_t* block,
                            uint8_t* outRgbaTile) {
    // Treat the BC4 block as a DXT5 alpha block — use the same palette logic
    // Then assign the alpha value to R, G, B, and set A=255.
    uint8_t a0 = block[0];
    uint8_t a1 = block[1];
    uint64_t lookup = 0;
    for (int i = 0; i < 6; i++) {
        lookup |= ((uint64_t)block[2 + i]) << (i * 8);
    }

    uint8_t palette[8];
    palette[0] = a0;
    palette[1] = a1;
    if (a0 > a1) {
        palette[2] = (uint8_t)((6 * a0 + a1) / 7);
        palette[3] = (uint8_t)((5 * a0 + 2 * a1) / 7);
        palette[4] = (uint8_t)((4 * a0 + 3 * a1) / 7);
        palette[5] = (uint8_t)((3 * a0 + 4 * a1) / 7);
        palette[6] = (uint8_t)((2 * a0 + 5 * a1) / 7);
        palette[7] = (uint8_t)((a0 + 6 * a1) / 7);
    } else {
        palette[2] = (uint8_t)((4 * a0 + a1) / 5);
        palette[3] = (uint8_t)((3 * a0 + 2 * a1) / 5);
        palette[4] = (uint8_t)((2 * a0 + 3 * a1) / 5);
        palette[5] = (uint8_t)((a0 + 4 * a1) / 5);
        palette[6] = 0;
        palette[7] = 255;
    }

    for (int i = 0; i < 16; i++) {
        uint8_t idx = (uint8_t)((lookup >> (i * 3)) & 0x7);
        uint8_t v = palette[idx];
        outRgbaTile[i * 4 + 0] = v;  // R
        outRgbaTile[i * 4 + 1] = v;  // G (grayscale)
        outRgbaTile[i * 4 + 2] = v;  // B (grayscale)
        outRgbaTile[i * 4 + 3] = 255; // A
    }
}

// --- decode BC5 two-channel block (16 bytes, R + G) ---
// Two BC4 blocks back-to-back: first is R, second is G. B=0, A=255.
static void decodeBc5Block(const uint8_t* block,
                            uint8_t* outRgbaTile) {
    // Decode R block (bytes 0..7)
    uint8_t r0 = block[0];
    uint8_t r1 = block[1];
    uint64_t rLookup = 0;
    for (int i = 0; i < 6; i++) {
        rLookup |= ((uint64_t)block[2 + i]) << (i * 8);
    }

    // Decode G block (bytes 8..15)
    uint8_t g0 = block[8];
    uint8_t g1 = block[9];
    uint64_t gLookup = 0;
    for (int i = 0; i < 6; i++) {
        gLookup |= ((uint64_t)block[10 + i]) << (i * 8);
    }

    auto palette = [](uint8_t v0, uint8_t v1, uint8_t* out) {
        out[0] = v0;
        out[1] = v1;
        if (v0 > v1) {
            out[2] = (uint8_t)((6 * v0 + v1) / 7);
            out[3] = (uint8_t)((5 * v0 + 2 * v1) / 7);
            out[4] = (uint8_t)((4 * v0 + 3 * v1) / 7);
            out[5] = (uint8_t)((3 * v0 + 4 * v1) / 7);
            out[6] = (uint8_t)((2 * v0 + 5 * v1) / 7);
            out[7] = (uint8_t)((v0 + 6 * v1) / 7);
        } else {
            out[2] = (uint8_t)((4 * v0 + v1) / 5);
            out[3] = (uint8_t)((3 * v0 + 2 * v1) / 5);
            out[4] = (uint8_t)((2 * v0 + 3 * v1) / 5);
            out[5] = (uint8_t)((v0 + 4 * v1) / 5);
            out[6] = 0;
            out[7] = 255;
        }
    };

    uint8_t rPalette[8], gPalette[8];
    palette(r0, r1, rPalette);
    palette(g0, g1, gPalette);

    for (int i = 0; i < 16; i++) {
        uint8_t rIdx = (uint8_t)((rLookup >> (i * 3)) & 0x7);
        uint8_t gIdx = (uint8_t)((gLookup >> (i * 3)) & 0x7);
        outRgbaTile[i * 4 + 0] = rPalette[rIdx]; // R
        outRgbaTile[i * 4 + 1] = gPalette[gIdx]; // G
        outRgbaTile[i * 4 + 2] = 0;               // B (BC5 has no blue channel)
        outRgbaTile[i * 4 + 3] = 255;             // A
    }
}

// ============================================================================
// BC6H (BPTC float HDR) decoder — v0.7.24 rewrite
// ============================================================================
// v0.7.24 (H-N3, H-N4): The v0.6.0 hand-rolled BC6H decoder was a placeholder —
// a wrong-size partition table, a bogus endpoint bit layout (it read
// endpoints per-component-contiguously instead of the spec's scrambled
// per-mode layout), no delta-endpoint transform, and an unquantizer that
// produced negative float16 (sign bit) for values >= half scale.
//
// The decode now delegates to the vendored bcdec.h (iOrange, MIT/public
// domain) which implements the spec bit layouts exactly. bcdec outputs
// RGB half-floats; we then tone-map HDR -> LDR (Reinhard + sRGB gamma),
// same policy as before.

#define BCDEC_IMPLEMENTATION
#include "bcdec/bcdec.h"

// Half-float -> float (kept for the tone-mapping pass below).
static inline float halfToFloatBc6(uint16_t h) {
    uint32_t sign = (uint32_t)(h >> 15) & 1;
    uint32_t exp  = (uint32_t)(h >> 10) & 0x1F;
    uint32_t man  = (uint32_t)(h) & 0x3FF;
    uint32_t f;
    if (exp == 0) {
        if (man == 0) {
            f = sign << 31;
        } else {
            // Subnormal half -> normalized float
            exp = 127 - 15 + 1;
            while ((man & 0x400) == 0) { man <<= 1; exp--; }
            man &= 0x3FF;
            f = (sign << 31) | (exp << 23) | (man << 13);
        }
    } else if (exp == 0x1F) {
        // Inf / NaN
        f = (sign << 31) | (0xFF << 23) | (man << 13);
    } else {
        f = (sign << 31) | ((exp - 15 + 127) << 23) | (man << 13);
    }
    float result;
    memcpy(&result, &f, sizeof(result));
    return result;
}

// Decode a single BC6H block (16 bytes) to a 4x4 RGBA tile.
// HDR float values are tone-mapped to RGBA8888 via Reinhard tone mapping
// (luminance-based, then gamma-encode). Alpha is always 255 (BC6H has none).
static void decodeBc6hBlock(const uint8_t* block,
                              uint8_t* outRgbaTile) {
    // bcdec decodes into 4 rows x 4 texels x 3 half-float components.
    uint16_t rgb[4][4 * 3];
    bcdec_bc6h_half(block, rgb, 4 * 3, 0 /* unsigned */);

    for (int i = 0; i < 4; i++) {
        for (int j = 0; j < 4; j++) {
            float r = halfToFloatBc6(rgb[i][j * 3 + 0]);
            float g = halfToFloatBc6(rgb[i][j * 3 + 1]);
            float b = halfToFloatBc6(rgb[i][j * 3 + 2]);

            // Negative values can occur for signed content decoded as
            // unsigned; clamp before tone mapping.
            if (r < 0.0f) r = 0.0f;
            if (g < 0.0f) g = 0.0f;
            if (b < 0.0f) b = 0.0f;

            // Reinhard tone mapping (HDR -> LDR): out = c / (1 + L)
            float L = (r + g + b) / 3.0f;
            float scale = 1.0f / (1.0f + L);
            r *= scale;
            g *= scale;
            b *= scale;

            // sRGB gamma encode
            auto gamma = [](float v) -> uint8_t {
                if (v <= 0.0031308f) return (uint8_t)(v * 12.92f * 255.0f + 0.5f);
                return (uint8_t)((1.055f * powf(v, 1.0f / 2.4f) - 0.055f) * 255.0f + 0.5f);
            };

            outRgbaTile[(i * 4 + j) * 4 + 0] = gamma(r);
            outRgbaTile[(i * 4 + j) * 4 + 1] = gamma(g);
            outRgbaTile[(i * 4 + j) * 4 + 2] = gamma(b);
            outRgbaTile[(i * 4 + j) * 4 + 3] = 255;
        }
    }
}

// ============================================================================
// BC7 (BPTC) decoder — v0.7.24 rewrite
// ============================================================================
// v0.7.24 (C-N2, H-N1, H-N2): The v0.6.0 hand-rolled BC7 decoder was
// structurally wrong: bogus interpolation weight tables (a linear 0..64 ramp
// for 4-bit indices), only 4 of the 64 2-subset partition patterns (the rest
// zero), no 3-subset table at all (mode 2), no P-bit handling, per-endpoint
// (instead of per-channel-interleaved) endpoint bit layout, and guessed anchor
// positions. Every BC7 texture shipped with wrong gradients.
//
// The decode now delegates to the vendored bcdec.h (iOrange, MIT/public
// domain), which implements the Khronos BPTC spec bit-exactly: correct weight
// tables ({0,21,43,64} / {0,9,18,27,37,46,55,64} / {0,4,9,13,17,21,26,30,34,
// 38,43,47,51,55,60,64}), the full 64-pattern 2-subset and 3-subset partition
// tables, P-bit expansion, anchor (fix-up) index handling, channel rotation
// for modes 4/5, and the mode-4 index-selection bit.

// Decode a single BC7 block (16 bytes) to a 4x4 RGBA tile.
static void decodeBc7Block(const uint8_t* block,
                              uint8_t* outRgbaTile) {
    // bcdec writes 4 rows x 4 texels x 4 RGBA bytes.
    uint8_t rgba[4][4 * 4];
    bcdec_bc7(block, rgba, 4 * 4);
    for (int i = 0; i < 4; i++) {
        for (int j = 0; j < 4; j++) {
            outRgbaTile[(i * 4 + j) * 4 + 0] = rgba[i][j * 4 + 0];
            outRgbaTile[(i * 4 + j) * 4 + 1] = rgba[i][j * 4 + 1];
            outRgbaTile[(i * 4 + j) * 4 + 2] = rgba[i][j * 4 + 2];
            outRgbaTile[(i * 4 + j) * 4 + 3] = rgba[i][j * 4 + 3];
        }
    }
}

// --- main decoder function ---
bool decodeDxt(const uint8_t* srcData, size_t srcLen,
                int width, int height,
                DxtFormat fmt,
                uint8_t* outRgba, size_t outLen,
                bool use1BitAlpha) {
    if (srcData == nullptr || outRgba == nullptr) return false;
    if (width <= 0 || height <= 0) return false;

    int blocksX = (width + 3) / 4;
    int blocksY = (height + 3) / 4;
    int blockSize = dxtBlockSize(fmt);
    size_t requiredSrc = (size_t)blocksX * blocksY * blockSize;
    if (srcLen < requiredSrc) return false;

    size_t requiredOut = (size_t)width * height * 4;
    if (outLen < requiredOut) return false;

    // Initialize output to zero (so unused edge pixels are not garbage)
    memset(outRgba, 0, requiredOut);

    bool isDxt1 = (fmt == DxtFormat::DXT1);
    bool isDxt3 = (fmt == DxtFormat::DXT3);
    bool isDxt5 = (fmt == DxtFormat::DXT5);
    bool isBC4  = (fmt == DxtFormat::BC4);
    bool isBC5  = (fmt == DxtFormat::BC5);
    bool isBC6H = (fmt == DxtFormat::BC6H);
    bool isBC7  = (fmt == DxtFormat::BC7);

    for (int by = 0; by < blocksY; by++) {
        for (int bx = 0; bx < blocksX; bx++) {
            const uint8_t* block = srcData + ((size_t)by * blocksX + bx) * blockSize;
            uint8_t tile[16 * 4]; // 16 pixels * 4 bytes RGBA

            // Decode block into a 4x4 tile
            if (isDxt1) {
                // DXT1: 8 bytes, 1-bit alpha (transparent when color0 <= color1 and lookup=3)
                // v0.6.0 (parity §6 6-4): use the caller-provided use1BitAlpha flag
                // (true for DXT1a with alpha, false for opaque DXT1)
                decodeDxt1ColorBlock(block, tile, use1BitAlpha);
            } else if (isDxt3) {
                // DXT3: 8 bytes alpha + 8 bytes color
                decodeDxt1ColorBlock(block + 8, tile, /*use1BitAlpha=*/false, /*forceFourColor=*/true);
                decodeDxt3AlphaBlock(block, tile);
            } else if (isDxt5) {
                // DXT5: 8 bytes alpha + 8 bytes color
                decodeDxt1ColorBlock(block + 8, tile, /*use1BitAlpha=*/false, /*forceFourColor=*/true);
                decodeDxt5AlphaBlock(block, tile);
            } else if (isBC4) {
                // BC4: 8 bytes single channel
                decodeBc4Block(block, tile);
            } else if (isBC5) {
                // BC5: 16 bytes two channels
                decodeBc5Block(block, tile);
            } else if (isBC6H) {
                // BC6H: 16 bytes HDR RGB float — v0.6.0 (parity §6 6-9)
                decodeBc6hBlock(block, tile);
            } else if (isBC7) {
                // BC7: 16 bytes high-quality RGBA — v0.6.0 (parity §6 6-10)
                decodeBc7Block(block, tile);
            } else {
                return false; // unknown format
            }

            // Copy the tile into the output, clipping at image edges
            for (int py = 0; py < 4; py++) {
                int destY = by * 4 + py;
                if (destY >= height) break;
                for (int px = 0; px < 4; px++) {
                    int destX = bx * 4 + px;
                    if (destX >= width) break;
                    uint8_t* dest = outRgba + ((size_t)destY * width + destX) * 4;
                    memcpy(dest, tile + (py * 4 + px) * 4, 4);
                }
            }
        }
    }

    return true;
}

} // namespace wepwawet
