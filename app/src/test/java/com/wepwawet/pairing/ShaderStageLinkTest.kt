package com.wepwawet.pairing

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * v0.7.22: cross-stage varying harmonization tests.
 *
 * The failing case is REAL and user-observed (applog 2026-11-01, the official
 * Android app rendering a glitter-effect wallpaper):
 *
 *   Failed linking program effects/glitter_prepare (Error: output v_TexCoord
 *   type mismatch with other stage. Error: Input Output Mismatch.)
 *
 * The shipped asset declares `varying vec2 v_TexCoord` in the vertex shader
 * and `varying vec4 v_TexCoord` in the fragment shader — D3D links by
 * register so the PC never notices, the phone's strict ESSL 3.00 linker
 * refuses the program. [ShaderStageLink] unifies the pair (and every other
 * mismatched pair in any wallpaper) with fxc-faithful semantics.
 */
class ShaderStageLinkTest {

    // ═══════════════════════ ground truth (shipped assets) ═══════════════

    /** effects/glitter/shaders/effects/glitter_prepare.vert — verbatim. */
    private val glitterVert = """
attribute vec3 a_Position;
attribute vec2 a_TexCoord;

varying vec2 v_TexCoord;
varying vec2 v_NoiseCoord;

void main() {
        gl_Position = vec4(a_Position, 1.0);
        v_TexCoord.xy = a_TexCoord.xy;

        v_NoiseCoord = vec2(a_TexCoord.x, a_TexCoord.y) * 5;
}
""".trimStart()

    /** effects/glitter/shaders/effects/glitter_prepare.frag — verbatim. */
    private val glitterFrag = """

uniform sampler2D g_Texture0; // {"material":"framebuffer","label":"ui_editor_properties_framebuffer","hidden":true}
uniform sampler2D g_Texture1; // {"label":"ui_editor_properties_noise","default":"util/perlin_256"}

uniform float g_Time;

uniform float g_Speed; // {"material":"speed","label":"ui_editor_properties_speed","default":1,"range":[0.0, 2]}
uniform float g_Density; // {"material":"density","label":"ui_editor_properties_density","default":0.5,"range":[0.01, 1]}

varying vec4 v_TexCoord;
varying vec2 v_NoiseCoord;

void main() {
        vec4 albedo = CAST4(1);

        vec3 effectAlbedo = albedo.rgb;
        float density = g_Density * g_Density;
        float time = g_Time * g_Speed * density;

        vec2 noiseCoord = v_NoiseCoord;
        vec4 noise0 = texSample2D(g_Texture1, noiseCoord);
        noise0.r = noise0.r * (1.0 - noise0.g);
        float timer0 = frac(noise0.r * 100 + time);

        float glitterDensity = density * 0.5;
        float glitter0 = smoothstep(0.5 - glitterDensity, 0.5, timer0) * smoothstep(0.5 + glitterDensity, 0.5, timer0);

        glitter0 = smoothstep(0.5, 1.0, glitter0);
        glitter0 *= glitter0;
        effectAlbedo = CAST3(glitter0);

        albedo.rgb = effectAlbedo;

        gl_FragColor = albedo;
}
""".trimStart()

    @Test
    fun `glitter - the exact engine link failure is fixed`() {
        val entries = mutableListOf(
            "shaders/effects/glitter_prepare.vert" to glitterVert.toByteArray(),
            "shaders/effects/glitter_prepare.frag" to glitterFrag.toByteArray(),
        )
        val report = ShaderStageLink.harmonize(entries)

        // exactly one rewrite: the fragment declaration vec4 -> vec2
        assertEquals(listOf(
            "shaders/effects/glitter_prepare: v_TexCoord: fragment vec4→vec2 " +
                "(fragment needs 0 ≤ vertex size 2; D3D passes the vertex register unchanged)"
        ), report)

        val newFrag = String(entries[1].second, Charsets.UTF_8)
        assertTrue("fragment declaration retyped to the vertex type",
            newFrag.contains("varying vec2 v_TexCoord;"))
        assertTrue("v_NoiseCoord untouched",
            newFrag.contains("varying vec2 v_NoiseCoord;"))
        // the vertex shader is the authority — byte-identical
        assertEquals(glitterVert, String(entries[0].second, Charsets.UTF_8))
        // and every other byte of the fragment is untouched
        assertEquals(glitterFrag.replace("varying vec4 v_TexCoord;", "varying vec2 v_TexCoord;"), newFrag)
    }

