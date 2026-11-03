/*
 * shader_transcoder_jni.cpp — JNI wrapper for glslang + SPIRV-Cross.
 *
 * v0.6.0 (parity §7-1): glslang + SPIRV-Cross are fetched via CMake
 * FetchContent; if the fetch fails, CMakeLists.txt only logs a WARNING and
 * this TU compiles to a stub (GLSLANG_AVAILABLE undefined) — the Kotlin
 * caller (ShaderTranscoder.transcodeShader) then falls back to the regex
 * path. (v0.7.24, L-N1/L-S12: corrected the stale "hard-errors" comment.)
 *
 * v0.6.0 (parity §7-2): Supports ALL shader stages (vertex, fragment, geometry,
 * hull, domain, compute). Previously only vertex + fragment were supported,
 * dropping tessellation and compute effects on Android.
 *
 * v0.6.0 (parity §7-5): Added custom glslang::TShader::Includer that resolves
 * #include directives. The previous default resolver returned "file not found"
 * for all includes, breaking shaders that use #include "common_*.h".
 *
 * Compiles GLSL/HLSL shaders to SPIR-V via glslang, then cross-compiles
 * to GLSL ES 3.00 via SPIRV-Cross's C++ API.
 */

#include <jni.h>

/*
 * v0.7.24 (H-S2): Availability probe compiled UNCONDITIONALLY (outside the
 * GLSLANG_AVAILABLE guard). The Kotlin-side `isLoaded` only proves that
 * libwepwawet_etc2.so loaded — NOT that glslang+SPIRV-Cross were fetched at
 * build time. When they weren't, every transcodeShader call entered the JNI
 * path just to throw UnsatisfiedLinkError. This probe lets isAvailable()
 * report the truth so callers can pick the regex fallback up front.
 */
extern "C" JNIEXPORT jboolean JNICALL
Java_com_wepwawet_pairing_ShaderTranscoder_nativeIsAvailable(
        JNIEnv* /*env*/, jclass /*clazz*/) {
#ifdef GLSLANG_AVAILABLE
    return JNI_TRUE;
#else
    return JNI_FALSE;
#endif
}

#ifdef GLSLANG_AVAILABLE

#include <string>
#include <vector>
#include <cstring>
#include <unordered_map>
#include <mutex>

// glslang includes — the include path is set in CMakeLists.txt to ${glslang_src_SOURCE_DIR}
#include "glslang/Public/Shader.h"
#include "glslang/Public/ResourceLimits.h"
#include "SPIRV/GlslangToSpv.h"

// SPIRV-Cross C++ API — include path set to ${spirv_cross_src_SOURCE_DIR}/include
#include "spirv_cross/spirv_cross.hpp"

// ============================================================================
// v0.6.0 (parity §7-5): Custom #include resolver
// ============================================================================
// WE PC resolves #include "common_*.h" directives against its installation's
// effects/ and shaders/ directories. The default glslang::TShader::Includer
// returns "file not found" for all includes, breaking shaders that use them.
//
// We bundle a minimal set of WE PC's common headers as Android resources and
// resolve includes via a JNI callback to the Kotlin side (which reads from
// the assets directory).
//
// For now, we provide the headers inlined directly in the binary (matching
// what MpkgPacker.kt::fixShadersForMobile does on the regex path). This means
// the native path doesn't need a callback for the 5 commonly-inlined headers
// (common_blending.h, common.h, common_fragment.h, common_vertex.h, common_blur.h).
// For other headers, we return an empty string (effectively no-op'ing the include).

class WepWawetIncluder : public glslang::TShader::Includer {
public:
    // Result struct used by glslang.
    // v0.7.24 (C-S2 BUG FIX): the base-class `headerName` pointer was left
    // pointing at the CALLER's local `name` std::string — destroyed as soon
    // as includeLocal() returned. glslang reads headerName after the call
    // (diagnostics, line directives, include stack) — a dangling-pointer
    // read. Both the name and the content are now owned copies.
    struct IncludeResultImpl : public IncludeResult {
        std::string name_owned;
        std::string content_owned;
        IncludeResultImpl(const std::string& name, const std::string& content)
            : IncludeResult(nullptr, nullptr, 0, nullptr),
              name_owned(name),
              content_owned(content) {
            IncludeResult* base = static_cast<IncludeResult*>(this);
            base->headerName = name_owned.c_str();
            base->headerData = content_owned.c_str();
            base->headerLength = content_owned.size();
        }
    };

