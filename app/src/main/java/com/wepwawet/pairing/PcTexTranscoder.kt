package com.wepwawet.pairing

import java.io.ByteArrayOutputStream

/**
 * PC-exact .tex transcoder — replicates what Wallpaper Engine PC's
 * resourcecompiler64.exe produces when the transcoder
 * (wallpaperui.exe `convertProjectToMobile`, 0x14003d0e0) runs
 *
 *   resourcecompiler64.exe -transcode -i "<in>" -o "<out>" -f ETC2 \
 *       [-c force] -shrink <n> -maxmipmaps 1
 *
 * in-place on every .tex of the transcoded project copy (wallpaperui builds
 * the command at 0x14003f884–0x14003f95d; `-c force` is appended only when
 * the file's parent path is `materials`/`materials\masks` AND the paired
 * device's `compression` setting != "high_quality").
 *
 * ════════════════════════════════════════════════════════════════════════
 * v0.7.18 — the decision tree, decoded line-by-line from
 * resourcecompiler64.exe FUN_140063c70 (+ the in-place call semantics):
 *
 *  • Input routing (the caller mirrors this before calling in):
 *      – formats 8 (RG88), 9 (R8), imageCount > 1 (animated/spritesheet),
 *        TEXI flags & 0x20, and format 1 (RGB888) without `-c force`
 *        → the tool COPIES the file through unchanged.
 *      – formats the switch does not handle (2, 3, 5, 10, 11, 13, 14, 20,
 *        anything > 12) → the tool ERRORS; wallpaperui IGNORES the exit
 *        code and the original bytes ship in the .mpkg.
 *      – handled: 0 (RGBA8888, incl. PNG/JPEG bodies), 1 (RGB888, forced),
 *        4 (DXT5), 6 (DXT3), 7 (DXT1), 12 (BC7 — NOT BC5; internal code
 *        0x35 = the "Compressing as BC7" path).
 *
 *  • Output format (there is NO size threshold — the previous ">2048 px ⇒
 *    ETC2" rule was a coincidence of one ground-truth file):
 *      – DXT/BC sources  → ETC2_RGBA8 (format 5), via etcpack (the PC's
 *        encoder — see [EtcpackEncoder]); dims padded to 4.
 *      – format 0/1 without force → RGBA8888 (format 0).
 *      – with force → ETC2 for everything.
 *
 *  • `-shrink <n>` (the device's texture-reduction setting): applied only
 *    when BOTH source dims > 128 (FUN_140063c70 @1400645e1); the new dims
 *    are w/n, h/n (integer division); factor clamped to 1..8.
 *
 *  • `-maxmipmaps 1` → single mip; the output header is always normalized
 *    to TEXB0004 with texbFormat = -1, imageCount = 1.
 *
 *  • LZ4 storage (FUN_14005ab90):
 *      – attempted ONLY when the body is larger than 16383 bytes;
 *      – kept ONLY when (float)compressed / (float)raw < 0.95
 *        (the "Discarding compression, not worth it." threshold);
 *      – `LZ4_compress_default` semantics (lz4-java fastCompressor verified
 *        byte-identical on PC bodies).
 *
 *  • TEXI flags of the output: `(sourceFlags AND NOT 0x40000000)`
 *    (bit 30 stripped, FUN_140063c70 @1400641af) `OR 0x08 when the body is
 *    stored uncompressed`. Ground truth: waterflowphase (raw 4096 B body,
 *    under the 16 KiB LZ4 floor) 0x00→0x08; output-onlinepngtools (LZ4 kept)
 *    0x02→0x02 — the 0x08 bit tracks RAW storage, NOT the mip count.
 *
 *  • Output dims: RGBA path uses the decoded image's own dims (un-padded);
 *    the ETC2 path uses the 4-padded dims in texW/texH AND the mip entry
 *    (FUN_1400598c0 pads; the padded pixels repeat the row's last pixel
 *    with alpha zeroed for pad columns and duplicate the last row).
 *
 * Output format:
 *
 *   TEXV0005\0                     (9 bytes)
 *   TEXI0001\0                     (9 bytes)
 *   u32 texiFormat                 0 = RGBA8888, 5 = ETC2_RGBA8
 *   u32 flags                      source flags, bit 30 stripped; |= 0x08 raw
 *   u32 texW/texH                  padded dims (ETC2) or image dims (RGBA)
 *   u32 imgW/imgH                  same
 *   u32 color                      preserved from the source TEXI
 *   TEXB0004\0                     (9 bytes — TEXB0003 normalized to 0004)
 *   u32 imageCount = 1
 *   u32 texbFormat = -1            (always, even when the source said 13)
 *   u32 isVideoMp4 = 0
 *   u32 mipmapCount = 1
 *   u32 mipW, mipH
 *   u32 isLz4
 *   u32 decompSize                 body size when uncompressed
 *   u32 byteCount                  stored body bytes
 *   body
 */
