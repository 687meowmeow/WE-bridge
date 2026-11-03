# CMakeLists.txt for WepWawet native library
# Builds: ETC2 encoder + DXT decoder (+ optional glslang + SPIRV-Cross)
# for Android arm64-v8a as a single shared library: libwepwawet_etc2.so

cmake_minimum_required(VERSION 3.18.1)

project(wepwawet_etc2 LANGUAGES CXX)

set(CMAKE_CXX_STANDARD 17)  # glslang 14.x requires C++17
set(CMAKE_CXX_STANDARD_REQUIRED ON)

# ============================================================================
# ETC2 encoder (Google's etc2comp)
# ============================================================================
include_directories(
    ${CMAKE_CURRENT_SOURCE_DIR}/etc2comp/EtcLib/Etc
    ${CMAKE_CURRENT_SOURCE_DIR}/etc2comp/EtcLib/EtcCodec
    ${CMAKE_CURRENT_SOURCE_DIR}/etcpack
)

file(GLOB ETC_SOURCES
    ${CMAKE_CURRENT_SOURCE_DIR}/etc2comp/EtcLib/Etc/*.cpp
    ${CMAKE_CURRENT_SOURCE_DIR}/etc2comp/EtcLib/EtcCodec/*.cpp
)

# ============================================================================
# etcpack — the ETC2 encoder Wallpaper Engine's resourcecompiler64.exe uses
# (AMD Compressonator v3.x bundles Ericsson's etcpack; m_fast = true).
# Byte-exact with the PC's ETC2 RGBA8 output. See etcpack_jni.cpp.
# ============================================================================
set(ETCPACK_SOURCES
    ${CMAKE_CURRENT_SOURCE_DIR}/etcpack/etcpack.cxx
    ${CMAKE_CURRENT_SOURCE_DIR}/etcpack/etcdec.cxx
    ${CMAKE_CURRENT_SOURCE_DIR}/etcpack/etcimage.cxx
)
# etcpack is vendored third-party code (Ericsson, via AMD Compressonator) —
# silence its warnings; do not modify the algorithm.
set_source_files_properties(${ETCPACK_SOURCES} PROPERTIES
    COMPILE_OPTIONS "-w;-Wno-deprecated;"
)

# ============================================================================
# JNI wrappers (always built)
# ============================================================================
set(JNI_SOURCES
    ${CMAKE_CURRENT_SOURCE_DIR}/etc2_jni.cpp
    ${CMAKE_CURRENT_SOURCE_DIR}/dxt_jni.cpp
    ${CMAKE_CURRENT_SOURCE_DIR}/dxt_decoder.cpp
    ${CMAKE_CURRENT_SOURCE_DIR}/etcpack_jni.cpp
)

# ============================================================================
# glslang + SPIRV-Cross (for HLSL→GLSL shader transcoding — v0.6.0 parity §7-1)
# ============================================================================
# v0.6.0 (parity §7-1): glslang is strongly preferred. If FetchContent fails
# (network issues during build, GitHub unavailable, Git not installed on build
# machine), the build still succeeds WITHOUT glslang. The Kotlin ShaderTranscoder
# class checks isAvailable() and falls back to the regex-based HLSL→GLSL
# syntax conversion + GLSL ES 3.00 compatibility fixes (Phase 0.5 in
# MpkgPacker.fixShadersForMobile — added in v0.6.0).
#
# The regex fallback now includes HLSL→GLSL syntax conversion (cbuffer, mul,
# saturate, lerp, frac, ddx/ddy), so it handles MOST HLSL shaders that don't
# need full SPIR-V cross-compilation. Pure HLSL shaders with complex features
# (custom sampler states, structured buffers, etc.) may still need glslang.
#
# Recommended: ensure network access during build so glslang is fetched.
# If your build environment has no network access, vendor glslang+SPIRV-Cross
# source as git submodules under app/src/main/cpp/third_party/.

option(ENABLE_GLSLANG "Build with glslang+SPIRV-Cross for shader transcoding" ON)

set(GLSLANG_AVAILABLE FALSE)

if(ENABLE_GLSLANG)
    include(FetchContent)

    # Fetch glslang (use full commit SHA for reliability with shallow clones)
    FetchContent_Declare(
        glslang_src
        GIT_REPOSITORY https://github.com/KhronosGroup/glslang.git
        GIT_TAG        14.3.0
        GIT_SHALLOW    TRUE
        TIMEOUT        180
    )
    set(ENABLE_OPT OFF CACHE BOOL "Disable SPIRV-Tools optimizer" FORCE)
    set(ENABLE_HLSL ON CACHE BOOL "Enable HLSL front-end" FORCE)
    set(ENABLE_GLSL ON CACHE BOOL "Enable GLSL front-end" FORCE)
    set(BUILD_SHARED_LIBS OFF CACHE BOOL "Build static libs" FORCE)

    # Try to make glslang available. Don't fail the build if it doesn't work
    # (the regex fallback path handles HLSL→GLSL syntax conversion).
    FetchContent_MakeAvailable(glslang_src)

    # Fetch SPIRV-Cross
    FetchContent_Declare(
        spirv_cross_src
        GIT_REPOSITORY https://github.com/KhronosGroup/SPIRV-Cross.git
        GIT_TAG        vulkan-sdk-1.3.296
        GIT_SHALLOW    TRUE
        TIMEOUT        180
    )
    set(SPIRV_CROSS_SKIP_TESTS ON CACHE BOOL "" FORCE)
    set(SPIRV_CROSS_SKIP_INSTALL ON CACHE BOOL "" FORCE)
    FetchContent_MakeAvailable(spirv_cross_src)

    # Verify the source directories are set AND the header files actually exist
    if(EXISTS "${glslang_src_SOURCE_DIR}/glslang/Public/Shader.h" AND
       EXISTS "${spirv_cross_src_SOURCE_DIR}/include/spirv_cross/spirv_cross.hpp")
        set(GLSLANG_AVAILABLE TRUE)
        list(APPEND JNI_SOURCES ${CMAKE_CURRENT_SOURCE_DIR}/shader_transcoder_jni.cpp)
        message(STATUS "glslang+SPIRV-Cross available — shader transcoder enabled")
        message(STATUS "  glslang source: ${glslang_src_SOURCE_DIR}")
        message(STATUS "  spirv-cross source: ${spirv_cross_src_SOURCE_DIR}")
    else()
        message(WARNING
            "glslang or SPIRV-Cross source not found — shader transcoder DISABLED.\n"
            "  The regex-based HLSL→GLSL syntax conversion (Phase 0.5 in fixShadersForMobile)\n"
            "  will be used as the fallback path. This handles MOST HLSL shaders.\n"
            "  For full parity (including complex HLSL features), ensure network access\n"
            "  during build so glslang+SPIRV-Cross can be fetched via FetchContent.\n"
            "  glslang src dir: ${glslang_src_SOURCE_DIR}\n"
            "  spirv-cross src dir: ${spirv_cross_src_SOURCE_DIR}")
    endif()
endif()

# ============================================================================
# Build the shared library
# ============================================================================
add_library(wepwawet_etc2 SHARED
    ${JNI_SOURCES}
    ${ETC_SOURCES}
    ${ETCPACK_SOURCES}
)

# Compiler flags
target_compile_options(wepwawet_etc2 PRIVATE
    -Wall -Wextra -Wno-unused-parameter -Wno-unused-variable -Wno-unused-but-set-variable
    -O2
    -fvisibility=hidden -fvisibility-inlines-hidden
    -fexceptions -frtti
    # v0.7.24 (M-N3): allow the linker to strip the dead vendored codec code
    # (etc2comp/etcpack/bcdec export far more than we call) — smaller .so.
    -ffunction-sections -fdata-sections
)

# v0.7.24 (M-N3): strip unused sections at link time.
target_link_options(wepwawet_etc2 PRIVATE -Wl,--gc-sections)

target_compile_definitions(wepwawet_etc2 PRIVATE
    ETC2_COMP_STATIC=1
)
# v0.7.24 (M-N4): NDEBUG only for RELEASE builds — the unconditional define
# disabled assert() even in debug builds, hiding texture-decoder bugs.
if(CMAKE_BUILD_TYPE STREQUAL "Debug")
    target_compile_definitions(wepwawet_etc2 PRIVATE ETC2_DEBUG=1)
else()
    target_compile_definitions(wepwawet_etc2 PRIVATE NDEBUG)
endif()

# Link with standard libraries
target_link_libraries(wepwawet_etc2
    log   # Android logging
)

# If glslang is available, add include paths and link libraries
if(GLSLANG_AVAILABLE)
    target_include_directories(wepwawet_etc2 PRIVATE
        ${glslang_src_SOURCE_DIR}
        ${spirv_cross_src_SOURCE_DIR}/include
    )
    target_link_libraries(wepwawet_etc2
        glslang              # glslang core (GLSL/HLSL front-end)
        SPIRV                # glslang's SPIR-V codegen (has GlslangToSpv)
        HLSL                 # glslang's HLSL front-end
        spirv-cross-glsl     # SPIRV-Cross GLSL backend
    )
    target_compile_definitions(wepwawet_etc2 PRIVATE
        GLSLANG_AVAILABLE=1
    )
endif()
