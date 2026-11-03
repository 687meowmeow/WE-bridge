package com.wepwawet.pairing

/**
 * GLSL ES 3.00 safety pass — replicates, at source level, the semantics the
 * REAL Wallpaper Engine PC pipeline gets for free from the D3D shader
 * compiler (fxc), but which the official Android engine's strict GLSL ES
 * compiler rejects.
 *
 * Why this exists (verified against the shipping binaries):
 *  - wallpaperui.exe's mobile transcoder (fixShader @0x140015f20 +
 *    deprModFix @0x140017dd0 + zcompat substitution) does NOT fix the
 *    HLSL-tolerant constructs below — the PC ships them verbatim in the
 *    .mpkg and relies on fxc to accept them.
 *  - The official Android engine (libscenejni.so) only prepends a macro
 *    header (#define texSample2D texture, ...) and compiles with strict
 *    GLSL ES 3.00 (proven by the engine's own "Failed compiling shader"
 *    log for workshop 2798696916's sharpen_filter.frag: implicit vec4→float
 *    truncation and an int-ternary both rejected).
 *  - Therefore the backend must make the PC's tolerant semantics explicit
 *    so the same shader compiles identically on both compilers.
 *
 * Rewrites performed (all semantics-preserving, fxc-faithful):
 *  1. Ternary with a non-bool scalar condition   c ? a : b  →  bool(c) ? a : b
 *     (fxc converts int/float conditions via != 0)
 *  2. if / while / for / do-while with a non-bool scalar condition → bool(c)
 *  3. Logical && / || / ! on non-bool scalars → bool(x)
 *  4. Implicit truncation on initialization / assignment / return:
 *       float x = <vecN>;  →  float x = (<expr>).x;      (N > 1)
 *       vec2  x = <vecN>;  →  vec2  x = (<expr>).xy;     (N > 2)
 *     (fxc X3206 "implicit truncation of vector type" takes the leading
 *      components)
 *  5. Scalar constructor from a vector argument:
 *       float(v4) / CASTF(v4)  →  float((v4).x) / CASTF((v4).x)
 *     (fxc scalar cast = .x; GLSL ES forbids scalar-from-vector ctors)
 *  6. #define'd macro bodies get the same treatment (they are expanded by
 *     the engine's preprocessor, so they must be ESSL-safe too).
 *
 * Design rule: CONSERVATIVE. Types come from declarations in the file, from
 * [COMBO] metadata (the engine injects `#define NAME <int>` for every combo
 * — verified from the engine's runtime shader assembly), from a built-in
 * function table, and from simple #define bodies. Anything not confidently
 * typed is left untouched. If anything about the token structure looks
 * inconsistent, the pass makes NO edits at all.
 *
 * The pass is pure Kotlin (no Android imports) and unit-testable on the JVM.
 */
object ShaderEsslSafety {

    // ═══════════════════════════════ lexer ═══════════════════════════════

    private enum class TKind { WS, COMMENT, IDENT, NUM, PUNCT, PREPROC }

    private class Tok(val kind: TKind, val s: String, val start: Int, val end: Int) {
        fun punct(p: String) = kind == TKind.PUNCT && s == p
        fun ident(id: String) = kind == TKind.IDENT && s == id
    }

    private val PUNCT3 = listOf("<<=", ">>=", "...")
    private val PUNCT2 = listOf("==", "!=", "<=", ">=", "&&", "||", "++", "--",
        "+=", "-=", "*=", "/=", "%=", "&=", "|=", "^=", "<<", ">>", "->")
    private val PUNCT1 = "{}()[];,.?:=+-*/%<>!~&|^#".map { it.toString() }

    private fun lex(src: String): List<Tok>? {
        val out = ArrayList<Tok>()
        var i = 0
        val n = src.length
        while (i < n) {
            val c = src[i]
            val start = i
            when {
                c == '\n' -> { i++; out.add(Tok(TKind.WS, "\n", start, i)) }
                c == ' ' || c == '\t' || c == '\r' -> {
                    while (i < n && (src[i] == ' ' || src[i] == '\t' || src[i] == '\r')) i++
                    out.add(Tok(TKind.WS, src.substring(start, i), start, i))
                }
                c == '/' && i + 1 < n && src[i + 1] == '/' -> {
                    while (i < n && src[i] != '\n') i++
                    out.add(Tok(TKind.COMMENT, src.substring(start, i), start, i))
                }
                c == '/' && i + 1 < n && src[i + 1] == '*' -> {
                    i += 2
                    while (i + 1 < n && !(src[i] == '*' && src[i + 1] == '/')) i++
                    if (i + 1 >= n) return null            // unterminated comment
                    i += 2
                    out.add(Tok(TKind.COMMENT, src.substring(start, i), start, i))
                }
                c == '#' && atLineStart(src, i) -> {
                    while (i < n && src[i] != '\n') i++
                    out.add(Tok(TKind.PREPROC, src.substring(start, i), start, i))
                }
                c == '"' -> {
                    i++
                    while (i < n && src[i] != '"' && src[i] != '\n') {
                        if (src[i] == '\\') i++
                        i++
                    }
                    if (i >= n || src[i] != '"') return null
                    i++
                    out.add(Tok(TKind.PREPROC, src.substring(start, i), start, i))
                }
                c.isDigit() || (c == '.' && i + 1 < n && src[i + 1].isDigit()) -> {
                    if (c == '0' && i + 1 < n && (src[i + 1] == 'x' || src[i + 1] == 'X')) {
                        i += 2
                        while (i < n && (src[i].isDigit() || src[i] in 'a'..'f' || src[i] in 'A'..'F')) i++
                    } else {
                        var seenDot = false
                        var seenE = false
                        while (i < n) {
                            val d = src[i]
                            if (d.isDigit()) i++
                            else if (d == '.' && !seenDot && !seenE) { seenDot = true; i++ }
                            else if ((d == 'e' || d == 'E') && !seenE && i + 1 < n &&
                                (src[i + 1].isDigit() || src[i + 1] == '+' || src[i + 1] == '-')) {
                                seenE = true; i++
                                if (src[i] == '+' || src[i] == '-') i++
                            } else if (d == 'f' || d == 'F' || d == 'u' || d == 'U' ||
                                d == 'l' || d == 'L') i++
                            else break
                        }
                    }
                    out.add(Tok(TKind.NUM, src.substring(start, i), start, i))
                }
                c == '_' || c.isLetter() -> {
                    while (i < n && (src[i] == '_' || src[i].isLetterOrDigit())) i++
                    out.add(Tok(TKind.IDENT, src.substring(start, i), start, i))
                }
                else -> {
                    var matched = false
                    for (p in PUNCT3) if (src.startsWith(p, i)) {
                        i += 3; out.add(Tok(TKind.PUNCT, p, start, i)); matched = true; break
                    }
                    if (!matched) for (p in PUNCT2) if (src.startsWith(p, i)) {
                        i += 2; out.add(Tok(TKind.PUNCT, p, start, i)); matched = true; break
                    }
                    if (!matched) {
                        if (c.toString() !in PUNCT1) return null   // stray char → bail
                        i++
                        out.add(Tok(TKind.PUNCT, c.toString(), start, i))
                    }
                }
            }
        }
        return out
    }

    private fun atLineStart(src: String, i: Int): Boolean {
        var j = i - 1
        while (j >= 0 && (src[j] == ' ' || src[j] == '\t')) j--
        return j < 0 || src[j] == '\n'
    }

    // ═══════════════════════════════ types ═══════════════════════════════

    private enum class B { F, I, U, BO, S, SH, M, VO, UNK }

    private class Ty(val b: B, val n: Int = 1, val arr: Boolean = false) {
        val isNumericScalar get() = n == 1 && (b == B.F || b == B.I || b == B.U)
        companion object {
            val F1 = Ty(B.F); val F2 = Ty(B.F, 2); val F3 = Ty(B.F, 3); val F4 = Ty(B.F, 4)
            val I1 = Ty(B.I)
            val BO1 = Ty(B.BO)
            val UNK = Ty(B.UNK)
            val VOID = Ty(B.VO)
        }
    }

    private fun typeKeyword(s: String): Ty? = when (s) {
        "float" -> Ty.F1; "vec2" -> Ty.F2; "vec3" -> Ty.F3; "vec4" -> Ty.F4
        "int" -> Ty.I1; "ivec2" -> Ty(B.I, 2); "ivec3" -> Ty(B.I, 3); "ivec4" -> Ty(B.I, 4)
        "uint" -> Ty(B.U); "uvec2" -> Ty(B.U, 2); "uvec3" -> Ty(B.U, 3); "uvec4" -> Ty(B.U, 4)
        "bool" -> Ty.BO1; "bvec2" -> Ty(B.BO, 2); "bvec3" -> Ty(B.BO, 3); "bvec4" -> Ty(B.BO, 4)
        "mat2" -> Ty(B.M, 2); "mat3" -> Ty(B.M, 3); "mat4" -> Ty(B.M, 4)
        "void" -> Ty.VOID
        "sampler2D", "sampler3D", "samplerCube",
        "sampler2DArray", "isampler2D", "usampler2D", "samplerExternalOES" -> Ty(B.S)
        "sampler2DShadow", "samplerCubeShadow" -> Ty(B.SH)
        else -> null
    }

    private fun scalarCtor(s: String): B? = when (s) {
        "float", "CASTF" -> B.F
        "int", "CASTI" -> B.I
        "uint", "CASTU" -> B.U
        "bool", "CASTB" -> B.BO
        else -> null
    }

    private fun vectorCtor(s: String): Ty? = when (s) {
        "vec2", "CAST2" -> Ty.F2
        "vec3", "CAST3" -> Ty.F3
        "vec4", "CAST4" -> Ty.F4
        "ivec2" -> Ty(B.I, 2); "ivec3" -> Ty(B.I, 3); "ivec4" -> Ty(B.I, 4)
        "uvec2" -> Ty(B.U, 2); "uvec3" -> Ty(B.U, 3); "uvec4" -> Ty(B.U, 4)
        "bvec2" -> Ty(B.BO, 2); "bvec3" -> Ty(B.BO, 3); "bvec4" -> Ty(B.BO, 4)
        else -> null
    }

    private class FnSig(val ret: Ty, val params: List<Ty> = emptyList())

    // ═══════════════ built-in function return types ══════════════════════

