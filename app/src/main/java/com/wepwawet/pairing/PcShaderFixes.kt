package com.wepwawet.pairing

/**
 * EXACT port of Wallpaper Engine PC's shader fixer — wallpaperui.exe
 * `fixShader` (subroutine at 0x140015f20, plus `fixBlendOpacity` at
 * 0x140014c20), reverse-engineered instruction by instruction from the
 * shipping binary (WE 2.8.x, x64). Every regex, replacement string,
 * gating condition and ordering below is taken verbatim from the binary's
 * .rdata string table and disassembly — this is the ground truth the real
 * PC transcoder applies to every .vert/.frag/.geom before packing a
 * mobile .mpkg.
 *
 * Pipeline (order matters, verified from disassembly):
 *
 *  1. BLUR FIX — content-gated, runs BEFORE the name checks. VERIFIED DEAD
 *     CODE on the real PC: the pattern stored in the binary's .rdata at
 *     0x140ab2490 is `^(#include "common_blur\\.h")` — the runtime string
 *     contains a LITERAL DOUBLE BACKSLASH, so as an ECMAScript regex it
 *     requires a literal '\' character in the file before the '.' — which
 *     no real shader contains. (Proof: the official Android APK ships
 *     blur_k3.frag / editorpaintbrush.frag / common_blur.h with the include
 *     line and every `uniform sampler2D g_Texture0;` UNTOUCHED — if the
 *     blur fix fired, the uniform would have been hoisted above the
 *     include.) We reproduce the regex verbatim (same dead pattern, same
 *     icase uniform deletion) so that IF a file ever matched it we would
 *     still produce the PC's exact bytes — and real files never match.
 *
 *  2. NAME-GATED PATCHES — the effect name (shader file stem, e.g.
 *     "xray" for shaders/effects/xray.frag) is compared for EXACT
 *     equality; the first match wins:
 *     foliagesway / blend / shift_hue / xray / perspective / skew
 *     (see the companion object for the exact patterns)
 *
 *  3. GENERIC PER-LINE PASSES — the content is split into lines
 *     (std::getline semantics: split on '\n', a trailing newline does
 *     NOT produce an extra empty line, '\r' stays attached to the line).
 *     For each line, in order:
 *       - loop lines   (`\b(for|while)\b\s*\(`): apply the sampleCount
 *                        for-loop rewrite ONLY
 *       - `^\s*#`                              : kept unchanged
 *       - `^(uniform|attribute|varying|out|in)[\s]+`: kept unchanged
 *       - `^\s*\[`                             : kept unchanged
 *       - otherwise:
 *           int→float  ([\s\(/\)\+\-\*\%\\/\]])(\d+)(?!\.)(\b) → $1$2.0$3
 *           sci-notation fix  ([\d\.]e[-]*[\d]+)\.0 → $1
 *           sample → _sample  (\b)(sample)(\b) → $1_$2$3
 *           sampleCount decl  (\s*const )int( sampleCount) → $1float$2
 *     Every line (transformed or not) is re-emitted with a '\n'.
 *     NOTE: ordinary `//` comments are NOT skipped — only the four line
 *     shapes above are. `// [COMBO] {…}` metadata lines survive because
 *     of the `^\s*\[` rule *and* because the int→float character class
 *     contains no ':' ',' '[' '"'.
 *
 *  4. BLENDOPACITY FIX — rewrite `BlendOpacity(base, blend, F, O)` calls
 *     to `mix(base,F(base,blend),O)`. Occurrences that are part of a
 *     `#define BlendOpacity` line are copied through unchanged.
 *
 * Verified byte-exact against a real PC-produced .mpkg for:
 *   - pulse.frag (2724 B original → 2729 B PC output, identical bytes)
 *   - Simple_Audio_Bars.frag zcompat substitution (11630 B → 11662 B)
 */
object PcShaderFixes {

    // ── Stage 0: deprecated mod() rename (wallpaperui.exe @0x140017dd0) ───

