package com.wepwawet.pairing

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * v0.7.16: PC-exact pipeline tests.
 *
 * The fixtures under `src/test/resources/pcgroundtruth/` are REAL BYTES:
 *   • `*_pc_output.*`  — extracted from a .mpkg produced by the genuine
 *                        Wallpaper Engine PC transcoder (ground truth).
 *   · `*_original.*`   — the .pkg-side inputs that produced them.
 *
 * A test that fails here means WepWawet's output diverges from what a real
 * PC ships to the phone — not a style nit, a compatibility bug.
 */
class PcPipelineTest {

    private fun res(name: String): ByteArray =
        javaClass.classLoader.getResourceAsStream("pcgroundtruth/$name")!!.readBytes()

    // ═════════════════════════════════════════════════════════════════════
    // GROUND-TRUTH BYTE-EXACT: shader pipeline
    // ═════════════════════════════════════════════════════════════════════

    @Test
    fun `pulse frag - PC output reproduced byte for byte`() {
        val original = String(res("pulse_original.frag"), Charsets.UTF_8)
        val pcOutput = String(res("pulse_pc_output.frag"), Charsets.UTF_8)
        val ours = PcShaderFixes.fixShader("pulse", original)
        assertEquals("pulse.frag must match the real PC transcoder byte-for-byte", pcOutput, ours)
    }

    @Test
    fun `Simple_Audio_Bars zcompat - PC output reproduced byte for byte`() {
        val zcompat = String(res("sab_zcompat_input.frag"), Charsets.UTF_8)
        val pcOutput = String(res("sab_pc_output.frag"), Charsets.UTF_8)
        // The PC substitutes the zcompat shader and STILL runs fixShader on it.
        val ours = PcShaderFixes.fixShader("Simple_Audio_Bars", zcompat)
        assertEquals("zcompat Simple_Audio_Bars must match the PC byte-for-byte", pcOutput, ours)
    }

    // ═════════════════════════════════════════════════════════════════════
    // PC fixShader — stage-by-stage behavior
    // ═════════════════════════════════════════════════════════════════════

    @Test
    fun `sample is renamed to underscore-sample not sampleVal`() {
        val out = PcShaderFixes.fixShader("test", "vec4 sample = texSample2D(g_Texture0, uv);\n")
        assertTrue(out.contains("vec4 _sample = texSample2D"))
        assertFalse(out.contains("sampleVal"))
        // texSample2D is NOT touched (no word boundary inside it)
        assertTrue(out.contains("texSample2D(g_Texture0, uv)"))
    }

    @Test
    fun `int to float uses the PC character class - no comma or colon contexts`() {
        val src = "float a = 1 + 2;\n" +
            "vec2 v = uv * 2 - 1;\n" +
            "uniform float g_X; // {\"default\":1,\"range\":[0,10]}\n" +
            "float b = 1e-6;\n"
        val out = PcShaderFixes.fixShader("test", src)
        assertTrue("space/paren-preceded ints convert", out.contains("float a = 1.0 + 2.0;"))
        assertTrue(out.contains("vec2 v = uv * 2.0 - 1.0;"))
        // uniform declaration line is SKIPPED entirely (^(uniform|...) rule)
        assertTrue("uniform line kept verbatim", out.contains("uniform float g_X; // {\"default\":1,\"range\":[0,10]}"))
        // scientific notation is repaired after int→float (1e-6.0 → 1e-6)
        assertTrue(out.contains("float b = 1e-6;"))
    }

    @Test
    fun `COMBO comment lines are preserved`() {
        val line = "// [COMBO] {\"material\":\"ui\",\"combo\":\"NOISE\",\"type\":\"options\",\"default\":0}\n"
        assertEquals(line, PcShaderFixes.fixShader("test", line))
    }

    @Test
    fun `preprocessor lines are preserved`() {
        val line = "#if AUDIOPROCESSING == 0\n"
        assertEquals(line, PcShaderFixes.fixShader("test", line))
    }

