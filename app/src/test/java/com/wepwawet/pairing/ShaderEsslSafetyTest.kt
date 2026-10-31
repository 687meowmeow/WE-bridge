package com.wepwawet.pairing

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * v0.7.17: GLSL ES safety pass tests.
 *
 * The failing case is REAL: workshop 2798696916's sharpen_filter.frag, whose
 * compile errors the official Android engine logged verbatim:
 *
 *   ERROR: 0:56: '=' :  cannot convert from '4-component vector of float' to 'float'
 *   ERROR: 0:57: '' : boolean expression expected
 *
 * The PC ships those constructs unchanged (fxc tolerates them); the Android
 * engine's strict GLSL ES 3.00 compiler does not. [ShaderEsslSafety] makes
 * the fxc tolerance explicit so the same source compiles on both.
 */
class ShaderEsslSafetyTest {

    // ═══════════════════════════ ground truth ═══════════════════════════

    /**
     * The sharpen_filter.frag body EXACTLY as the PC pipeline emits it
     * (fixShader already applied — 0 → 0.0 etc.), reconstructed from the
     * engine's own numbered failure log.
     */
    private val sharpenPcOutput = """
// [COMBO] {"material":"Invert mask","combo":"INVERT","type":"options","default":0}
uniform sampler2D g_Texture0; // {"material":"framebuffer","label":"ui_editor_properties_framebuffer","hidden":true}
uniform sampler2D g_Texture1; // {"combo":"OPACITY","default":"util/white","label":"ui_editor_properties_opacity_mask","material":"mask","mode":"opacitymask","paintdefaultcolor":"1 1 1 1"}
uniform float u_strength; // {"material":"Strength","default":1,"range":[0,5]}
uniform float u_radius; // {"material":"Radius","default":1,"range":[0,5]}
uniform vec2 g_TexelSize;
varying vec4 v_TexCoord;
#define Src(a,b) texSample2D(g_Texture0, fragCoord + vec2(a,b) * g_TexelSize)
vec4 sharpen(vec2 fragCoord, float mask) {
        vec4 orig = Src(0.0, 0.0);
        vec4 blur = (orig)/16.0;
        return blur;
}
void main() {
        vec4 albedo = texSample2D(g_Texture0, v_TexCoord.xy);
        float mask = texSample2D(g_Texture1, v_TexCoord.xy);
        mask = INVERT ? 1.0 - mask : mask;
        if (mask > 0.1) albedo = sharpen(v_TexCoord.xy, mask);
        out_FragColor = albedo;
}
""".trimStart()

    @Test
    fun `sharpen_filter - the exact engine failure is fixed`() {
        val out = ShaderEsslSafety.apply(sharpenPcOutput)
        // fxc X3206: implicit truncation takes the leading component
        assertTrue(out.contains("float mask = (texSample2D(g_Texture1, v_TexCoord.xy)).x;"))
        // fxc: int ternary condition converts via != 0
        assertTrue(out.contains("mask = bool(INVERT) ? 1.0 - mask : mask;"))
        // everything else must be untouched
        assertTrue(out.contains("vec4 albedo = texSample2D(g_Texture0, v_TexCoord.xy);"))
        assertTrue(out.contains("if (mask > 0.1) albedo = sharpen(v_TexCoord.xy, mask);"))
        assertTrue(out.contains("#define Src(a,b) texSample2D(g_Texture0, fragCoord + vec2(a,b) * g_TexelSize)"))
    }

    @Test
    fun `sharpen_filter - full mobile chain from the raw workshop source`() {
        // The raw workshop source (pre-fixShader): ints without .0 — the
        // full chain must produce PC-exact fixShader output + the two
        // safety rewrites.
        val raw = sharpenPcOutput
            .replace("Src(0.0, 0.0)", "Src(0, 0)")
            .replace("/16.0;", "/16;")
        val out = PcShaderFixes.fixShaderForMobile("sharpen_filter", raw)
        assertTrue("PC int→float must still be applied",
            out.contains("vec4 blur = (orig)/16.0;"))
        assertTrue(out.contains("float mask = (texSample2D(g_Texture1, v_TexCoord.xy)).x;"))
        assertTrue(out.contains("mask = bool(INVERT) ? 1.0 - mask : mask;"))
    }