    private val BUILTIN_RET: Map<String, Ty> = mapOf(
        // WE sampling macros (the engine header maps them to texture/textureLod)
        "texSample2D" to Ty.F4, "texSample2DLod" to Ty.F4,
        "texSample3D" to Ty.F4, "texSample2DBackBuffer" to Ty.F4,
        "texSample2DCompare" to Ty.F1, "texSample2DCompareLevelZero" to Ty.F1,
        // GLSL builtins with fixed returns
        "texture" to Ty.F4, "texture2D" to Ty.F4, "texture3D" to Ty.F4,
        "textureLod" to Ty.F4, "textureProj" to Ty.F4, "texelFetch" to Ty.F4,
        "length" to Ty.F1, "distance" to Ty.F1, "dot" to Ty.F1,
        "cross" to Ty.F3,
        "any" to Ty.BO1, "all" to Ty.BO1,
        // engine CAST macros with fixed returns
        "CASTF" to Ty.F1, "CASTI" to Ty.I1, "CASTU" to Ty(B.U), "CASTB" to Ty.BO1,
        "CAST2" to Ty.F2, "CAST3" to Ty.F3, "CAST4" to Ty.F4
    )

    /** Builtins whose return type follows their first argument. */
    private val FIRST_ARG_RET = setOf(
        "radians", "degrees", "sin", "cos", "tan", "asin", "acos", "atan", "atan2",
        "sinh", "cosh", "tanh", "asinh", "acosh", "atanh", "exp", "log", "exp2", "log2",
        "sqrt", "inversesqrt", "abs", "sign", "floor", "ceil", "fract", "trunc", "round",
        "roundEven", "mod", "min", "max", "clamp", "mix", "step", "smoothstep", "fma",
        "normalize", "faceforward", "reflect", "refract", "pow", "modf",
        "dFdx", "dFdy", "fwidth", "saturate", "frac", "fmod", "lerp", "mod2", "not"
    )

    /**
     * v0.7.19: builtins that in ESSL 3.00 ONLY have (genType, genType)
     * signatures — NO scalar overloads (verified against the ESSL 3.00 spec
     * §8.2/8.3: pow, reflect and the two-argument atan lack mixed forms in
     * every position, while mix/mod/min/max/clamp DO have scalar overloads
     * and must NOT be rewritten; step accepts a scalar only in the EDGE
     * (first) position). fxc broadcasts scalar arguments
     * (pow(float3, float) etc.), so shaders written for the PC use the mixed
     * form (e.g. the current filmgrain's `pow(noise, g_NoisePower)` with
     * noise:vec3). The official APK's own shaders use the explicit form
     * (`pow(v, CAST3(2.4))` in combine_hdr.frag) — confirming the engine
     * dialect expects the broadcast to be explicit. Value = the argument
     * positions that REQUIRE genType.
     */
    private val SCALAR_VECTOR_BUILTINS: Map<String, Set<Int>> = mapOf(
        "pow" to setOf(0, 1),
        "reflect" to setOf(0, 1),
        "atan" to setOf(0, 1),
        "step" to setOf(1),
    )

    /**
     * v0.7.19: parameter types of the ENGINE's include-set functions
     * (the engine's shader include set — common.h, common_blur.h,
     * common_blending.h, common_composite.h, common_fog.h, common_particles.h,
     * common_pbr.h, common_pbr_2.h, common_perspective.h, common_foliage.h
     * and the base headers), extracted from the official Android APK's
     * shipped headers. The safety pass cannot see the include files
     * themselves (they live inside the engine), so their signatures are
     * pinned here for implicit-argument-conversion fixes (e.g. shimmer.frag's
     * rotateVec2(v_TexCoord, …) with a vec4 argument).
     */
    private val HEADER_FUNCTIONS: Map<String, List<List<Ty>>> = mapOf(
        "ApplyAlphaToCoverage" to listOf(listOf(Ty.F1)),
        "ApplyAmbientLighting" to listOf(listOf(Ty.F3)),
        "ApplyBlending" to listOf(listOf(Ty.I1, Ty.F3, Ty.F3, Ty.F1)),
        "ApplyComposite" to listOf(listOf(Ty.F4, Ty.F4)),
        "ApplyCompositeOffset" to listOf(listOf(Ty.F2, Ty.F2)),
        "ApplyFog" to listOf(listOf(Ty.F3, Ty.F2)),
        "ApplyFogAlpha" to listOf(listOf(Ty.F1, Ty.F2)),
        "ApplyPosition" to listOf(listOf(Ty.F3, Ty.F4)),
        "ApplyPositionNormal" to listOf(listOf(Ty.F3, Ty.F3, Ty.F4, Ty.F3)),
        "ApplySkinningPosition" to listOf(listOf(Ty.F3, Ty(B.U, 4), Ty.F4, Ty.F4)),
        "ApplySkinningPositionNormal" to listOf(listOf(Ty.F3, Ty.F3, Ty(B.U, 4), Ty.F4, Ty.F4, Ty.F3)),
        "ApplySkinningTangentSpace" to listOf(listOf(Ty.F3, Ty.F4, Ty(B.U, 4), Ty.F4, Ty.F3, Ty.F3)),
        "ApplyTangentSpace" to listOf(listOf(Ty.F3, Ty.F4, Ty.F3, Ty.F3)),
        "BlendColor" to listOf(listOf(Ty.F3, Ty.F3)),
        "BlendHue" to listOf(listOf(Ty.F3, Ty.F3)),
        "BlendLuminosity" to listOf(listOf(Ty.F3, Ty.F3)),
        "BlendSaturation" to listOf(listOf(Ty.F3, Ty.F3)),
        "BuildTangentSpace" to listOf(listOf(Ty.F3, Ty.F4), listOf(Ty(B.M, 3), Ty.F3, Ty.F4)),
        "CalcFoliageAnimation" to listOf(listOf(Ty.F3, Ty.F3, Ty.F2, Ty.F1, Ty.F1, Ty.F1, Ty.F1, Ty.F1, Ty.F1, Ty.F1, Ty.F1, Ty.F1, Ty.F1, Ty.F1, Ty.F2)),
        "CalcLeavesUVWeight" to listOf(listOf(Ty.F2, Ty.F2)),
        "CalculateFogPixelState" to listOf(listOf(Ty.F1, Ty.F1)),
        "CalculateProjectedCoords" to listOf(listOf(Ty.F3, Ty(B.M, 4))),
        "CalculateProjectedCoordsCascades" to listOf(listOf(Ty.F3, Ty(B.M, 4))),
        "CalculateProjectedCoordsPoint" to listOf(listOf(Ty.F3, Ty.F3, Ty.F4, Ty.F4)),
        "ClipSpaceToScreenSpace" to listOf(listOf(Ty.F4, Ty.F3)),
        "CombineLighting" to listOf(listOf(Ty.F3, Ty.F3), listOf(Ty.F3, Ty.F3, Ty.F3)),
        "ComputeLight" to listOf(listOf(Ty.F3, Ty.F3, Ty.F3, Ty.F1)),
        "ComputeLightSpecular" to listOf(listOf(Ty.F3, Ty.F3, Ty.F3, Ty.F1, Ty.F3, Ty.F1, Ty.F1, Ty.F1, Ty.F1, Ty.F3)),
        "ComputeMaterialSpecularPower" to listOf(listOf(Ty.F1, Ty.F1)),
        "ComputeMaterialSpecularStrength" to listOf(listOf(Ty.F1, Ty.F1)),
        "ComputePBRLight" to listOf(listOf(Ty.F3, Ty.F3, Ty.F3, Ty.F3, Ty.F3, Ty.F3, Ty.F1, Ty.F1)),
        "ComputePBRLightShadow" to listOf(listOf(Ty.F3, Ty.F3, Ty.F3, Ty.F3, Ty.F3, Ty.F1, Ty.F1, Ty.F1, Ty.F3, Ty.F3, Ty.F1, Ty.F1, Ty.F1)),
        "ComputePBRLightShadowInfinite" to listOf(listOf(Ty.F3, Ty.F3, Ty.F3, Ty.F3, Ty.F3, Ty.F3, Ty.F1, Ty.F1, Ty.F1)),
        "ComputeParticlePosition" to listOf(listOf(Ty.F2, Ty.F1, Ty.F4, Ty.F3, Ty.F3)),
        "ComputeParticleTangents" to listOf(listOf(Ty.F3, Ty.F3, Ty.F3)),
        "ComputeParticleTrailTangents" to listOf(listOf(Ty.F3, Ty.F3, Ty.F3, Ty.F3)),
        "ComputeScreenRefractionCoord" to listOf(listOf(Ty.F3, Ty.F3)),
        "ComputeScreenRefractionTangents" to listOf(listOf(Ty.F3, Ty.F3, Ty.F3, Ty.F3, Ty.F4)),
        "ComputeSpriteFrame" to listOf(listOf(Ty.F1, Ty.F4, Ty.F2, Ty.F1)),
        "ContrastSaturationBrightness" to listOf(listOf(Ty.F3, Ty.F1, Ty.F1, Ty.F1)),
        "ConvertSampleR8" to listOf(listOf(Ty.F4)),
        "ConvertTexture0Format" to listOf(listOf(Ty.F4)),
        "ConvertTextureFormat" to listOf(listOf(Ty.I1, Ty.F4)),
        "DecompressNormal" to listOf(listOf(Ty.F4)),
        "DecompressNormalWithMask" to listOf(listOf(Ty.F4)),
        "Desaturate" to listOf(listOf(Ty.F3, Ty.F1)),
        "Distribution_GGX" to listOf(listOf(Ty.F3, Ty.F3, Ty.F1)),
        "FresnelSchlick" to listOf(listOf(Ty.F1, Ty.F3)),
        "GeoSmith" to listOf(listOf(Ty.F3, Ty.F3, Ty.F3, Ty.F1)),
        "HSLToRGB" to listOf(listOf(Ty.F3)),
        "HueToRGB" to listOf(listOf(Ty.F1, Ty.F1, Ty.F1)),
        "PerformPointShadowMapping" to listOf(listOf(Ty.F4)),
        "PerformShadowMapping" to listOf(listOf(Ty.F3, Ty.F4)),
        "PointSegmentDelta" to listOf(listOf(Ty.F3, Ty.F3, Ty.F3)),
        "RGBToHSL" to listOf(listOf(Ty.F3)),
        "Schlick_GGX" to listOf(listOf(Ty.F1, Ty.F1)),
        "blur13" to listOf(listOf(Ty.F2, Ty.F2)),
        "blur13a" to listOf(listOf(Ty.F2, Ty.F2)),
        "blur3" to listOf(listOf(Ty.F2, Ty.F2)),
        "blur3a" to listOf(listOf(Ty.F2, Ty.F2)),
        "blur7" to listOf(listOf(Ty.F2, Ty.F2)),
        "blur7a" to listOf(listOf(Ty.F2, Ty.F2)),
        "blurRadial13a" to listOf(listOf(Ty.F2, Ty.F2, Ty.F1)),
        "blurRadial3a" to listOf(listOf(Ty.F2, Ty.F2, Ty.F1)),
        "blurRadial7a" to listOf(listOf(Ty.F2, Ty.F2, Ty.F1)),
        "blurRotateVec2" to listOf(listOf(Ty.F2, Ty.F1)),
        "greyscale" to listOf(listOf(Ty.F3)),
        "hsv2rgb" to listOf(listOf(Ty.F3)),
        "random" to listOf(listOf(Ty.F2)),
        "rgb2hsv" to listOf(listOf(Ty.F3)),
        "rotateVec2" to listOf(listOf(Ty.F2, Ty.F1)),
        "squareToQuad" to listOf(listOf(Ty.F2, Ty.F2, Ty.F2, Ty.F2)),
    )