    IncludeResult* includeLocal(const char* headerName, const char* includerName,
                                  size_t inclusionDepth) override {
        (void)includerName;
        (void)inclusionDepth;

        // Built-in WE PC headers (synchronously inlined — same content as
        // MpkgPacker.kt's regex path uses).
        // For brevity, we use lookup by name. If not found, return empty
        // (effectively no-op'ing the include).
        std::string name(headerName);
        std::string content;

        if (name == "common_blending.h") {
            content = "// common_blending.h (auto-inlined by WepWawet native shader transcoder)\n"
                      "vec3 ApplyBlending(int mode, vec3 colorA, vec3 colorB, float blend) {\n"
                      "    return mix(colorA, colorB, blend);\n"
                      "}\n";
        } else if (name == "common.h") {
            content = "// common.h (auto-inlined by WepWawet native shader transcoder)\n"
                      "vec3 hsv2rgb(vec3 c) { vec4 K = vec4(1.0, 2.0/3.0, 1.0/3.0, 3.0); vec3 p = abs(fract(c.xxx + K.xyz) * 6.0 - K.www); return c.z * mix(K.xxx, clamp(p - K.xxx, 0.0, 1.0), c.y); }\n"
                      "vec3 rgb2hsv(vec3 c) { vec4 K = vec4(0.0, -1.0/3.0, 2.0/3.0, -1.0); vec4 p = mix(vec4(c.bg, K.wz), vec4(c.gb, K.xy), step(c.b, c.g)); vec4 q = mix(vec4(p.xyw, c.r), vec4(c.r, p.yzx), step(p.x, c.r)); float d = q.x - min(q.w, q.y); float e = 1.0e-10; return vec3(abs(q.z + (q.w - q.y) / (6.0 * d + e)), d / (q.x + e), q.x); }\n"
                      "vec2 rotateVec2(vec2 v, float r) { float c = cos(r); float s = sin(r); return vec2(v.x * c - v.y * s, v.x * s + v.y * c); }\n";
        } else if (name == "common_fragment.h") {
            content = "// common_fragment.h (auto-inlined by WepWawet native shader transcoder)\n"
                      "vec3 DecompressNormal(vec4 c) { vec3 normal; normal.xy = c.wy * 2.0 - 1.0; normal.z = sqrt(max(0.0, 1.0 - dot(normal.xy, normal.xy))); return normal; }\n"
                      "vec4 DecompressNormalWithMask(vec4 c) { vec4 result; result.xyz = DecompressNormal(c); result.w = c.z; return result; }\n"
                      "float ConvertSampleR8(vec4 c) { return c.r; }\n";
        } else if (name == "common_vertex.h") {
            content = "// common_vertex.h (auto-inlined by WepWawet native shader transcoder)\n"
                      "mat3 BuildTangentSpace(vec3 normal, vec4 signedTangent) {\n"
                      "    vec3 tangent = normalize(signedTangent.xyz);\n"
                      "    vec3 bitangent = cross(normal, tangent) * signedTangent.w;\n"
                      "    return mat3(tangent, bitangent, normal);\n"
                      "}\n";
        } else if (name == "common_blur.h") {
            content = "// common_blur.h (auto-inlined by WepWawet native shader transcoder)\n"
                      "uniform sampler2D g_Texture0;\n"
                      "vec3 blur13(sampler2D tex, vec2 uv, vec2 px) {\n"
                      "    vec3 sum = vec3(0.0);\n"
                      "    sum += texSample2D(tex, uv - 4.0*px).rgb * 0.196482;\n"
                      "    sum += texSample2D(tex, uv - 3.0*px).rgb * 0.297024;\n"
                      "    sum += texSample2D(tex, uv - 2.0*px).rgb * 0.094470;\n"
                      "    sum += texSample2D(tex, uv - 1.0*px).rgb * 0.010348;\n"
                      "    sum += texSample2D(tex, uv).rgb * 0.010348;\n"
                      "    sum += texSample2D(tex, uv + 1.0*px).rgb * 0.094470;\n"
                      "    sum += texSample2D(tex, uv + 2.0*px).rgb * 0.297024;\n"
                      "    sum += texSample2D(tex, uv + 3.0*px).rgb * 0.196482;\n"
                      "    sum += texSample2D(tex, uv + 4.0*px).rgb * 0.010348;\n"
                      "    return sum;\n"
                      "}\n";
        } else {
            // Unknown header — return empty (effectively no-op'ing the include).
            // The caller's regex path may have inlined it already.
            content = "// " + name + " (unknown — no-op'd by WepWawet native shader transcoder)\n";
        }

        return new IncludeResultImpl(name, content);
    }

    void releaseInclude(IncludeResult* result) override {
        if (result != nullptr) {
            delete static_cast<IncludeResultImpl*>(result);
        }
    }
};