    // ═══════════════════════════ truncation ═══════════════════════════

    @Test
    fun `truncation keeps leading components`() {
        val src = """
uniform sampler2D g_Tex0;
uniform vec4 u_v4;
varying vec4 v_TexCoord;
void main() {
        float a = texSample2D(g_Tex0, v_TexCoord.xy);
        vec2 b = texSample2D(g_Tex0, v_TexCoord.xy);
        vec3 c = u_v4;
        vec2 d = u_v4;
        out_FragColor = vec4(a, b.x, c.x, d.x);
}
""".trimStart()
        val out = ShaderEsslSafety.apply(src)
        assertTrue(out.contains("float a = (texSample2D(g_Tex0, v_TexCoord.xy)).x;"))
        assertTrue(out.contains("vec2 b = (texSample2D(g_Tex0, v_TexCoord.xy)).xy;"))
        assertTrue(out.contains("vec3 c = (u_v4).xyz;"))
        assertTrue(out.contains("vec2 d = (u_v4).xy;"))
    }

    @Test
    fun `assignment and return truncation`() {
        val src = """
uniform sampler2D g_Tex0;
varying vec4 v_TexCoord;
float pick() { return texSample2D(g_Tex0, v_TexCoord.xy); }
void main() {
        float a = 0.0;
        a = texSample2D(g_Tex0, v_TexCoord.xy);
        out_FragColor = vec4(pick() + a, 0.0, 0.0, 1.0);
}
""".trimStart()
        val out = ShaderEsslSafety.apply(src)
        assertTrue("return must be swizzled",
            out.contains("return (texSample2D(g_Tex0, v_TexCoord.xy)).x;"))
        assertTrue("assignment must be swizzled",
            out.contains("a = (texSample2D(g_Tex0, v_TexCoord.xy)).x;"))
    }

    // ═══════════════════════════ conditions ═══════════════════════════

    @Test
    fun `non-bool conditions become bool`() {
        val src = """
uniform int u_flag;
uniform float u_speed;
void main() {
        float x = 0.0;
        if (u_flag) x += 1.0;
        while (u_speed) { x += 1.0; break; }
        for (int i = 0; i < 4; i++) { if (i) x += 1.0; }
        out_FragColor = vec4(x, 0.0, 0.0, 1.0);
}
""".trimStart()
        val out = ShaderEsslSafety.apply(src)
        assertTrue(out.contains("if (bool(u_flag))"))
        assertTrue(out.contains("while (bool(u_speed))"))
        assertTrue(out.contains("if (bool(i))"))
        // for-loop condition `i < 4` is already bool — untouched
        assertTrue(out.contains("for (int i = 0; i < 4; i++)"))
    }

    @Test
    fun `ternary with int condition and nested ternaries`() {
        val src = """
uniform int u_a;
uniform int u_b;
uniform vec4 u_v;
void main() {
        float r = u_a ? 1.0 : 0.0;
        float s = u_a ? 1.0 : u_b ? 2.0 : 3.0;
        vec2 w = u_a ? u_v : u_v.zwxy;
        out_FragColor = vec4(r, s, w.x, w.y);
}
""".trimStart()
        val out = ShaderEsslSafety.apply(src)
        assertTrue(out.contains("float r = bool(u_a) ? 1.0 : 0.0;"))
        assertTrue(out.contains("float s = bool(u_a) ? 1.0 : bool(u_b) ? 2.0 : 3.0;"))
        // nested wraps nest: truncation is the OUTER wrap
        assertEquals("vec2 w = (bool(u_a) ? u_v : u_v.zwxy).xy;",
            out.lineSequence().first { it.contains("vec2 w") }.trim())
    }