    @Test
    fun `glitter - harmonized pair links under ESSL 3 00`() {
        val entries = mutableListOf(
            "shaders/effects/glitter_prepare.vert" to glitterVert.toByteArray(),
            "shaders/effects/glitter_prepare.frag" to glitterFrag.toByteArray(),
        )
        ShaderStageLink.harmonize(entries)
        // the engine maps `varying` -> out (vertex) / in (fragment); the link
        // rule needs the DECLARED TYPES to be identical on both sides
        val vertType = Regex("varying\\s+(\\w+)\\s+v_TexCoord")
            .find(String(entries[0].second, Charsets.UTF_8))!!.groupValues[1]
        val fragType = Regex("varying\\s+(?:lowp\\s+|mediump\\s+|highp\\s+)?(\\w+)\\s+v_TexCoord")
            .find(String(entries[1].second, Charsets.UTF_8))!!.groupValues[1]
        assertEquals("both stages must declare the same v_TexCoord type", vertType, fragType)
    }

    // ═══════════════════════ cursorripple preview ═══════════════════════

    @Test
    fun `cursorripple preview - frag uses only xy so the decl is retyped`() {
        val vert = """
attribute vec3 a_Position;
attribute vec2 a_TexCoord;
varying vec2 v_TexCoord;
void main() {
        gl_Position = vec4(a_Position, 1.0);
        v_TexCoord.xy = a_TexCoord.xy;
}
""".trimStart()
        val frag = """
uniform sampler2D g_Texture0;
varying vec4 v_TexCoord;
void main() {
        vec2 texSource = v_TexCoord.xy;
        gl_FragColor = texSample2D(g_Texture0, texSource);
}
""".trimStart()
        val entries = mutableListOf(
            "shaders/effects/cursorripple_apply_force.vert" to vert.toByteArray(),
            "shaders/effects/cursorripple_apply_force.frag" to frag.toByteArray(),
        )
        val report = ShaderStageLink.harmonize(entries)
        assertTrue(report.size == 1 && report[0].contains("fragment vec4→vec2"))
        val newFrag = String(entries[1].second, Charsets.UTF_8)
        assertTrue(newFrag.contains("varying vec2 v_TexCoord;"))
        // .xy stays valid on vec2 — untouched
        assertTrue(newFrag.contains("vec2 texSource = v_TexCoord.xy;"))
    }

    // ═══════════════════════ consistent pairs are untouched ═══════════════

    @Test
    fun `consistent pair - zero edits, byte identical`() {
        val vert = """
attribute vec3 a_Position;
attribute vec2 a_TexCoord;
varying vec2 v_TexCoord;
varying vec2 v_Scroll;
void main() {
        gl_Position = vec4(a_Position, 1.0);
        v_TexCoord.xy = a_TexCoord.xy;
        v_Scroll = vec2(0.0);
}
""".trimStart()
        val frag = """
uniform sampler2D g_Texture0;
varying vec2 v_TexCoord;
varying vec2 v_Scroll;
void main() {
        gl_FragColor = texSample2D(g_Texture0, v_TexCoord + v_Scroll);
}
""".trimStart()
        val entries = mutableListOf(
            "shaders/effects/scroll.vert" to vert.toByteArray(),
            "shaders/effects/scroll.frag" to frag.toByteArray(),
        )
        assertEquals(emptyList<String>(), ShaderStageLink.harmonize(entries))
        assertEquals(vert, String(entries[0].second, Charsets.UTF_8))
        assertEquals(frag, String(entries[1].second, Charsets.UTF_8))
    }

    @Test
    fun `frag-only varying - never a link error, never touched`() {
        // waterflow-style: the fragment declares v_Scroll the vertex doesn't
        // have — GLSL ES 3.00 defines it as undefined value, NOT a link error
        // (verified with glslangValidator -l) and the PC behaves the same.
        val vert = """
attribute vec3 a_Position;
varying vec2 v_TexCoord;
void main() {
        gl_Position = vec4(a_Position, 1.0);
        v_TexCoord.xy = vec2(0.0);
}
""".trimStart()
        val frag = """
uniform sampler2D g_Texture0;
varying vec2 v_TexCoord;
varying vec2 v_Scroll;
void main() {
        gl_FragColor = texSample2D(g_Texture0, v_TexCoord + v_Scroll);
}
""".trimStart()
        val entries = mutableListOf(
            "shaders/effects/waterflow.vert" to vert.toByteArray(),
            "shaders/effects/waterflow.frag" to frag.toByteArray(),
        )
        assertEquals(emptyList<String>(), ShaderStageLink.harmonize(entries))
        assertEquals(frag, String(entries[1].second, Charsets.UTF_8))
    }

    // ═══════════════════════ Path B — vertex upsize ═══════════════════════

