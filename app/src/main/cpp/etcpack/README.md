# etcpack (vendored)

Ericsson's ETC2 reference codec, as bundled in AMD Compressonator v3.1.4064
(https://github.com/GPUOpen-Tools/compressonator, tag v3.1.4064).

This is the EXACT encoder Wallpaper Engine's resourcecompiler64.exe embeds
(RTTI: .?AVCCodec_ETC2_RGBA@@ / CCodec@AMD_Compress; the block pipeline is
FUN_1400598c0 -> FUN_140059720 -> FUN_140115ce0 -> FUN_1400da510 with
m_fast = true). Vendored unmodified except for the `USE_IN_COMPRESSONATOR`
build flag, which we leave undefined so the etcpack CLI main() is excluded.

License: see LICENSE-ETCPACK.txt (Ericsson SLA — permits use in binary form
for products that compress/decompress according to the Khronos OpenGL /
OpenGL ES / WebGL specifications, which is exactly what this app does).

Files:
  etcpack.cxx    the codec (compressBlockAlphaFast,
                  compressBlockETC2FastPerceptual, and dependencies)
  etcdec.cxx     the decoder (used by roundtrip_test.cpp)
  etcimage.cxx   image container helpers (dependency of etcpack.cxx)
  etcpack_lib.h  public prototypes
  sys/timeb.h    bionic compatibility shim (Android removed <sys/timeb.h>;
                 only the CLI timing helpers use ftime())
  etcimage.h     image helpers header
  roundtrip_test.cpp  host-build verification: encodes known blocks and
                  decodes them with etcpack's own decoder (orientation,
                  channels, alpha, pad-4 semantics; asymmetric 6x6 case)

Build (host, for verification):
  g++ -std=c++17 -O2 -w -I. roundtrip_test.cpp etcpack.cxx etcdec.cxx \
      etcimage.cxx -o roundtrip && ./roundtrip   # prints ROUND-TRIP OK