    @Test
    fun `combos from uniform-line metadata are ints`() {
        // shake.frag declares MASK on the uniform line, not as [COMBO]
        val src = """
uniform sampler2D g_Texture0;
uniform sampler2D g_Texture1; // {"combo":"MASK","default":"util/white","label":"mask"}
varying vec4 v_TexCoord;
void main() {
        float m = MASK ? 1.0 : 0.0;
        out_FragColor = vec4(m, 0.0, 0.0, 1.0);
}
""".trimStart()
        val out = ShaderEsslSafety.apply(src)
        assertTrue("uniform-line combo must be treated as int",
            out.contains("float m = bool(MASK) ? 1.0 : 0.0;"))
    }

    // ═════════════════════ constructors & conversions ═══════════════════

    @Test
    fun `scalar constructor from vector`() {
        val src = """
uniform sampler2D g_Tex0;
uniform vec4 u_v4;
varying vec4 v_TexCoord;
void main() {
        float a = float(u_v4);
        float b = CASTF(u_v4);
        int c = int(u_v4);
        float d = float(texSample2D(g_Tex0, v_TexCoord.xy));
        float e = float(u_v4.x);
        out_FragColor = vec4(a, b, float(c), d + e);
}
""".trimStart()
        val out = ShaderEsslSafety.apply(src)
        assertTrue(out.contains("float a = float((u_v4).x);"))
        assertTrue(out.contains("float b = CASTF((u_v4).x);"))
        assertTrue(out.contains("int c = int((u_v4).x);"))
        assertTrue(out.contains("float d = float((texSample2D(g_Tex0, v_TexCoord.xy)).x);"))
        assertTrue("scalar-from-scalar constructor must stay untouched",
            out.contains("float e = float(u_v4.x);"))
    }

    @Test
    fun `float literal into int declaration gets explicit ctor`() {
        // The PC's own fixShader int→float pass produces `int d = 3.0;`
        // (fxc accepts the implicit float→int; strict ESSL does not).
        val src = """
void main() {
        int d = 3.0;
        int e = 5;
        uint u = 7.0;
        out_FragColor = vec4(float(d), float(e), float(u), 1.0);
}
""".trimStart()
        val out = ShaderEsslSafety.apply(src)
        assertTrue("float→int needs int() — `int d = 3.0` is an ESSL error",
            out.contains("int d = int(3.0);"))
        assertTrue("int→int literals are legal ESSL — untouched",
            out.contains("int e = 5;"))
        assertTrue(out.contains("uint u = uint(7.0);"))
    }

    // ═══════════════════════════ deprModFix ═══════════════════════════

    @Test
    fun `deprModFix renames only the first user mod definition`() {
        val src = "float mod(float a, float b) { return a - b * floor(a / b); }\n" +
            "float mod2(float a, float b) { return a - b * floor(a / b); }\n"
        val out = PcShaderFixes.deprModFix(src)
        assertEquals("float _deprFn_0(float a, float b) { return a - b * floor(a / b); }\n" +
            "float mod2(float a, float b) { return a - b * floor(a / b); }\n", out)
    }

    @Test
    fun `deprModFix leaves shaders without mod alone`() {
        assertEquals("void main() {}\n", PcShaderFixes.deprModFix("void main() {}\n"))
    }

    // ═══════════════════════ no-op guarantees ═══════════════════════════

    @Test
    fun `clean shaders are byte-identical`() {
        val src = """
uniform sampler2D g_Texture0;
varying vec4 v_TexCoord;
void main() {
        vec4 albedo = texSample2D(g_Texture0, v_TexCoord.xy);
        float m = texSample2D(g_Texture0, v_TexCoord.zw).r;
        bool flag = m > 0.5;
        float x = flag ? 1.0 : 0.0;
        if (flag) { x += 1.0; }
        for (int i = 0; i < 4; i++) { x += 0.25; }
        out_FragColor = albedo * x;
}
""".trimStart()
        assertEquals(src, ShaderEsslSafety.apply(src))
    }