    /**
     * The PC's OTHER shader pass, decoded from wallpaperui.exe at
     * 0x140017dd0 — a single std::string::find + replace executed BEFORE
     * fixShader (call site 0x14003ff5f, before the fixShader call at
     * 0x14003ff97):
     *
     *   content.replace(first "float mod(", "float _deprFn_0(")
     *
     * WE shaders that define their own `float mod(...)` helper (HLSL needs
     * it) would redeclare GLSL's built-in mod() on Android, which the
     * official engine rejects. The PC renames only the FIRST definition —
     * calls still resolve (GLSL mod() has identical semantics) — and so do
     * we, byte for byte.
     */
    fun deprModFix(content: String): String {
        val i = content.indexOf("float mod(")
        if (i < 0) return content
        return content.substring(0, i) + "float _deprFn_0(" +
            content.substring(i + "float mod(".length)
    }

    /**
     * The complete PC shader chain plus the Android-side GLSL ES safety
     * pass, in the exact order the binaries apply them:
     *
     *   1. deprModFix      (wallpaperui.exe 0x140017dd0, before fixShader)
     *   2. fixShader       (wallpaperui.exe 0x140015f20 — the 4-stage port)
     *   3. ESSL safety     (see [ShaderEsslSafety] — replicates the fxc
     *      tolerance the PC's D3D compiler provides for free, because the
     *      official Android engine compiles the very same bytes with strict
     *      GLSL ES 3.00 and rejects implicit truncation / int conditions)
     */
    fun fixShaderForMobile(name: String, content: String): String =
        ShaderEsslSafety.apply(fixShader(name, deprModFix(content)))

    // ── Stage 1: blur ──────────────────────────────────────────────────────
    private val BLUR_INCLUDE = Regex("""^(#include "common_blur\\.h")""")
    private val BLUR_UNIFORM = Regex("""uniform sampler2D g_Texture0;""", RegexOption.IGNORE_CASE)
    private const val BLUR_REPLACEMENT = "uniform sampler2D g_Texture0;\n"

    // ── Stage 2: name-gated patches ────────────────────────────────────────
    private const val FOLIAGE_MARKER = """offset = saturate\(\(offset - v_Bounds.x\) \* v_Bounds\.y\);"""
    private val FOLIAGE_UNIFORM = Regex("""(uniform sampler2D g_Texture1;)""")
    private const val FOLIAGE_REPLACEMENT = "varying vec2 v_Bounds;\n"

    private const val BLEND_SEARCH = """step\(0, uv\) \* step\(uv, 1\)"""
    private const val BLEND_REPLACEMENT = "step(CAST2(0.0), uv) * step(uv, CAST2(1.0))"

    private const val SHIFT_HUE_SEARCH = """albedo\.rgb = mix\(albedo, newAlbedo, mask\)"""
    private const val SHIFT_HUE_REPLACEMENT = "albedo.rgb = mix(albedo.rgb, newAlbedo, mask)"

    private const val XRAY_VARYING = "varying float v_PointerScale;"
    private const val XRAY_VARYING_REPLACEMENT = "varying float v_PointerScale;\nuniform vec4 g_PointerState;"
    private const val XRAY_UNPROJECTED_SEARCH = """unprojectedUVs \+= 0\.5;"""
    private const val XRAY_UNPROJECTED_MARK = "unprojectedUVs *= CAST2(1.0 / max(0.00001, g_PointerState.y));"
    private const val XRAY_UNPROJECTED_REPLACEMENT =
        "unprojectedUVs *= CAST2(1.0 / max(0.00001, g_PointerState.y));\nunprojectedUVs += 0.5;"
    private const val XRAY_MVPINV = "uniform mat4 g_ModelViewProjectionMatrixInverse;"
    private const val XRAY_MVPINV_REPLACEMENT =
        "uniform mat4 g_ModelViewProjectionMatrixInverse;\nuniform mat4 g_EffectTextureProjectionMatrixInverse;"
    private val XRAY_MVPINV_USE = Regex("""g_ModelViewProjectionMatrixInverse\)\.xyw""")
    private const val XRAY_MVPINV_USE_REPLACEMENT = "g_EffectTextureProjectionMatrixInverse).xyw;"
    private const val XRAY_POINTERUV_SEARCH = """v_PointerUV\.xy \*= 1\.0 / g_Texture0Resolution\.xy;"""
    private const val XRAY_POINTERUV_REPLACEMENT = "v_PointerUV.xy *= 0.5;"