    private fun promote(a: Ty, b: Ty): Ty {
        if (a.b == B.UNK || b.b == B.UNK) return Ty.UNK
        if (a.b == B.S || b.b == B.S || a.b == B.SH || b.b == B.SH ||
            a.b == B.M || b.b == B.M || a.b == B.VO || b.b == B.VO) return Ty.UNK
        val base = if (a.b == B.F || b.b == B.F) B.F else if (a.b == B.U || b.b == B.U) B.U else B.I
        return Ty(base, maxOf(a.n, b.n))
    }

    // ═══════════════════════════ edits ═══════════════════════════════════

    private class Edit(val pos: Int, val text: String, val closing: Boolean = false)

    /**
     * v0.7.19 (blur-include hoist): the engine's only header that references
     * a sampler it does not declare is common_blur.h (every blur*() helper
     * samples g_Texture0). The real PC pipeline NEVER fixes the resulting
     * declaration order — wallpaperui.exe's blur fix is dead code (its regex
     * `^(#include "common_blur\.h")` is anchored with the optimize flag and
     * no multiline bit, so `^` can only match at offset 0; proven against the
     * official Android APK, which ships blur_k3.frag / editorpaintbrush.frag
     * with the include ABOVE the uniform) — so PC-made .mpkgs carry shaders
     * where the inlined blur functions reference g_Texture0 before its
     * declaration. The Android engine inlines #include at the directive line
     * and its strict compiler then rejects the shader ('g_Texture0' :
     * undeclared identifier — observed in the engine's own failure log for
     * blur_precise_gaussian.frag), silently dropping the whole effect.
     *
     * This pass reproduces, in the safety layer, exactly the hoist the PC's
     * dead blur-fix stage was designed to perform:
     *   • delete every `uniform sampler2D g_Texture0;` that appears AFTER the
     *     `#include "common_blur.h"` line (substring deletion, like the PC's
     *     icase regex; a trailing metadata comment survives on its line)
     *   • insert one `uniform sampler2D g_Texture0;\n` immediately before the
     *     include line (the PC's 29-byte replacement string)
     * A shader that already declares g_Texture0 above the include (e.g.
     * godrays_gaussian.frag) is left byte-identical.
     */
    private val BLUR_INCLUDE_LINE = Regex("""^[ \t]*#[ \t]*include[ \t]*"common_blur\.h""", RegexOption.MULTILINE)
    private val G_TEXTURE0_DECL = Regex("""uniform[ \t]+sampler2D[ \t]+g_Texture0[ \t]*;""", RegexOption.IGNORE_CASE)
    private const val G_TEXTURE0_HOIST = "uniform sampler2D g_Texture0;\n"

    private fun blurIncludeHoist(content: String): String {
        val inc = BLUR_INCLUDE_LINE.find(content) ?: return content
        val after = content.substring(inc.range.last + 1)
        if (!G_TEXTURE0_DECL.containsMatchIn(after)) return content
        // Delete every declaration after the include, then hoist one before it.
        val cleanedAfter = G_TEXTURE0_DECL.replace(after) { "" }
        val cleaned = content.substring(0, inc.range.last + 1) + cleanedAfter
        return cleaned.substring(0, inc.range.first) + G_TEXTURE0_HOIST +
            cleaned.substring(inc.range.first)
    }

    /**
     * v0.7.19 (reserved-word identifiers): GLSL ES 3.00 §3.7 reserves a list
     * of words that HLSL happily allows as identifiers — most notably
     * `input` and `output` (the PC's current blend.frag / blendgradient.frag
     * declare `vec4 input = albedo;` inside #if WRITEALPHA, so any wallpaper
     * using the blend effect with Write-Alpha enabled ships a shader that
     * spec-strict compilers reject with "'input' : Reserved word"). Rename
     * every DECLARED identifier whose name is reserved, using the PC's own
     * deprecation naming convention (`_deprFn_0` for the mod() rename):
     * `_deprVar_0`, `_deprVar_1`, … All occurrences of the name are renamed
     * (declaration + uses), except member accesses (`x.input`) whose struct
     * is defined elsewhere, and preprocessor/comment text.
     */
    private val ESSL_RESERVED_WORDS = setOf(
        "input", "output", "half", "fixed", "double", "filter", "superp", "packed",
        "this", "sizeof", "cast", "namespace", "using", "asm", "class", "union",
        "enum", "typedef", "template", "goto", "inline", "noinline", "volatile",
        "public", "static", "extern", "external", "interface", "flat", "long",
        "short", "unsigned", "hvec2", "hvec3", "hvec4", "dvec2", "dvec3", "dvec4",
        "fvec2", "fvec3", "fvec4", "sampler1D", "sampler1DShadow", "sampler2DRect",
        "sampler2DRectShadow", "sampler3DRect", "image1D", "image2D", "image3D",
        "imageCube", "image1DArray", "image2DArray", "iimage", "uimage", "noise1",
        "noise2", "noise3", "noise4"
    )

    private fun renameReservedIdentifiers(content: String): Pair<String, List<String>> {
        val toks = lex(content) ?: return content to emptyList()
        val code = toks.filter { it.kind != TKind.WS && it.kind != TKind.COMMENT }
        // 1) find declarations of reserved names: TYPE_KEYWORD <reserved> …
        val declared = LinkedHashSet<String>()
        for (k in 0 until code.size) {
            val t = code[k]
            if (t.kind != TKind.IDENT) continue
            if (typeKeyword(t.s) == null && t.s !in MODIFIERS) continue
            // scan forward: modifiers/precision then the identifier
            var j = k + 1
            while (j < code.size && (code[j].kind == TKind.IDENT && code[j].s in MODIFIERS)) j++
            val nameTok = code.getOrNull(j) ?: continue
            if (nameTok.kind == TKind.IDENT && nameTok.s in ESSL_RESERVED_WORDS) {
                declared.add(nameTok.s)
            }
        }
        if (declared.isEmpty()) return content to emptyList()
        // 2) rename every occurrence (except after '.' — struct member access
        //    whose definition may live in an include). IDENT tokens are all in
        //    the code subsequence, so walking it covers every occurrence.
        val renames = HashMap<String, String>()
        var n = 0
        for (d in declared) { renames[d] = "_deprVar_${n}"; n++ }
        val sb = StringBuilder(content.length)
        var cursor = 0
        val notes = ArrayList<String>()
        for (ci in code.indices) {
            val t = code[ci]
            if (t.kind != TKind.IDENT || t.s !in renames) continue
            val prev = if (ci > 0) code[ci - 1] else null
            if (prev?.punct(".") == true) continue
            sb.append(content, cursor, t.start)
            sb.append(renames[t.s]!!)
            cursor = t.end
            notes.add("reserved-word rename ${t.s}→${renames[t.s]} @char${t.start}")
        }
        sb.append(content, cursor, content.length)
        return sb.toString() to notes
    }

    /**
     * v0.7.19 note (#require): `#require LightingV1` (used by the current
     * fluidsimulation_combine.frag, chroma4.frag, generic4.frag, fur4.frag,
     * foliage4.frag, genericparticle.frag — all shipped in the official APK)
     * is the ENGINE's feature-injection directive: the engine's shader loader
     * consumes it and injects the LightingV1 feature code (the embedded
     * `vec3 PerformLighting_V1(vec3, vec3, vec3, vec3, vec3, vec3, float,
     * float)` body lives as a string inside libscenejni.so at 0xc654cf,
     * assembled with the g_LPoint_ / g_LTube_ / g_LDirectional_ uniform blocks
     * also embedded there). It must therefore ship VERBATIM in the .mpkg —
     * stripping it would prevent the injection and break the shader on the
     * phone. No rewrite is performed; this note exists so future agents do
     * not "fix" it away (an earlier draft of this pass stripped it — wrong).
     */

    /**
     * v0.7.19 (shadow-sampler translation): `sampler2DComparison` /
     * `texSample2DCompare` are Wallpaper Engine's PC-side shadow-sampling
     * constructs. NEITHER exists on the Android engine: the engine's prepended
     * define block (disassembled at libscenejni.so 0x2606fbc) only defines
     * texSample2D / texSample2DLod / texSample3D, and the strings
     * "sampler2DComparison" / "texSample2DCompare" appear nowhere in
     * libscenejni.so — yet the APK's own common_pbr_2.h calls them (dead
     * PC-only paths that Android never compiles). GLSL ES 3.00's native
     * equivalents are sampler2DShadow + texture(s, vec3(uv, ref)), which is
     * what the PC's HLSL SampleCmp maps to. Translate both so shadow-mapped
     * effects (fluidsimulation with LIGHTS_SHADOW_MAPPING, workshop shaders
     * using PerformShadowMapping) compile on the phone instead of being
     * silently dropped.
     */
    private val SAMPLER_COMPARISON_TYPE = Regex("""\bsampler2DComparison\b""")
    // ESSL requires an explicit precision qualifier on non-sampler2D sampler
    // types ("'sampler2DShadow' : type requires declaration of default
    // precision qualifier" — glslang 16.6), so the replacement carries one.
    private const val SAMPLER_SHADOW_TYPE = "mediump sampler2DShadow"