    @Test
    fun `CRLF line endings are preserved exactly`() {
        val src = "uniform int u_a;\r\nvoid main() {\r\n\tfloat x = u_a ? 1.0 : 0.0;\r\n" +
            "\tout_FragColor = vec4(x, 0.0, 0.0, 1.0);\r\n}\r\n"
        val out = ShaderEsslSafety.apply(src)
        assertTrue(out.contains("float x = bool(u_a) ? 1.0 : 0.0;\r\n"))
        assertEquals(src.count { it == '\r' }, out.count { it == '\r' })
    }

    @Test
    fun `macro bodies are fixed too`() {
        val src = """
uniform int u_a;
#define PICK(v) (v > 0.5 ? 1.0 : 0.0)
#define FLAG (u_a)
void main() {
        float p = PICK(0.7);
        float f = FLAG ? p : 0.0;
        out_FragColor = vec4(p, f, 0.0, 1.0);
}
""".trimStart()
        val out = ShaderEsslSafety.apply(src)
        // PICK's parameter is untyped at #define time and its condition is a
        // comparison (bool after expansion) — the body is already ESSL-safe
        assertTrue("comparison conditions are already bool — body untouched",
            out.contains("#define PICK(v) (v > 0.5 ? 1.0 : 0.0)"))
        // object-like macro with an int body → uses get bool()
        assertTrue("object-like macro body type must be inferred",
            out.contains("float f = bool(FLAG) ? p : 0.0;"))
    }

    @Test
    fun `redeclared preprocessor variants are left alone`() {
        // generic.vert pattern: #if branches redeclare v_TexCoord with
        // different types — the engine's preprocessor sees one branch only.
        val src = """
attribute vec4 a_TexCoordVec4;
attribute vec2 a_TexCoord;
#ifdef WIDE
varying vec4 v_TexCoord;
#else
varying vec2 v_TexCoord;
#endif
void main() {
        v_TexCoord = a_TexCoordVec4;
        v_TexCoord = a_TexCoord;
        gl_Position = vec4(0.0, 0.0, 0.0, 1.0);
}
""".trimStart()
        assertEquals("redeclaration across preprocessor branches must not be rewritten",
            src, ShaderEsslSafety.apply(src))
    }

    @Test
    fun `unbalanced braces abort with no edits`() {
        val src = "void main() {\n\tfloat x = 1.0;\n"
        assertEquals(src, ShaderEsslSafety.apply(src))
    }

    @Test
    fun `logical operators on numerics`() {
        val src = """
uniform int u_a;
uniform float u_b;
void main() {
        bool x = u_a && (u_b > 0.5);
        bool y = !u_a;
        out_FragColor = vec4(x, y, 0.0, 1.0);
}
""".trimStart()
        val out = ShaderEsslSafety.apply(src)
        assertTrue(out.contains("bool x = bool(u_a) && (u_b > 0.5);"))
        assertTrue(out.contains("bool y = !bool(u_a);"))
    }

    @Test
    fun `safety pass changes something on the failing case`() {
        val (out, report) = ShaderEsslSafety.applyWithReport(sharpenPcOutput)
        assertNotEquals(sharpenPcOutput, out)
        assertEquals(2, report.size)
    }

    // ═════════════════════ v0.7.19: audio_base ground truth ═════════════════════