    @Test
    fun `path B - vertex upsize with write wrap and read swizzle`() {
        val vert = """
attribute vec3 a_Position;
attribute vec2 a_TexCoord;
varying vec2 v_TexCoord;
void main() {
        gl_Position = vec4(a_Position, 1.0);
        v_TexCoord = a_TexCoord * 2.0;
        vec2 t = v_TexCoord;
        t.x += 1.0;
        v_TexCoord.xy = t;
}
""".trimStart()
        val frag = """
uniform sampler2D g_Texture0;
varying vec4 v_TexCoord;
void main() {
        vec2 uv = v_TexCoord.xy;
        float m = v_TexCoord.z;
        gl_FragColor = texSample2D(g_Texture0, uv + vec2(m, 0.0));
}
""".trimStart()
        val entries = mutableListOf(
            "shaders/effects/synth.vert" to vert.toByteArray(),
            "shaders/effects/synth.frag" to frag.toByteArray(),
        )
        val report = ShaderStageLink.harmonize(entries)
        assertTrue(report.size == 1 && report[0].contains("vertex vec2→vec4"))
        val newVert = String(entries[0].second, Charsets.UTF_8)
        assertTrue(newVert.contains("varying vec4 v_TexCoord;"))
        // whole-variable write zero-extended
        assertTrue(newVert.contains("v_TexCoord = vec4(a_TexCoord * 2.0, 0.0, 0.0);"))
        // whole-variable read keeps its vec2 type
        assertTrue(newVert.contains("vec2 t = v_TexCoord.xy;"))
        // partial write untouched
        assertTrue(newVert.contains("v_TexCoord.xy = t;"))
        // fragment untouched
        assertEquals(frag, String(entries[1].second, Charsets.UTF_8))
    }

    // ═══════════════════════ guards ═════════════════════════════════════

    @Test
    fun `shadowed name - skipped`() {
        val vert = """
attribute vec3 a_Position;
varying vec2 v_TexCoord;
void main() {
        gl_Position = vec4(a_Position, 1.0);
        v_TexCoord.xy = vec2(1.0);
}
""".trimStart()
        val frag = """
varying vec4 v_TexCoord;
void main() {
        vec4 v_TexCoord = vec4(1.0);
        gl_FragColor = v_TexCoord;
}
""".trimStart()
        val entries = mutableListOf(
            "shaders/effects/shadow.vert" to vert.toByteArray(),
            "shaders/effects/shadow.frag" to frag.toByteArray(),
        )
        assertEquals(emptyList<String>(), ShaderStageLink.harmonize(entries))
        assertEquals(vert, String(entries[0].second, Charsets.UTF_8))
        assertEquals(frag, String(entries[1].second, Charsets.UTF_8))
    }

    @Test
    fun `preprocessor-referenced name - skipped`() {
        val vert = """
attribute vec3 a_Position;
varying vec2 v_TexCoord;
void main() {
        gl_Position = vec4(a_Position, 1.0);
        v_TexCoord.xy = vec2(1.0);
}
""".trimStart()
        val frag = """
#define GET_UV v_TexCoord.zw
varying vec4 v_TexCoord;
void main() {
        gl_FragColor = vec4(GET_UV, 0.0, 1.0);
}
""".trimStart()
        val entries = mutableListOf(
            "shaders/effects/macro.vert" to vert.toByteArray(),
            "shaders/effects/macro.frag" to frag.toByteArray(),
        )
        // the fragment reads .zw through the macro — analysis cannot see it,
        // so the name must not be rewritten at all
        assertEquals(emptyList<String>(), ShaderStageLink.harmonize(entries))
        assertEquals(frag, String(entries[1].second, Charsets.UTF_8))
    }

    @Test
    fun `compound assignment on upsize path - skipped`() {
        val vert = """
attribute vec3 a_Position;
varying vec2 v_TexCoord;
void main() {
        gl_Position = vec4(a_Position, 1.0);
        v_TexCoord.xy = vec2(1.0);
        v_TexCoord *= 2.0;
}
""".trimStart()
        val frag = """
uniform sampler2D g_Texture0;
varying vec4 v_TexCoord;
void main() {
        float m = v_TexCoord.z;
        gl_FragColor = vec4(m, 0.0, 0.0, 1.0);
}
""".trimStart()
        val entries = mutableListOf(
            "shaders/effects/compound.vert" to vert.toByteArray(),
            "shaders/effects/compound.frag" to frag.toByteArray(),
        )
        val report = ShaderStageLink.harmonize(entries)
        // skipped names report a reason but make no edits
        assertTrue(report.all { it.contains("SKIPPED") })
        assertEquals(vert, String(entries[0].second, Charsets.UTF_8))
        assertEquals(frag, String(entries[1].second, Charsets.UTF_8))
    }

