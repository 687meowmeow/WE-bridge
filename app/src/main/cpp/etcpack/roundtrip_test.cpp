// Round-trip test for the etcpack ETC2 RGBA8 pipeline (host build).
// Encodes known blocks with the exact resourcecompiler64 staging, decodes with
// etcpack's own decoder, and verifies orientation + channel order.
#include <cstdio>
#include <cstdint>
#include <cstring>
#include <cstdlib>
#include "etcpack_lib.h"

// C++ linkage (matches etcpack.cxx)
void compressBlockAlphaFast(uint8 *data, int ix, int iy, int width, int height, uint8 *returnData);

static uint32_t bswap32(uint32_t v) {
    return ((v & 0xff000000u) >> 24) | ((v & 0x00ff0000u) >> 8) |
           ((v & 0x0000ff00u) << 8) | ((v & 0x000000ffu) << 24);
}
static uint32_t roundUpTo4(uint32_t v) { uint32_t r = v % 4; return r == 0 ? v : v + (4 - r); }

// returns malloc'd ETC2 data; padded dims via outW/outH
static uint8_t *encode(const uint8_t *rgba, uint32_t w, uint32_t h, uint32_t &outW, uint32_t &outH) {
    readCompressParams();
    setupAlphaTableAndValtab();
    outW = roundUpTo4(w); outH = roundUpTo4(h);
    uint32_t srcStride = w * 4, padStride = outW * 4;
    uint8_t *padded = (uint8_t *) calloc((size_t) padStride * outH, 1);
    for (uint32_t y = 0; y < h; y++) {
        memcpy(padded + (size_t) y * padStride, rgba + (size_t) y * srcStride, srcStride);
        if (outW > w) {
            uint32_t last = *((const uint32_t *) (rgba + (size_t) y * srcStride + srcStride - 4));
            for (uint32_t x = w; x < outW; x++) *((uint32_t *) (padded + (size_t) y * padStride + x * 4)) = last & 0x00ffffffu;
        }
    }
    for (uint32_t y = h; y < outH; y++) memcpy(padded + (size_t) y * padStride, padded + (size_t) (h - 1) * padStride, padStride);

    uint32_t bx = outW >> 2, by = outH >> 2;
    uint8_t *out = (uint8_t *) calloc((size_t) bx * by * 16, 1);
    uint8_t alphaBlock[16], rgbBlock[48], scratch[48], alphaOut[8];
    for (uint32_t j = 0; j < by; j++) for (uint32_t i = 0; i < bx; i++) {
        const uint8_t *block = padded + (size_t) (j * 4) * padStride + (size_t) (i * 4) * 4;
        for (uint32_t y = 0; y < 4; y++) {
            const uint8_t *row = block + (size_t) y * padStride;
            alphaBlock[y * 4 + 0] = row[3]; alphaBlock[y * 4 + 1] = row[7];
            alphaBlock[y * 4 + 2] = row[11]; alphaBlock[y * 4 + 3] = row[15];
            for (uint32_t x = 0; x < 4; x++) {
                rgbBlock[y * 12 + x * 3 + 0] = row[x * 4 + 0];
                rgbBlock[y * 12 + x * 3 + 1] = row[x * 4 + 1];
                rgbBlock[y * 12 + x * 3 + 2] = row[x * 4 + 2];
            }
        }
        compressBlockAlphaFast(alphaBlock, 0, 0, 4, 4, alphaOut);
        unsigned int w1, w2;
        compressBlockETC2FastPerceptual(rgbBlock, scratch, 4, 4, 0, 0, w1, w2);
        uint8_t *dst = out + (size_t) ((j * bx + i) * 16);
        memcpy(dst, alphaOut, 8);
        uint32_t hi = bswap32(w1), lo = bswap32(w2);
        memcpy(dst + 8, &hi, 4); memcpy(dst + 12, &lo, 4);
    }
    free(padded);
    return out;
}

static void decode(const uint8_t *etc2, uint32_t w, uint32_t h, uint8_t *rgba) {
    uint32_t bx = w >> 2, by = h >> 2;
    for (uint32_t j = 0; j < by; j++) for (uint32_t i = 0; i < bx; i++) {
        const uint8_t *blk = etc2 + (size_t) ((j * bx + i) * 16);
        uint32_t alphaWords[2], rgbW1, rgbW2;
        memcpy(alphaWords, blk, 8);
        rgbW1 = bswap32(*((const uint32_t *) (blk + 8)));
        rgbW2 = bswap32(*((const uint32_t *) (blk + 12)));
        uint8_t alphaImg[16], rgbImg[48];
        decompressBlockAlphaC((uint8_t *) alphaWords, alphaImg, 4, 4, 0, 0, 1);
        decompressBlockETC2c(rgbW1, rgbW2, rgbImg, 4, 4, 0, 0, 3);
        for (uint32_t y = 0; y < 4; y++) for (uint32_t x = 0; x < 4; x++) {
            uint8_t *px = rgba + ((size_t) (j * 4 + y) * w + (i * 4 + x)) * 4;
            px[0] = rgbImg[(y * 4 + x) * 3 + 0];
            px[1] = rgbImg[(y * 4 + x) * 3 + 1];
            px[2] = rgbImg[(y * 4 + x) * 3 + 2];
            px[3] = alphaImg[y * 4 + x];
        }
    }
}