    private const val PERSPECTIVE_MIX_SEARCH = """mix\(-1\.0\, 1\.0"""
    private const val PERSPECTIVE_MIX_REPLACEMENT =
        "mix(-g_TextureReductionScale, g_TextureReductionScale"
    private const val TEXRES_UNIFORM = "uniform vec4 g_Texture0Resolution;"
    private const val TEXRES_REPLACEMENT =
        "uniform vec4 g_Texture0Resolution;\nuniform float g_TextureReductionScale;"

    private const val SKEW_SEARCH = """vec2 textureScale = g_Texture0Resolution\.zw;"""
    private const val SKEW_REPLACEMENT =
        "vec2 textureScale = g_Texture0Resolution.zw * g_TextureReductionScale;"

    // ── Stage 3: generic per-line passes ───────────────────────────────────
    private val LOOP_LINE = Regex("""\b(for|while)\b\s*\(""")
    private val HASH_LINE = Regex("""^\s*#""")
    private val DECL_LINE = Regex("""^(uniform|attribute|varying|out|in)[\s]+""")
    private val BRACKET_LINE = Regex("""^\s*\[""")

    private val FOR_INT_SAMPLECOUNT = Regex("""for \(int (.+) = 0; i < sampleCount;""")
    private const val FOR_FLOAT_SAMPLECOUNT_PREFIX = "for (float "
    private const val FOR_FLOAT_SAMPLECOUNT_SUFFIX = " = 0.0; i < sampleCount;"

    /** Exact PC int→float regex — note the character class has NO ',', ':' or '='. */
    private val INT_TO_FLOAT = Regex("""([\s\(/\)\+\-\*\%\\\]])(\d+)(?!\.)(\b)""")
    private val SCI_NOTATION = Regex("""([\d\.]e[-]*[\d]+)\.0""")
    private val SAMPLE_KEYWORD = Regex("""(\b)(sample)(\b)""")
    private val CONST_INTC_SAMPLECOUNT = Regex("""(\s*const )int( sampleCount)""")

    // ── Stage 4: BlendOpacity ──────────────────────────────────────────────
    private val DEFINE_BLENDOPACITY = Regex("""#define\s+BlendOpacity""")