    @Test
    fun `ordinary comments DO get the generic passes - PC behavior`() {
        // The PC has NO comment skipping for plain // lines — only the four
        // line shapes (^#, ^[, ^(uniform|…), for/while) are skipped. This is
        // exactly how the zcompat Simple_Audio_Bars comments got converted.
        val out = PcShaderFixes.fixShader("test", "// y = 1 / (abs((x - 1) % 4 - 2) - 1)\n")
        assertTrue(out.contains("// y = 1.0 / (abs((x - 1.0) % 4.0 - 2.0) - 1.0)"))
        val out2 = PcShaderFixes.fixShader("test", "// Define the audio sample arrays\n")
        assertTrue(out2.contains("// Define the audio _sample arrays"))
    }

    @Test
    fun `for-loop with sampleCount gets the PC rewrite - other loops untouched`() {
        val src = "for (int i = 0; i < sampleCount; i++) {\n" +
            "for (int j = 1; j < sampleCount; j++) {\n" +
            "while (i < 4) {\n"
        val out = PcShaderFixes.fixShader("test", src)
        // The PC regex is `for \(int (.+) = 0; i < sampleCount;` — counter must
        // be named i and the limit must be sampleCount, otherwise untouched.
        assertTrue(out.contains("for (float i = 0.0; i < sampleCount; i++) {"))
        assertTrue("non-i loops untouched", out.contains("for (int j = 1; j < sampleCount; j++) {"))
        assertTrue("while line untouched by int→float", out.contains("while (i < 4) {"))
    }

    @Test
    fun `const int sampleCount becomes const float`() {
        val out = PcShaderFixes.fixShader("test", "const int sampleCount = 14;\n")
        assertTrue(out.contains("const float sampleCount = 14.0;"))
    }

    @Test
    fun `CRLF line endings survive the per-line loop`() {
        val src = "float a = 1;\r\nfloat b = 2;\r\n"
        val out = PcShaderFixes.fixShader("test", src)
        assertEquals("float a = 1.0;\r\nfloat b = 2.0;\r\n", out)
    }

    @Test
    fun `no trailing newline is added when the file has none`() {
        // v0.7.19 (APK ground truth): the PC's line loop PRESERVES the file's
        // trailing-newline state — the APK ships volumetricsback.frag /
        // flat.frag / flatpoint.frag / editorsprite.frag at byte sizes
        // IDENTICAL to the PC originals, so no newline is ever added. A file
        // that DOES end with '\n' keeps exactly one (pulse.frag ground
        // truth).
        val out = PcShaderFixes.fixShader("test", "float a = 1;")
        assertEquals("float a = 1.0;", out)
        val out2 = PcShaderFixes.fixShader("test", "float a = 1;\n")
        assertEquals("float a = 1.0;\n", out2)
    }

    // ── name-gated patches ────────────────────────────────────────────────

    @Test
    fun `blur patch hoists only g_Texture0 before common_blur include`() {
        // v0.7.19: the PC's blur-fix stage is VERIFIED DEAD CODE (its .rdata
        // pattern carries a literal double backslash, so no real file can
        // match) — fixShader leaves the bytes untouched and the ESSL safety
        // pass performs the hoist the dead stage was designed for (the APK's
        // own blur_k3.frag / editorpaintbrush.frag ship with it applied).
        val src = "#include \"common_blur.h\"\n\nuniform sampler2D g_Texture0; // {\"hidden\":true}\nuniform sampler2D g_Texture1;\n"
        val pcOut = PcShaderFixes.fixShader("blur_precise_gaussian", src)
        assertEquals("the dead PC stage must not touch the file", src, pcOut)
        val out = PcShaderFixes.fixShaderForMobile("blur_precise_gaussian", src)
        // The include line gains the uniform BEFORE it; the original
        // declaration is deleted. g_Texture1 stays where it was.
        assertTrue(out.contains("uniform sampler2D g_Texture0;\n#include \"common_blur.h\""))
        val firstIdx = out.indexOf("uniform sampler2D g_Texture0;")
        val includeIdx = out.indexOf("#include \"common_blur.h\"")
        assertTrue("hoisted uniform precedes the include", firstIdx < includeIdx)
        // exactly ONE g_Texture0 declaration remains (the original was deleted)
        assertEquals(1, Regex("uniform sampler2D g_Texture0;").findAll(out).count())
    }