    /**
     * texSample2DCompare(s, uv, ref) → texture(s, vec3(uv, ref)) — a balanced
     * scanner (the same argument-splitting rules as the PC's BlendOpacity
     * fixer): top-level commas split the three arguments; anything else is
     * copied verbatim. Only fires on the exact 3-argument form; other shapes
     * are left untouched.
     *
     * v0.7.19 (glslang 16.6 ground truth): texture() on a sampler2DShadow
     * returns a SCALAR float, so a trailing component selection on the
     * rewritten call (`texSample2DCompare(...).r`, the universal HLSL
     * SampleCmp idiom) becomes a scalar swizzle — legal in desktop GLSL,
     * "'scalar swizzle' : not supported with this profile: es" in ESSL 3.00
     * (proven with the exact PerformShadowMapping body the engine's own
     * common_pbr_2.h ships). The rewrite therefore CONSUMES one trailing
     * .r/.x/.s selection when it directly follows the call.
     */
    private fun rewriteCompareSamplerCalls(content: String): Pair<String, List<String>> {
        if (!content.contains("texSample2DCompare")) return content to emptyList()
        val out = StringBuilder(content.length)
        val notes = ArrayList<String>()
        var i = 0
        val n = content.length
        while (i < n) {
            val at = content.indexOf("texSample2DCompare", i)
            if (at < 0) { out.append(content, i, n); break }
            // must not be part of a longer identifier (texSample2DCompareLevelZero)
            val afterName = at + "texSample2DCompare".length
            val prevChar = if (at > 0) content[at - 1] else ' '
            val nextChar = if (afterName < n) content[afterName] else ' '
            val isIdent = prevChar.isLetterOrDigit() || prevChar == '_' || nextChar.isLetterOrDigit() || nextChar == '_'
            var j = afterName
            while (j < n && content[j].isWhitespace()) j++
            if (isIdent || j >= n || content[j] != '(') {
                out.append(content, i, afterName)
                i = afterName
                continue
            }
            // parse the balanced argument list starting at j '('
            var depth = 0
            val args = ArrayList<String>()
            val cur = StringBuilder()
            var k = j
            var end = -1
            while (k < n) {
                val c = content[k]
                when {
                    c == '(' -> { depth++; if (depth > 1) cur.append(c) }
                    c == ')' -> {
                        depth--
                        if (depth == 0) { args.add(cur.toString()); end = k + 1; break }
                        cur.append(c)
                    }
                    c == ',' && depth == 1 -> { args.add(cur.toString()); cur.setLength(0) }
                    else -> cur.append(c)
                }
                k++
            }
            if (end < 0 || args.size != 3) {
                out.append(content, i, afterName)
                i = afterName
                continue
            }
            out.append(content, i, at)
            out.append("texture(").append(args[0].trim())
                .append(", vec3(").append(args[1].trim()).append(", ").append(args[2].trim()).append("))")
            notes.add("texSample2DCompare→texture+vec3 @char$at")
            i = end
            // consume ONE trailing component selection — texture() on a
            // shadow sampler already returns the compared float
            var sw = i
            while (sw < n && content[sw] == ' ') sw++
            if (sw < n && content[sw] == '.' && sw + 1 < n &&
                (content[sw + 1] == 'r' || content[sw + 1] == 'x' || content[sw + 1] == 's')) {
                val afterSw = sw + 2
                val nc = if (afterSw < n) content[afterSw] else ' '
                if (!nc.isLetterOrDigit() && nc != '_') {
                    notes.add("shadow-result .${content[sw + 1]} consumed @char$sw")
                    i = afterSw
                }
            }
        }
        return out.toString() to notes
    }

    /**
     * v0.7.19 (xray ground truth): the CURRENT built-in xray assets ship
     * PRE-PATCHED — `varying float v_PointerScale;` is immediately followed
     * by the patch's own `uniform vec4 g_PointerState;`, and the frag's
     * `unprojectedUVs += 0.5;` is preceded by the patched
     * `unprojectedUVs *= CAST2(1.0 / max(0.00001, g_PointerState.y));` line.
     * Re-applying the PC's xray patch table (which the byte-parity port
     * faithfully does) duplicates BOTH — and a duplicated uniform is a
     * redefinition error in ESSL 3.00 (fxc tolerates it like a C tentative
     * definition, so the PC pipeline never noticed). fxc-tolerant redeclared
     * uniforms are deduplicated here: every later declaration whose
     * `uniform <type> <name>;` text is IDENTICAL to an earlier one (ignoring
     * the trailing metadata comment) is removed.
     */
    private val UNIFORM_DECL = Regex("""^[ \t]*uniform[ \t]+((?:\w+[ \t]+)*)(\w+)[ \t]*;""", RegexOption.MULTILINE)

    private fun dedupeUniformDeclarations(content: String): Pair<String, List<String>> {
        val seen = HashMap<String, Int>()          // "type name" -> count
        var dup = false
        for (m in UNIFORM_DECL.findAll(content)) {
            val key = m.groupValues[1].replace(Regex("[ \t]+"), " ").trim() + " " + m.groupValues[2]
            val c = seen.getOrDefault(key, 0)
            seen[key] = c + 1
            if (c + 1 > 1) dup = true
        }
        if (!dup) return content to emptyList()
        val counts = HashMap<String, Int>()
        val notes = ArrayList<String>()
        val out = StringBuilder(content.length)
        var cursor = 0
        for (m in UNIFORM_DECL.findAll(content)) {
            val key = m.groupValues[1].replace(Regex("[ \t]+"), " ").trim() + " " + m.groupValues[2]
            val c = counts.getOrDefault(key, 0)
            counts[key] = c + 1
            if (c + 1 > 1) {
                // remove the whole line (the declaration plus its newline)
                var lineEnd = m.range.last + 1
                while (lineEnd < content.length && content[lineEnd] != '\n') lineEnd++
                if (lineEnd < content.length) lineEnd++
                out.append(content, cursor, m.range.first)
                cursor = lineEnd
                notes.add("dedup uniform $key")
            }
        }
        out.append(content, cursor, content.length)
        return out.toString() to notes
    }

    /**
     * v0.7.19 (xray preview ground truth): the PC's xray patch inserts a
     * `g_PointerState.y` use gated on a `varying float v_PointerScale;`
     * declaration that not every xray variant carries — when the varying is
     * absent (the preview variant) the patched frag references an undeclared
     * identifier. g_PointerState is an engine-bound uniform (the patch
     * declares it as `uniform vec4 g_PointerState;` whenever it fires), so
     * referencing it without a declaration is repaired by declaring it at
     * the top of the file — the engine binds uniforms by name, exactly as it
     * does for the declaration the patch itself inserts.
     */
    private fun declareMissingEngineUniforms(content: String): Pair<String, List<String>> {
        val notes = ArrayList<String>()
        var out = content
        // v0.7.24 (H-S1 BUG FIX): the detection regex required a BARE
        // `uniform vec4 g_PointerState;` — a pre-existing precision-qualified
        // declaration (`uniform lowp/mediump/highp vec4 …`) did NOT match,
        // so the pass inserted a SECOND declaration → ESSL redefinition
        // error. The regex now accepts an optional precision qualifier.
        if (Regex("""\bg_PointerState\b""").containsMatchIn(out) &&
            !Regex("""uniform[ \t]+(?:lowp|mediump|highp[ \t]+)?vec4[ \t]+g_PointerState[ \t]*;""").containsMatchIn(out)) {
            out = "uniform vec4 g_PointerState;\n" + out
            notes.add("declared missing uniform vec4 g_PointerState")
        }
        return out to notes
    }

    /** Public entry: apply the pass, returning the new source and a report. */
    fun applyWithReport(content: String): Pair<String, List<String>> {
        val (callRewritten, callNotes) = rewriteCompareSamplerCalls(content)
        val shadowTyped = if (SAMPLER_COMPARISON_TYPE.containsMatchIn(callRewritten)) {
            SAMPLER_COMPARISON_TYPE.replace(callRewritten) { SAMPLER_SHADOW_TYPE }
        } else callRewritten
        val shadowNotes = if (shadowTyped !== callRewritten) listOf("sampler2DComparison→sampler2DShadow") else emptyList()
        val (deduped, dedupeNotes) = dedupeUniformDeclarations(shadowTyped)
        val (declared, declareNotes) = declareMissingEngineUniforms(deduped)
        val (renamed, renameNotes) = renameReservedIdentifiers(declared)
        val hoisted = blurIncludeHoist(renamed)
        val notes = ArrayList<String>(callNotes + shadowNotes + dedupeNotes + declareNotes + renameNotes)
        if (hoisted !== renamed) notes.add("blur-include hoist @char${BLUR_INCLUDE_LINE.find(renamed)?.range?.first}")
        // v0.7.24 (H-S6 BUG FIX): when lex() fails on the hoisted source (an
        // unterminated comment/string or a stray non-ASCII char), the old code
        // returned the ORIGINAL `content` with an EMPTY report — silently
        // discarding every prior rewrite (call replacement, shadow samplers,
        // dedupe, declare-missing, rename, blur hoist). The string-level
        // rewrites are still valid on `hoisted`; only the token-level edits
        // are skipped. Return the hoisted source + the notes accumulated so far.
        val toks = lex(hoisted) ?: return hoisted to (notes + listOf("lexer-failure: token-level passes skipped"))
        val edits = ArrayList<Edit>()
        val code = toks.filter { it.kind != TKind.WS && it.kind != TKind.COMMENT }

        val p = ProgramParser(hoisted, toks, code, edits, notes)
        try {
            p.parseProgram()
        } catch (e: Throwable) {
            // v0.7.24 (C-S4 BUG FIX): was `catch (e: Exception)`. The recursive
            // descent parser has no depth limit — a shader with hundreds of
            // nested parens/ternaries overflows the JVM stack, and
            // StackOverflowError extends Error, NOT Exception, so it escaped
            // this handler and crashed the process. Catching Throwable keeps
            // the fail-safe "return the rewritten-so-far source" behavior.
            notes.add("parser-failure (${e.javaClass.simpleName}): token-level edits skipped")
            return hoisted to notes
        }
        if (!p.balanced) return hoisted to notes
        if (edits.isEmpty()) return hoisted to notes

        // Splice order for edits at the SAME position (nested wraps):
        //   1. closings, in collection order  (inner ')' before outer ')')
        //   2. openings, in REVERSE collection order (outer '(' before inner)
        // e.g. `vec2 w = u_a ? u_v : u_v.zwxy;` needs
        //      (bool(u_a) ? u_v : u_v.zwxy).xy   — never  bool((u_a) ? …).xy
        val seq = HashMap<Edit, Int>()
        edits.forEachIndexed { idx, e -> seq[e] = idx }
        val sorted = edits.sortedWith(
            compareBy({ it.pos }, { if (it.closing) 0 else 1 }, { if (it.closing) seq[it]!! else -seq[it]!! })
        )
        val sb = StringBuilder(hoisted.length + 64)
        var cursor = 0
        for (e in sorted) {
            if (e.pos < cursor) return hoisted to notes   // overlap → keep the hoist only
            sb.append(hoisted, cursor, e.pos)
            sb.append(e.text)
            cursor = e.pos
        }
        sb.append(hoisted, cursor, hoisted.length)
        return sb.toString() to notes
    }

    fun apply(content: String): String = applyWithReport(content).first

    // ═══════════════════ expression result handle ════════════════════════

    private class ERes(val ty: Ty, val startIdx: Int, val endIdx: Int)

    // ═══════════════════ base expression parser ══════════════════════════