    /**
     * The full PC shader fix. [name] is the shader file stem (basename
     * without extension) — the exe compares it for exact equality against
     * the known-broken effect names.
     */
    fun fixShader(name: String, content: String): String {
        var c = content

        // ── 1. blur (content-gated, before the name chain) ──
        if (BLUR_INCLUDE.containsMatchIn(c)) {
            c = BLUR_UNIFORM.replace(c, "")
            c = BLUR_INCLUDE.replace(c) { m -> BLUR_REPLACEMENT + m.groupValues[1] }
        }

        // ── 2. name-gated patches (first match wins) ──
        when (name) {
            "foliagesway" -> {
                if (Regex(FOLIAGE_MARKER).containsMatchIn(c)) {
                    c = FOLIAGE_UNIFORM.replace(c) { m -> FOLIAGE_REPLACEMENT + m.groupValues[1] }
                }
            }
            "blend" -> {
                if (Regex(BLEND_SEARCH).containsMatchIn(c)) {
                    c = Regex(BLEND_SEARCH).replace(c, BLEND_REPLACEMENT)
                }
            }
            "shift_hue" -> {
                if (Regex(SHIFT_HUE_SEARCH).containsMatchIn(c)) {
                    c = Regex(SHIFT_HUE_SEARCH).replace(c, SHIFT_HUE_REPLACEMENT)
                }
            }
            "xray" -> {
                // v0.7.19 (pre-patched asset ground truth): the CURRENT xray
                // assets ship with the patch already applied — `varying float
                // v_PointerScale;` is followed by the patch's own
                // `uniform vec4 g_PointerState;`, and the frag already carries
                // the `unprojectedUVs *= CAST2(1.0 / max(0.00001,
                // g_PointerState.y));` line. Re-applying would duplicate both
                // (a uniform redefinition error in ESSL 3.00, and a doubled
                // scale factor in the math). Both sub-patches are therefore
                // IDEMPOTENT: they fire only when their result is not already
                // present, which is the only behavior consistent with the
                // shipped assets compiling on the engine.
                if (c.contains(XRAY_VARYING) &&
                    !c.contains("uniform vec4 g_PointerState;")) {
                    c = c.replace(XRAY_VARYING, XRAY_VARYING_REPLACEMENT)
                }
                if (Regex(XRAY_UNPROJECTED_SEARCH).containsMatchIn(c) &&
                    !c.contains(XRAY_UNPROJECTED_MARK)) {
                    c = Regex(XRAY_UNPROJECTED_SEARCH).replace(c, XRAY_UNPROJECTED_REPLACEMENT)
                }
                if (c.contains(XRAY_MVPINV) && XRAY_MVPINV_USE.containsMatchIn(c) &&
                    Regex(XRAY_POINTERUV_SEARCH).containsMatchIn(c)) {
                    c = c.replace(XRAY_MVPINV, XRAY_MVPINV_REPLACEMENT)
                    c = XRAY_MVPINV_USE.replace(c, XRAY_MVPINV_USE_REPLACEMENT)
                    c = Regex(XRAY_POINTERUV_SEARCH).replace(c, XRAY_POINTERUV_REPLACEMENT)
                }
            }
            "perspective" -> {
                if (Regex(PERSPECTIVE_MIX_SEARCH).containsMatchIn(c) && c.contains(TEXRES_UNIFORM)) {
                    c = Regex(PERSPECTIVE_MIX_SEARCH).replace(c, PERSPECTIVE_MIX_REPLACEMENT)
                    c = c.replace(TEXRES_UNIFORM, TEXRES_REPLACEMENT)
                }
            }
            "skew" -> {
                if (Regex(SKEW_SEARCH).containsMatchIn(c) && c.contains(TEXRES_UNIFORM)) {
                    c = Regex(SKEW_SEARCH).replace(c, SKEW_REPLACEMENT)
                    c = c.replace(TEXRES_UNIFORM, TEXRES_REPLACEMENT)
                }
            }
        }

        // ── 3. per-line generic passes (std::getline semantics) ──
        // v0.7.19 (APK ground truth): the PC's line loop PRESERVES the file's
        // trailing-newline state. A file that does NOT end with '\n' gets its
        // last line emitted WITHOUT a trailing '\n' (proof: the APK's
        // volumetricsback.frag / flat.frag / flatpoint.frag / editorsprite.frag
        // are 18/212/176/248 bytes, identical sizes to the PC originals — no
        // byte added). A file that DOES end with '\n' keeps exactly one.
        val endsWithNewline = c.isNotEmpty() && c.last() == '\n'
        val lines = c.split('\n').let { if (it.isNotEmpty() && it.last().isEmpty()) it.dropLast(1) else it }
        val out = StringBuilder(c.length + lines.size)
        for ((idx, line) in lines.withIndex()) {
            var l = line
            if (LOOP_LINE.containsMatchIn(l)) {
                l = FOR_INT_SAMPLECOUNT.replace(l) { m ->
                    FOR_FLOAT_SAMPLECOUNT_PREFIX + m.groupValues[1] + FOR_FLOAT_SAMPLECOUNT_SUFFIX
                }
            } else if (HASH_LINE.containsMatchIn(l) || DECL_LINE.containsMatchIn(l) ||
                BRACKET_LINE.containsMatchIn(l)) {
                // kept unchanged
            } else {
                l = INT_TO_FLOAT.replace(l) { m -> m.groupValues[1] + m.groupValues[2] + ".0" + m.groupValues[3] }
                l = SCI_NOTATION.replace(l) { m -> m.groupValues[1] }
                l = SAMPLE_KEYWORD.replace(l) { m -> m.groupValues[1] + "_" + m.groupValues[2] + m.groupValues[3] }
                l = CONST_INTC_SAMPLECOUNT.replace(l) { m -> m.groupValues[1] + "float" + m.groupValues[2] }
            }
            out.append(l)
            // separator between lines, or the file's own trailing newline —
            // never a newline the file didn't have
            if (idx < lines.size - 1 || endsWithNewline) out.append('\n')
        }
        c = out.toString()

        // ── 4. BlendOpacity ──
        c = fixBlendOpacity(c)
        return c
    }