    @Test
    fun `blur patch does not fire without the common_blur include`() {
        val src = "uniform sampler2D g_Texture0;\n#include \"common.h\"\n"
        val out = PcShaderFixes.fixShader("shake", src)
        // exactly one g_Texture0 declaration, nothing hoisted, content unchanged
        assertEquals(1, Regex("uniform sampler2D g_Texture0;").findAll(out).count())
        assertEquals(src, out)
    }

    @Test
    fun `xray patch injects g_PointerState and the scale line`() {
        val src = "varying float v_PointerScale;\n" +
            "uniform mat4 g_ModelViewProjectionMatrixInverse;\n" +
            "vec3 u = (vec4(uv, 0.0, 1.0) * g_ModelViewProjectionMatrixInverse).xyw;\n" +
            "v_PointerUV.xy *= 1.0 / g_Texture0Resolution.xy;\n" +
            "unprojectedUVs += 0.5;\n"
        val out = PcShaderFixes.fixShader("xray", src)
        assertTrue(out.contains("varying float v_PointerScale;\nuniform vec4 g_PointerState;"))
        assertTrue(out.contains(
            "unprojectedUVs *= CAST2(1.0 / max(0.00001, g_PointerState.y));\nunprojectedUVs += 0.5;"))
        assertTrue(out.contains("uniform mat4 g_EffectTextureProjectionMatrixInverse;"))
        assertTrue(out.contains("g_EffectTextureProjectionMatrixInverse).xyw;"))
        assertTrue(out.contains("v_PointerUV.xy *= 0.5;"))
    }

    @Test
    fun `xray patch requires exact effect name`() {
        val src = "varying float v_PointerScale;\nunprojectedUVs += 0.5;\n"
        // "xray_v2" is NOT "xray" — the exe does exact length+memcmp
        val out = PcShaderFixes.fixShader("xray_v2", src)
        assertFalse(out.contains("g_PointerState"))
    }

    @Test
    fun `perspective skew blend shift_hue foliagesway patches apply`() {
        val p = PcShaderFixes.fixShader("perspective",
            "vec2 v = mix(-1.0, 1.0, x);\nuniform vec4 g_Texture0Resolution;\n")
        assertTrue(p.contains("mix(-g_TextureReductionScale, g_TextureReductionScale, x);"))
        assertTrue(p.contains("uniform float g_TextureReductionScale;"))

        val s = PcShaderFixes.fixShader("skew",
            "vec2 textureScale = g_Texture0Resolution.zw;\nuniform vec4 g_Texture0Resolution;\n")
        assertTrue(s.contains("vec2 textureScale = g_Texture0Resolution.zw * g_TextureReductionScale;"))

        val b = PcShaderFixes.fixShader("blend", "vec2 e = step(0, uv) * step(uv, 1);\n")
        assertTrue(b.contains("step(CAST2(0.0), uv) * step(uv, CAST2(1.0))"))

        val h = PcShaderFixes.fixShader("shift_hue",
            "albedo.rgb = mix(albedo, newAlbedo, mask);\n")
        assertTrue(h.contains("albedo.rgb = mix(albedo.rgb, newAlbedo, mask);"))

        val f = PcShaderFixes.fixShader("foliagesway",
            "offset = saturate((offset - v_Bounds.x) * v_Bounds.y);\nuniform sampler2D g_Texture1;\n")
        assertTrue(f.contains("varying vec2 v_Bounds;\nuniform sampler2D g_Texture1;"))
    }

    @Test
    fun `lerp is NOT converted - PC ships it untouched`() {
        // The v0.7.6 lerp→mix swap was OUR invention; the exe contains no
        // such pass. WE Android's preprocessor supplies the macro table.
        val src = "float x = lerp(a, b, t);\n"
        assertEquals(src, PcShaderFixes.fixShader("test", src))
    }