    private abstract class ExprParser(
        @Suppress("unused") val src: String,
        @Suppress("unused") val toks: List<Tok>,
        protected val edits: MutableList<Edit>,
        protected val notes: MutableList<String>,
    ) {
        abstract val code: List<Tok>
        open var baseOffset = 0
        var i = 0

        abstract fun lookup(name: String): Ty?
        abstract fun lookupCallable(name: String): Ty?

        /**
         * v0.7.19: parameter types of a callable, for implicit-argument-
         * conversion fixes (truncation / int→float). Returns null when the
         * signature is unknown or the arg count doesn't fit any overload.
         */
        open fun paramTypesFor(name: String, argc: Int): List<Ty>? {
            val overloads = HEADER_FUNCTIONS[name] ?: return null
            for (ps in overloads) {
                if (ps.size == argc) return ps
            }
            // a call with fewer args than params (default-valued params) also matches
            for (ps in overloads) {
                if (argc < ps.size) return ps.take(argc)
            }
            return null
        }

        fun peek(k: Int = 0): Tok? = code.getOrNull(i + k)
        fun atEnd() = i >= code.size
        fun atP(p: String, k: Int = 0): Boolean = peek(k)?.punct(p) == true
        fun atId(id: String, k: Int = 0): Boolean = peek(k)?.ident(id) == true

        fun addEdit(pos: Int, text: String, closing: Boolean = false) {
            edits.add(Edit(pos + baseOffset, text, closing))
        }
        fun note(what: String, at: Tok) { notes.add("$what @char${at.start + baseOffset}") }

        fun tok(idx: Int): Tok? = code.getOrNull(idx)

        /**
         * v0.7.19: ESSL 3.00 has NO implicit int→float conversion — not in
         * assignments, not in binary operators (verified with glslang 16.6:
         * `float f = 5;` and `vec2 * 2` are both errors, while desktop GLSL
         * and fxc accept both). The PC pipeline only rewrites integer
         * LITERALS (its int→float regex), so `1.0 / numLayers` (int variable)
         * survives PC processing and fails on strict ES compilers. Wrap the
         * integer-typed operand in the matching float constructor — exactly
         * the conversion fxc applies implicitly.
         */
        fun wrapIntToFloat(e: ERes) {
            if (e.ty.b != B.I && e.ty.b != B.U) return
            if (e.ty.n < 1 || e.ty.n > 4) return
            val first = tok(e.startIdx) ?: return
            val last = tok(e.endIdx) ?: return
            val ctor = if (e.ty.n == 1) "float(" else "vec${e.ty.n}("
            addEdit(first.start, ctor)
            addEdit(last.end, ")", closing = true)
            note("int→float", first)
        }

        /** true for float-based types (float / vecN) */
        fun isFloatTy(t: Ty) = t.b == B.F
        /** true for integer-based types (int/uint and their vectors) */
        fun isIntTy(t: Ty) = t.b == B.I || t.b == B.U
        /** true for any numeric scalar/vector base we can convert */
        fun isNumericTy(t: Ty) =
            (t.b == B.F || t.b == B.I || t.b == B.U || t.b == B.BO) && t.n in 1..4 && !t.arr

        /**
         * Mixed int/float arithmetic or comparison: wrap the int side.
         * A scalar int works in any context after float() (vecN op float is
         * legal ESSL); an ivecN only pairs with a matching-size or scalar
         * float side after vecN(). Returns the promoted type of the pair so
         * callers (ternary arms) record the CORRECT result type — otherwise
         * the wrap's edit and the recorded type disagree and an outer pass
         * wraps a second time (float(float(x))).
         */
        fun fixMixedIntFloat(lhs: ERes, rhs: ERes): Ty {
            val lf = isFloatTy(lhs.ty); val rf = isFloatTy(rhs.ty)
            val li = isIntTy(lhs.ty); val ri = isIntTy(rhs.ty)
            fun sizesOk(intTy: Ty, floatTy: Ty) =
                intTy.n == 1 || floatTy.n == 1 || intTy.n == floatTy.n
            return when {
                lf && ri && rhs.ty.n in 1..4 && sizesOk(rhs.ty, lhs.ty) -> {
                    wrapIntToFloat(rhs); lhs.ty
                }
                rf && li && lhs.ty.n in 1..4 && sizesOk(lhs.ty, rhs.ty) -> {
                    wrapIntToFloat(lhs); rhs.ty
                }
                else -> promote(lhs.ty, rhs.ty)
            }
        }

        // ── entry: full expression with assignment ──
        fun parseAssignExpr(): ERes {
            val lhs = parseExpr(2)
            val t = peek() ?: return lhs
            if (t.kind == TKind.PUNCT && (t.s == "=" || t.s in COMPOUND_ASSIGN_OPS)) {
                val lhsTy = lhs.ty
                val compound = t.s != "="
                i++
                val rhs = parseAssignExpr()                // right-assoc
                if (lhsTy.b != B.UNK && lhsTy.b != B.S && lhsTy.b != B.SH && lhsTy.b != B.M && !lhsTy.arr) {
                    // v0.7.19: compound assignments (`a *= expr`) previously
                    // fell through un-parsed — the whole RHS was skipped, so
                    // int/float mixes and truncations inside them were missed
                    if (!compound) {
                        wrapTruncation(lhsTy, rhs)
                    } else {
                        // v0.7.19: `v *= intExpr` / `f += intExpr` — ESSL 3.00
                        // has no implicit int→float in compound assignments
                        // either (fxc promotes silently). Same-size or scalar
                        // int RHS against a float LHS gets the ctor wrap.
                        if (isFloatTy(lhsTy) && isIntTy(rhs.ty) &&
                            (lhsTy.n == rhs.ty.n || rhs.ty.n == 1 || lhsTy.n == 1)) {
                            wrapIntToFloat(rhs)
                        }
                    }
                }
                return ERes(lhsTy, lhs.startIdx, rhs.endIdx)
            }
            return lhs
        }

        private val COMPOUND_ASSIGN_OPS = setOf("+=", "-=", "*=", "/=", "%=", "&=", "|=", "^=", "<<=", ">>=")

        // ── binary / ternary; stops at '=', ',', ';', ')' … ──
        private fun parseExpr(minPrec: Int): ERes {
            var lhs = parseUnary()
            while (true) {
                val t = peek() ?: break
                if (t.kind != TKind.PUNCT) break
                val op = t.s
                when {
                    op == "?" -> {
                        if (minPrec > 2) break
                        if (lhs.ty.isNumericScalar) {
                            val first = tok(lhs.startIdx)
                            val last = tok(lhs.endIdx)
                            if (first != null && last != null) {
                                addEdit(first.start, "bool(")
                                addEdit(last.end, ")", closing = true)
                                note("ternary→bool", first)
                            }
                        }
                        i++
                        val thenE = parseAssignExpr()
                        if (atP(":")) i++
                        val elseE = parseExpr(2)            // right-assoc chain
                        // v0.7.19: mixed ternary arms (`c ? 1 : 0.5`, `c ? i : f`)
                        // — fxc promotes the int arm to float; ESSL 3.00
                        // requires identical arm types. The promoted type is
                        // recorded so an outer assignment does not wrap twice.
                        val armTy = fixMixedIntFloat(thenE, elseE)
                        val rty = if (armTy.b != B.UNK) armTy else
                            (if (thenE.ty.b != B.UNK) thenE.ty else elseE.ty)
                        lhs = ERes(rty, lhs.startIdx, elseE.endIdx)
                    }
                    op == "||" || op == "&&" -> {
                        val prec = if (op == "||") 3 else 4
                        if (prec < minPrec) break
                        wrapBoolIfNumeric(lhs)
                        i++
                        val rhs = parseExpr(prec + 1)
                        wrapBoolIfNumeric(rhs)
                        lhs = ERes(Ty.BO1, lhs.startIdx, rhs.endIdx)
                    }
                    op == "|" -> { val r = op(5, minPrec, lhs, op) ?: break; lhs = r }
                    op == "^" -> { val r = op(6, minPrec, lhs, op) ?: break; lhs = r }
                    op == "&" -> { val r = op(7, minPrec, lhs, op) ?: break; lhs = r }
                    op == "==" || op == "!=" -> { val r = op(8, minPrec, lhs, op) ?: break; lhs = r }
                    op == "<" || op == ">" || op == "<=" || op == ">=" -> { val r = op(9, minPrec, lhs, op) ?: break; lhs = r }
                    op == "<<" || op == ">>" -> { val r = op(10, minPrec, lhs, op) ?: break; lhs = r }
                    op == "+" || op == "-" -> { val r = op(11, minPrec, lhs, op) ?: break; lhs = r }
                    op == "*" || op == "/" || op == "%" -> { val r = op(12, minPrec, lhs, op) ?: break; lhs = r }
                    else -> break
                }
            }
            return lhs
        }

        private fun op(prec: Int, minPrec: Int, lhs: ERes, opTok: String): ERes? {
            if (prec < minPrec) return null
            i++
            val rhs = parseExpr(prec + 1)
            val ty = when (opTok) {
                "==", "!=", "<", ">", "<=", ">=" -> Ty.BO1
                "<<", ">>" -> Ty(B.I, maxOf(lhs.ty.n, rhs.ty.n))
                "|", "&", "^" -> promoteInt(lhs.ty, rhs.ty)
                else -> promote(lhs.ty, rhs.ty)
            }
            // v0.7.19: mixed int/float arithmetic/comparison — ESSL 3.00 has
            // no implicit int→float (fxc does). Wrap the integer operand.
            if (opTok in ARITH_CMP_OPS) fixMixedIntFloat(lhs, rhs)
            return ERes(ty, lhs.startIdx, rhs.endIdx)
        }

        private val ARITH_CMP_OPS = setOf("+", "-", "*", "/", "%",
            "==", "!=", "<", ">", "<=", ">=")

        private fun promoteInt(a: Ty, b: Ty): Ty {
            if (a.b == B.UNK || b.b == B.UNK) return Ty.UNK
            val base = if (a.b == B.U || b.b == B.U) B.U else B.I
            return Ty(base, maxOf(a.n, b.n))
        }

        private fun wrapBoolIfNumeric(e: ERes) {
            if (!e.ty.isNumericScalar) return
            val first = tok(e.startIdx) ?: return
            val last = tok(e.endIdx) ?: return
            addEdit(first.start, "bool(")
            addEdit(last.end, ")", closing = true)
            note("logical→bool", first)
        }

        private fun parseUnary(): ERes {
            val t = peek() ?: return ERes(Ty.UNK, i, i - 1)
            if (t.kind == TKind.PUNCT) {
                when (t.s) {
                    "!" -> {
                        i++
                        val operandStart = i
                        val operand = parseUnary()
                        if (operand.ty.isNumericScalar) {
                            val first = tok(operandStart) ?: return operand
                            val last = tok(i - 1) ?: return operand
                            addEdit(first.start, "bool(")
                            addEdit(last.end, ")", closing = true)
                            note("not→bool", first)
                        }
                        return ERes(Ty.BO1, operandStart - 1, i - 1)
                    }
                    "-", "+", "~" -> {
                        i++
                        val operandStart = i
                        val operand = parseUnary()
                        return ERes(operand.ty, operandStart - 1, i - 1)
                    }
                    "++", "--" -> {
                        // v0.7.24 (H-S4 BUG FIX): the old span was
                        // `(i - 2, i - 1)` computed AFTER the operand — correct
                        // only for a single-token operand. For `++x.y` the
                        // start pointed at `y` instead of `++`, so outer wraps
                        // inserted parens in the middle of the expression.
                        // The operator index is captured BEFORE consuming it.
                        val opIdx = i
                        i++
                        val operand = parseUnary()
                        return ERes(operand.ty, opIdx, i - 1)
                    }
                }
            }
            return parsePostfix()
        }

        private fun parsePostfix(): ERes {
            var r = parsePrimary()
            while (true) {
                val t = peek() ?: break
                if (t.punct(".")) {
                    val nt = peek(1) ?: break
                    if (nt.kind == TKind.IDENT) {
                        if (nt.s == "length" && peek(2)?.punct("(") == true) {
                            i += 3
                            if (atP(")")) i++
                            r = ERes(Ty.I1, r.startIdx, i - 1)
                            continue
                        }
                        if (nt.s.isNotEmpty() && nt.s.all { it in "xyzwrgbastpq" }) {
                            i += 2
                            val cnt = minOf(4, nt.s.length)
                            val base = if (r.ty.b == B.UNK || r.ty.b == B.S || r.ty.b == B.SH ||
                                r.ty.b == B.M || r.ty.b == B.VO) B.UNK else r.ty.b
                            r = ERes(Ty(base, cnt), r.startIdx, i - 1)
                            continue
                        }
                        i += 2                                        // struct member / unknown
                        r = ERes(Ty.UNK, r.startIdx, i - 1)
                        continue
                    }
                    break
                } else if (t.punct("[")) {
                    i++
                    val idx = parseAssignExpr()
                    // v0.7.19: float array/matrix subscript (`arr[f]`) — fxc
                    // implicitly truncates float→int in subscripts; ESSL 3.00
                    // requires a scalar integer expression. (The engine team's
                    // own APK ships `g_BlendMap[float(x) / 4.0]` — a float
                    // subscript — in a dead branch of puppettexturechannels.)
                    if (idx.ty.b == B.F && idx.ty.n == 1) {
                        val first = tok(idx.startIdx)
                        val last = tok(idx.endIdx)
                        if (first != null && last != null) {
                            addEdit(first.start, "int(")
                            addEdit(last.end, ")", closing = true)
                            note("subscript→int", first)
                        }
                    }
                    if (atP("]")) i++
                    val inner = when {
                        r.ty.arr -> Ty(r.ty.b, r.ty.n, false)
                        r.ty.b == B.M -> Ty(B.F, r.ty.n)
                        else -> Ty(if (r.ty.b == B.UNK) B.UNK else r.ty.b, 1)
                    }
                    r = ERes(inner, r.startIdx, i - 1)
                    continue
                } else if (t.punct("++") || t.punct("--")) {
                    i++
                    continue
                } else break
            }
            return r
        }

        private fun parsePrimary(): ERes {
            val t = peek() ?: return ERes(Ty.UNK, i, i - 1)
            when (t.kind) {
                TKind.NUM -> {
                    i++
                    return ERes(numType(t.s), i - 1, i - 1)
                }
                TKind.IDENT -> {
                    if (peek(1)?.punct("(") == true) return parseCall()
                    i++
                    return ERes(lookup(t.s) ?: Ty.UNK, i - 1, i - 1)
                }
                TKind.PUNCT -> {
                    if (t.s == "(") {
                        val openIdx = i
                        i++
                        val inner = parseAssignExpr()
                        if (atP(")")) i++
                        return ERes(inner.ty, openIdx, i - 1)
                    }
                    i++                                              // stray punct: survive
                    return ERes(Ty.UNK, i - 1, i - 1)
                }
                else -> return ERes(Ty.UNK, i, i - 1)                // PREPROC boundary: stop
            }
        }

        private fun parseCall(): ERes {
            val callStart = i
            val nameTok = peek()!!
            val name = nameTok.s
            i += 2                                                    // NAME (
            val args = ArrayList<ERes>()
            if (!atP(")")) {
                while (true) {
                    args.add(parseAssignExpr())
                    if (atP(",")) { i++; continue }
                    break
                }
            }
            if (atP(")")) i++

            // scalar constructor with a single vector argument → (arg).x
            val sc = scalarCtor(name)
            if (sc != null && args.size == 1) {
                val a = args[0]
                if (a.ty.n in 2..4 && a.ty.b != B.UNK && a.ty.b != B.S && a.ty.b != B.SH && a.ty.b != B.M) {
                    val first = tok(a.startIdx)
                    val last = tok(a.endIdx)
                    if (first != null && last != null) {
                        addEdit(first.start, "(")
                        addEdit(last.end, ").x", closing = true)
                        note("scalar-ctor→.x", first)
                    }
                }
            }
            // NOTE: vector constructors from larger vectors are LEGAL ESSL (vec2(v4)), no rewrite.

            // ── v0.7.19: genType-only builtins called with a scalar where the
            // other argument is a vector (fxc/HLSL broadcasts the scalar;
            // ESSL 3.00 has no pow(vecN,float)/reflect(vecN,float)/atan(y,x)
            // mixed overloads). Wrap the scalar in the matching CASTN.
            val genPositions = SCALAR_VECTOR_BUILTINS[name]
            if (genPositions != null && args.size >= 2) {
                val n = args.map { it.ty.n }.filter { it in 2..4 }.maxOrNull()
                if (n != null) {
                    for ((ai, a) in args.withIndex()) {
                        if (ai !in genPositions) continue
                        if (a.ty.n == 1 && isFloatTy(a.ty)) {
                            val first = tok(a.startIdx)
                            val last = tok(a.endIdx)
                            if (first != null && last != null) {
                                addEdit(first.start, "CAST$n(")
                                addEdit(last.end, ")", closing = true)
                                note("scalar→vec$n broadcast ($name)", first)
                            }
                        }
                    }
                }
            }

            // ── v0.7.19: IMPLICIT ARGUMENT CONVERSIONS (fxc accepts any
            // numeric base/size mix in arguments; ESSL 3.00 accepts NONE —
            // glslang 16.6: `void f(int); f(1.0)` is an error, and so is
            // `void f(vec2); f(1.0)` and `void f(int); f(i + 1.0)`).
            // Applies to user functions declared in this file (the parser's
            // scope) and to the ENGINE's include-set functions
            // (HEADER_FUNCTIONS — common.h/common_blur.h/…, whose signatures
            // are fixed and version-stable). Every mismatch gets an explicit
            // constructor: truncation takes the leading components (fxc
            // X3206), scalar args broadcast (fxc scalar promotion), base
            // mixes convert (fxc implicit conversion).
            val paramTys = paramTypesFor(name, args.size)
            if (paramTys != null) {
                for ((ai, a) in args.withIndex()) {
                    if (ai >= paramTys.size) break
                    val pt = paramTys[ai]
                    if (pt == null) continue
                    fixImplicitArgConversion(pt, a, name)
                }
            }

            // ── v0.7.19 note: texSample2DCompare(A,B,C) → texture(A, vec3(B,C))
            // is rewritten by the balanced pre-pass (rewriteCompareSamplerCalls)
            // BEFORE lexing — the edit mechanism here is insert-only.

            val vc = vectorCtor(name)
            val ret: Ty = when {
                // v0.7.19: texture() on a SHADOW sampler returns the compared
                // float, not vec4 — typing it vec4 made the pass wrap return
                // values with a bogus `.x` (a scalar swizzle error in ESSL).
                // When the sampler's type is UNKNOWN (declared in another
                // translation unit — e.g. an engine include processed
                // standalone) the result is UNKNOWN too: assuming vec4 would
                // corrupt shadow paths, and no edit is always safe.
                (name == "texture" || name == "textureLod" || name == "texSample2DLod" ||
                    name == "texSample2D" || name == "texSample2DBackBuffer" ||
                    name == "texSample2DCompare") -> when (args.firstOrNull()?.ty?.b) {
                    B.SH -> Ty.F1
                    B.S -> Ty.F4
                    else -> Ty.UNK
                }
                vc != null -> vc
                sc != null -> Ty(sc, 1)
                name == "mat2" -> Ty(B.M, 2)
                name == "mat3" || name == "CAST3X3" -> Ty(B.M, 3)
                name == "mat4" -> Ty(B.M, 4)
                name == "mul" -> {
                    val x = args.getOrNull(0)?.ty ?: Ty.UNK
                    val y = args.getOrNull(1)?.ty ?: Ty.UNK
                    when {
                        x.b == B.M && y.b == B.M -> x
                        x.b == B.M -> y
                        y.b == B.M -> x
                        else -> promote(x, y)
                    }
                }
                else -> lookupCallable(name)
                    ?: BUILTIN_RET[name]
                    ?: if (name in FIRST_ARG_RET) args.firstOrNull()?.ty ?: Ty.UNK else Ty.UNK
            }
            return ERes(ret, callStart, i - 1)
        }

        /**
         * v0.7.19: the ONE implicit-conversion routine for call arguments —
         * replicates fxc's argument conversion table at source level:
         *
         *   arg N > param N   → leading components (fxc X3206 truncation)
         *   arg N < param N   → scalar broadcast (vecN(arg)) — fxc promotes
         *                       scalars to any vector size; ESSL needs the ctor
         *   base mismatch      → explicit ctor of the PARAMETER type (fxc
         *                       converts any numeric base silently; ESSL 3.00
         *                       has NO implicit conversions at all — glslang
         *                       16.6 rejects even `float f = intVar;`)
         */
        fun fixImplicitArgConversion(pt: Ty, a: ERes, fname: String) {
            if (!isNumericTy(pt) || !isNumericTy(a.ty)) return
            if (pt.b == a.ty.b && pt.n == a.ty.n) return
            val first = tok(a.startIdx) ?: return
            val last = tok(a.endIdx) ?: return
            if (a.ty.n > pt.n) {
                // truncation to the leading components
                val swz = "." + "xyzw".take(pt.n)
                if (pt.b == a.ty.b) {
                    addEdit(first.start, "(")
                    addEdit(last.end, ")$swz", closing = true)
                    note("arg-truncation→$swz ($fname)", first)
                } else {
                    val ctor = if (pt.n == 1) scalarCtorName(pt.b) else vectorCtorName(pt)
                    if (ctor == null) return
                    addEdit(first.start, "$ctor((")
                    addEdit(last.end, ")$swz)", closing = true)
                    note("arg-truncation→$ctor$swz ($fname)", first)
                }
            } else if (a.ty.n < pt.n) {
                if (a.ty.n != 1) return                  // vec→larger vec: fxc rejects too
                val ctor = vectorCtorName(pt) ?: return
                addEdit(first.start, "$ctor(")
                addEdit(last.end, ")", closing = true)
                note("arg-broadcast→$ctor ($fname)", first)
            } else {
                // same size, different base (incl. scalar/scalar)
                val ctor = if (pt.n == 1) scalarCtorName(pt.b) else vectorCtorName(pt)
                if (ctor == null) return
                addEdit(first.start, "$ctor(")
                addEdit(last.end, ")", closing = true)
                note("arg-base→$ctor ($fname)", first)
            }
        }

        private fun numType(s: String): Ty = when {
            s.startsWith("0x") || s.startsWith("0X") -> Ty.I1
            s.endsWith("u") || s.endsWith("U") -> Ty(B.U)
            s.endsWith("f") || s.endsWith("F") || s.contains('.') ||
                s.contains('e') || s.contains('E') -> Ty.F1
            else -> Ty.I1
        }

        /**
         * fxc implicit conversions that GLSL ES 3.00 rejects, replicated at
         * source level for initialization / assignment / return:
         *
         *  A. implicit truncation  (fxc X3206): vecN → smaller type keeps the
         *     leading components          →  (expr).xyz
         *  B. scalar broadcast (fxc scalar promotion): `vec2 x = 1.0;` —
         *     ESSL 3.00 requires an explicit vec2() ctor (glslang 16.6:
         *     "cannot convert from 'const float' to '2-component vector'").
         *     The engine team hit exactly this class: the APK's
         *     editorpaintbrush.frag carries `vec2 texelSize = 1.0;` in a
         *     never-compiled editor path.
         *  C. base conversion: fxc converts any numeric base implicitly
         *     (float→int, int→uint, anything→bool, …) while ESSL 3.00 has NO
         *     implicit conversions at all — glslang 16.6 rejects even
         *     `float f = intVar;` and `uint u = intVar;` (verified against
         *     the exact engine view). Every mismatch gets an explicit ctor.
         *     NOTE: the PC's own fixShader int→float pass (`int d = 3;` →
         *     `int d = 3.0;`) lands here too — fxc compiles that fine,
         *     strict ESSL does not.
         */
        fun wrapTruncation(lhs: Ty, rhs: ERes) {
            val rt = rhs.ty
            if (rt.b == B.UNK || rt.b == B.S || rt.b == B.SH || rt.b == B.M || rt.b == B.VO || rt.arr) return
            if (lhs.b == B.UNK || lhs.b == B.S || lhs.b == B.SH || lhs.b == B.M || lhs.b == B.VO || lhs.arr) return
            if (rt.n !in 1..4 || lhs.n !in 1..4) return
            if (rt.b != B.F && rt.b != B.I && rt.b != B.U && rt.b != B.BO) return
            if (lhs.b != B.F && lhs.b != B.I && lhs.b != B.U && lhs.b != B.BO) return
            val truncates = rt.n > lhs.n
            val broadcasts = rt.n == 1 && lhs.n > 1
            val convertsBad = lhs.b != rt.b
            if (!truncates && !broadcasts && !convertsBad) return
            val first = tok(rhs.startIdx) ?: return
            val last = tok(rhs.endIdx) ?: return

            if (truncates) {
                val swz = "." + "xyzw".take(lhs.n)
                if (lhs.b == rt.b) {
                    addEdit(first.start, "(")
                    addEdit(last.end, ")$swz", closing = true)
                } else {
                    val ctor = scalarCtorName(lhs.b) ?: return
                    addEdit(first.start, "$ctor((")
                    addEdit(last.end, ")$swz)", closing = true)
                }
                note("truncation→$swz", first)
            } else if (broadcasts) {
                // scalar → vector of any base: the ctor converts and
                // broadcasts in one step (vec2(1.0), vec3(intScalar), …)
                val ctor = vectorCtorName(lhs) ?: return
                addEdit(first.start, "$ctor(")
                addEdit(last.end, ")", closing = true)
                note("broadcast→$ctor", first)
            } else {
                // same size, incompatible base
                if (rt.n == lhs.n) {
                    val ctor = if (lhs.n == 1) scalarCtorName(lhs.b) else vectorCtorName(lhs)
                    if (ctor == null) return
                    addEdit(first.start, "$ctor(")
                    addEdit(last.end, ")", closing = true)
                    note("base→${ctor}", first)
                } else if (rt.n < lhs.n) {
                    // scalar broadcast into a vector of another base — both
                    // fixes at once via the vector ctor
                    val ctor = vectorCtorName(lhs) ?: return
                    addEdit(first.start, "$ctor(")
                    addEdit(last.end, ")", closing = true)
                    note("base→${ctor}", first)
                }
            }
        }

        private fun scalarCtorName(b: B): String? = when (b) {
            B.F -> "float"; B.I -> "int"; B.U -> "uint"; B.BO -> "bool"; else -> null
        }

        private fun vectorCtorName(t: Ty): String? = when (t.b) {
            B.F -> when (t.n) { 2 -> "vec2"; 3 -> "vec3"; 4 -> "vec4"; else -> null }
            B.I -> when (t.n) { 2 -> "ivec2"; 3 -> "ivec3"; 4 -> "ivec4"; else -> null }
            B.U -> when (t.n) { 2 -> "uvec2"; 3 -> "uvec3"; 4 -> "uvec4"; else -> null }
            B.BO -> when (t.n) { 2 -> "bvec2"; 3 -> "bvec3"; 4 -> "bvec4"; else -> null }
            else -> null
        }

        fun checkConditionBool(cond: ERes) {
            if (!cond.ty.isNumericScalar) return
            val first = tok(cond.startIdx) ?: return
            val last = tok(cond.endIdx) ?: return
            addEdit(first.start, "bool(")
            addEdit(last.end, ")", closing = true)
            note("condition→bool", first)
        }
    }