object PcTexTranscoder {

    /**
     * The LZ4 floor — bodies of 16383 bytes or fewer are NEVER compressed
     * (resourcecompiler64 FUN_14005ab90: `if (0x3fff < size)`).
     */
    const val LZ4_MIN_BYTES = 0x3fff

    /**
     * The "worth it" threshold — compression is kept only when
     * (float)compressed / (float)raw < 0.95
     * (DAT_140587bf8 = 0.95f, compared as `if (0.95f <= ratio) discard`).
     */
    const val LZ4_BENEFIT_RATIO = 0.95f

    /** TEXI flag the PC ORs in when a body is stored uncompressed. */
    const val FLAG_UNCOMPRESSED = 0x08

    /** TEXI flag bit the PC strips from the output. */
    const val FLAG_STRIP_MASK = 0x40000000

    /** The shrink gate — both dims must exceed 128 px (0x80). */
    const val SHRINK_MIN_DIM = 0x80

    /** The shrink factor clamp (FUN_140063c70: min(8, n), max(1, …)). */
    const val SHRINK_MAX_FACTOR = 8

    /** Fully parsed .tex header (TEXB0003 and TEXB0004 layouts). */
    class TexInfo(
        val texiFormat: Int,
        val flags: Int,
        val color: Int,
        val texW: Int, val texH: Int,
        val imgW: Int, val imgH: Int,
        val texbVersion: Int,          // 3 or 4
        val mipCount: Int,             // 1 for TEXB0003 (implicit)
        val imageCount: Int,           // animated / spritesheet textures carry > 1
        val mipW: Int, val mipH: Int,
        val bodyIsLz4: Boolean,
        val decompSize: Int,
        val byteCount: Int,            // the stored mip-0 block size (may be 0/bogus)
        val bodyStart: Int,            // 87 (TEXB0003) or 91 (TEXB0004)
        val bodyLen: Int,              // bytes from bodyStart to EOF (may include trailing mips)
    )

    /** Parse a .tex header; returns null when the file is not a recognizable TEXV. */
    fun parse(tex: ByteArray): TexInfo? {
        if (tex.size < 92) return null
        if (!String(tex, 0, 8, Charsets.US_ASCII).startsWith("TEXV")) return null
        if (!String(tex, 9, 8, Charsets.US_ASCII).startsWith("TEXI")) return null
        val texb = String(tex, 46, 8, Charsets.US_ASCII)
        val texiFormat = readU32(tex, 18)
        val flags = readU32(tex, 22)
        val texW = readU32(tex, 26)
        val texH = readU32(tex, 30)
        val imgW = readU32(tex, 34)
        val imgH = readU32(tex, 38)
        val color = readU32(tex, 42)
        return when {
            texb.startsWith("TEXB0003") -> {
                val imageCount = readU32(tex, 55)
                TexInfo(
                    texiFormat, flags, color, texW, texH, imgW, imgH,
                    texbVersion = 3, mipCount = 1, imageCount = imageCount,
                    mipW = readU32(tex, 67), mipH = readU32(tex, 71),
                    bodyIsLz4 = readU32(tex, 75) != 0,
                    decompSize = readU32(tex, 79),
                    byteCount = readU32(tex, 83),
                    bodyStart = 87, bodyLen = tex.size - 87,
                )
            }
            texb.startsWith("TEXB0004") -> {
                val imageCount = readU32(tex, 55)
                TexInfo(
                    texiFormat, flags, color, texW, texH, imgW, imgH,
                    texbVersion = 4, mipCount = readU32(tex, 67), imageCount = imageCount,
                    mipW = readU32(tex, 71), mipH = readU32(tex, 75),
                    bodyIsLz4 = readU32(tex, 79) != 0,
                    decompSize = readU32(tex, 83),
                    byteCount = readU32(tex, 87),
                    bodyStart = 91, bodyLen = tex.size - 91,
                )
            }
            else -> null
        }
    }