    // ── BlendOpacity ──────────────────────────────────────────────────────

    @Test
    fun `BlendOpacity call inlined to mix`() {
        val out = PcShaderFixes.fixBlendOpacity(
            "albedo.rgb = BlendOpacity(albedo.rgb, newAlbedo.rgb, BlendScreen, opacity);\n")
        assertEquals(
            "albedo.rgb = mix(albedo.rgb,BlendScreen(albedo.rgb,newAlbedo.rgb),opacity);\n", out)
    }

    @Test
    fun `BlendOpacity define line is preserved`() {
        val src = "#define BlendOpacity(base, blend, F, O) mix(base, F(base, blend), O)\n"
        val out = PcShaderFixes.fixBlendOpacity(src)
        assertEquals(src, out)
    }

    @Test
    fun `BlendOpacity with wrong arity is copied verbatim`() {
        val src = "x = BlendOpacity(a, b);\n"
        assertEquals(src, PcShaderFixes.fixBlendOpacity(src))
    }

    // ═════════════════════════════════════════════════════════════════════
    // GROUND-TRUTH BYTE-EXACT: texture pipeline (pure-JVM paths)
    // ═════════════════════════════════════════════════════════════════════

    @Test
    fun `waterflowphase - PC output reproduced byte for byte`() {
        val original = res("waterflowphase_original.tex")
        val pcOutput = res("waterflowphase_pc_output.tex")
        val info = PcTexTranscoder.parse(original)!!
        assertEquals(0, info.texiFormat)
        assertEquals(32, info.mipW)
        assertEquals(32, info.mipH)
        assertFalse(info.bodyIsLz4)
        val body = PcTexTranscoder.bodyBytes(original)
        assertEquals(4096, body.size)

        // v0.7.18: the PC never attempts LZ4 at or below 16383 bytes
        // (FUN_14005ab90 `if (0x3fff < size)`), so this 4096-byte body is
        // stored RAW with flags |= 0x08 — the PC output differs from the
        // input by exactly one byte. Our rebuild is byte-identical.
        val ours = PcTexTranscoder.transcodeRgbaBody(
            info.texiFormat, false, info.flags, info.color, info.mipW, info.mipH, body, etc2Encoder = null)!!
        assertArrayEquals("waterflowphase must match the PC byte-for-byte", pcOutput, ours)
        val outInfo = PcTexTranscoder.parse(ours)!!
        assertEquals(0x08, outInfo.flags)
        assertFalse(outInfo.bodyIsLz4)
    }

    @Test
    fun `output-onlinepngtools - TEXB0003 LZ4 RGBA rebuilt - byte exact`() {
        val original = res("output_onlinepngtools_original.tex")
        val pcOutput = res("output_onlinepngtools_pc_output.tex")
        val info = PcTexTranscoder.parse(original)!!
        assertEquals(3, info.texbVersion)
        assertEquals(400, info.mipW)
        assertEquals(400, info.mipH)
        assertTrue(info.bodyIsLz4)
        assertEquals(640000, info.decompSize)
        // Decompress mip 0 — the source must be sliced to the stored
        // byteCount: the .tex appends further LZ4 blocks (extra frames/mips)
        // after mip 0, and decompressing the whole remainder fails.
        assertEquals(2520, info.byteCount)
        val raw = Lz4Compat.decompress(PcTexTranscoder.bodyBytes(original), info.decompSize)
        assertEquals(640000, raw.size)
        val ours = PcTexTranscoder.transcodeRgbaBody(
            info.texiFormat, false, info.flags, info.color, info.mipW, info.mipH, raw, etc2Encoder = null)!!
        assertArrayEquals("output-onlinepngtools must match the PC byte-for-byte", pcOutput, ours)
    }