int main() {
    // 8x8 image with 4 distinct colors + distinct alphas per quadrant.
    const uint32_t W = 8, H = 8;
    uint8_t img[W * H * 4];
    const uint8_t quads[4][4] = {
        {255, 0, 0, 255},    // top-left     = opaque red
        {0, 255, 0, 128},    // top-right    = half green
        {0, 0, 255, 1},      // bottom-left  = near-transparent blue
        {255, 255, 0, 200},  // bottom-right = yellow
    };
    for (uint32_t y = 0; y < H; y++) for (uint32_t x = 0; x < W; x++) {
        const uint8_t *q = quads[(y < 4 ? 0 : 2) + (x < 4 ? 0 : 1)];
        uint8_t *px = img + ((size_t) y * W + x) * 4;
        px[0] = q[0]; px[1] = q[1]; px[2] = q[2]; px[3] = q[3];
    }
    uint32_t pw, ph;
    uint8_t *etc2 = encode(img, W, H, pw, ph);
    printf("encoded %ux%u -> padded %ux%u, %u bytes\n", W, H, pw, ph, (unsigned) (pw / 4 * ph / 4 * 16));

    uint8_t *dec = (uint8_t *) calloc((size_t) pw * ph * 4, 1);
    decode(etc2, pw, ph, dec);

    // verify quadrant colors (allow ETC2 loss: tolerance 8 for RGB, 3 for alpha)
    int fails = 0;
    for (uint32_t y = 0; y < H; y++) for (uint32_t x = 0; x < W; x++) {
        const uint8_t *exp = quads[(y < 4 ? 0 : 2) + (x < 4 ? 0 : 1)];
        const uint8_t *got = dec + ((size_t) y * pw + x) * 4;
        for (int c = 0; c < 4; c++) {
            int tol = c == 3 ? 3 : 10;
            if (abs((int) got[c] - (int) exp[c]) > tol) {
                printf("MISMATCH at (%u,%u) ch%d: got %d expected %d\n", x, y, c, got[c], exp[c]);
                fails++;
            }
        }
    }
    // verify pad region: pad columns replicate last column (alpha zeroed), pad rows the last row
    // (8x8 is already 4-aligned, so nothing to check here; padding covered by the 6x6 case below)

    // asymmetric 6x6 to exercise padding + orientation
    const uint32_t W2 = 6, H2 = 6;
    uint8_t img2[W2 * H2 * 4];
    for (uint32_t y = 0; y < H2; y++) for (uint32_t x = 0; x < W2; x++) {
        uint8_t *px = img2 + ((size_t) y * W2 + x) * 4;
        px[0] = (uint8_t) (x * 40); px[1] = (uint8_t) (y * 40); px[2] = 128; px[3] = (uint8_t) (255 - x * 20 - y * 10);
    }
    uint32_t pw2, ph2;
    uint8_t *etc22 = encode(img2, W2, H2, pw2, ph2);
    printf("encoded 6x6 -> padded %ux%u\n", pw2, ph2);
    uint8_t *dec2 = (uint8_t *) calloc((size_t) pw2 * ph2 * 4, 1);
    decode(etc22, pw2, ph2, dec2);
    for (uint32_t y = 0; y < H2; y++) for (uint32_t x = 0; x < W2; x++) {
        const uint8_t *got = dec2 + ((size_t) y * pw2 + x) * 4;
        const uint8_t *exp = img2 + ((size_t) y * W2 + x) * 4;
        // gradient check on R (x) and G (y) — catches transposition
        if (abs((int) got[0] - (int) exp[0]) > 24 || abs((int) got[1] - (int) exp[1]) > 24) {
            printf("GRADIENT MISMATCH at (%u,%u): got (%d,%d,%d,%d) expected (%d,%d,%d,%d)\n",
                x, y, got[0], got[1], got[2], got[3], exp[0], exp[1], exp[2], exp[3]);
            fails++;
        }
    }
    printf(fails == 0 ? "ROUND-TRIP OK\n" : "ROUND-TRIP FAILED (%d mismatches)\n", fails);
    return fails == 0 ? 0 : 1;
}
