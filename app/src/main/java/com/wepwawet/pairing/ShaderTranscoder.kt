package com.wepwawet.pairing

import com.wepwawet.log.WepWawetLogger

/**
 * Native shader transcoder using glslang + SPIRV-Cross.
 *
 * Compiles HLSL/GLSL shaders to SPIR-V via glslang, then cross-compiles
 * to GLSL ES 3.00 via SPIRV-Cross. This matches what WE PC's transcoder
 * does internally (wallpaperui.exe has its own HLSL→GLSL converter; we
 * use the open-source Khronos Group tools as the equivalent).
 *
 * Used as a FALLBACK in fixShadersForMobile — the regex-based patching
 * handles most GLSL ES compatibility issues. This is used for complex
 * shaders that can't be fixed with regex (e.g., pure HLSL shaders).
 *
 * v0.6.0 (parity §7-2): Added support for ALL shader stages (vertex, fragment,
 * geometry, hull, domain, compute). Previously only vertex + fragment were
 * supported, dropping tessellation and compute effects on Android.
 *
 * Native library: libwepwawet_etc2.so (shared with ETC2 encoder + DXT decoder,
 * built from app/src/main/cpp/ via CMakeLists.txt which fetches glslang +
 * SPIRV-Cross via FetchContent).
 */
object ShaderTranscoder {
    private const val TAG = "ShaderTranscoder"

    // v0.6.0 (parity §7-2): All shader stages supported
    /** Shader stage: vertex shader. */
    const val STAGE_VERTEX = 0
    /** Shader stage: fragment shader. */
    const val STAGE_FRAGMENT = 1
    /** Shader stage: geometry shader (rare in WE wallpapers). */
    const val STAGE_GEOMETRY = 2
    /** Shader stage: hull / tessellation control shader (rare). */
    const val STAGE_HULL = 3
    /** Shader stage: domain / tessellation evaluation shader (rare). */
    const val STAGE_DOMAIN = 4
    /** Shader stage: compute shader (rare in WE wallpapers). */
    const val STAGE_COMPUTE = 5

    private val isLoaded: Boolean by lazy {
        try {
            System.loadLibrary("wepwawet_etc2")
            WepWawetLogger.proxy("$TAG: native library loaded ✓")
            true
        } catch (e: UnsatisfiedLinkError) {
            WepWawetLogger.proxyWarn("$TAG: native library not loaded — shader transcoding unavailable",
                "exception" to e.javaClass.name)
            false
        }
    }

    /**
     * Check if the shader transcoder is available.
     * v0.7.24 (H-S2 BUG FIX): `isLoaded` only proves the .so loaded — NOT that
     * glslang+SPIRV-Cross were compiled in (CMake only warns when the fetch
     * fails). The new native probe (compiled unconditionally, outside the
     * GLSLANG_AVAILABLE guard) reports the real state so callers choose the
     * regex fallback up front instead of eating an UnsatisfiedLinkError per
     * call. Older builds without the probe symbol fall back to `false`.
     */
    fun isAvailable(): Boolean {
        if (!isLoaded) return false
        return try {
            nativeIsAvailable()
        } catch (_: UnsatisfiedLinkError) {
            // Probe symbol missing (pre-v0.7.24 .so) — assume unavailable so
            // callers use the regex path instead of throw-per-call.
            false
        }
    }

    // v0.7.24 (H-S2): native availability probe — returns false when the
    // .so was built without glslang+SPIRV-Cross.
    private external fun nativeIsAvailable(): Boolean

    /**
     * Transcode an HLSL or GLSL shader to GLSL ES 3.00.
     *
     * Flow: source → glslang (compile to SPIR-V) → SPIRV-Cross (cross-compile to GLSL ES)
     *
     * @param shaderSource The shader source code (HLSL or GLSL)
     * @param shaderStage 0=vertex, 1=fragment, 2=geometry, 3=hull, 4=domain, 5=compute
     * @param isHlsl true if source is HLSL, false if GLSL
     * @return GLSL ES 3.00 source, or null on failure
     */
    fun transcodeShader(shaderSource: String, shaderStage: Int, isHlsl: Boolean): String? {
        // v0.6.0 (parity §7-2): Validate shader stage
        require(shaderStage in STAGE_VERTEX..STAGE_COMPUTE) {
            "Invalid shaderStage: $shaderStage (must be $STAGE_VERTEX..$STAGE_COMPUTE)"
        }
        if (!isLoaded) return null
        val srcBytes = shaderSource.toByteArray(Charsets.UTF_8)
        return try {
            val result = nativeTranscodeShader(srcBytes, shaderStage, isHlsl)
            if (result != null) {
                val stageName = when (shaderStage) {
                    STAGE_VERTEX -> "vertex"
                    STAGE_FRAGMENT -> "fragment"
                    STAGE_GEOMETRY -> "geometry"
                    STAGE_HULL -> "hull"
                    STAGE_DOMAIN -> "domain"
                    STAGE_COMPUTE -> "compute"
                    else -> "unknown"
                }
                WepWawetLogger.proxy("$TAG.transcodeShader SUCCESS",
                    "stage" to stageName,
                    "is_hlsl" to isHlsl,
                    "input_size" to srcBytes.size,
                    "output_size" to result.size)
                String(result, Charsets.UTF_8)
            } else {
                // v0.7.4: same full stage mapping as the success branch — the old
                // `if (vertex) "vertex" else "fragment"` mislabeled
                // geometry/hull/domain/compute failures as "fragment".
                val stageName = when (shaderStage) {
                    STAGE_VERTEX -> "vertex"
                    STAGE_FRAGMENT -> "fragment"
                    STAGE_GEOMETRY -> "geometry"
                    STAGE_HULL -> "hull"
                    STAGE_DOMAIN -> "domain"
                    STAGE_COMPUTE -> "compute"
                    else -> "unknown"
                }
                WepWawetLogger.proxyWarn("$TAG.transcodeShader FAILED — native call returned null (glslang may not be compiled in)",
                    "stage" to stageName,
                    "is_hlsl" to isHlsl)
                null
            }
        } catch (e: UnsatisfiedLinkError) {
            // glslang not compiled into the .so — fall back to regex-based shader fixes
            WepWawetLogger.proxyDebug("$TAG.transcodeShader: glslang not available — using regex-based fixes",
                "exception" to e.javaClass.name)
            null
        } catch (e: Throwable) {
            WepWawetLogger.proxyWarn("$TAG.transcodeShader EXCEPTION",
                "exception" to e.javaClass.name, "message" to (e.message ?: ""))
            null
        }
    }

    @JvmStatic
    private external fun nativeTranscodeShader(
        srcBytes: ByteArray,
        shaderStage: Int,
        isHlsl: Boolean,
    ): ByteArray?
}
