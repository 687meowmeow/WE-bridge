# bcdec.h (vendored)

Single-header, zero-dependency BC1-BC7 block decompression library.

- Upstream: https://github.com/iOrange/bcdec (v0.985)
- License: MIT OR The Unlicense (dual-licensed; see the license block at the
  bottom of bcdec.h). Vendored unmodified.
- Used by `dxt_decoder.cpp` for bit-exact BC6H and BC7 decoding (the
  hand-rolled v0.6.0 implementations had incorrect weight tables, incomplete
  partition tables, and missing delta-endpoint transforms).