extern "C" {

// v0.7.24 (C-S3 BUG FIX): glslang::InitializeProcess must run EXACTLY once
// per process; concurrent first-calls from two threads corrupted the glslang
// global pool (plain bool had no synchronization). std::call_once is the
// canonical fix.
static std::once_flag g_glslang_once;
static void ensureGlslangInit() {
    std::call_once(g_glslang_once, []() {
        glslang::InitializeProcess();
    });
}

/*
 * Class:     com_wepwawet_pairing_ShaderTranscoder
 * Method:    nativeTranscodeShader
 * Signature: ([BIZ)[B
 *
 * Transcodes shader source (HLSL or GLSL) to GLSL ES 3.00 via:
 *   source → glslang (compile to SPIR-V) → SPIRV-Cross (cross-compile to GLSL ES)
 *
 * Args:
 *   srcBytes    - byte[] of shader source code
 *   shaderStage - 0=vertex, 1=fragment, 2=geometry, 3=hull, 4=domain, 5=compute
 *                 (v0.6.0: previously only 0/1 were supported)
 *   isHlsl      - true if source is HLSL, false if GLSL
 *
 * Returns: byte[] of GLSL ES 3.00 source, or null on failure.
 */
JNIEXPORT jbyteArray JNICALL
Java_com_wepwawet_pairing_ShaderTranscoder_nativeTranscodeShader(
    JNIEnv *env, jclass /* cls */,
    jbyteArray srcBytes, jint shaderStage, jboolean isHlsl) {

    ensureGlslangInit();

    // Get source bytes
    jsize srcLen = env->GetArrayLength(srcBytes);
    if (srcLen <= 0) return nullptr;
    jbyte* src = env->GetByteArrayElements(srcBytes, nullptr);
    if (src == nullptr) return nullptr;

    std::string shaderSource((const char*)src, srcLen);
    env->ReleaseByteArrayElements(srcBytes, src, JNI_ABORT);

    // v0.6.0 (parity §7-2): Map shader stage to glslang EShLanguage
    EShLanguage stage;
    switch (shaderStage) {
        case 0: stage = EShLangVertex; break;
        case 1: stage = EShLangFragment; break;
        case 2: stage = EShLangGeometry; break;
        case 3: stage = EShLangTessControl; break;
        case 4: stage = EShLangTessEvaluation; break;
        case 5: stage = EShLangCompute; break;
        default:
            // Invalid stage — return null
            return nullptr;
    }

    // Create glslang shader object
    glslang::TShader shader(stage);
    const char* sourceCStr = shaderSource.c_str();
    int sourceLen = (int)shaderSource.length();
    shader.setStringsWithLengths(&sourceCStr, &sourceLen, 1);

    // Set source language (HLSL or GLSL) and target (SPIR-V 1.0)
    shader.setEnvInput(isHlsl ? glslang::EShSourceHlsl : glslang::EShSourceGlsl,
                       stage, glslang::EShClientVulkan, 100);
    shader.setEnvClient(glslang::EShClientVulkan, glslang::EShTargetVulkan_1_0);
    shader.setEnvTarget(glslang::EShTargetSpv_1_0);

    // v0.6.0 (parity §7-5): Use custom include resolver
    WepWawetIncluder includer;
    shader.setIncluder(&includer);

    // Compile to SPIR-V
    std::string infoLog;
    const TBuiltInResource* resources = GetDefaultResources();
    bool compiled = shader.parse(resources, 100, false,
                                  EShMsgDefault, &infoLog);

    if (!compiled) {
        return nullptr;  // Compilation failed — caller falls back to regex-based fixes
    }

    // Link (for single-shader compilation, this is trivial)
    glslang::TProgram program;
    program.addShader(&shader);
    if (!program.link(EShMsgDefault)) {
        return nullptr;
    }

    // Generate SPIR-V
    // v0.7.24 (M-N6): getIntermediate can return null (e.g. when the link
    // step didn't produce the requested stage) — dereferencing it was UB.
    std::vector<unsigned int> spirv;
    const glslang::TIntermediate* intermediate = program.getIntermediate(stage);
    if (intermediate == nullptr) {
        return nullptr;
    }
    glslang::GlslangToSpv(*intermediate, spirv);
    if (spirv.empty()) {
        return nullptr;
    }

    // Cross-compile SPIR-V to GLSL ES 3.00 using SPIRV-Cross C++ API
    spirv_cross::CompilerGLSL compiler(std::move(spirv));

    spirv_cross::CompilerGLSL::Options options;
    options.version = 300;       // GLSL ES 3.00
    options.es = true;           // ES profile
    options.vulkan_semantics = false;  // Use GL semantics (not Vulkan)
    // v0.7.24 (H-S3): the setter is set_common_options (there is no public
    // set_options on CompilerGLSL — this TU failed to compile whenever
    // GLSLANG_AVAILABLE was defined, which is why the races above never
    // manifested in shipped builds).
    compiler.set_common_options(options);

    std::string glslResult;
    try {
        glslResult = compiler.compile();
    } catch (...) {
        return nullptr;  // Cross-compilation failed
    }

    if (glslResult.empty()) {
        return nullptr;
    }

    // Copy result to Java byte array
    jsize resultLen = (jsize)glslResult.length();
    jbyteArray result = env->NewByteArray(resultLen);
    if (result != nullptr) {
        env->SetByteArrayRegion(result, 0, resultLen,
            reinterpret_cast<const jbyte*>(glslResult.c_str()));
        // v0.7.24 (M-N2): if SetByteArrayRegion raised (e.g. array was mutated
        // concurrently), do NOT keep executing native code with a pending
        // exception — return the (safe) null result immediately.
        if (env->ExceptionCheck()) {
            return nullptr;
        }
    }

    return result;
}

} // extern "C"

#endif // GLSLANG_AVAILABLE