    /**
     * The mip-0 body of [tex]: sliced to the stored byteCount when that is
     * plausible (animated/multi-mip .tex files append further LZ4 blocks
     * after mip 0 — decompressing the whole remainder corrupts or fails),
     * otherwise everything from bodyStart to EOF.
     */
    fun bodyBytes(tex: ByteArray): ByteArray {
        val info = parse(tex) ?: return ByteArray(0)
        val rest = tex.copyOfRange(info.bodyStart, tex.size)
        return if (info.byteCount in 1..rest.size) rest.copyOf(info.byteCount) else rest
    }

    /**
     * The PC's ETC2-vs-RGBA decision, given the SOURCE format and the force
     * flag (wallpaperui appends `-c force` only for materials textures when
     * the device compression setting != "high_quality").
     *
     * DXT/BC sources (4/6/7/12) → ETC2. Format 0 (RGBA8888, including
     * PNG/JPEG bodies) and 1 (RGB888) → ETC2 only when forced.
     */
    fun shouldUseEtc2(sourceTexiFormat: Int, force: Boolean): Boolean =
        sourceTexiFormat in DXT_SOURCE_FORMATS || force

    /** The DXT/BC-family source formats the transcoder decodes and re-encodes. */
    val DXT_SOURCE_FORMATS = setOf(4, 6, 7, 12)

    /**
     * The PC's LZ4-vs-raw decision for a body (FUN_14005ab90):
     * attempt only above the 16 KiB floor, keep only when the ratio beats
     * 0.95 — both compared exactly as the PC does, in float32.
     */
    fun lz4IsBeneficial(rawLen: Int, compressedLen: Int): Boolean {
        if (rawLen <= LZ4_MIN_BYTES) return false
        return compressedLen.toFloat() / rawLen.toFloat() < LZ4_BENEFIT_RATIO
    }

    /**
     * The PC's `-shrink` gate: reduction applies only when BOTH source dims
     * exceed 128 px, with the factor clamped to 1..8 and the new dims taken
     * by integer division.
     */
    fun shrinkDims(w: Int, h: Int, factor: Int): Pair<Int, Int> {
        val f = factor.coerceIn(1, SHRINK_MAX_FACTOR)
        if (f < 2 || w <= SHRINK_MIN_DIM || h <= SHRINK_MIN_DIM) return w to h
        return (w / f).coerceAtLeast(1) to (h / f).coerceAtLeast(1)
    }

    /**
     * Build the PC-format output .tex.
     *
     * @param format 0 (RGBA8888) or 5 (ETC2_RGBA8)
     * @param flags the SOURCE TEXI flags (bit 30 is stripped here; 0x08 is
     *        OR-ed in when the body is stored raw)
     * @param texDims the dims for texW/texH/imgW/imgH AND the mip entry —
     *        the 4-padded dims for ETC2, the image's own dims for RGBA
     * @param body the stored body bytes
     * @param decompSize the uncompressed body size
     * @param isLz4Body whether [body] is LZ4-compressed
     */
    fun build(
        format: Int, flags: Int, color: Int, texW: Int, texH: Int,
        body: ByteArray, decompSize: Int, isLz4Body: Boolean,
    ): ByteArray {
        val outFlags = (flags and FLAG_STRIP_MASK.inv()) or
            (if (isLz4Body) 0 else FLAG_UNCOMPRESSED)
        val out = ByteArrayOutputStream(91 + body.size)
        out.write("TEXV0005\u0000".toByteArray(Charsets.US_ASCII))
        out.write("TEXI0001\u0000".toByteArray(Charsets.US_ASCII))
        writeLeInt(out, format)
        writeLeInt(out, outFlags)
        writeLeInt(out, texW)
        writeLeInt(out, texH)
        writeLeInt(out, texW)
        writeLeInt(out, texH)
        writeLeInt(out, color)
        out.write("TEXB0004\u0000".toByteArray(Charsets.US_ASCII))
        writeLeInt(out, 1)          // imageCount
        writeLeInt(out, -1)         // texbFormat — always -1 in PC output
        writeLeInt(out, 0)          // isVideoMp4
        writeLeInt(out, 1)          // mipmapCount — -maxmipmaps 1
        writeLeInt(out, texW)
        writeLeInt(out, texH)
        writeLeInt(out, if (isLz4Body) 1 else 0)
        writeLeInt(out, decompSize)
        writeLeInt(out, body.size)
        out.write(body)
        return out.toByteArray()
    }