    /**
     * The audio_base.frag constructs EXACTLY as the PC pipeline emits them
     * (reconstructed from the real engine failure log of workshop 3588616811,
     * 2026-10-31): int/float comparisons and int-var arithmetic against float
     * literals, with the results fed to an int-parameter helper — the float
     * RESULT must come back through an explicit int() (fxc's implicit
     * float→int argument conversion truncates toward zero).
     */
    private val audioBasePcOutput = """
uniform sampler2D g_Texture0; // {"material":"framebuffer","label":"ui_editor_properties_framebuffer","hidden":true}
uniform float g_AudioSpectrum64Left[64];
uniform float g_AudioSpectrum64Right[64];
varying vec2 v_TexCoord;
float getAudioValueByBand(int index) {
        if (index < 0.0) index = int(0.0);
        if (index >= 64.0) index = int(63.0);
        return (g_AudioSpectrum64Left[index] + g_AudioSpectrum64Right[index]) * 0.5;
}
float getInterpolatedAudio(float position) {
        int index = int(floor(position));
        float p0 = getAudioValueByBand(index - 1.0);
        float p1 = getAudioValueByBand(index);
        float p2 = getAudioValueByBand(index + 1.0);
        float p3 = getAudioValueByBand(index + 2.0);
        return p0 + p1 + p2 + p3;
}
void main() {
        float v = getInterpolatedAudio(v_TexCoord.x);
        out_FragColor = vec4(v, v, v, 1.0);
}
""".trimStart()

    @Test
    fun `audio_base - int float comparisons get the int side promoted`() {
        val out = ShaderEsslSafety.apply(audioBasePcOutput)
        // fxc: int promotes to float for the comparison (glslang 16.6 rejects
        // the mixed form — the exact errors in the engine log)
        assertTrue(out.contains("if (float(index) < 0.0)"))
        assertTrue(out.contains("if (float(index) >= 64.0)"))
    }

    @Test
    fun `audio_base - float argument into int parameter gets explicit int`() {
        val out = ShaderEsslSafety.apply(audioBasePcOutput)
        // fxc: index - 1.0 evaluates in float, then the call converts the
        // float back to int (truncation toward zero) — both steps explicit
        assertTrue(out.contains("getAudioValueByBand(int(float(index) - 1.0));"))
        assertTrue(out.contains("getAudioValueByBand(int(float(index) + 1.0));"))
        assertTrue(out.contains("getAudioValueByBand(int(float(index) + 2.0));"))
        // the pure-int call is untouched
        assertTrue(out.contains("getAudioValueByBand(index);"))
    }

    // ═════════════════════ v0.7.19: scalar broadcast ═════════════════════

    @Test
    fun `scalar broadcast in declaration gets vector ctor`() {
        val src = """
uniform float u_x;
void main() {
        vec2 texelSize = 1.0;
        vec3 scale = u_x;
        vec4 zero = 0.0;
        out_FragColor = zero + vec4(texelSize, 0.0) + vec4(scale, 1.0);
}
""".trimStart()
        val out = ShaderEsslSafety.apply(src)
        // the APK's own editorpaintbrush.frag ships `vec2 texelSize = 1.0;`
        // in an editor-only path — ESSL 3.00 requires the ctor (glslang 16.6:
        // "cannot convert from 'const float' to '2-component vector'")
        assertTrue(out.contains("vec2 texelSize = vec2(1.0);"))
        assertTrue(out.contains("vec3 scale = vec3(u_x);"))
        assertTrue(out.contains("vec4 zero = vec4(0.0);"))
    }

    @Test
    fun `scalar broadcast in plain assignment gets vector ctor`() {
        val src = """
uniform float u_x;
void main() {
        vec2 t;
        t = 1.0;
        out_FragColor = vec4(t, 0.0);
}
""".trimStart()
        val out = ShaderEsslSafety.apply(src)
        assertTrue(out.contains("t = vec2(1.0);"))
    }

    // ═════════════════════ v0.7.19: compound assignment ═════════════════════

    @Test
    fun `compound assignment with int operand gets ctor`() {
        val src = """
uniform int u_i;
void main() {
        vec2 v = vec2(1.0);
        v *= u_i;
        float f = 1.0;
        f += u_i;
        out_FragColor = vec4(v, f, 1.0);
}
""".trimStart()
        val out = ShaderEsslSafety.apply(src)
        assertTrue(out.contains("v *= float(u_i);"))
        assertTrue(out.contains("f += float(u_i);"))
    }

    // ═════════════════════ v0.7.19: array subscript ═════════════════════