    @Test
    fun `LZ4 output is byte-identical to the PC compressor`() {
        // Round-trip the PC's own stored bodies: decompress the ground-truth
        // output and re-compress with our Lz4Compat — the bytes must be
        // identical (this is the exact property verified offline with
        // python-lz4's default block mode against four PC textures).
        val pc = res("output_onlinepngtools_pc_output.tex")
        val body = pc.copyOfRange(91, pc.size)
        val raw = Lz4Compat.decompress(body, 640000)
        assertArrayEquals("LZ4 recompression must be identical", body, Lz4Compat.compress(raw))
    }

    @Test
    fun `waterripplenormal PC output parse - raw RGBA with flags 0x08`() {
        // The original has a PNG body (needs BitmapFactory — device-only),
        // but the PC OUTPUT structure is fully checkable on the JVM.
        val pcOutput = res("waterripplenormal_pc_output.tex")
        val info = PcTexTranscoder.parse(pcOutput)!!
        assertEquals(0, info.texiFormat)
        assertEquals(0x08, info.flags)
        assertEquals(256, info.mipW)
        assertEquals(256, info.mipH)
        assertFalse(info.bodyIsLz4)      // noise-like normal map — LZ4 saved nothing → raw
        assertEquals(262144, info.decompSize)
        assertEquals(info.bodyLen, info.decompSize)
        // and our rebuild of the SAME pixels reproduces it
        val body = pcOutput.copyOfRange(info.bodyStart, pcOutput.size)
        val ours = PcTexTranscoder.transcodeRgbaBody(
            info.texiFormat, false, info.flags, info.color, info.mipW, info.mipH, body, etc2Encoder = null)!!
        assertArrayEquals("waterripplenormal rebuild must be identical", pcOutput, ours)
    }

    @Test
    fun `parse handles TEXB0003 and TEXB0004 layouts`() {
        val t3 = PcTexTranscoder.parse(res("output_onlinepngtools_original.tex"))!!
        assertEquals(3, t3.texbVersion)
        assertEquals(87, t3.bodyStart)
        val t4 = PcTexTranscoder.parse(res("waterflowphase_original.tex"))!!
        assertEquals(4, t4.texbVersion)
        assertEquals(91, t4.bodyStart)
        // TEXB0004 carries an explicit mipCount field
        assertEquals(1, t4.mipCount)
    }

    @Test
    fun `ETC2 and LZ4 decision rules match the ground truth`() {
        // v0.7.18: ETC2 follows the SOURCE FORMAT (resourcecompiler64's
        // re-decision in FUN_140063c70), not the pixel count. MSK — the
        // ground truth's single ETC2 texture — was a DXT source; every
        // fmt-0 texture stayed RGBA. There is no size threshold.
        assertTrue("DXT5 sources become ETC2", PcTexTranscoder.shouldUseEtc2(4, false))
        assertTrue("DXT3 sources become ETC2", PcTexTranscoder.shouldUseEtc2(6, false))
        assertTrue("DXT1 sources become ETC2", PcTexTranscoder.shouldUseEtc2(7, false))
        assertTrue("BC7 (12) sources become ETC2", PcTexTranscoder.shouldUseEtc2(12, false))
        assertFalse("3000x2000 fmt-0 stays RGBA (no size rule!)", PcTexTranscoder.shouldUseEtc2(0, false))
        assertFalse("32x32 fmt-0 stays RGBA", PcTexTranscoder.shouldUseEtc2(0, false))
        assertFalse("RGB888 stays RGB without force", PcTexTranscoder.shouldUseEtc2(1, false))
        assertTrue("force makes fmt-0 ETC2", PcTexTranscoder.shouldUseEtc2(0, true))
        assertTrue("force makes RGB888 ETC2", PcTexTranscoder.shouldUseEtc2(1, true))

        // LZ4 rules (FUN_14005ab90): attempted only ABOVE 16383 bytes, kept
        // only under the 0.95 float threshold.
        // icon (0.038) / output (0.004) / HD (0.768) → compress;
        // waterripplenormal (1.004) → keep raw.
        assertTrue(PcTexTranscoder.lz4IsBeneficial(2934000, 110299))
        assertTrue(PcTexTranscoder.lz4IsBeneficial(640000, 2520))
        assertTrue(PcTexTranscoder.lz4IsBeneficial(348096, 267307))
        assertFalse(PcTexTranscoder.lz4IsBeneficial(262144, 263156))
        // the 16 KiB floor: waterflowphase (4096 B) is NEVER compressed,
        // no matter how well it would compress
        assertFalse("at the floor (16383)", PcTexTranscoder.lz4IsBeneficial(16383, 1))
        assertTrue("one past the floor", PcTexTranscoder.lz4IsBeneficial(16384, 1))
        assertFalse("small bodies never compress", PcTexTranscoder.lz4IsBeneficial(4096, 3409))
        assertFalse("small bodies never compress (1000/800)", PcTexTranscoder.lz4IsBeneficial(1000, 800))
        // the 0.95 boundary (float compare, strictly below keeps)
        assertFalse("exactly 95% is not beneficial", PcTexTranscoder.lz4IsBeneficial(100000, 95000))
        assertTrue("94.99% is beneficial", PcTexTranscoder.lz4IsBeneficial(100000, 94999))
    }