    /**
     * Rewrite `BlendOpacity(base, blend, F, O)` calls to
     * `mix(base,F(base,blend),O)` (the macro body from common_blending.h,
     * inlined because the mobile common_blending.h does not ship the
     * macro).
     *
     * Scanner semantics copied 1:1 from the exe (0x140014c20): a boolean
     * "inside #define BlendOpacity" state is set whenever the scan
     * position is on a '#' whose remainder matches `^#define\s+BlendOpacity`,
     * and it stays sticky (carried char by char) until the next '#'
     * recomputes it. While the state is set, BlendOpacity occurrences are
     * copied through unchanged; otherwise a `BlendOpacity` followed by
     * optional whitespace (newlines included) and '(' with four balanced
     * top-level-comma-separated arguments is rewritten; anything else is
     * copied verbatim.
     */
    fun fixBlendOpacity(content: String): String {
        val out = StringBuilder(content.length)
        var inDefine = false
        var i = 0
        val n = content.length
        while (i < n) {
            val ch = content[i]
            if (ch == '#') {
                inDefine = DEFINE_BLENDOPACITY.matchAt(content, i) != null
            }
            if (!inDefine && content.startsWith("BlendOpacity", i)) {
                var j = i + "BlendOpacity".length
                while (j < n && content[j].isWhitespace()) j++   // exe skips \n \r too
                if (j < n && content[j] == '(') {
                    val parsed = parseCallArgs(content, j)
                    if (parsed != null && parsed.args.size == 4) {
                        val (base, blend, f, o) = parsed.args.map { it.trim() }
                        out.append("mix(").append(base).append(',')
                            .append(f).append('(').append(base).append(',').append(blend).append(')')
                            .append(',').append(o).append(')')
                        i = parsed.end
                        continue
                    }
                }
            }
            out.append(ch)
            i++
        }
        return out.toString()
    }

    private class ParsedCall(val args: List<String>, val end: Int)

    /** Parse a balanced `(a, b, c, d)` argument list starting at [open]. */
    private fun parseCallArgs(s: String, open: Int): ParsedCall? {
        var depth = 0
        val args = mutableListOf<String>()
        val cur = StringBuilder()
        var k = open
        val n = s.length
        while (k < n) {
            val c = s[k]
            when {
                c == '(' -> {
                    depth++
                    if (depth > 1) cur.append(c)
                }
                c == ')' -> {
                    depth--
                    if (depth == 0) {
                        args.add(cur.toString())
                        return ParsedCall(args, k + 1)
                    }
                    cur.append(c)
                }
                c == ',' && depth == 1 -> {
                    args.add(cur.toString()); cur.setLength(0)
                }
                else -> cur.append(c)
            }
            k++
        }
        return null
    }
}