    @Test
    fun `float array subscript gets explicit int`() {
        val src = """
uniform float u_arr[4];
uniform float u_f;
varying vec4 v_TexCoord;
void main() {
        float x = u_arr[u_f];
        out_FragColor = vec4(x);
}
""".trimStart()
        val out = ShaderEsslSafety.apply(src)
        // the engine team's own APK ships a float subscript in a dead branch
        // of puppettexturechannels.vert — ESSL needs the scalar int expr
        assertTrue(out.contains("u_arr[int(u_f)]"))
    }

    // ═════════════════════ v0.7.19: ternary arms ═════════════════════

    @Test
    fun `mixed ternary arms get the int side promoted`() {
        val src = """
uniform int u_a;
uniform float u_b;
void main() {
        float x = u_b > 0.5 ? 1 : 0.5;
        float y = u_b > 0.5 ? u_a : 0.5;
        out_FragColor = vec4(x, y, 0.0, 1.0);
}
""".trimStart()
        val out = ShaderEsslSafety.apply(src)
        assertTrue(out.contains("? 1.0 : 0.5;") || out.contains("? float(1) : 0.5;") ||
            out.contains("u_b > 0.5 ? 1 : 0.5".replace("1 :", "float(1) :")))
        assertTrue(out.contains("u_b > 0.5 ? float(u_a) : 0.5;"))
    }

    // ═════════════════════ v0.7.19: argument conversions ═════════════════════

    @Test
    fun `scalar argument into vector parameter gets broadcast ctor`() {
        val src = """
uniform float u_x;
void helper(vec2 dir, vec3 amt) { }
void main() {
        helper(u_x, 2.0);
        out_FragColor = vec4(0.0);
}
""".trimStart()
        val out = ShaderEsslSafety.apply(src)
        // fxc broadcasts scalars to any vector parameter; ESSL 3.00 needs the
        // ctor (the APK's combine_hdr.frag uses the explicit CAST3(2.4) form)
        assertTrue(out.contains("helper(vec2(u_x), vec3(2.0));"))
    }

    @Test
    fun `vector argument into smaller parameter keeps leading components`() {
        val src = """
uniform vec4 u_v;
void helper(vec2 dir) { }
void main() {
        helper(u_v);
        out_FragColor = vec4(0.0);
}
""".trimStart()
        val out = ShaderEsslSafety.apply(src)
        assertTrue(out.contains("helper((u_v).xy);"))
    }

    @Test
    fun `gl VertexID into uint parameter gets explicit cast`() {
        val src = """
uniform sampler2D g_Morph;
uniform vec4 g_MorphTexel;
uniform uint g_MorphOffsets[12];
uniform float g_MorphWeights[12];
void ApplyMorph(in uint vertexID, sampler2D morphTexture, in vec4 morphTextureTexel,
        in uint morphOffsets[12], in float morphWeights[12], inout vec3 localPos) { }
void main() {
        vec3 pos = vec3(0.0);
        ApplyMorph(gl_VertexID, g_Morph, g_MorphTexel, g_MorphOffsets, g_MorphWeights, pos);
        out_FragColor = vec4(pos, 1.0);
}
""".trimStart()
        val out = ShaderEsslSafety.apply(src)
        // the engine's own model_vertex_v1.h morphing functions take
        // `in uint vertexID` while gl_VertexID is int in ESSL 3.00
        assertTrue(out.contains("ApplyMorph(uint(gl_VertexID),"))
    }

    @Test
    fun `base conversion in assignments - no implicit conversions in ESSL`() {
        val src = """
uniform int u_i;
void main() {
        uint u = u_i;
        float f = u_i;
        int back = f;
        out_FragColor = vec4(u, f, back, 1.0);
}
""".trimStart()
        val out = ShaderEsslSafety.apply(src)
        // glslang 16.6 rejects even `float f = intVar;` — ESSL 3.00 has NO
        // implicit conversions at all
        assertTrue(out.contains("uint u = uint(u_i);"))
        assertTrue(out.contains("float f = float(u_i);"))
        assertTrue(out.contains("int back = int(f);"))
    }