    /**
     * The full RGBA-body pipeline (the pixel data is already RGBA8888 —
     * either a raw/LZ4 body or a decoded image). Returns the PC-format
     * .tex or null when the body cannot be used.
     *
     * [etc2Encoder] is injected so unit tests (pure JVM) pass null and get
     * the RGBA path even for DXT sources; the app passes the etcpack encoder.
     *
     * @param sourceFormat the SOURCE TexI format (drives ETC2-vs-RGBA)
     * @param force the `-c force` flag
     */
    fun transcodeRgbaBody(
        sourceFormat: Int, force: Boolean,
        flags: Int, color: Int, w: Int, h: Int,
        rgba: ByteArray,
        etc2Encoder: ((ByteArray, Int, Int) -> ByteArray?)?,
    ): ByteArray? {
        if (w <= 0 || h <= 0) return null
        // v0.7.24 (H-S7 BUG FIX): `w * h * 4` is 32-bit Int math — for huge
        // inputs (≥ 2^29 px) it overflows NEGATIVE, making the check PASS on
        // exactly the inputs that then corrupt the header. 64-bit comparison.
        if (rgba.size.toLong() < w.toLong() * h.toLong() * 4L) return null

        val useEtc2 = shouldUseEtc2(sourceFormat, force)
        if (useEtc2 && etc2Encoder != null) {
            val etc2 = etc2Encoder(rgba, w, h)
            if (etc2 != null) {
                // ETC2 outputs carry the 4-padded dims.
                val pw = (w + 3) and -4
                val ph = (h + 3) and -4
                val lz4 = if (etc2.size > LZ4_MIN_BYTES) Lz4Compat.compress(etc2) else null
                return if (lz4 != null && lz4IsBeneficial(etc2.size, lz4.size)) {
                    build(5, flags, color, pw, ph, lz4, etc2.size, true)
                } else {
                    build(5, flags, color, pw, ph, etc2, etc2.size, false)
                }
            }
            // Encoder unavailable/failed — fall through to the RGBA path.
            // A >2048px RGBA body is large but VALID; passing the original
            // through (the caller's null fallback) could ship a body the
            // phone cannot load at all. RGBA always loads.
        }

        val lz4 = if (rgba.size > LZ4_MIN_BYTES) Lz4Compat.compress(rgba) else null
        return if (lz4 != null && lz4IsBeneficial(rgba.size, lz4.size)) {
            build(0, flags, color, w, h, lz4, rgba.size, true)
        } else {
            build(0, flags, color, w, h, rgba, rgba.size, false)
        }
    }

    private fun readU32(d: ByteArray, off: Int): Int =
        (d[off].toInt() and 0xFF) or
            ((d[off + 1].toInt() and 0xFF) shl 8) or
            ((d[off + 2].toInt() and 0xFF) shl 16) or
            ((d[off + 3].toInt() and 0xFF) shl 24)

    private fun writeLeInt(out: ByteArrayOutputStream, v: Int) {
        out.write(v and 0xFF)
        out.write((v ushr 8) and 0xFF)
        out.write((v ushr 16) and 0xFF)
        out.write((v ushr 24) and 0xFF)
    }
}