    @Test
    fun `shrink rules - both dims over 128, integer division, clamped`() {
        // off
        assertEquals(3000 to 2000, PcTexTranscoder.shrinkDims(3000, 2000, 1))
        assertEquals(3000 to 2000, PcTexTranscoder.shrinkDims(3000, 2000, 0))
        // half
        assertEquals(1500 to 1000, PcTexTranscoder.shrinkDims(3000, 2000, 2))
        // quarter
        assertEquals(750 to 500, PcTexTranscoder.shrinkDims(3000, 2000, 4))
        // clamped to 8
        assertEquals(375 to 250, PcTexTranscoder.shrinkDims(3000, 2000, 16))
        // gate: both dims must EXCEED 128
        assertEquals(128 to 2000, PcTexTranscoder.shrinkDims(128, 2000, 2))
        assertEquals(3000 to 128, PcTexTranscoder.shrinkDims(3000, 128, 2))
        assertEquals(64 to 1000, PcTexTranscoder.shrinkDims(129, 2000, 2))
        // a dim at or below 128 blocks the shrink entirely (both must exceed)
        assertEquals(129 to 2, PcTexTranscoder.shrinkDims(129, 2, 2))
        // integer division when both are over the gate
        assertEquals(64 to 1001, PcTexTranscoder.shrinkDims(129, 2003, 2))
    }

    @Test
    fun `build produces the exact PC header layout`() {
        val body = ByteArray(16) { it.toByte() }
        val out = PcTexTranscoder.build(0, 0x0A, 0x11223344, 8, 8, body, 16, false)
        // v0.7.18 flag rules: raw storage ORs 0x08 in (0x0A already has it)
        assertEquals("TEXV0005\u0000", String(out, 0, 9, Charsets.US_ASCII))
        assertEquals("TEXI0001\u0000", String(out, 9, 9, Charsets.US_ASCII))
        assertEquals("TEXB0004\u0000", String(out, 46, 9, Charsets.US_ASCII))
        fun u32(off: Int) = java.nio.ByteBuffer.wrap(out, off, 4)
            .order(java.nio.ByteOrder.LITTLE_ENDIAN).int
        assertEquals(0, u32(18))          // format
        assertEquals(0x0A, u32(22))       // flags
        assertEquals(8, u32(26)); assertEquals(8, u32(30))   // tex dims
        assertEquals(8, u32(34)); assertEquals(8, u32(38))   // img dims (un-padded!)
        assertEquals(0x11223344, u32(42)) // color preserved
        assertEquals(1, u32(55))          // imageCount
        assertEquals(-1, u32(59))         // texbFormat = -1 (always)
        assertEquals(0, u32(63))          // isVideoMp4
        assertEquals(1, u32(67))          // mipCount (maxmipmaps 1)

        // v0.7.18: the output flags = (sourceFlags AND NOT 0x40000000) OR
        // (0x08 when raw). LZ4 bodies leave the source flags alone.
        val raw30 = PcTexTranscoder.build(0, 0x40000010, 0, 4, 4, body, 16, false)
        fun f32(b: ByteArray, off: Int) = java.nio.ByteBuffer.wrap(b, off, 4)
            .order(java.nio.ByteOrder.LITTLE_ENDIAN).int
        assertEquals("bit 30 stripped, 0x08 OR-ed for raw", 0x18, f32(raw30, 22))
        val lz4Body = PcTexTranscoder.build(0, 0x40000002, 0, 4, 4, body, 16, true)
        assertEquals("bit 30 stripped, no 0x08 for LZ4", 0x02, f32(lz4Body, 22))
        val plain = PcTexTranscoder.build(0, 0x00, 0, 4, 4, body, 16, false)
        assertEquals("0x08 OR-ed on a clean raw body", 0x08, f32(plain, 22))
        assertEquals(8, u32(71)); assertEquals(8, u32(75))
        assertEquals(0, u32(79))          // isLz4
        assertEquals(16, u32(83))         // decompSize
        assertEquals(16, u32(87))         // byteCount
        assertEquals(91 + 16, out.size)
    }