    // ═════════════════════ v0.7.19: shadow samplers ═════════════════════

    @Test
    fun `texSample2DCompare becomes texture vec3 with trailing swizzle consumed`() {
        val src = """
uniform sampler2DComparison g_ShadowMap;
varying vec4 v_TexCoord;
void main() {
        float lit = texSample2DCompare(g_ShadowMap, v_TexCoord.xy, v_TexCoord.z).r;
        out_FragColor = vec4(lit, lit, lit, 1.0);
}
""".trimStart()
        val out = ShaderEsslSafety.apply(src)
        // texture() on a shadow sampler already returns the compared float —
        // the universal `.r` idiom must be consumed (a scalar swizzle is
        // "'scalar swizzle' : not supported with this profile: es")
        assertTrue(out.contains("float lit = texture("))
        assertTrue(!out.contains(").r;") && !out.contains(").x;"))
        // the comparison type becomes a precision-qualified shadow sampler
        assertTrue(out.contains("mediump sampler2DShadow g_ShadowMap;"))
    }

    @Test
    fun `shadow sampler texture result is not wrapped with a bogus swizzle`() {
        val src = """
uniform sampler2DShadow g_ShadowMap;
varying vec4 v_TexCoord;
float shade() {
        return texture(g_ShadowMap, vec3(v_TexCoord.xy, v_TexCoord.z));
}
void main() {
        out_FragColor = vec4(shade());
}
""".trimStart()
        val out = ShaderEsslSafety.apply(src)
        // the return is a float-typed call into a float function — NO `.x`
        // wrap may appear (the pass once typed shadow texture() as vec4)
        assertTrue(out.contains("return texture(g_ShadowMap, vec3(v_TexCoord.xy, v_TexCoord.z));"))
    }

    // ═════════════════════ v0.7.19: xray patch idempotence ═════════════════════

    @Test
    fun `xray patch is idempotent on pre-patched assets`() {
        // The CURRENT built-in xray vert ships pre-patched: the varying is
        // already followed by the patch's uniform
        val prePatchedVert = """
uniform mat4 g_ModelViewProjectionMatrix;
attribute vec3 a_Position;
varying float v_PointerScale;
uniform vec4 g_PointerState;
void main() {
        gl_Position = mul(vec4(a_Position, 1.0), g_ModelViewProjectionMatrix);
        v_PointerScale = 1.0;
}
""".trimStart()
        val out = PcShaderFixes.fixShader("xray", prePatchedVert)
        // re-applying the patch must NOT duplicate the uniform (an ESSL
        // redefinition error)
        assertEquals(1, Regex("uniform vec4 g_PointerState;").findAll(out).count())
    }

    @Test
    fun `duplicate identical uniform declarations are deduplicated`() {
        val src = """
uniform sampler2D g_Texture0;
varying float v_PointerScale;
uniform vec4 g_PointerState;
uniform vec4 g_PointerState;
void main() {
        out_FragColor = vec4(g_PointerState.y);
}
""".trimStart()
        val out = ShaderEsslSafety.apply(src)
        assertEquals(1, Regex("uniform vec4 g_PointerState;").findAll(out).count())
    }

    @Test
    fun `referenced but undeclared g_PointerState gets declared`() {
        val src = """
varying vec4 v_TexCoord;
void main() {
        vec2 unprojectedUVs = v_TexCoord.xy * CAST2(1.0 / max(0.00001, g_PointerState.y));
        out_FragColor = vec4(unprojectedUVs, 0.0, 1.0);
}
""".trimStart()
        val out = ShaderEsslSafety.apply(src)
        // the preview-variant xray frag references the engine-bound uniform
        // without a declaration — the pass declares it (the engine binds by
        // name, exactly as for the declaration the patch itself inserts)
        assertTrue(out.startsWith("uniform vec4 g_PointerState;"))
    }
}