    @Test
    fun `matrix and array varyings - skipped`() {
        val vert = """
attribute vec3 a_Position;
varying mat3 v_XForm;
varying vec2 v_Arr[4];
varying vec2 v_Ok;
void main() {
        gl_Position = vec4(a_Position, 1.0);
        v_Ok.xy = vec2(0.0);
}
""".trimStart()
        val frag = """
varying mat4 v_XForm;
varying vec4 v_Arr[4];
varying vec2 v_Ok;
void main() {
        gl_FragColor = vec4(v_Ok, 0.0, 1.0);
}
""".trimStart()
        val entries = mutableListOf(
            "shaders/effects/mats.vert" to vert.toByteArray(),
            "shaders/effects/mats.frag" to frag.toByteArray(),
        )
        // mat3 vs mat4 and vec2[] vs vec4[] are not float-vector scalar cases
        // — untouched; v_Ok matches already
        assertEquals(emptyList<String>(), ShaderStageLink.harmonize(entries))
    }

    @Test
    fun `idempotent - second run makes no edits`() {
        val entries = mutableListOf(
            "shaders/effects/glitter_prepare.vert" to glitterVert.toByteArray(),
            "shaders/effects/glitter_prepare.frag" to glitterFrag.toByteArray(),
        )
        ShaderStageLink.harmonize(entries)
        val afterFirst = entries.map { it.first to it.second.toList() }
        val second = ShaderStageLink.harmonize(entries)
        assertEquals(emptyList<String>(), second)
        assertEquals(afterFirst, entries.map { it.first to it.second.toList() })
    }

    @Test
    fun `non-pair files are ignored`() {
        val entries = mutableListOf(
            "scene.json" to "{}".toByteArray(),
            "shaders/effects/lonely.frag" to glitterFrag.toByteArray(),  // no .vert
            "materials/effects/glitter_prepare.json" to "{}".toByteArray(),
        )
        assertEquals(emptyList<String>(), ShaderStageLink.harmonize(entries))
    }

    @Test
    fun `precision qualifier preserved when retyping`() {
        val vert = """
attribute vec3 a_Position;
varying vec2 v_TexCoord;
void main() {
        gl_Position = vec4(a_Position, 1.0);
        v_TexCoord.xy = vec2(1.0);
}
""".trimStart()
        val frag = """
varying mediump vec4 v_TexCoord;
void main() {
        gl_FragColor = vec4(1.0);
}
""".trimStart()
        val entries = mutableListOf(
            "shaders/effects/prec.vert" to vert.toByteArray(),
            "shaders/effects/prec.frag" to frag.toByteArray(),
        )
        ShaderStageLink.harmonize(entries)
        val newFrag = String(entries[1].second, Charsets.UTF_8)
        assertTrue(newFrag.contains("varying mediump vec2 v_TexCoord;"))
    }

    @Test
    fun `direct in and out declarations are harmonized too`() {
        // ESSL-style workshop shader: vert uses `out`, frag uses `in`
        // (instead of WE's `varying`) — same interface, same fix.
        val vert = """
attribute vec3 a_Position;
attribute vec2 a_TexCoord;
out vec2 v_TexCoord;
void main() {
        gl_Position = vec4(a_Position, 1.0);
        v_TexCoord.xy = a_TexCoord.xy;
}
""".trimStart()
        val frag = """
uniform sampler2D g_Texture0;
in vec4 v_TexCoord;
void main() {
        gl_FragColor = texSample2D(g_Texture0, v_TexCoord.xy);
}
""".trimStart()
        val entries = mutableListOf(
            "shaders/effects/directio.vert" to vert.toByteArray(),
            "shaders/effects/directio.frag" to frag.toByteArray(),
        )
        val report = ShaderStageLink.harmonize(entries)
        assertTrue(report.size == 1 && report[0].contains("fragment vec4→vec2"))
        val newFrag = String(entries[1].second, Charsets.UTF_8)
        assertTrue(newFrag.contains("in vec2 v_TexCoord;"))
        // attribute-style `in` in the vertex shader must NEVER be treated as
        // an interface input, and `out` in the fragment shader (the render
        // target) must never be treated as an interface output
        val newVert = String(entries[0].second, Charsets.UTF_8)
        assertTrue(newVert.contains("out vec2 v_TexCoord;"))
        assertTrue(newVert.contains("attribute vec3 a_Position;"))
    }
}