    // ═════════════════════════════════════════════════════════════════════
    // Packing rules
    // ═════════════════════════════════════════════════════════════════════

    @Test
    fun `workshopurl insertion is byte exact`() {
        val original = String(res("project_original.json"), Charsets.UTF_8)
        val pcOutput = String(res("project_pc_output.json"), Charsets.UTF_8)
        val ours = PcPacking.addWorkshopUrl(
            original, "https://steamcommunity.com/sharedfiles/filedetails/?id=3655958892")
        assertEquals("project.json must match the PC byte-for-byte", pcOutput, ours)
    }

    @Test
    fun `workshopurl is idempotent`() {
        val once = PcPacking.addWorkshopUrl("{\r\n\t\"a\" : 1\r\n}", "http://x")
        assertEquals(once, PcPacking.addWorkshopUrl(once, "http://x"))
    }

    @Test
    fun `entry order is case-insensitive like NTFS enumeration`() {
        val names = mutableListOf(
            "materials/MSK12003_x.tex", "materials/masks/a.tex", "shaders/effects/shake.frag",
            "scene.json", "effects/shake/effect.json", "preview.gif", "project.json",
            "models/x.json", "shaders/effects/waterflow.frag", "materials/effects/waterflow.json")
        names.sortWith(PcPacking.PC_ENTRY_ORDER)
        // masks must come BEFORE MSK12003 (case-insensitive) — the exact
        // order observed in the real PC .mpkg.
        val expected = listOf(
            "effects/shake/effect.json",
            "materials/effects/waterflow.json",
            "materials/masks/a.tex",
            "materials/MSK12003_x.tex",
            "models/x.json",
            "preview.gif",
            "project.json",
            "scene.json",
            "shaders/effects/shake.frag",
            "shaders/effects/waterflow.frag")
        assertEquals(expected, names)
    }

    @Test
    fun `sounds paths are recognized`() {
        assertTrue(PcPacking.isSoundsPath("sounds/ambient.wav"))
        assertTrue(PcPacking.isSoundsPath("sounds/sub/x.ogg"))
        assertTrue(PcPacking.isSoundsPath("SOUNDS/x.wav"))
        assertFalse(PcPacking.isSoundsPath("materials/soundslike.tex"))
        assertFalse(PcPacking.isSoundsPath("shaders/effects/shake.frag"))
    }

    private fun assertArrayEquals(message: String, expected: ByteArray, actual: ByteArray) {
        if (!expected.contentEquals(actual)) {
            var i = 0
            while (i < expected.size && i < actual.size && expected[i] == actual[i]) i++
            org.junit.Assert.fail("$message — first diff at byte $i " +
                "(expected len ${expected.size}, actual len ${actual.size}; " +
                "expected[${i}]=%02x actual[${i}]=%02x)".format(expected[i], actual[i]))
        }
    }
}