    // ═══════════════ program parser (statements + symbols) ═══════════════

    private val MODIFIERS = setOf("uniform", "varying", "attribute", "const", "static",
        "in", "out", "inout", "flat", "smooth", "noperspective", "highp", "mediump", "lowp")

    private class ProgramParser(
        src: String,
        toks: List<Tok>,
        override val code: List<Tok>,
        edits: MutableList<Edit>,
        notes: MutableList<String>,
    ) : ExprParser(src, toks, edits, notes) {

        val global = HashMap<String, Ty>()
        val scopes = ArrayList<HashMap<String, Ty>>()
        val functions = HashMap<String, FnSig>()
        val macroObj = HashMap<String, Ty>()
        val macroFn = HashMap<String, Ty>()
        val fnReturns = ArrayDeque<Ty>()
        var balanced = true

        override fun lookup(name: String): Ty? {
            for (s in scopes.asReversed()) if (s.containsKey(name)) return s[name]
            if (global.containsKey(name)) return global[name]
            if (macroObj.containsKey(name)) return macroObj[name]
            return null
        }

        override fun lookupCallable(name: String): Ty? {
            if (functions.containsKey(name)) return functions[name]!!.ret
            if (macroFn.containsKey(name)) return macroFn[name]
            if (macroObj.containsKey(name)) return macroObj[name]
            return null
        }

        override fun paramTypesFor(name: String, argc: Int): List<Ty>? {
            functions[name]?.let { sig ->
                if (sig.params.size == argc) return sig.params
            }
            return super.paramTypesFor(name, argc)
        }

        fun parseProgram() {
            registerCombosAndSystemDefines()
            while (!atEnd()) {
                val t = peek()!!
                when {
                    t.kind == TKind.PREPROC -> { handlePreproc(t); i++ }
                    t.punct(";") -> i++
                    t.punct("{") -> { enterScope(); i++ }
                    t.punct("}") -> { leaveScope(); i++ }
                    else -> parseStatement()
                }
            }
            if (scopes.isNotEmpty()) balanced = false
        }

        private fun enterScope() { scopes.add(HashMap()) }
        private fun leaveScope() {
            if (scopes.isEmpty()) { balanced = false; return }
            scopes.removeAt(scopes.size - 1)
        }

        private fun registerCombosAndSystemDefines() {
            // The engine injects `#define NAME <int>` for every [COMBO] at load
            // time (verified: `#define INVERT 0` in its own shader-compile log)
            // and integer system defines (PLATFORM_ANDROID 1, GLSL 1, ...).
            for (n in listOf("PLATFORM_ANDROID", "GLSL", "HLSL", "SCENE_ORTHO",
                "PLATFORM_DIRECTX", "PLATFORM_WINDOWS")) macroObj[n] = Ty.I1
            // v0.7.19: built-in vertex-shader scalars — gl_VertexID/gl_InstanceID
            // are int in ESSL 3.00 (HLSL declares them uint, and the engine's
            // own model_vertex_v1.h morphing functions take `in uint vertexID`
            // — the fxc-implicit int→uint at those call sites must become an
            // explicit ctor on the strict compiler)
            macroObj["gl_VertexID"] = Ty.I1
            macroObj["gl_InstanceID"] = Ty.I1
            // Combos are declared three ways in WE shaders, and the engine
            // turns ALL of them into integer defines (verified:
            // `#define INVERT 0` in its own shader-compile log):
            //   // [COMBO]     {"combo":"INVERT",...}     (standalone)
            //   // [COMBO_OFF] {"combo":"SPECULAR",...}   (default-off variant)
            //   uniform ...; // {"combo":"MASK",...}      (uniform-line metadata)
            val rx = Regex(""""combo"\s*:\s*"([A-Za-z_][A-Za-z0-9_]*)"""")
            for (t in toks) {
                if (t.kind != TKind.COMMENT) continue
                if (!t.s.contains("\"combo\"")) continue
                for (m in rx.findAll(t.s)) global[m.groupValues[1]] = Ty.I1
            }
        }

        private fun handlePreproc(t: Tok) {
            val body = t.s.trim()
            if (!body.startsWith("#define")) return
            val nameM = Regex("""^#define\s+([A-Za-z_][A-Za-z0-9_]*)""").find(body) ?: return
            val name = nameM.groupValues[1]
            // C preprocessor rule: a macro is FUNCTION-like only when the
            // '(' follows the name with NO whitespace. `#define FLAG (u_a)`
            // is an OBJECT-like macro whose body is `(u_a)`.
            val afterRaw = body.substring(nameM.range.last + 1)
            val afterName = afterRaw.trim()
            if (afterRaw.startsWith("(")) {
                // function-like macro: #define NAME(a,b) BODY
                val open = body.indexOf('(', nameM.range.last + 1)
                if (open < 0) return
                var depth = 0
                var close = -1
                for (j in open until body.length) {
                    if (body[j] == '(') depth++
                    else if (body[j] == ')') { depth--; if (depth == 0) { close = j; break } }
                }
                if (close < 0) return
                val macroBody = body.substring(close + 1).trim()
                if (macroBody.isEmpty() || macroBody.length > 400) return
                val bodyStartInSrc = t.start + t.s.indexOf(macroBody)
                val sub = MacroBodyParser(this, macroBody, edits, notes, bodyStartInSrc)
                try {
                    val ty = sub.parseAssignExpr()
                    if (ty.ty.b != B.UNK) macroFn[name] = ty.ty
                } catch (e: Exception) { /* best effort */ }
            } else if (afterName.isNotEmpty()) {
                val lit = afterName.substringBefore(' ').trim()
                val ty = when {
                    lit.startsWith("0x") || lit.startsWith("0X") -> Ty.I1
                    lit.matches(Regex("""-?\d+[uU]""")) -> Ty(B.U)
                    lit.matches(Regex("""-?\d+\.\d*[fF]?""")) ||
                        lit.matches(Regex("""-?\d+[fF]""")) -> Ty.F1
                    lit.matches(Regex("""-?\d+""")) -> Ty.I1
                    else -> null
                }
                if (ty != null) {
                    macroObj[name] = ty
                } else if (afterName.length <= 400) {
                    // non-literal object-like body: #define FLAG (u_a) —
                    // infer the body's type (and fix its constructs) so uses
                    // like `FLAG ? a : b` expand to ESSL-safe code.
                    val bodyStartInSrc = t.start + t.s.indexOf(afterName)
                    val sub = MacroBodyParser(this, afterName, edits, notes, bodyStartInSrc)
                    try {
                        val r = sub.parseAssignExpr()
                        if (r.ty.b != B.UNK) macroObj[name] = r.ty
                    } catch (e: Exception) { /* best effort */ }
                }
            }
        }

        // ── statements ──
        fun parseStatement() {
            while (peek()?.kind == TKind.IDENT &&
                (peek()!!.s in MODIFIERS || peek()!!.s == "layout" || peek()!!.s == "precision")) {
                when (peek()!!.s) {
                    "layout" -> skipParens()
                    "precision" -> { skipToSemicolon(); return }
                    else -> i++
                }
            }
            val t = peek() ?: return
            when {
                t.kind == TKind.PREPROC -> { handlePreproc(t); i++ }
                t.punct("{") -> { enterScope(); i++ }
                t.punct("}") -> { leaveScope(); i++ }
                t.punct(";") -> i++
                t.kind != TKind.IDENT -> skipToSemicolon()
                t.ident("struct") -> parseStruct()
                t.ident("if") -> parseIf()
                t.ident("while") -> parseWhile()
                t.ident("do") -> parseDoWhile()
                t.ident("for") -> parseFor()
                t.ident("return") -> parseReturn()
                t.ident("break") || t.ident("continue") || t.ident("discard") -> { i++; if (atP(";")) i++ }
                t.ident("else") -> i++
                else -> {
                    val ty = typeKeyword(t.s)
                    val nameTok = peek(1)
                    val after = peek(2)
                    if (ty != null && nameTok?.kind == TKind.IDENT && after != null &&
                        (after.punct("=") || after.punct(";") || after.punct(",") || after.punct("["))) {
                        parseDeclaration(ty)
                    } else if (ty != null && nameTok?.kind == TKind.IDENT && after?.punct("(") == true) {
                        parseFunction(ty, nameTok)
                    } else {
                        parseExpressionStatement()
                    }
                }
            }
        }

        private fun parseStruct() {
            i++
            var depth = 0
            while (!atEnd()) {
                val t = peek() ?: break
                i++
                if (t.punct("{")) depth++
                else if (t.punct("}")) { depth--; if (depth == 0) break }
            }
            skipToSemicolon()
        }

        private fun parseFunction(ret: Ty, nameTok: Tok) {
            i += 2
            val bodyScope = HashMap<String, Ty>()
            val paramList = ArrayList<Ty>()
            if (!atP("(")) { skipToSemicolon(); return }
            i++
            while (!atEnd() && !atP(")")) {
                while (peek()?.kind == TKind.IDENT && peek()!!.s in MODIFIERS) i++
                val pt = peek() ?: break
                val pty = typeKeyword(pt.s)
                if (pty != null) {
                    i++
                    if (peek()?.kind == TKind.IDENT) {
                        val pname = next()
                        var arr = false
                        while (!atEnd() && !atP(")") && !atP(",")) {
                            if (atP("[")) arr = true
                            i++
                        }
                        bodyScope[pname!!.s] = Ty(pty.b, pty.n, arr)
                        paramList.add(Ty(pty.b, pty.n, arr))
                    }
                } else if (!atP(",") && !atP(")")) {
                    i++
                }
                if (atP(",")) i++
            }
            if (atP(")")) i++
            functions[nameTok.s] = FnSig(ret, paramList)
            if (atP(";")) { i++; return }                              // prototype
            fnReturns.addLast(ret)
            scopes.add(bodyScope)
            if (atP("{")) {
                i++
                parseBlockInner()
                if (atP("}")) {
                    i++
                } else {
                    // v0.7.24 (H-S5 BUG FIX): parseBlockInner can also exit at
                    // end-of-input when the function body's closing `}` is
                    // missing. The old code popped the scope unconditionally,
                    // so parseProgram's `scopes.isNotEmpty()` balanced-check
                    // saw an EMPTY stack and treated the unbalanced source as
                    // balanced — token edits recorded for the broken body were
                    // then spliced in. Mark the program unbalanced instead.
                    balanced = false
                }
            } else {
                skipToSemicolon()
            }
            if (scopes.isNotEmpty()) scopes.removeAt(scopes.size - 1)
            if (fnReturns.isNotEmpty()) fnReturns.removeLast()
        }

        private fun next(): Tok? = peek()?.also { i++ }

        private fun parseBlockInner() {
            while (!atEnd() && !atP("}")) {
                val t = peek()!!
                when {
                    t.kind == TKind.PREPROC -> { handlePreproc(t); i++ }
                    t.punct(";") -> i++
                    else -> parseStatement()
                }
            }
        }

        private fun parseDeclaration(ty: Ty) {
            i++
            while (true) {
                val nameTok = peek()
                if (nameTok?.kind != TKind.IDENT) { skipToSemicolon(); return }
                i++
                var arr = false
                while (atP("[")) { arr = true; skipBracketGroup() }
                val varTy = Ty(ty.b, ty.n, arr)
                scopePutRedeclareSafe(nameTok.s, varTy)
                if (atP("=")) {
                    i++
                    val rhs = parseAssignExpr()
                    // LHS type for the truncation check: what THIS branch sees
                    val effTy = currentScopeType(nameTok.s) ?: varTy
                    wrapTruncation(effTy, rhs)
                }
                if (atP(",")) { i++; continue }
                if (atP(";")) { i++; return }
                skipToSemicolon()
                return
            }
        }

        /**
         * A redeclaration with a DIFFERENT type in the same scope means the
         * GLSL is branch-legal only via the preprocessor (#if/#else variants
         * of the same variable — e.g. generic.vert's vec4/vec2 v_TexCoord).
         * The engine's preprocessor sees one branch; we see both. Such names
         * are typed UNKNOWN so their assignments are never rewritten.
         */
        private fun scopePutRedeclareSafe(name: String, t: Ty) {
            val scope = if (scopes.isEmpty()) global else scopes.last()
            val prev = scope[name]
            if (prev != null && (prev.b != t.b || prev.n != t.n || prev.arr != t.arr)) {
                scope[name] = Ty.UNK
            } else {
                scope[name] = t
            }
        }

        private fun currentScopeType(name: String): Ty? {
            for (s in scopes.asReversed()) if (s.containsKey(name)) return s[name]
            return global[name]
        }

        private fun parseExpressionStatement() {
            parseAssignExpr()
            if (atP(";")) i++
            else if (!atEnd() && !atP("}") && !atP(")")) skipToSemicolon()
        }

        private fun parseIf() {
            i++
            if (!atP("(")) { skipToSemicolon(); return }
            i++
            val cond = parseAssignExpr()
            checkConditionBool(cond)
            if (atP(")")) i++
            parseBody()
            if (atId("else")) {
                i++
                if (atId("if")) parseIf() else parseBody()
            }
        }

        private fun parseBody() {
            if (atP("{")) {
                enterScope(); i++
                parseBlockInner()
                if (atP("}")) { i++; leaveScope() }
            } else parseStatement()
        }

        private fun parseWhile() {
            i++
            if (!atP("(")) { skipToSemicolon(); return }
            i++
            val cond = parseAssignExpr()
            checkConditionBool(cond)
            if (atP(")")) i++
            parseBody()
        }

        private fun parseDoWhile() {
            i++
            parseBody()
            if (atId("while") && atP("(", 1)) {
                i += 2
                val cond = parseAssignExpr()
                checkConditionBool(cond)
                if (atP(")")) i++
                if (atP(";")) i++
            }
        }

        private fun parseFor() {
            i++
            if (!atP("(")) { skipToSemicolon(); return }
            i++
            val depth0 = scopes.size
            scopes.add(HashMap())
            if (!atP(";")) parseStatement()                             // init
            if (atP(";")) i++
            if (!atP(";")) {
                val cond = parseAssignExpr()
                checkConditionBool(cond)
            }
            if (atP(";")) i++
            if (!atP(")")) parseAssignExpr()                           // increment
            if (atP(")")) i++
            parseBody()
            while (scopes.size > depth0) leaveScope()
        }

        private fun parseReturn() {
            i++
            val ret = fnReturns.lastOrNull() ?: Ty.VOID
            if (atP(";")) { i++; return }
            val rhs = parseAssignExpr()
            wrapTruncation(ret, rhs)
            if (atP(";")) i++
        }

        private fun skipToSemicolon() {
            var depth = 0
            while (!atEnd()) {
                val t = peek() ?: break
                i++
                if (t.kind == TKind.PREPROC) continue
                if (t.punct("(") || t.punct("[") || t.punct("{")) depth++
                else if (t.punct(")") || t.punct("]") || t.punct("}")) {
                    if (depth == 0) { i--; return }
                    depth--
                } else if (t.punct(";") && depth == 0) return
            }
        }

        private fun skipParens() {
            if (!atP("(")) return
            var depth = 0
            while (!atEnd()) {
                val t = peek() ?: break
                i++
                if (t.punct("(")) depth++
                else if (t.punct(")")) { depth--; if (depth == 0) return }
            }
        }

        private fun skipBracketGroup() {
            if (!atP("[")) return
            var depth = 0
            while (!atEnd()) {
                val t = peek() ?: break
                i++
                if (t.punct("[")) depth++
                else if (t.punct("]")) { depth--; if (depth == 0) return }
            }
        }
    }

    // ═════════════════ macro-body sub-parser (#define bodies) ═════════════

    private class MacroBodyParser(
        val host: ProgramParser,
        body: String,
        edits: MutableList<Edit>,
        notes: MutableList<String>,
        bodyStartInSrc: Int,
    ) : ExprParser("", emptyList(), edits, notes) {
        override val code: List<Tok> =
            lex(body)?.filter { it.kind != TKind.WS && it.kind != TKind.COMMENT } ?: emptyList()
        override var baseOffset = bodyStartInSrc

        override fun lookup(name: String): Ty? = host.lookup(name)
        override fun lookupCallable(name: String): Ty? = host.lookupCallable(name)
    }
}
