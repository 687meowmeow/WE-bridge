package com.wepwawet.pairing

import com.wepwawet.data.model.WorkshopItemTyped
import com.wepwawet.log.WepWawetLogger
import com.google.gson.JsonParser
import com.google.gson.JsonObject
import com.google.gson.JsonArray
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.OutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import android.graphics.Bitmap
import android.graphics.BitmapFactory

/**
 * Packs Wallpaper Engine .mpkg files using the REAL binary format verified
 * from C++ source + two real samples + the decompiled libscenejni.so.
 *
 * === SUPPORTED INPUTS ===
 *
 * This packer supports THREE input modes:
 *
 * 1. **From .pkg file** (preferred — real WE content):
 *    User manually copies a .pkg file from their PC's Steam folder to their
 *    phone, then picks it via the file picker. The .pkg is read by [PkgReader],
 *    assets are extracted, and we repackage them as a .mpkg. This produces a
 *    wallpaper that's byte-for-byte compatible with what the WE PC would send
 *    to the WE Android app.
 *
 * 2. **From local image file** (JPEG/PNG):
 *    User picks a local image. We encode it to a .tex (RGBA8888 format=0)
 *    and pack as a Scene .mpkg. This works for any image, but the resulting
 *    wallpaper renders as a static image with no animation/effects.
 *
 * 3. **From local video file** (MP4):
 *    User picks a local MP4. We package it directly as a Video .mpkg. The
 *    WE Android app's VideoDrawable plays the MP4 from inside the .mpkg VFS.
 *
 * === BRIGHTNESS FIX (v2) ===
 *
 * Previous version had brightness issues:
 *   - Schemecolor was hardcoded to "0.000 0.000 0.000" (black) — should be
 *     the texture's average color (matching real WE .mpkg behavior)
 *   - Blending mode was "translucent" which can cause additive blending on
 *     some shaders — should be "normal" for opaque content
 *
 * This version:
 *   - Computes schemecolor from the texture's average RGBA color
 *   - Uses "blending": "normal" for opaque textures
 *   - Sets "depthtest": "enabled" to match real WE samples
 *   - Computes clearcolor from the texture's average color (dimmed to 30%)
 *
 * === .MPKG FORMAT ===
 *
 *   [u32 LE header_marker = 8]
 *   [8-byte ASCII magic]    ("PKGM0020")  ← bumped from PKGM0014 to match
 *                                          the latest wallpaperui.exe binary
 *                                          (string at file offset 0x00ab4840
 *                                          in the user's wallpaperui.exe, dated
 *                                          2026-06-29). The Android native lib
 *                                          libscenejni.so uses integer version
 *                                          comparison (no hardcoded string) so
 *                                          it accepts any version >= PKGM0001.
 *                                          For 100% parity with real WE PC, we
 *                                          emit PKGM0020 (the latest).
 *   [u32 LE entry_count]
 *   For each entry:
 *     [u32 LE name_len][name_bytes][u32 LE file_offset][u32 LE file_size]
 *   [data section: file blobs packed back-to-back]
 *
 * === .TEX FORMAT (for Scene wallpapers) ===
 *
 *   [TEXV0005\0]                  9 bytes — file magic
 *   [TEXI0001\0]                  9 bytes — header chunk
 *   [7x u32 LE]                  28 bytes — format, flags, tex_w, tex_h, img_w, img_h, color
 *   [TEXB0004\0]                  9 bytes — image container chunk
 *   [3x u32 LE]                  12 bytes — imageCount=1, format=-1, isVideoMp4=0
 *   [1x u32 LE]                   4 bytes — mipmap_count=1
 *   [4x u32 LE]                  16 bytes — width, height, IsLZ4=0, DecompressedSize
 *   [1x u32 LE]                   4 bytes — byteCount
 *   [byte[byteCount]]                       — raw pixel data (RGBA8888 when IsLZ4=0)
 */
object MpkgPacker {

    const val MAGIC = "PKGM0020"  // default / ceiling — see [negotiateMagic] for the
                                  // per-transfer selection (v0.7.4, parity G1)

    const val HEADER_MARKER = 8

    // ══════════════════════════════════════════════════════════════════════
    // v0.7.4 (parity G5): optional texture shrink ("texturereduction" on the
    // PC — the -shrink lever that halves/quarters texture resolution per
    // device). 1 = off (default, current behavior), 2 = half, 4 = quarter.
    // Applied ONLY to textures that go through the decode → ETC2 re-encode
    // pipeline (PNG/DXT/RG88/R8/RGBA8888); already-ETC2 textures (format=5)
    // are passed through unchanged because the device has no ETC2 decoder
    // to shrink them with. Set from SettingsScreen.
    // ══════════════════════════════════════════════════════════════════════
    @Volatile
    var textureShrinkFactor: Int = 1

    // ══════════════════════════════════════════════════════════════════════
    // v0.7.18 (PC parity — the `-c force` gate): wallpaperui appends
    // " -c force" to the resourcecompiler64 command only when the .tex's
    // parent path is materials/materials\masks AND the paired device's
    // "compression" setting != "high_quality" (FUN_140019b80:
    // `*out = (value != "high_quality")`, missing key ⇒ "" ⇒ forced).
    //
    // WepWawet defaults to the device-equivalent of "high_quality" (false) —
    // the setting that produced the byte-verified reference .mpkg: DXT/BC
    // sources still become ETC2, but PNG/RGBA sources keep lossless RGBA8888
    // (exactly what the PC shipped for those textures). Enabling it mirrors
    // the PC's "compressed" device option: every materials texture is forced
    // to ETC2_RGBA8.
    // ══════════════════════════════════════════════════════════════════════
    @Volatile
    var forceEtc2Compression: Boolean = false

    // ══════════════════════════════════════════════════════════════════════
    // v0.7.4 (parity G9): video wallpaper alignment position. The PC writes
    // the device-configured alignment into project.json as
    // {"alignmentposition": {"value": <int>}} (FUN_14003d0e0, disasm
    // 14003f27b–14003f2be: "{"value":" + int + "}"). The int follows the
    // desktop "alignment position" semantics (0 = centered default; the
    // PC UI exposes a 0–100 slider whose value lands in config.json's
    // 'alignmentposition'). WepWawet surfaces the same control as a
    // settings slider, defaulting to 0 = centered, which is byte-identical
    // to the previous hardcoded behavior.
    // ══════════════════════════════════════════════════════════════════════
    @Volatile
    var videoAlignmentPosition: Int = 0

    /**
     * v0.7.4 (parity G1): select the .mpkg magic for one transfer, mirroring
     * the PC packer (wallpaperui.exe 2.8.0.42, FUN_14003d0e0 at the
     * FUN_14020a660 call site; container layout itself is magic-independent
     * — FUN_14020a660 writes the 8-char magic as a plain string):
     *
     *   int version;
     *   if (isVideo) version = 0xe;                 // 14 — ALWAYS for video
     *   else version = this->deviceMpkgVersion;      // phone's reported int
     *   string magic = "PKGM0020";                   // default string
     *   if (version > 0) magic = "PKGM" + "%04i" % version;
     *
     * The phone reports its .mpkg version in the pairing auth JSON
     * ("mpkgVersion":"PKGMdddd" — parsed by WePairingServer into
     * [WePairingServer.negotiatedMpkgVersion]). The PC therefore:
     *   - never hands a phone a container NEWER than the phone's own
     *     reported version, and
     *   - unconditionally delivers video wallpapers as PKGM0014 (the
     *     VideoDrawable format that every phone generation understands).
     *
     * WepWawet policy (identical output for real devices):
     *   - video wallpapers → PKGM0014, always;
     *   - scene wallpapers → min(deviceVersion, 20), where an unknown
     *     device version (0, no phone paired yet) yields PKGM0020 — the
     *     exact behavior of every previous WepWawet release.
     *
     * The extra min(…, 20) guard is WepWawet's: our packer's content is at
     * most v20-level, so a hypothetical future phone reporting PKGM0030
     * still receives a container its parser fully understands.
     */
    fun negotiateMagic(wallpaperType: String): String {
        val deviceVersion = WePairingServer.negotiatedMpkgVersion
        return if (wallpaperType.equals("video", ignoreCase = true)) {
            "PKGM0014"
        } else {
            // The WORKING mpkg uses PKGM0019. The PC transcoder produces this version.
            val version = if (deviceVersion in 1..19) deviceVersion else 19
            "PKGM" + "%04d".format(version)
        }
    }

    /**
     * Bump whenever the DXT decoder, ETC2 encoder, effort policy, or .tex
     * output format changes. This invalidates the texture conversion cache
     * so stale entries are not reused after a code change.
     *
     * v2: fix-dxt-decoder.patch (3-bit DXT5/BC4/BC5 indices + forceFourColor
     *     for DXT3/DXT5 color blocks) + size-based effort (10/25/40) + more
     *     jobs (oversubscribed) + NDEBUG.
     */
    const val CONVERTER_VERSION = 2

    /** PNG magic bytes — used to detect PNG data inside .tex files. */
    private val PNG_MAGIC = byteArrayOf(0x89.toByte(), 0x50, 0x4e, 0x47) // \x89PNG
    private val JPEG_MAGIC = byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte()) // JPEG SOI marker (FFD8FF)

    /**
     * Check if a byte is a valid JPEG marker type (the byte after FFD8FF).
     * Used to prevent false-positive JPEG detection in .tex files that contain
     * binary data (DXT textures, LZ4-compressed data, etc.) which happens to
     * contain the FFD8FF byte sequence.
     *
     * Valid JPEG markers after SOI (FFD8):
     *   0xC0-0xCF (except 0xC4, 0xC8, 0xCC): SOF0-SOF15
     *   0xC4: DHT, 0xDA: SOS, 0xDB: DQT, 0xDD: DRI
     *   0xD0-0xD7: RST0-RST7
     *   0xE0-0xEF: APP0-APP15 (JFIF, EXIF, etc.)
     *   0xFE: COM (Comment)
     */
    private fun isValidJpegMarker(byte: Byte): Boolean {
        val b = byte.toInt() and 0xFF
        return (b in 0xC0..0xCF && b !in setOf(0xC4, 0xC8, 0xCC)) || // SOF variants
               b == 0xC4 || // DHT
               b == 0xDA || // SOS
               b == 0xDB || // DQT
               b == 0xDD || // DRI
               b in 0xD0..0xD7 || // RST0-RST7
               b in 0xE0..0xEF || // APP0-APP15
               b == 0xFE  // COM
    }

    /**
     * Parse the .tex header to determine the texture format.
     * Returns: Pair(TexI_format, TexB_IsLz4) or null if header is malformed.
     *
     * .tex format (from backup repo's parse_tex.py + make_tex.py):
     *   [TEXV0005\0]  9 bytes  — file magic
     *   [TEXI0001\0]  9 bytes  — info chunk
     *   [7x u32 LE]    28 bytes — format, flags, tex_w, tex_h, img_w, img_h, color
     *   [TEXB0004\0]  9 bytes  — body chunk
     *   [3x u32 LE]    12 bytes — imageCount, format, isVideoMp4
     *   [1x u32 LE]     4 bytes — mipmap_count
     *   [4x u32 LE]    16 bytes — width, height, IsLZ4, DecompressedSize
     *   [1x u32 LE]     4 bytes — byteCount
     *   [data]
     *
     * TexI format values:
     *   0  = FIF_UNKNOWN (auto-detect via magic bytes)
     *   5  = ETC2_RGBA8 (already mobile-compatible — no conversion needed!)
     *   13 = FIF_PNG
     *   14 = FIF_JPEG (maybe — unconfirmed)
     */
    private fun parseTexHeader(texData: ByteArray): Pair<Int, Boolean>? {
        try {
            // TEXV0005\0 (9 bytes)
            if (texData.size < 91) return null
            val texvMagic = String(texData, 0, 8, Charsets.US_ASCII)
            if (!texvMagic.startsWith("TEXV")) return null
            var pos = 9 // after TEXV0005\0

            // TEXI0001\0 (9 bytes)
            val texiMagic = String(texData, pos, 8, Charsets.US_ASCII)
            if (!texiMagic.startsWith("TEXI")) return null
            pos += 9 // after TEXI0001\0

            // 7x u32 LE (28 bytes): format, flags, tex_w, tex_h, img_w, img_h, color
            val texiFormat = readU32Le(texData, pos)
            pos += 28

            // TEXB0004\0 (9 bytes)
            val texbMagic = String(texData, pos, 8, Charsets.US_ASCII)
            if (!texbMagic.startsWith("TEXB")) return null
            pos += 9

            // 3x u32 LE (12 bytes): imageCount, format, isVideoMp4
            // v0.7.4: the unused `texbFormat` read was removed (dead code).
            pos += 12

            // 1x u32 LE (4 bytes): mipmap_count
            val mipmapCount = readU32Le(texData, pos)
            pos += 4

            // Per mipmap: 4x u32 LE (16 bytes): w, h, IsLZ4, DecompressedSize
            if (mipmapCount > 0) {
                val isLz4 = readU32Le(texData, pos + 8) // IsLZ4 field
                return Pair(texiFormat.toInt(), isLz4 != 0L)
            }
            return Pair(texiFormat.toInt(), false)
        } catch (e: Exception) {
            return null
        }
    }

    private fun readU32Le(data: ByteArray, offset: Int): Long {
        if (offset + 4 > data.size) return 0L
        return (data[offset].toLong() and 0xFF) or
               ((data[offset + 1].toLong() and 0xFF) shl 8) or
               ((data[offset + 2].toLong() and 0xFF) shl 16) or
               ((data[offset + 3].toLong() and 0xFF) shl 24)
    }

    // ==================== FROM .PKG FILE (preferred) ====================

    /**
     * Pack a .mpkg from a previously-extracted .pkg file's contents.
     *
     * This is the cleanest path: the user provides a real .pkg file from
     * Steam Workshop (downloaded via Steam client on PC), we extract all
     * the assets (project.json, scene.json, .tex files, materials, etc.)
     * and repackage them as a .mpkg with the correct binary format.
     *
     * For Video wallpapers (project.json has type:"video"), the wallpaper.mp4
     * file is included as-is.
     *
     * For Scene wallpapers (type:"scene"), all scene assets are preserved
     * as-is — including the original ETC2_RGBA8 .tex files (format=5) which
     * the WE Android native lib (libscenejni.so) handles natively via
     * OpenGL ES 3.0+ ETC2 texture upload.
     *
     * @param item workshop item metadata
     * @param pkgFile the source .pkg file
     * @param outputFile the destination .mpkg file
     * @return size in bytes, or 0 on failure
     */
    fun packFromPkg(
        item: WorkshopItemTyped,
        pkgFile: File,
        outputFile: File,
        installDir: File? = null,
    ): Long {
        WepWawetLogger.proxy("MpkgPacker.packFromPkg START",
            "item_id" to item.id,
            "title" to item.title.take(80),
            "pkg_file" to pkgFile.name,
            "pkg_size" to pkgFile.length(),
            "output" to outputFile.name)

        // Step 1: Read the .pkg file
        // Step 1: Read the .pkg file — use streaming for large .pkg files to avoid OOM
        val STREAM_THRESHOLD = 50_000_000L // 50MB
        val vfsData: Pair<List<PkgReader.VfsEntry>, Long>? = if (pkgFile.length() > STREAM_THRESHOLD) {
            PkgReader.readVfsTable(pkgFile)
        } else {
            null
        }
        val entries: MutableList<Pair<String, ByteArray>> = if (vfsData != null) {
            // Large .pkg — load ONLY entries that need modification (shaders, JSONs).
            // v0.6.1: CRITICAL OOM FIX — .tex entries are NOT loaded here. They are loaded
            // and converted ONE AT A TIME in the .tex processing phase below. This prevents
            // OOM when a .pkg has many large .tex files (e.g., 310 entries × 2MB avg = 620MB,
            // exceeding the 512MB heap). After conversion, each .tex is ~4-8x smaller
            // (ETC2+LZ4), so the converted results fit comfortably in memory.
            val (vfsEntries, dataSectionStart) = vfsData!!
            WepWawetLogger.proxy("MpkgPacker.packFromPkg: streaming mode (large .pkg)",
                "pkg_size" to pkgFile.length(),
                "vfs_entries" to vfsEntries.size,
                "loading_only_modified" to true,
                "tex_entries_deferred" to vfsEntries.count { it.path.endsWith(".tex") })
            val loaded = mutableListOf<Pair<String, ByteArray>>()
            for (vfs in vfsEntries) {
                // Load non-.tex entries that need modification (shaders, JSONs).
                // .tex entries are deferred to the one-at-a-time processing phase.
                val needsMod = vfs.path.endsWith(".frag") || vfs.path.endsWith(".vert") ||
                    vfs.path == "scene.json" || vfs.path == "project.json"
                if (needsMod) {
                    val data = PkgReader.readEntryData(pkgFile, dataSectionStart, vfs.offset, vfs.size)
                    if (data != null) loaded.add(vfs.path to data)
                }
            }
            loaded
        } else {
            // Small .pkg — load all entries (fits in heap)
            PkgReader.read(pkgFile).toMutableList()
        }
        if (entries.isEmpty()) {
            WepWawetLogger.proxyError("MpkgPacker.packFromPkg FAILED — no entries in .pkg",
                "pkg_file" to pkgFile.name)
            return 0L
        }

        // Step 2: Log entry count (type detection moved to after standalone files)
        WepWawetLogger.proxy("MpkgPacker.packFromPkg entries loaded",
            "entry_count" to entries.size)

        // Step 3: Log all entries (for debugging)
        for (entry in entries) {
            val (name, blob) = entry
            WepWawetLogger.proxyDebug("MpkgPacker.packFromPkg entry",
                "name" to name,
                "size" to blob.size)
        }

        // Step 3b: Include standalone files from the install directory
        // (project.json, preview.jpg, etc.) that are NOT inside the .pkg
        // but are needed by WE's .mpkg format.
        //
        // WE PC's transcoder (function 0x14003d1e0 in wallpaperui.exe) writes
        // multiple preview variants: preview.jpg, preview.gif, preview.png.
        // The .pkg side may have ANY of these — we now include all of them
        // (instead of just preview.jpg) for 100% parity with real WE PC.
        // (Verified from wallpaperui.exe strings: "preview.jpg", "preview.gif",
        // "preview.png" all appear, plus "nopreview.jpg" as the placeholder.)
        if (installDir != null && installDir.exists()) {
            val standaloneNames = listOf(
                "project.json",
                "preview.jpg", "preview.gif", "preview.png", "preview.jpeg", "preview.webp"
            )
            val existingNames = entries.map { it.first }.toSet()
            for (name in standaloneNames) {
                if (name in existingNames) continue // already in .pkg
                val standaloneFile = File(installDir, name)
                if (standaloneFile.exists() && standaloneFile.length() > 0) {
                    val blob = standaloneFile.readBytes()
                    entries.add(Pair(name, blob))
                    WepWawetLogger.proxy("MpkgPacker.packFromPkg added standalone file",
                        "name" to name,
                        "size" to blob.size,
                        "source" to standaloneFile.absolutePath)
                }
            }
        }

        // v0.7.4 (parity G6): the PC guarantees every .mpkg carries a preview
        // — it generates a 360 px thumbnail when possible and falls back to
        // its bundled nopreview.jpg placeholder otherwise (FUN_140018a50 +
        // the "nopreview.jpg" string in wallpaperui.exe). Degenerate local
        // projects that ship NO preview at all got none from WepWawet, so
        // the phone showed a blank card in its wallpaper list. Add the same
        // placeholder as a preview.jpg entry of last resort.
        val hasAnyPreview = entries.any { (name, _) ->
            name.equals("preview.jpg", true) || name.equals("preview.jpeg", true) ||
                name.equals("preview.gif", true) || name.equals("preview.png", true) ||
                name.equals("preview.webp", true)
        }
        if (!hasAnyPreview) {
            entries.add("preview.jpg" to buildNoPreviewJpeg())
            WepWawetLogger.proxy("MpkgPacker.packFromPkg: no preview present — added nopreview placeholder entry (G6)",
                "entry" to "preview.jpg")
        }

        // Step 2b: Detect wallpaper type from project.json (AFTER adding standalone files,
        // because project.json might only be in the install dir, not in the .pkg)
        val type = PkgReader.detectWallpaperType(entries)
        WepWawetLogger.proxy("MpkgPacker.packFromPkg type detected",
            "type" to type,
            "entry_count" to entries.size)

        // v0.7.4 (parity G7): the full set of VFS entry names, used by the
        // missing-texture scan below. In streaming mode `entries` only holds
        // the modified subset, so union in the .pkg's VFS paths.
        val allVfsNames: Set<String> = buildSet {
            addAll(entries.map { it.first })
            vfsData?.first?.forEach { add(it.path) }
        }

        // Step 3b-fix (v0.7.4, parity G7 — MISSING-SCENETEXTURE REMOVAL):
        // The PC removes scenetexture references whose file is absent from
        // the package (FUN_14003d0e0's entry-erasure loop around the
        // "scenetexture" comparison; each missing map entry is erased and
        // its "files"-manifest line dropped). WepWawet previously kept the
        // dangling reference, producing phantom-texture render artifacts
        // (yellow/black checkerboards) on the phone.
        //
        // Conservative, namespace-scoped rule — only a reference that:
        //   1. looks like a content file (known texture extension), AND
        //   2. is NOT present in the VFS, AND
        //   3. lives under a top-level directory that DOES exist in the VFS
        //      (e.g. "materials/foo.tex" when materials/ entries exist)
        // is treated as missing. WE built-in assets ("particle/halo_6",
        // "util/blur.png", …) ship inside the phone app, not the .pkg, so
        // they are never touched. A nulled slot is then filled with the
        // shader's declared default by fixSceneJsonTextureBindings below —
        // exactly the PC's end state for such materials.
        removeMissingSceneTextures(entries, allVfsNames)

        // Step 3b-fix: Fix scene.json — replace null texture bindings with
        // shader-declared defaults. On WE PC, null means "use default from
        // installation directory". On WE Android, null means "no texture",
        // which causes effects like X-ray to silently fail (the shader reads
        // sprite.ra which is 0 for a missing texture → blend=0 → no X-ray).
        //
        // The real WE Android .mpkg explicitly binds all texture slots,
        // including defaults like "particle/halo_6" for the X-ray cursor halo.
        // Our .pkg from Steam has null entries that work on PC but NOT on Android.
        //
        // Fix: parse scene.json, find all texture arrays with null entries,
        // look up the shader's default texture names, and replace nulls.
        fixSceneJsonTextureBindings(entries)

        // Step 3b-fix2: Fix shaders for OpenGL ES (GLSL 3.00 ES) compatibility.
        // WE PC uses DirectX (HLSL) which allows implicit int→float conversion.
        // GLSL ES does NOT — integer constants must have .0 suffix.
        // WE Android's automatic conversion has bugs and misses some constants.
        // Without fixing these, shaders fail to compile and effects are SILENTLY
        // REMOVED by WE Android (including the X-ray effect).
        fixShadersForMobile(entries)

        // Step 3c: Convert .tex files.
        //
        // v0.6.1: CRITICAL OOM FIX — In streaming mode, .tex entries are loaded from
        // the .pkg ONE AT A TIME, converted, and the converted (smaller) result is kept.
        // The original .tex bytes are freed after each conversion. This prevents OOM
        // when a .pkg has many large .tex files (e.g., 100+ textures × 2MB avg = 200MB+
        // just for original .tex data, plus RGBA decode buffers + ETC2 encode buffers
        // = well over 512MB heap).
        //
        // After conversion, each .tex is ~4-8x smaller (ETC2+LZ4 vs DXT/PNG), so the
        // converted results fit comfortably in memory for the subsequent streamPack phase.
        val processedEntries = mutableListOf<Pair<String, ByteArray>>()
        var texConverted = 0
        var texDxtConverted = 0
        var texPngConverted = 0
        var texRgbaConverted = 0  // v0.6.0: RGBA8888 → ETC2 conversions
        var totalOriginalTexBytes = 0L
        var totalConvertedTexBytes = 0L

        // Pass 1: Process non-.tex entries from `entries` (already loaded, small)
        for (entry in entries) {
            val (name, blob) = entry
            if (name.endsWith(".tex")) continue  // .tex handled in Pass 2
            processedEntries.add(entry)
        }

        // Pass 2: Process .tex entries ONE AT A TIME using a lazy sequence.
        // v0.6.1: CRITICAL OOM FIX — .tex entries are NEVER all loaded into memory at once.
        // A Kotlin Sequence is used to lazily load each .tex from .pkg (streaming mode) or
        // from the entries list (non-streaming mode). Each .tex is loaded, converted, and
        // the result added to processedEntries. The original bytes go out of scope after
        // each iteration, allowing GC to reclaim between iterations.
        val texSource: Sequence<Pair<String, ByteArray>> = if (vfsData != null) {
            // Streaming mode: lazily load each .tex from .pkg one at a time
            val (vfsEntries, dataSectionStart) = vfsData!!
            val texVfsEntries = vfsEntries.filter { it.path.endsWith(".tex") }
            WepWawetLogger.proxy("MpkgPacker.packFromPkg: streaming .tex entries (lazy, one at a time)",
                "tex_count" to texVfsEntries.size)
            texVfsEntries.asSequence().map { vfs ->
                val data = PkgReader.readEntryData(pkgFile, dataSectionStart, vfs.offset, vfs.size)
                if (data != null) vfs.path to data else null
            }.filterNotNull()
        } else {
            // Non-streaming mode: .tex entries are already in `entries`
            val texEntries = entries.filter { it.first.endsWith(".tex") }
            WepWawetLogger.proxy("MpkgPacker.packFromPkg: processing .tex entries from loaded list",
                "tex_count" to texEntries.size)
            texEntries.asSequence()
        }

        for (texEntry in texSource) {
            val (name, blob) = texEntry

            // ══════════════════════════════════════════════════════════════
            // v0.7.18 (PC-exact): the .tex routing replicates what the PC's
            // in-place `resourcecompiler64.exe -transcode -i X -o X -f ETC2
            // [-c force] -shrink N -maxmipmaps 1` does to EVERY .tex of the
            // transcoded project copy (wallpaperui 0x14003f884–0x14003f95d;
            // the exit code is IGNORED — a texture the tool fails on keeps
            // its original bytes and is packed unchanged):
            //
            //   • format 8 (RG88) / 9 (R8)               → copied verbatim
            //   • imageCount > 1 (animated/spritesheet)  → copied verbatim
            //   • TEXI flags & 0x20                      → copied verbatim
            //   • format 1 (RGB888) without -c force     → copied verbatim
            //   • formats the tool cannot handle (5 = ETC2, 11, 13, 14,
            //     20, anything else)                     → original bytes ship
            //   • handled: 0 (RGBA8888 incl. PNG/JPEG bodies), 4 (DXT5),
            //     6 (DXT3), 7 (DXT1), 12 (BC7):
            //       – DXT/BC sources  → ETC2_RGBA8 via etcpack, padded dims
            //       – format 0 sources → RGBA8888 (ETC2 only with -c force)
            //       – LZ4 only above 16383 bytes, kept only under 0.95 ratio
            //       – flags: bit 30 stripped; 0x08 OR-ed when stored raw
            //       – single mip; TEXS section preserved after the body
            // ══════════════════════════════════════════════════════════════
            val texInfo = PcTexTranscoder.parse(blob)
            val texiFormat = texInfo?.texiFormat ?: -1

            // ---- The verbatim-passthrough rules ----
            val pcCopyThrough = texInfo == null ||
                texiFormat == 8 || texiFormat == 9 ||
                (texInfo.imageCount > 1) ||
                (texInfo.flags and 0x20) != 0 ||
                (texiFormat == 1 && !forceEtc2Compression)

            // ---- The formats the PC tool errors on (original bytes ship) ----
            val pcErrorsOnIt = !pcCopyThrough &&
                texiFormat != 0 && texiFormat != 1 &&
                texiFormat !in PcTexTranscoder.DXT_SOURCE_FORMATS

            if (texInfo != null) {
                WepWawetLogger.proxyDebug("MpkgPacker.packFromPkg: .tex header",
                    "name" to name, "texi_format" to texiFormat,
                    "image_count" to texInfo.imageCount,
                    "flags" to "0x%02x".format(texInfo.flags),
                    "is_lz4" to texInfo.bodyIsLz4, "tex_size" to blob.size,
                    "pc_copy_through" to pcCopyThrough,
                    "pc_errors" to pcErrorsOnIt,
                    "etc2_source" to (texiFormat in PcTexTranscoder.DXT_SOURCE_FORMATS))
            }

            if (pcCopyThrough) {
                WepWawetLogger.proxy("MpkgPacker.packFromPkg: .tex passes through unchanged (PC rule)",
                    "name" to name, "texi_format" to texiFormat, "tex_size" to blob.size,
                    "reason" to when {
                        texInfo == null -> "unparseable header"
                        texiFormat == 8 || texiFormat == 9 -> "RG88/R8 (format $texiFormat)"
                        texInfo.imageCount > 1 -> "animated/multi-image (imageCount=${texInfo.imageCount})"
                        (texInfo.flags and 0x20) != 0 -> "TEXI flag 0x20"
                        texiFormat == 1 -> "RGB888 without -c force"
                        else -> "?"
                    })
                processedEntries.add(texEntry)
                continue
            }
            if (pcErrorsOnIt) {
                // The PC's tool exits nonzero on these; wallpaperui ignores
                // the exit code and the ORIGINAL file is what lands in the
                // .mpkg. Verified against a real PC-produced .mpkg: RG88/R8
                // masks and ETC2 textures ship byte-identical to the .pkg.
                WepWawetLogger.proxy("MpkgPacker.packFromPkg: .tex not handled by the PC tool — original bytes ship (PC rule)",
                    "name" to name, "texi_format" to texiFormat, "tex_size" to blob.size)
                processedEntries.add(texEntry)
                continue
            }

            // ---- The transcoded formats: 0, 1 (forced), 4, 6, 7, 12 ----
            val dxtFormat = DxtDecoder.formatForTexFormat(texiFormat)
            val converted: ByteArray?
            if (dxtFormat != null) {
                WepWawetLogger.proxy("MpkgPacker.packFromPkg: .tex is ${dxtFormat.displayName} (format=$texiFormat) — PC: decode → etcpack ETC2",
                    "name" to name, "tex_size" to blob.size, "is_lz4" to (texInfo?.bodyIsLz4 ?: false))
                converted = convertTexDxtToEtc2(blob, name)
                if (converted != null) {
                    processedEntries.add(Pair(name, converted))
                    texConverted++
                    texDxtConverted++
                    totalOriginalTexBytes += blob.size
                    totalConvertedTexBytes += converted.size
                } else {
                    processedEntries.add(texEntry)
                    WepWawetLogger.proxyWarn("MpkgPacker.packFromPkg: DXT/BC .tex transcode FAILED — using original (matches the PC's ignored exit code)",
                        "name" to name, "dxt_format" to dxtFormat.displayName)
                }
                continue
            }

            // ---- format 0 (RGBA8888, incl. PNG/JPEG bodies) / forced RGB888 ----
            // PC pipeline: decode to RGBA at the image's intrinsic size
            // (PNG/JPEG body decoded, LZ4 body decompressed, raw body as-is),
            // apply -shrink, then RGBA8888 out (ETC2 only with -c force).
            run {
                val result = convertFormat0TexPc(blob, name)
                if (result != null) {
                    processedEntries.add(Pair(name, result))
                    texConverted++
                    if (PcTexTranscoder.parse(result)?.texiFormat == 5) texPngConverted++
                    else texRgbaConverted++
                    totalOriginalTexBytes += blob.size
                    totalConvertedTexBytes += result.size
                } else {
                    processedEntries.add(texEntry)
                    WepWawetLogger.proxyWarn("MpkgPacker.packFromPkg: format=0 .tex conversion FAILED — using original (matches the PC's ignored exit code)",
                        "name" to name, "texi_format" to texiFormat, "is_lz4" to (texInfo?.bodyIsLz4 ?: false))
                }
            }
        }
        // v0.6.1: Always replace entries with processedEntries.
        // processedEntries contains non-.tex entries (Pass 1) + converted .tex entries (Pass 2).
        // In streaming mode, `entries` doesn't have .tex entries (they were loaded one-at-a-time
        // in Pass 2), so we MUST replace entries with processedEntries to have the complete list.
        entries.clear()
        entries.addAll(processedEntries)
        if (texConverted > 0) {
            WepWawetLogger.proxy("MpkgPacker.packFromPkg: converted .tex files summary",
                "tex_converted_count" to texConverted,
                "tex_dxt_converted" to texDxtConverted,
                "tex_png_converted" to texPngConverted,
                "total_original_tex_bytes" to totalOriginalTexBytes,
                "total_converted_tex_bytes" to totalConvertedTexBytes,
                "size_increase_bytes" to (totalConvertedTexBytes - totalOriginalTexBytes),
                "size_increase_mb" to "%.1f".format((totalConvertedTexBytes - totalOriginalTexBytes) / 1024.0 / 1024.0))
        }

        // Step 3d: Transcode exotic videos to .mp4 with H.264 (100% parity with WE PC)
        //
        // v0.7.4 (parity G2 — EXOTIC CONTAINERS): the PC re-encodes EVERY
        // video entry that is not already MP4 before packing — Media
        // Foundation for H264/H265/VP8/VP9/VP10/WMV3/YUY2 (FUN_140213e20
        // topology GUIDs) and the bundled ffmpeg for the fallback codec
        // identifiers `ffmpeg_avi_raw` / `ffmpeg_avi_yuv420` /
        // `ffmpeg_avi_yuv422` / `ffmpeg_gif` / `ffmpeg_mp4_h264` /
        // `ffmpeg_mp4_h265` / `ffmpeg_mp4_vp8` / `ffmpeg_mp4_vp9`
        // (FUN_1400e21d0, strings verified in wallpaperui.exe 2.8.0.42).
        // The phone's VideoDrawable only understands MP4/WebM-class
        // content delivered as `mp4_h264`.
        //
        // WepWawet previously converted ONLY .webm entries — a wallpaper
        // whose .pkg carried a .wmv/.avi/.gif video shipped those bytes
        // untouched and rendered black on the phone. This pass now routes
        // every non-.mp4 video entry through the matching on-device
        // converter:
        //   .webm/.mkv/.wmv/.flv/.ts/.m4v/.mov/.3gp → VideoTranscoder
        //       (MediaExtractor + MediaCodec — WMV/FLV succeed on devices
        //        that ship the vendor demuxer)
        //   .avi → AviToMp4Transcoder (custom RIFF demux; MJPG/raw-DIB/
        //        H264-in-AVI/XVID via MediaCodec)
        //   .gif → GifToMp4Transcoder (Movie frame decode + H264 encode)
        // and keeps the same rename + project.json rewrite the WebM path
        // has always used. On failure the original entry is kept (the
        // current, logged behavior) — better a loud log than a broken
        // silent rename.
        // v0.7.4: in-pkg video entries whose VFS path was replaced by a
        // transcoded .mp4 (streaming mode only) — the pack step filters these
        // out so the .mpkg does not contain BOTH the original and the mp4.
        var inPkgDroppedVfsPaths: Set<String> = emptySet()

        if (type == "video" || type == "unknown") {
            val videoExtensions = listOf(
                ".webm", ".mkv", ".wmv", ".avi", ".flv", ".ts",
                ".m4v", ".mov", ".3gp", ".gif",
            )

            // v0.7.4: in STREAMING mode the video entries are NOT in `entries`
            // (only shaders/JSONs were loaded), so collect the work list from
            // the VFS table and stream each source to a temp file instead of
            // holding it in memory. Non-streaming mode uses the loaded list.
            // Sources above MAX_IN_PKG_VIDEO_BYTES are left untouched — reading
            // them back (or holding the re-encoded result) risks OOM for
            // multi-hundred-MB videos, and matches the previous behavior.
            val MAX_IN_PKG_VIDEO_BYTES = 256L * 1024 * 1024
            val droppedVfsPaths = mutableSetOf<String>()

            data class PendingVideo(val name: String, val vfs: PkgReader.VfsEntry?)
            val pendingVideos = mutableListOf<PendingVideo>()
            if (vfsData != null) {
                val (vfsEntriesForVideo, _) = vfsData!!
                for (vfs in vfsEntriesForVideo) {
                    if (videoExtensions.any { vfs.path.endsWith(it, ignoreCase = true) }) {
                        pendingVideos.add(PendingVideo(vfs.path, vfs))
                    }
                }
            } else {
                for ((name, _) in entries) {
                    if (videoExtensions.any { name.endsWith(it, ignoreCase = true) }) {
                        pendingVideos.add(PendingVideo(name, null))
                    }
                }
            }

            if (pendingVideos.isNotEmpty()) {
                WepWawetLogger.proxy("MpkgPacker.packFromPkg: found non-MP4 video(s) — will transcode to .mp4",
                    "count" to pendingVideos.size,
                    "files" to pendingVideos.map { it.name },
                    "streaming_mode" to (vfsData != null))
                for (pending in pendingVideos) {
                    val videoName = pending.name
                    val mp4Name = videoName.substringBeforeLast('.') + ".mp4"
                    val sourceExt = videoName.substringAfterLast('.', "bin").lowercase()

                    val tempSource = File.createTempFile("wepwawet_transcode_", ".$sourceExt")
                    val tempMp4 = File.createTempFile("wepwawet_transcode_", ".mp4")
                    try {
                        // Materialize the source bytes:
                        //  - loaded list mode: take the entry's bytes directly
                        //  - streaming mode: stream the VFS entry to the temp file
                        val videoBytes: ByteArray? = if (pending.vfs == null) {
                            entries.firstOrNull { it.first == videoName }?.second
                        } else if (pending.vfs.size > MAX_IN_PKG_VIDEO_BYTES) {
                            WepWawetLogger.proxyWarn("MpkgPacker.packFromPkg: in-pkg video too large to transcode — passing through unchanged",
                                "name" to videoName,
                                "size" to pending.vfs.size,
                                "cap" to MAX_IN_PKG_VIDEO_BYTES)
                            null
                        } else {
                            val (_, dataStart) = vfsData!!
                            java.io.FileOutputStream(tempSource).use { fos ->
                                PkgReader.streamEntryData(pkgFile, dataStart,
                                    pending.vfs.offset, pending.vfs.size.toLong(), fos)
                            }
                            tempSource.readBytes()
                        }

                        if (videoBytes != null) {
                            WepWawetLogger.proxy("MpkgPacker.packFromPkg: transcoding → .mp4",
                                "source_name" to videoName,
                                "mp4_name" to mp4Name,
                                "source_size" to videoBytes.size,
                                "kind" to sourceExt)
                            tempSource.writeBytes(videoBytes)
                            val transcodeOk = when (sourceExt) {
                                "avi" -> AviToMp4Transcoder.transcode(tempSource, tempMp4)
                                "gif" -> GifToMp4Transcoder.transcode(tempSource, tempMp4)
                                else -> VideoTranscoder.transcodeToMp4H264(tempSource, tempMp4)
                            }
                            if (transcodeOk && tempMp4.exists() && tempMp4.length() > 0) {
                                val mp4Bytes = tempMp4.readBytes()
                                if (pending.vfs != null) {
                                    // Streaming mode: the converted entry becomes a
                                    // "standalone" modified entry (its .mp4 name is not
                                    // in the VFS table), and the original VFS path is
                                    // dropped from the pack list below.
                                    entries.add(mp4Name to mp4Bytes)
                                    droppedVfsPaths.add(videoName)
                                } else {
                                    val srcIndex = entries.indexOfFirst { it.first == videoName }
                                    if (srcIndex >= 0) entries[srcIndex] = mp4Name to mp4Bytes
                                }
                                WepWawetLogger.proxy("MpkgPacker.packFromPkg: video transcoded to .mp4",
                                    "source_name" to videoName,
                                    "mp4_name" to mp4Name,
                                    "source_size" to videoBytes.size,
                                    "mp4_size" to mp4Bytes.size,
                                    "size_ratio" to "%.2fx".format(mp4Bytes.size.toDouble() / videoBytes.size))
                                // Also update project.json's "file" field if it references the source
                                val pjIndex = entries.indexOfFirst { it.first == "project.json" }
                                if (pjIndex >= 0) {
                                    val pjStr = String(entries[pjIndex].second, Charsets.UTF_8)
                                    val updatedPj = pjStr.replace(videoName, mp4Name)
                                    if (updatedPj != pjStr) {
                                        entries[pjIndex] = "project.json" to updatedPj.toByteArray(Charsets.UTF_8)
                                        WepWawetLogger.proxy("MpkgPacker.packFromPkg: updated project.json file field",
                                            "old" to videoName, "new" to mp4Name)
                                    }
                                }
                            } else {
                                WepWawetLogger.proxyWarn("MpkgPacker.packFromPkg: transcode FAILED — keeping original as-is",
                                    "source_name" to videoName,
                                    "note" to "the entry keeps its original container; the phone " +
                                        "may not be able to play it (see transcoder logs above)")
                            }
                        }
                    } finally {
                        tempSource.delete()
                        tempMp4.delete()
                    }
                }
            }

            // v0.7.4: expose the dropped VFS paths (streaming mode) to the
            // pack step so the original .webm/.avi entry is not also copied.
            inPkgDroppedVfsPaths = droppedVfsPaths
        }

        // Step 3e: project.json — add workshopurl, PC-style.
        //
        // v0.7.16 (byte-exact): the PC (wallpaperui.exe 0x1400416b8, gated on
        // the workshop item id being present) appends "workshopurl" as the
        // LAST key of project.json, re-serialized by WE's own JSON writer —
        // tab indent, `"key" : value` spacing, CRLF line endings. Because the
        // .pkg's project.json was written by that same writer, a TEXTUAL
        // insertion reproduces the PC output byte-for-byte:
        //
        //      \t"version" : 0\r\n}            (original tail)
        //   → \t"version" : 0,\r\n\t"workshopurl" : "<url>"\r\n}
        //
        // (verified against the WORKING .mpkg: 1378 bytes, identical).
        // The previous Gson pretty-print rewrite (2-space indent, LF, no
        // space before the colon) produced a DIFFERENT byte stream than the
        // PC for every wallpaper.
        for (i in entries.indices) {
            val (name, blob) = entries[i]
            if (name == "project.json") {
                try {
                    val jsonText = String(blob, Charsets.UTF_8)
                    val url = "https://steamcommunity.com/sharedfiles/filedetails/?id=${item.id}"
                    val rewrittenText = PcPacking.addWorkshopUrl(jsonText, url)
                    if (rewrittenText != jsonText) {
                        val rewritten = rewrittenText.toByteArray(Charsets.UTF_8)
                        entries[i] = name to rewritten
                        WepWawetLogger.proxy("MpkgPacker.packFromPkg: added workshopurl to project.json (PC textual format)",
                            "original_size" to blob.size, "rewritten_size" to rewritten.size)
                    }
                } catch (e: Exception) {
                    WepWawetLogger.proxyWarn("MpkgPacker.packFromPkg: failed to add workshopurl",
                        "exception" to e.javaClass.name)
                }
                break
            }
        }

        // v0.7.16: drop sounds — the PC deletes every file under a "sounds"
        // directory before packing (wallpaperui.exe 0x14003ffdd: path contains
        // "sounds" → std::filesystem::remove). Sound files are not part of the
        // mobile package.
        val soundsBefore = entries.size
        entries.removeAll { (n, _) -> PcPacking.isSoundsPath(n) }
        if (entries.size != soundsBefore) {
            WepWawetLogger.proxy("MpkgPacker.packFromPkg: dropped sounds entries (PC parity)",
                "before" to soundsBefore, "after" to entries.size)
        }

        // Sort entries the way the PC packs them: the PC walks the transcoded
        // temp directory with a depth-first recursive enumeration, which on
        // NTFS yields entries in CASE-INSENSITIVE alphabetical order —
        // "materials/masks/…" therefore sorts BEFORE "materials/MSK12003…"
        // (M-a < M-s in the NTFS upcase table). A plain case-sensitive sort
        // puts MSK12003 first and diverges from every real PC .mpkg.
        // (Verified: the WORKING .mpkg's 52-entry order is exactly
        // sorted(entries, key=str.lower).)
        entries.sortWith(compareBy(PcPacking.PC_ENTRY_ORDER) { it.first })
        WepWawetLogger.proxy("MpkgPacker.packFromPkg: entries sorted (case-insensitive, PC order)",
            "entry_count" to entries.size)

        // Step 4: Pack the entries into a .mpkg
        //
        // v0.7.4 (parity G1): the .mpkg magic is selected per transfer —
        // PKGM0014 for video wallpapers, min(deviceVersion, 20) for scenes —
        // exactly like the PC packer (see [negotiateMagic]). The container
        // layout is identical for every version; only the 8-char magic
        // string differs (FUN_14020a660 writes it verbatim).
        val magic = negotiateMagic(type)
        WepWawetLogger.proxy("MpkgPacker.packFromPkg: mpkg magic negotiated",
            "magic" to magic,
            "wallpaper_type" to type,
            "device_mpkg_version" to WePairingServer.negotiatedMpkgVersion)
        val totalSize: Long
        if (vfsData != null) {
            // Streaming pack — writes modified entries from memory, streams
            // unmodified entries directly from .pkg to .mpkg (no OOM)
            val (vfsEntries, dataSectionStart) = vfsData!!
            // v0.7.4 (G2): drop in-pkg video entries that were replaced by
            // a transcoded .mp4 — the .mp4 was added as a standalone entry.
            val effectiveVfsEntries = if (inPkgDroppedVfsPaths.isEmpty()) vfsEntries
                else vfsEntries.filter { it.path !in inPkgDroppedVfsPaths }
            if (effectiveVfsEntries.size != vfsEntries.size) {
                WepWawetLogger.proxy("MpkgPacker.packFromPkg: filtered replaced VFS video entries",
                    "before" to vfsEntries.size,
                    "after" to effectiveVfsEntries.size,
                    "dropped" to inPkgDroppedVfsPaths)
            }
            val modifiedMap = entries.associate { it.first to it.second }
            totalSize = streamPack(pkgFile, dataSectionStart, effectiveVfsEntries, modifiedMap, outputFile, magic)
            // Skip verifyMpkg for large files (it loads entire .mpkg into memory)
        } else {
            // Standard pack — all entries in memory
            totalSize = pack(entries, outputFile, magic)
            verifyMpkg(outputFile, entries, magic)
        }

        WepWawetLogger.proxy("MpkgPacker.packFromPkg COMPLETE",
            "output" to outputFile.name,
            "mpkg_size" to totalSize,
            "entry_count" to entries.size,
            "type" to type,
            "magic" to magic,
            "streaming" to (vfsData != null))
        return totalSize
    }

    // ==================== FROM LOCAL IMAGE (Scene) ====================

    /**
     * Pack a Scene-type .mpkg from an image file (JPEG/PNG).
     *
     * Builds a minimal Scene wallpaper with:
     *   - project.json (type:"scene", file:"scene.json")
     *   - scene.json (camera + one fullscreen image quad)
     *   - models/wepwawet_image.json
     *   - materials/wepwawet_image.json (uses "genericimage2" shader)
     *   - materials/wepwawet_image.tex (RGBA8888 format=0, uncompressed)
     *   - preview.jpg
     *
     * The texture format is RGBA8888 (format=0). The WE Android native lib
     * (libscenejni.so) supports this format directly.
     *
     * BRIGHTNESS FIX (v2):
     *   - schemecolor = texture's average RGBA color (matching real WE .mpkg)
     *   - clearcolor = average color dimmed to 30% (provides subtle ambient)
     *   - blending = "normal" (not "translucent" — translucent can cause additive)
     *   - depthtest = "enabled" (matches real WE samples)
     *
     * @param item workshop item metadata
     * @param imageFile source JPEG/PNG
     * @param outputFile destination .mpkg file
     * @param maxTextureDim cap texture dimension (default 1920)
     * @return size in bytes
     */
    fun packSceneWallpaper(
        item: WorkshopItemTyped,
        imageFile: File,
        outputFile: File,
        maxTextureDim: Int = 1920,
    ): Long {
        WepWawetLogger.proxy("MpkgPacker.packSceneWallpaper START",
            "item_id" to item.id,
            "title" to item.title.take(80),
            "image_file" to imageFile.name,
            "image_size_bytes" to imageFile.length(),
            "max_texture_dim" to maxTextureDim,
            "output" to outputFile.name)

        // Step 1: Decode the source image to RGBA
        WepWawetLogger.proxyDebug("MpkgPacker.packSceneWallpaper decoding image",
            "path" to imageFile.absolutePath)
        val origBitmap = BitmapFactory.decodeFile(imageFile.absolutePath)
            ?: throw IllegalArgumentException("Cannot decode image: ${imageFile.absolutePath}")
        WepWawetLogger.proxy("MpkgPacker.packSceneWallpaper image decoded",
            "src_width" to origBitmap.width,
            "src_height" to origBitmap.height,
            "config" to (origBitmap.config?.name ?: "null"))

        // Downsample if too large (keep aspect ratio, cap max dim)
        var srcW = origBitmap.width
        var srcH = origBitmap.height
        val maxDim = maxOf(srcW, srcH)
        var bitmap = origBitmap
        if (maxDim > maxTextureDim) {
            val scale = maxTextureDim.toFloat() / maxDim
            val newW = (srcW * scale).toInt().coerceAtLeast(4)
            val newH = (srcH * scale).toInt().coerceAtLeast(4)
            WepWawetLogger.proxy("MpkgPacker.packSceneWallpaper downsampling",
                "scale" to scale,
                "new_width" to newW,
                "new_height" to newH,
                "reason" to "max_dim $maxDim exceeds cap $maxTextureDim")
            bitmap = Bitmap.createScaledBitmap(origBitmap, newW, newH, true)
            origBitmap.recycle()
            srcW = newW
            srcH = newH
        }

        // Pad to multiple of 4 (some texture formats require 4x4 block alignment)
        val texW = ((srcW + 3) / 4) * 4
        val texH = ((srcH + 3) / 4) * 4
        if (texW != srcW || texH != srcH) {
            WepWawetLogger.proxyDebug("MpkgPacker.packSceneWallpaper padding to 4x4 block",
                "from" to "${srcW}x${srcH}",
                "to" to "${texW}x${texH}")
            val padded = Bitmap.createBitmap(texW, texH, android.graphics.Bitmap.Config.ARGB_8888)
            val canvas = android.graphics.Canvas(padded)
            canvas.drawColor(android.graphics.Color.TRANSPARENT)
            canvas.drawBitmap(bitmap, 0f, 0f, null)
            bitmap.recycle()
            bitmap = padded
        }
        WepWawetLogger.proxy("MpkgPacker.packSceneWallpaper texture dimensions",
            "tex_width" to texW, "tex_height" to texH,
            "img_width" to srcW, "img_height" to srcH)

        // Convert bitmap to RGBA bytes (row-major, top-down)
        WepWawetLogger.proxyDebug("MpkgPacker.packSceneWallpaper extracting pixels")
        val pixelBuf = java.nio.IntBuffer.allocate(texW * texH)
        bitmap.getPixels(pixelBuf.array(), 0, texW, 0, 0, texW, texH)
        bitmap.recycle()
        val rgbaBytes = ByteArray(texW * texH * 4)
        for (i in 0 until texW * texH) {
            val argb = pixelBuf[i]
            rgbaBytes[i * 4 + 0] = ((argb shr 16) and 0xFF).toByte()  // R
            rgbaBytes[i * 4 + 1] = ((argb shr 8) and 0xFF).toByte()   // G
            rgbaBytes[i * 4 + 2] = (argb and 0xFF).toByte()           // B
            rgbaBytes[i * 4 + 3] = ((argb shr 24) and 0xFF).toByte()  // A
        }
        WepWawetLogger.proxy("MpkgPacker.packSceneWallpaper RGBA bytes ready",
            "rgba_bytes" to rgbaBytes.size,
            "expected" to (texW * texH * 4))

        // Step 2: Compute average color (for schemecolor)
        val avgColor = computeAverageColor(rgbaBytes)
        val avgR = (avgColor and 0xFF)
        val avgG = ((avgColor shr 8) and 0xFF)
        val avgB = ((avgColor shr 16) and 0xFF)
        val avgA = ((avgColor shr 24) and 0xFF)
        val avgColorStr = "%.3f %.3f %.3f".format(avgR / 255.0f, avgG / 255.0f, avgB / 255.0f)
        WepWawetLogger.proxy("MpkgPacker.packSceneWallpaper average color computed",
            "r" to avgR, "g" to avgG, "b" to avgB, "a" to avgA,
            "as_float" to avgColorStr,
            "as_hex" to "0x%08x".format(avgColor))

        // Step 3: Build the .tex file with format=0 (RGBA8888)
        WepWawetLogger.proxyDebug("MpkgPacker.packSceneWallpaper building .tex file")
        val texBytes = buildTexRgba8888(texW, texH, srcW, srcH, avgColor, rgbaBytes)
        WepWawetLogger.proxy("MpkgPacker.packSceneWallpaper .tex file built",
            "tex_size" to texBytes.size,
            "tex_header_size" to 91,
            "pixel_data_size" to rgbaBytes.size)

        // Step 4: Build all JSON files
        val texBase = "wepwawet_image"
        WepWawetLogger.proxyDebug("MpkgPacker.packSceneWallpaper building JSONs",
            "tex_base" to texBase)
        val projectJson = buildSceneProjectJson(item, avgColorStr)
        val sceneJson = buildSceneJson(texW, texH, texBase, avgColorStr)
        val modelJson = buildModelJson(texBase)
        val materialJson = buildMaterialJson(texBase)  // uses "normal" blending
        val previewBytes = buildPreviewJpeg(imageFile)
        WepWawetLogger.proxy("MpkgPacker.packSceneWallpaper JSONs built",
            "project_json_size" to projectJson.size,
            "scene_json_size" to sceneJson.size,
            "model_json_size" to modelJson.size,
            "material_json_size" to materialJson.size,
            "preview_size" to previewBytes.size)

        // Step 5: Assemble .mpkg entries
        val entries = listOf(
            "project.json" to projectJson,
            "scene.json" to sceneJson,
            "models/$texBase.json" to modelJson,
            "materials/$texBase.json" to materialJson,
            "materials/$texBase.tex" to texBytes,
            "preview.jpg" to previewBytes,
        )

        WepWawetLogger.proxy("MpkgPacker.packSceneWallpaper packing entries",
            "entry_count" to entries.size,
            "total_bytes" to entries.sumOf { it.second.size })
        // v0.7.4 (parity G1): scene wallpapers get min(deviceVersion, 20) —
        // identical to PKGM0020 until a phone that reports an older .mpkg
        // version pairs with us.
        val sceneMagic = negotiateMagic("scene")
        val totalSize = pack(entries, outputFile, sceneMagic)

        WepWawetLogger.proxy("MpkgPacker.packSceneWallpaper COMPLETE",
            "output" to outputFile.name,
            "mpkg_size" to totalSize,
            "tex_dims" to "${texW}x${texH}",
            "schemecolor" to avgColorStr,
            "magic" to sceneMagic)

        verifyMpkg(outputFile, entries, sceneMagic)
        return totalSize
    }

    /**
     * Build a .tex file in WE format using RGBA8888 (format=0), uncompressed.
     */
    private fun buildTexRgba8888(
        texW: Int, texH: Int, imgW: Int, imgH: Int,
        avgColor: Int, rgbaBytes: ByteArray,
    ): ByteArray {
        val out = ByteArrayOutputStream(91 + rgbaBytes.size)
        // TEXV0005 + null
        out.write("TEXV0005\u0000".toByteArray(Charsets.US_ASCII))
        // TEXI0001 + null
        out.write("TEXI0001\u0000".toByteArray(Charsets.US_ASCII))
        // TEXI payload: 7x u32 LE
        writeLeInt(out, 0)  // format = RGBA8888
        writeLeInt(out, 2)  // flags = ClampUVs
        writeLeInt(out, texW)
        writeLeInt(out, texH)
        writeLeInt(out, imgW)
        writeLeInt(out, imgH)
        writeLeInt(out, avgColor)
        // TEXB0004 + null
        out.write("TEXB0004\u0000".toByteArray(Charsets.US_ASCII))
        // TEXB container: imageCount=1, format=-1, isVideoMp4=0
        writeLeInt(out, 1)
        writeLeInt(out, -1)  // FIF_UNKNOWN
        writeLeInt(out, 0)
        // mipmap_count = 1
        writeLeInt(out, 1)
        // mipmap entry (V3 format): width, height, IsLZ4=0, DecompressedSize, byteCount, data
        writeLeInt(out, texW)
        writeLeInt(out, texH)
        writeLeInt(out, 0)  // IsLZ4 = 0 (uncompressed)
        writeLeInt(out, rgbaBytes.size)  // DecompressedSize
        writeLeInt(out, rgbaBytes.size)  // byteCount
        out.write(rgbaBytes)
        return out.toByteArray()
    }

    /**
     * Compute the average RGBA color of an RGBA byte buffer.
     * Returns the color packed as 0xAA BB GG RR (little-endian u32).
     */
    private fun computeAverageColor(rgbaBytes: ByteArray): Int {
        var totalR = 0L
        var totalG = 0L
        var totalB = 0L
        var totalA = 0L
        var count = 0
        val step = (rgbaBytes.size / 4 / 4096).coerceAtLeast(1) * 4
        var i = 0
        // v0.7.4: bound check — if the buffer size isn't a multiple of 4 (corrupt
        // RGBA input), the last iteration read i+1..i+3 past the array end and
        // threw ArrayIndexOutOfBoundsException.
        while (i + 3 < rgbaBytes.size) {
            val r = rgbaBytes[i].toInt() and 0xFF
            val g = rgbaBytes[i+1].toInt() and 0xFF
            val b = rgbaBytes[i+2].toInt() and 0xFF
            val a = rgbaBytes[i+3].toInt() and 0xFF
            if (a > 0) {
                totalR += r
                totalG += g
                totalB += b
                totalA += a
                count++
            }
            i += step
        }
        if (count == 0) return 0xFF000000.toInt()
        val avgR = (totalR / count).toInt()
        val avgG = (totalG / count).toInt()
        val avgB = (totalB / count).toInt()
        val avgA = (totalA / count).toInt()
        return (avgA shl 24) or (avgB shl 16) or (avgG shl 8) or avgR
    }

    // ==================== FROM LOCAL VIDEO (Video) ====================

    /**
     * Pack a Video-type .mpkg from an MP4 file.
     *
     * Builds a Video wallpaper:
     *   - project.json (type:"video", file:"wallpaper.mp4")
     *   - wallpaper.mp4 (the source video file, as-is)
     *   - preview.jpg (extracted from the video at t=1s, or black)
     *
     * The WE Android app's VideoDrawable (via MediaPlayer + MPKGVideoReader)
     * plays the MP4 directly from inside the .mpkg VFS — same path as a real
     * WE PC Video wallpaper.
     *
     * @param item workshop item metadata
     * @param videoFile the source MP4 file
     * @param outputFile destination .mpkg file
     * @param previewFile optional preview image (extracted from video)
     * @return size in bytes
     */
    fun packVideoWallpaper(
        item: WorkshopItemTyped,
        videoFile: File,
        outputFile: File,
        previewFile: File? = null,
    ): Long {
        WepWawetLogger.proxy("MpkgPacker.packVideoWallpaper START",
            "item_id" to item.id,
            "title" to item.title.take(80),
            "video_file" to videoFile.name,
            "video_size" to videoFile.length(),
            "output" to outputFile.name)

        require(videoFile.exists()) { "videoFile not found: ${videoFile.absolutePath}" }

        // === v0.7.4 (parity G2): EXOTIC CONTAINERS — transcode to MP4 first ===
        // The PC pipeline re-encodes every non-MP4 video wallpaper before it
        // ever reaches the packer (`ffmpeg_gif` / `ffmpeg_avi_*` /
        // `ffmpeg_mp4_h264` identifiers in FUN_1400e21d0 of wallpaperui.exe
        // 2.8.0.42). The phone's VideoDrawable plays MP4/H.264 — a bare
        // .webm/.wmv/.avi/.gif packed as "wallpaper.mp4" renders black.
        // We therefore sniff the container and run the matching on-device
        // converter before packing; the pack below then streams a real MP4.
        val container = VideoContainerSniffer.sniff(videoFile)
        val effectiveVideoFile: File = when {
            !VideoContainerSniffer.needsTranscode(container) -> videoFile
            else -> {
                WepWawetLogger.proxy("MpkgPacker.packVideoWallpaper: source is ${VideoContainerSniffer.label(container)} — transcoding to MP4 first",
                    "container" to VideoContainerSniffer.label(container),
                    "size" to videoFile.length())
                val transcoded = File.createTempFile("wepwawet_video_", ".mp4",
                    videoFile.parentFile)
                val ok = when (container) {
                    VideoContainerSniffer.Kind.GIF ->
                        GifToMp4Transcoder.transcode(videoFile, transcoded)
                    VideoContainerSniffer.Kind.AVI ->
                        AviToMp4Transcoder.transcode(videoFile, transcoded)
                    else -> VideoTranscoder.transcodeToMp4H264(videoFile, transcoded)
                }
                if (!ok || !transcoded.exists() || transcoded.length() == 0L) {
                    runCatching { transcoded.delete() }
                    WepWawetLogger.proxyError("MpkgPacker.packVideoWallpaper: transcode FAILED",
                        "container" to VideoContainerSniffer.label(container),
                        "video_file" to videoFile.name,
                        "note" to "see transcoder logs above for the per-codec reason")
                    throw IllegalArgumentException(
                        "Cannot convert ${VideoContainerSniffer.label(container)} video to MP4 " +
                            "on this device — see logs (the wallpaper was NOT packed)")
                }
                WepWawetLogger.proxy("MpkgPacker.packVideoWallpaper: transcode OK",
                    "container" to VideoContainerSniffer.label(container),
                    "original_size" to videoFile.length(),
                    "mp4_size" to transcoded.length())
                transcoded
            }
        }

        // === v0.4.10: DO NOT load the entire video into memory!
        // Previously: `val videoBytes = videoFile.readBytes()` — this tries
        // to allocate 710MB for a 710MB video, but Android's heap limit is
        // ~512MB, causing OutOfMemoryError:
        //   "Failed to allocate a 743933128 byte allocation with 25070256
        //    free bytes and 504MB until OOM"
        // Fix: Stream the video file in 1MB chunks to the output .mpkg,
        // keeping only the small entries (project.json, preview.jpg) in
        // memory. This reduces peak memory from ~710MB to ~2MB. ===
        val videoSize = effectiveVideoFile.length()
        WepWawetLogger.proxy("MpkgPacker.packVideoWallpaper video size (streamed, not loaded)",
            "video_size" to videoSize,
            "video_file_path" to effectiveVideoFile.absolutePath,
            "container" to VideoContainerSniffer.label(container),
            "streaming_mode" to true)

        // Build preview.jpg
        // v0.7.4 (parity G6): the PC generates a 360 px preview thumbnail from
        // the video (FUN_140018a50 — the preview thumbnailer, 360 px) and
        // falls back to its bundled nopreview.jpg placeholder when nothing
        // can be extracted. We now do the same: MediaMetadataRetriever frame
        // at t=1 s scaled to a 360 px long edge, then the placeholder.
        val previewBytes: ByteArray = if (previewFile != null && previewFile.exists()) {
            WepWawetLogger.proxyDebug("MpkgPacker.packVideoWallpaper using provided preview",
                "preview_file" to previewFile.name,
                "preview_size" to previewFile.length())
            previewFile.readBytes()
        } else {
            val generated = generateVideoPreviewJpeg(effectiveVideoFile)
            if (generated != null) {
                WepWawetLogger.proxy("MpkgPacker.packVideoWallpaper generated 360px preview from video",
                    "preview_size" to generated.size)
                generated
            } else {
                WepWawetLogger.proxy("MpkgPacker.packVideoWallpaper no preview extractable — using nopreview placeholder")
                buildNoPreviewJpeg()
            }
        }

        val projectJson = buildVideoProjectJson(item)

        // === Stream the .mpkg manually instead of using pack() ===
        // The pack() function takes List<Pair<String, ByteArray>> which
        // requires ALL data in memory. For a 710MB video, that OOMs.
        // Instead, we compute the VFS table entries (with correct offsets
        // and sizes), write the header + VFS table, then stream the
        // video file in 1MB chunks.
        val HEADER_MARKER = 8
        // v0.7.4 (parity G1): the PC packer forces version 14 for EVERY
        // video wallpaper (FUN_14003d0e0: `if (isVideo) version = 0xe;`),
        // regardless of the phone's negotiated version — the VideoDrawable
        // container format that every phone generation parses.
        val MAGIC = negotiateMagic("video")
        WepWawetLogger.proxy("MpkgPacker.packVideoWallpaper mpkg magic",
            "magic" to MAGIC,
            "device_mpkg_version" to WePairingServer.negotiatedMpkgVersion)

        // Small entries (project.json + preview.jpg) — keep in memory
        val smallEntries = listOf(
            "project.json" to projectJson,
            "preview.jpg" to previewBytes,
        )

        // VFS table: for each entry: u32 LE name_len, name bytes, u32 LE offset, u32 LE size
        // Order: project.json, wallpaper.mp4, preview.jpg
        val headerSize = 4 + 8 + 4  // header_marker + magic + entry_count
        val nameBytes = { name: String -> name.toByteArray(Charsets.UTF_8) }
        val vfsTableSize = smallEntries.sumOf { 4 + nameBytes(it.first).size + 8 } +
            4 + nameBytes("wallpaper.mp4").size + 8
        val dataSectionStart = headerSize + vfsTableSize

        // v0.7.4 fix: compute offsets/sizes as LONG. The old code called
        // videoSize.toInt() — for a video ≥ 2 GiB that's a NEGATIVE Int, so
        // the VFS entry size and the running cursor wrapped and the table was
        // silently corrupted. The wire format is u32, so anything ≥ 4 GiB is
        // unrepresentable and now fails loudly instead of corrupting.
        var cursor = 0L
        val vfsEntries = mutableListOf<Triple<String, Long, Long>>()
        for ((name, blob) in smallEntries) {
            vfsEntries.add(Triple(name, cursor, blob.size.toLong()))
            cursor += blob.size
        }
        vfsEntries.add(Triple("wallpaper.mp4", cursor, videoSize))
        cursor += videoSize
        if (cursor > 0xFFFFFFFFL) {
            WepWawetLogger.proxyError("MpkgPacker.packVideoWallpaper ABORT — data section exceeds the u32 VFS limit",
                "total_data_bytes" to cursor,
                "limit_bytes" to 0xFFFFFFFFL)
            throw IllegalStateException("Video wallpaper too large for .mpkg u32 VFS ($cursor bytes)")
        }

        val totalSize = dataSectionStart + cursor
        WepWawetLogger.proxy("MpkgPacker.packVideoWallpaper layout computed",
            "header_size" to headerSize,
            "vfs_table_size" to vfsTableSize,
            "data_section_start" to dataSectionStart,
            "total_size" to totalSize,
            "entry_count" to (smallEntries.size + 1),
            "streaming_mode" to true)

        val out = outputFile.outputStream().buffered()
        try {
            // Write header
            val buf = ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN)
            buf.putInt(HEADER_MARKER)
            out.write(buf.array())
            out.write(MAGIC.toByteArray(Charsets.US_ASCII))
            buf.clear()
            buf.putInt(vfsEntries.size)
            out.write(buf.array())

            // Write VFS table
            for ((name, offset, size) in vfsEntries) {
                val nameBytesArr = name.toByteArray(Charsets.UTF_8)
                buf.clear()
                buf.putInt(nameBytesArr.size)
                out.write(buf.array())
                out.write(nameBytesArr)
                buf.clear()
                // v0.7.4: offsets/sizes are tracked as Long; toInt() keeps the
                // correct u32 bit pattern for anything < 4 GiB (guarded above).
                buf.putInt(offset.toInt())
                out.write(buf.array())
                buf.clear()
                buf.putInt(size.toInt())
                out.write(buf.array())
            }

            // Write small entries (project.json, preview.jpg)
            for ((_, blob) in smallEntries) {
                out.write(blob)
            }

            // === Stream the video file in 1MB chunks ===
            WepWawetLogger.proxy("MpkgPacker.packVideoWallpaper: streaming video file",
                "video_file" to effectiveVideoFile.name,
                "video_size" to videoSize,
                "chunk_size" to (1024 * 1024))
            val chunkSize = 1024 * 1024  // 1 MB
            var bytesWritten = 0L
            // v0.7.5: threshold-based progress log. The old `bytesWritten %
            // (10MiB) == 0` fired at most once (reads are never exactly 10MiB
            // multiples), so streaming progress was effectively invisible.
            var lastProgressLogAt = 0L
            java.io.FileInputStream(effectiveVideoFile).use { fis ->
                val buffer = ByteArray(chunkSize)
                while (true) {
                    val read = fis.read(buffer)
                    if (read <= 0) break
                    out.write(buffer, 0, read)
                    bytesWritten += read
                    if (bytesWritten - lastProgressLogAt >= 10L * 1024 * 1024) {
                        lastProgressLogAt = bytesWritten
                        WepWawetLogger.proxy("MpkgPacker.packVideoWallpaper: video streaming progress",
                            "bytes_written" to bytesWritten,
                            "total" to videoSize,
                            "pct" to (bytesWritten * 100 / videoSize).toInt())
                    }
                }
            }
            WepWawetLogger.proxy("MpkgPacker.packVideoWallpaper: video streaming complete",
                "bytes_written" to bytesWritten,
                "expected" to videoSize)
            out.flush()
        } finally {
            out.close()
            // v0.7.4 (G2): if the source was transcoded to a temp MP4, remove
            // it now — the .mpkg is complete and the original is left intact.
            if (effectiveVideoFile !== videoFile) {
                runCatching { effectiveVideoFile.delete() }
            }
        }

        WepWawetLogger.proxy("MpkgPacker.packVideoWallpaper COMPLETE (streamed)",
            "output" to outputFile.name,
            "mpkg_size" to outputFile.length(),
            "video_size" to videoSize,
            "source_container" to VideoContainerSniffer.label(container))
        return outputFile.length()
    }

    /**
     * Stream-pack entries from .pkg to .mpkg — loads ONLY modified entries into
     * memory, streams unmodified entries directly from .pkg to .mpkg. This
     * avoids OOM on large .pkg files (e.g., 256MB wallpapers with 163MB models).
     *
     * @param pkgFile The source .pkg file (for streaming unmodified entries).
     * @param dataSectionStart The offset where the .pkg data section begins.
     * @param vfsEntries The .pkg VFS table entries (path, offset, size).
     * @param modifiedEntries Map of path → modified ByteArray (shaders, .tex, JSONs).
     * @param outputFile The destination .mpkg file.
     */
    private fun streamPack(
        pkgFile: File,
        dataSectionStart: Long,
        vfsEntries: List<PkgReader.VfsEntry>,
        modifiedEntries: Map<String, ByteArray>,
        outputFile: File,
        magic: String = MAGIC,  // v0.7.4 (G1): per-transfer negotiated magic
    ): Long {
        // Build final entry list: modified entries + unmodified (from VFS) + standalone
        // CRITICAL: Standalone files (project.json, preview.gif) that were added from
        // the install directory but are NOT in the .pkg's VFS table MUST be included
        // in the .mpkg. Without project.json, the Android app can't load the wallpaper
        // → BLACK SCREEN.
        // v0.7.4: size is a Long now — vfs.size is a u32 that exceeds Int.MAX_VALUE
        // for entries ≥ 2 GiB, and the old `.toInt()` produced negative sizes that
        // corrupted the VFS table (and negative stream lengths).
        data class FinalEntry(val name: String, val size: Long, val data: ByteArray?, val vfsOffset: Long)
        val finalEntries = mutableListOf<FinalEntry>()
        val modifiedNames = modifiedEntries.keys
        val vfsPaths = vfsEntries.map { it.path }.toHashSet()

        for (vfs in vfsEntries) {
            if (vfs.path in modifiedNames) {
                val data = modifiedEntries[vfs.path]!!
                finalEntries.add(FinalEntry(vfs.path, data.size.toLong(), data, 0))
            } else {
                // Unmodified — stream from .pkg
                finalEntries.add(FinalEntry(vfs.path, vfs.size, null, vfs.offset))
            }
        }

        // Add standalone files that are NOT in the .pkg's VFS table.
        // These are files like project.json and preview.gif that exist in the
        // install directory but not inside the .pkg. Without these, the .mpkg
        // is incomplete and the Android app can't load the wallpaper.
        for ((name, data) in modifiedEntries) {
            if (name !in vfsPaths) {
                finalEntries.add(FinalEntry(name, data.size.toLong(), data, 0))
                WepWawetLogger.proxy("MpkgPacker.streamPack: added standalone entry (not in .pkg VFS)",
                    "name" to name,
                    "size" to data.size)
            }
        }

        // v0.7.16: PC entry order — case-insensitive sort (the PC packs in
        // NTFS depth-first enumeration order, which is case-insensitive
        // alphabetical; see the sort in packFromPkg for the full rationale).
        // Also drop sounds (PC parity) for the streaming path.
        finalEntries.removeAll { PcPacking.isSoundsPath(it.name) }
        finalEntries.sortWith(compareBy(PcPacking.PC_ENTRY_ORDER) { it.name })

        WepWawetLogger.proxy("MpkgPacker.streamPack layout",
            "total_entries" to finalEntries.size,
            "modified_entries" to finalEntries.count { it.data != null },
            "streamed_entries" to finalEntries.count { it.data == null },
            "data_section_start" to dataSectionStart)

        // Write .mpkg header + VFS table
        // v0.7.4: u32 overflow guard — the VFS offsets/sizes are u32 on the wire;
        // a data section ≥ 4 GiB cannot be represented and would silently corrupt
        // the table. Fail loudly instead.
        val totalDataSize = finalEntries.sumOf { it.size }
        if (totalDataSize > 0xFFFFFFFFL) {
            WepWawetLogger.proxyError("MpkgPacker.streamPack ABORT — data section exceeds the u32 VFS limit",
                "total_data_bytes" to totalDataSize,
                "entry_count" to finalEntries.size)
            throw IllegalStateException(".mpkg data section too large for u32 VFS ($totalDataSize bytes)")
        }
        val out = outputFile.outputStream().buffered()
        try {
            writeLeInt(out, HEADER_MARKER)
            out.write(magic.toByteArray(Charsets.US_ASCII))
            if (magic.length != 8) error("magic must be 8 bytes: '$magic'")
            writeLeInt(out, finalEntries.size)

            var cursor = 0L
            for (entry in finalEntries) {
                val nameBytes = entry.name.toByteArray(Charsets.UTF_8)
                writeLeInt(out, nameBytes.size)
                out.write(nameBytes)
                writeLeInt(out, cursor.toInt())
                writeLeInt(out, entry.size.toInt())
                cursor += entry.size
            }

            val vfsTableSize = finalEntries.sumOf { 4 + it.name.toByteArray(Charsets.UTF_8).size + 4 + 4 }
            WepWawetLogger.proxy("MpkgPacker.streamPack header written",
                "header_size" to (4 + 8 + 4),
                "vfs_table_size" to vfsTableSize,
                "data_section_size" to cursor,
                "data_section_start" to (4 + 8 + 4 + vfsTableSize))

            // Write data section: modified from memory, unmodified streamed from .pkg
            for ((index, entry) in finalEntries.withIndex()) {
                if (entry.data != null) {
                    out.write(entry.data)
                } else {
                    PkgReader.streamEntryData(pkgFile, dataSectionStart, entry.vfsOffset, entry.size, out)
                }
                if (index % 50 == 0 || index == finalEntries.lastIndex) {
                    WepWawetLogger.proxyDebug("MpkgPacker.streamPack progress",
                        "index" to index, "total" to finalEntries.size,
                        "name" to entry.name, "size" to entry.size,
                        "streamed" to (entry.data == null))
                }
            }
            out.flush()
        } finally {
            out.close()
        }

        val totalSize = outputFile.length()
        WepWawetLogger.proxy("MpkgPacker.streamPack COMPLETE",
            "output" to outputFile.name,
            "mpkg_size" to totalSize,
            "entry_count" to finalEntries.size)
        return totalSize
    }

    // ==================== JSON BUILDERS ====================

    /**
     * Build project.json for a Scene wallpaper.
     * Now accepts an optional schemecolor string (average color).
     */
    private fun buildSceneProjectJson(item: WorkshopItemTyped, schemecolor: String = "1.000 1.000 1.000"): ByteArray {
        val title = escapeJsonString(item.title)
        val description = escapeJsonString(item.description.ifBlank { "Sent from WepWawet" })
        val tags = item.tags.joinToString(", ") { "\"${escapeJsonString(it)}\"" }
        return buildString {
            append("{\r\n")
            append("\t\"contentrating\" : \"Everyone\",\r\n")
            append("\t\"description\" : \"$description\",\r\n")
            append("\t\"file\" : \"scene.json\",\r\n")
            append("\t\"general\" : \r\n")
            append("\t{\r\n")
            append("\t\t\"properties\" : \r\n")
            append("\t\t{\r\n")
            append("\t\t\t\"schemecolor\" : \r\n")
            append("\t\t\t{\r\n")
            append("\t\t\t\t\"order\" : 0,\r\n")
            append("\t\t\t\t\"text\" : \"ui_browse_properties_scheme_color\",\r\n")
            append("\t\t\t\t\"type\" : \"color\",\r\n")
            append("\t\t\t\t\"value\" : \"$schemecolor\"\r\n")
            append("\t\t\t}\r\n")
            append("\t\t}\r\n")
            append("\t},\r\n")
            append("\t\"preview\" : \"preview.jpg\",\r\n")
            append("\t\"tags\" : [ $tags ],\r\n")
            append("\t\"title\" : \"$title\",\r\n")
            append("\t\"type\" : \"scene\",\r\n")
            append("\t\"version\" : 0,\r\n")
            append("\t\"visibility\" : \"private\",\r\n")
            append("\t\"workshopurl\" : \"https://steamcommunity.com/sharedfiles/filedetails/?id=${item.id}\"\r\n")
            append("}")
        }.toByteArray(Charsets.UTF_8)
    }

    /**
     * Build scene.json with a fullscreen image quad.
     * BRIGHTNESS FIX: clearcolor set to dimmed average color (30% brightness).
     */
    private fun buildSceneJson(texW: Int, texH: Int, texBase: String, schemecolor: String): ByteArray {
        // Parse schemecolor to compute dimmed clearcolor
        val parts = schemecolor.split(" ").mapNotNull { it.toFloatOrNull() }
        val (r, g, b) = if (parts.size >= 3) Triple(parts[0], parts[1], parts[2]) else Triple(0f, 0f, 0f)
        val clearcolor = "%.3f %.3f %.3f".format(r * 0.3f, g * 0.3f, b * 0.3f)

        return buildString {
            append("{\r\n")
            append("\t\"camera\" : \r\n")
            append("\t{\r\n")
            append("\t\t\"center\" : \"0.000 0.000 -1.000\",\r\n")
            append("\t\t\"eye\" : \"0.000 0.000 0.000\",\r\n")
            append("\t\t\"up\" : \"0.000 1.000 0.000\"\r\n")
            append("\t},\r\n")
            append("\t\"general\" : \r\n")
            append("\t{\r\n")
            append("\t\t\"clearcolor\" : \"$clearcolor\",\r\n")
            append("\t\t\"clearenabled\" : true,\r\n")
            append("\t\t\"farz\" : 10000.0,\r\n")
            append("\t\t\"fov\" : 50.0,\r\n")
            append("\t\t\"nearz\" : 0.0099999997764825821,\r\n")
            append("\t\t\"orthogonalprojection\" : \r\n")
            append("\t\t{\r\n")
            append("\t\t\t\"height\" : $texH,\r\n")
            append("\t\t\t\"width\" : $texW\r\n")
            append("\t\t},\r\n")
            append("\t\t\"zoom\" : 1.0\r\n")
            append("\t},\r\n")
            append("\t\"objects\" : \r\n")
            append("\t[\r\n")
            append("\t\t{\r\n")
            append("\t\t\t\"alignment\" : \"center\",\r\n")
            append("\t\t\t\"alpha\" : 1.0,\r\n")
            append("\t\t\t\"angles\" : \"0.000 0.000 0.000\",\r\n")
            append("\t\t\t\"color\" : \"1.000 1.000 1.000\",\r\n")
            append("\t\t\t\"colorBlendMode\" : 0,\r\n")
            append("\t\t\t\"copybackground\" : true,\r\n")
            append("\t\t\t\"id\" : 1,\r\n")
            append("\t\t\t\"image\" : \"models/$texBase.json\",\r\n")
            append("\t\t\t\"ledsource\" : false,\r\n")
            append("\t\t\t\"locktransforms\" : true,\r\n")
            append("\t\t\t\"name\" : \"$texBase\",\r\n")
            append("\t\t\t\"origin\" : \"${texW/2}.000 ${texH/2}.000 0.000\",\r\n")
            append("\t\t\t\"parallaxDepth\" : \"0.000 1.000\",\r\n")
            append("\t\t\t\"perspective\" : false,\r\n")
            append("\t\t\t\"scale\" : \"1.000 1.000 1.000\",\r\n")
            append("\t\t\t\"size\" : \"$texW.000 $texH.000\",\r\n")
            append("\t\t\t\"solid\" : true,\r\n")
            append("\t\t\t\"visible\" : true\r\n")
            append("\t\t}\r\n")
            append("\t]\r\n")
            append("}")
        }.toByteArray(Charsets.UTF_8)
    }

    private fun buildModelJson(texBase: String): ByteArray {
        return buildString {
            append("{\r\n")
            append("\t\"autosize\" : true,\r\n")
            append("\t\"material\" : \"materials/$texBase.json\"\r\n")
            append("}")
        }.toByteArray(Charsets.UTF_8)
    }

    /**
     * Build material JSON using "genericimage2" shader.
     * BRIGHTNESS FIX: blending = "normal" (not "translucent" which can cause additive blending).
     * depthtest = "enabled" matches real WE samples.
     */
    private fun buildMaterialJson(texBase: String): ByteArray {
        return buildString {
            append("{\r\n")
            append("\t\"passes\" : \r\n")
            append("\t[\r\n")
            append("\t\t{\r\n")
            append("\t\t\t\"blending\" : \"normal\",\r\n")
            append("\t\t\t\"combos\" : \r\n")
            append("\t\t\t{\r\n")
            append("\t\t\t\t\"version\" : 2\r\n")
            append("\t\t\t},\r\n")
            append("\t\t\t\"cullmode\" : \"nocull\",\r\n")
            append("\t\t\t\"depthtest\" : \"enabled\",\r\n")
            append("\t\t\t\"depthwrite\" : \"enabled\",\r\n")
            append("\t\t\t\"shader\" : \"genericimage2\",\r\n")
            append("\t\t\t\"textures\" : [ \"$texBase\" ]\r\n")
            append("\t\t}\r\n")
            append("\t]\r\n")
            append("}")
        }.toByteArray(Charsets.UTF_8)
    }

    /**
     * Build a preview JPEG thumbnail from a source image file.
     */
    private fun buildPreviewJpeg(imageFile: File, maxDim: Int = 400): ByteArray {
        val orig = BitmapFactory.decodeFile(imageFile.absolutePath) ?: return ByteArray(0)
        val origW = orig.width
        val origH = orig.height
        val max = maxOf(origW, origH)
        if (max > maxDim) {
            val scale = maxDim.toFloat() / max
            val newW = (origW * scale).toInt().coerceAtLeast(1)
            val newH = (origH * scale).toInt().coerceAtLeast(1)
            val scaled = Bitmap.createScaledBitmap(orig, newW, newH, true)
            orig.recycle()
            val baos = ByteArrayOutputStream()
            scaled.compress(Bitmap.CompressFormat.JPEG, 85, baos)
            scaled.recycle()
            return baos.toByteArray()
        }
        val baos = ByteArrayOutputStream()
        orig.compress(Bitmap.CompressFormat.JPEG, 85, baos)
        orig.recycle()
        return baos.toByteArray()
    }

    /**
     * Build project.json for a Video wallpaper.
     *
     * v0.7.4 (parity G9): the PC transcoder always writes an
     * `alignmentposition` field for video wallpapers — "{\"value\": <int>}" —
     * with the int coming from the device/export configuration
     * (FUN_14003d0e0, disasm 14003f27b). WepWawet surfaces the same value
     * as a settings slider ([videoAlignmentPosition], default 0 = centered).
     */
    private fun buildVideoProjectJson(item: WorkshopItemTyped): ByteArray {
        val title = escapeJsonString(item.title)
        val description = escapeJsonString(item.description.ifBlank { "Sent from WepWawet" })
        val tags = item.tags.joinToString(", ") { "\"${escapeJsonString(it)}\"" }
        val alignment = videoAlignmentPosition.coerceIn(0, 100)
        return buildString {
            append("{\r\n")
            append("\t\"alignmentposition\" : \"{\\\"value\\\": $alignment}\",\r\n")
            append("\t\"contentrating\" : \"Everyone\",\r\n")
            append("\t\"description\" : \"$description\",\r\n")
            append("\t\"file\" : \"wallpaper.mp4\",\r\n")
            append("\t\"general\" : \r\n")
            append("\t{\r\n")
            append("\t\t\"properties\" : \r\n")
            append("\t\t{\r\n")
            append("\t\t\t\"schemecolor\" : \r\n")
            append("\t\t\t{\r\n")
            append("\t\t\t\t\"order\" : 0,\r\n")
            append("\t\t\t\t\"text\" : \"ui_browse_properties_scheme_color\",\r\n")
            append("\t\t\t\t\"type\" : \"color\",\r\n")
            append("\t\t\t\t\"value\" : \"0.000 0.000 0.000\"\r\n")
            append("\t\t\t}\r\n")
            append("\t\t}\r\n")
            append("\t},\r\n")
            append("\t\"preview\" : \"preview.jpg\",\r\n")
            append("\t\"tags\" : [ $tags ],\r\n")
            append("\t\"title\" : \"$title\",\r\n")
            append("\t\"type\" : \"video\",\r\n")
            append("\t\"version\" : 0,\r\n")
            append("\t\"visibility\" : \"private\",\r\n")
            append("\t\"workshopurl\" : \"https://steamcommunity.com/sharedfiles/filedetails/?id=${item.id}\"\r\n")
            append("}")
        }.toByteArray(Charsets.UTF_8)
    }

    // ==================== SHARED UTILITIES ====================

    private fun escapeJsonString(s: String): String {
        val sb = StringBuilder(s.length + 16)
        for (c in s) {
            when (c) {
                '\\' -> sb.append("\\\\")
                '"' -> sb.append("\\\"")
                '\n' -> sb.append("\\n")
                '\r' -> sb.append("\\r")
                '\t' -> sb.append("\\t")
                '\b' -> sb.append("\\b")
                '\u000C' -> sb.append("\\f")
                else -> {
                    if (c.code < 0x20) {
                        sb.append("\\u").append("%04x".format(c.code))
                    } else {
                        sb.append(c)
                    }
                }
            }
        }
        return sb.toString()
    }

    /**
     * Convert a .tex file containing PNG data inside the TEXB section to raw RGBA8888.
     *
     * === ACTUAL .tex FORMAT (verified against RePKG + linux-wallpaperengine source) ===
     *
     * Header:
     *   "TEXV0005\0" (9 bytes magic1, null-terminated string)
     *   "TEXI0001\0" (9 bytes magic2, null-terminated string)
     *   TEXI Header (28 bytes = 7 × int32 LE):
     *     format       (TextureFormat: 0=ARGB8888, 4=DXT5, 5=ETC2-RGBA?, 6=DXT3, 7=DXT1,
     *                   8=RG88, 9=R8, 12=BC7, 13=RGBa1010102, 14=RGBA16161616f, ...)
     *     flags        (TextureFlags: 1=NoInterpolation, 2=ClampUVs, 4=IsGif, 32=Video, ...)
     *     texture_width  (int) — GPU texture width (may be padded to multiple of 4)
     *     texture_height (int) — GPU texture height
     *     image_width    (int) — actual image width (may be < texture_width)
     *     image_height   (int) — actual image height
     *     UnkInt0       (int) — some hash/checksum, ignored by readers
     *
     * TEXB Container:
     *   "TEXB0004\0" (9 bytes magic, null-terminated string)
     *   image_count  (int)
     *   format       (int, FreeImageFormat: -1=FIF_UNKNOWN, 13=FIF_PNG, 2=FIF_JPEG, ...)
     *   is_video_mp4 (int, 0 or 1) — TEXB0004 only
     *   [For each image]:
     *     mipmap_count (int)
     *     [For each mipmap, v2/v3 format]:
     *       width (int)
     *       height (int)
     *       compression (int) — 0=none, 1=LZ4
     *       uncompressed_size (int) — if compression=0, reader overwrites with compressed_size
     *       compressed_size (int) — this IS the data length, regardless of compression
     *       data (compressed_size bytes)
     *
     * === WHY CONVERT ===
     *
     * Steam depot stores .tex files with:
     *   TexI format=0 (RGBA8888 declared)
     *   TexB format=13 (FIF_PNG)
     *   4 mipmaps of PNG data
     *
     * WE PC decodes the PNG via stb_image at load time.
     * WE Android does NOT include a PNG decoder for .tex files — it expects raw texture
     * data (FIF_UNKNOWN + format from TexI). When WE Android sees format=13, it falls
     * back to the "missing texture" yellow/black checkerboard pattern.
     *
     * === CONVERSION ===
     *
     * Input: .tex with PNG-encoded mipmaps (TexB format=13)
     * Output: .tex with raw RGBA8888 data (TexB format=-1, TexI format=0)
     *
     * Output structure:
     *   - TEXV0005\0 + TEXI0001\0 (preserved from original)
     *   - TEXI header: format=0 (RGBA8888), flags from original,
     *     dimensions from largest PNG (mipmap level 0)
     *   - TEXB0004\0
     *   - image_count=1, format=-1 (FIF_UNKNOWN), is_video_mp4=0
     *   - For image 0: mipmap_count=1 (only base level, to keep file size reasonable)
     *   - For mipmap 0: width, height, compression=0,
     *     uncompressed_size=w*h*4, compressed_size=w*h*4, data=raw_RGBA_bytes
     *
     * The RGBA bytes come from Android's BitmapFactory + Bitmap.copyPixelsToBuffer.
     * On little-endian Android (the common case), ARGB_8888 bitmaps stored in a
     * direct ByteBuffer in nativeOrder() are laid out as R,G,B,A per pixel — which
     * matches WE's RGBA8888 format exactly. NO byte swap is needed.
     *
     * File size estimate:
     *   3000×1800 RGBA = 21.6 MB per texture
     *   4 textures × 1 mipmap = 86.4 MB total
     * (Larger than the 5MB PNG version, but actually renders correctly on Android.
     * A future iteration can add LZ4 compression or ETC2/ASTC encoding to reduce size.)
     */
    private fun convertTexPngToRgba(texData: ByteArray, name: String): ByteArray? {
        try {
            // ---- Step 1: Parse original .tex structure to find PNG images ----
            // Find all PNG signatures (\x89PNG) and extract each PNG (up to IEND+CRC).
            data class PngEntry(val offset: Int, val end: Int, val bytes: ByteArray)
            val pngImages = mutableListOf<PngEntry>()
            var searchPos = 0
            while (true) {
                val pngStart = findBytes(texData, PNG_MAGIC, searchPos)
                if (pngStart < 0) break
                // v0.7.4: locate the TRUE PNG end by walking the chunk structure
                // (4-byte BE length + 4-byte type + data + 4-byte CRC until IEND).
                // The old raw search for the bytes "IEND" could match the literal
                // text inside a tEXt/zTXt/iTXt chunk, truncating the PNG mid-file.
                val pngEnd = findPngEnd(texData, pngStart)
                if (pngEnd < 0) {
                    WepWawetLogger.proxyWarn("MpkgPacker.convertTexPngToRgba: PNG without IEND",
                        "name" to name, "png_start" to pngStart)
                    break
                }
                val pngBytes = texData.copyOfRange(pngStart, pngEnd)
                pngImages.add(PngEntry(pngStart, pngEnd, pngBytes))
                searchPos = pngEnd
            }

            if (pngImages.isEmpty()) {
                WepWawetLogger.proxyWarn("MpkgPacker.convertTexPngToRgba: no PNGs found in .tex",
                    "name" to name,
                    "tex_size" to texData.size)
                return null
            }

            // ---- Step 2: Decode each PNG and find the largest (mipmap level 0) ----
            // We use only ONE mipmap to keep the file size manageable.
            // The selection defaults to mipmap level 2 (third-largest) to match the
            // real WE Android .tex file sizes (~5 MB total for 4 textures).
            // At mipmap level 0 (3000x1800), 4 textures would total ~86 MB.
            // At mipmap level 2 (750x450), 4 textures total ~5.4 MB.
            data class DecodedRgba(
                val pngIndex: Int,
                val width: Int,
                val height: Int,
                val rgba: ByteArray,
            )
            val decoded = mutableListOf<DecodedRgba>()
            for ((idx, pngEntry) in pngImages.withIndex()) {
                // CRITICAL: Set inPremultiplied=false so Android doesn't premultiply
                // alpha into RGB. WE's shaders expect non-premultiplied alpha — for
                // semi-transparent edges (anti-aliasing) and X-ray mask textures,
                // premultiplication makes RGB values darker than expected, breaking
                // the X-ray effect (mask shows as black instead of grey, figure shows
                // as black instead of visible).
                val options = android.graphics.BitmapFactory.Options().apply {
                    inPreferredConfig = android.graphics.Bitmap.Config.ARGB_8888
                    inPremultiplied = false  // CRITICAL for X-ray mask textures
                }
                val bitmap = android.graphics.BitmapFactory.decodeByteArray(
                    pngEntry.bytes, 0, pngEntry.bytes.size, options)
                if (bitmap == null) {
                    WepWawetLogger.proxyWarn("MpkgPacker.convertTexPngToRgba: BitmapFactory.decodeByteArray returned null",
                        "name" to name,
                        "png_index" to idx,
                        "png_size" to pngEntry.bytes.size)
                    continue
                }
                // Force ARGB_8888 config (it usually is already, but just in case)
                val rgbaBitmap = if (bitmap.config == android.graphics.Bitmap.Config.ARGB_8888) {
                    bitmap
                } else {
                    val copy = bitmap.copy(android.graphics.Bitmap.Config.ARGB_8888, false)
                    bitmap.recycle()
                    copy
                }
                if (rgbaBitmap == null) {
                    WepWawetLogger.proxyWarn("MpkgPacker.convertTexPngToRgba: copy to ARGB_8888 failed",
                        "name" to name,
                        "png_index" to idx)
                    continue
                }
                val w = rgbaBitmap.width
                val h = rgbaBitmap.height
                // Allocate a direct ByteBuffer in native byte order. On little-endian
                // Android (the common case), copyPixelsToBuffer writes R,G,B,A per pixel
                // for ARGB_8888 bitmaps — which matches WE's RGBA8888 format exactly.
                // NO byte swap is needed.
                val pixelBuffer = java.nio.ByteBuffer.allocateDirect(w * h * 4)
                    .order(java.nio.ByteOrder.nativeOrder())
                rgbaBitmap.copyPixelsToBuffer(pixelBuffer)
                rgbaBitmap.recycle()

                val rgba = ByteArray(w * h * 4)
                pixelBuffer.rewind()
                pixelBuffer.get(rgba)
                decoded.add(DecodedRgba(idx, w, h, rgba))

                WepWawetLogger.proxyDebug("MpkgPacker.convertTexPngToRgba: decoded PNG",
                    "name" to name,
                    "png_index" to idx,
                    "width" to w,
                    "height" to h,
                    "rgba_size" to rgba.size,
                    "premultiplied" to false,
                    "first_8_bytes_hex" to rgba.take(8).joinToString("") { "%02x".format(it) })
            }

            if (decoded.isEmpty()) {
                WepWawetLogger.proxyWarn("MpkgPacker.convertTexPngToRgba: no PNGs decoded successfully",
                    "name" to name)
                return null
            }

            // Pick the mipmap level to use.
            // v0.6.0 (parity §6 6-8): CRITICAL FIX — Use mipmap level 0 (largest) instead of
            // level 2 (third-largest). The previous behavior produced blurry textures on Android
            // when the ETC2 native lib was unavailable. Real WE PC always uses mipmap 0.
            // For size concerns, the ETC2+LZ4 path produces files ~5× smaller than RGBA8888,
            // so using mipmap 0 is not a size issue when ETC2 is available.
            // When ETC2 is unavailable (this fallback path), the file is larger but visually correct.
            // Mipmap levels (typical): 0=largest(3000x1800, 21.6MB), 1=1500x900(5.4MB),
            //                          2=750x450(1.35MB), 3=375x225(0.34MB)
            val sortedBySize = decoded.sortedByDescending { it.width.toLong() * it.height.toLong() }
            val targetMipmapLevel = 0  // v0.6.0: 0=largest (was 2 = third-largest)
            val targetIndex = minOf(targetMipmapLevel, sortedBySize.size - 1)
            val largest = sortedBySize[targetIndex]
            WepWawetLogger.proxy("MpkgPacker.convertTexPngToRgba: selected mipmap",
                "name" to name,
                "target_level" to targetMipmapLevel,
                "actual_level" to targetIndex,
                "available_levels" to sortedBySize.size,
                "selected_index_in_original" to largest.pngIndex,
                "width" to largest.width,
                "height" to largest.height,
                "rgba_bytes" to largest.rgba.size,
                "all_mipmap_sizes" to sortedBySize.joinToString(", ") { "${it.width}x${it.height}(${it.rgba.size}b)" })

            // ---- Step 3: Read original TEXI header fields (we'll preserve most) ----
            // Layout (offsets relative to start of .tex):
            //   0-8   "TEXV0005\0"
            //   9-17  "TEXI0001\0"
            //   18-21 format
            //   22-25 flags
            //   26-29 texture_width
            //   30-33 texture_height
            //   34-37 image_width
            //   38-41 image_height
            //   42-45 UnkInt0
            if (texData.size < 46) {
                WepWawetLogger.proxyWarn("MpkgPacker.convertTexPngToRgba: .tex too small for header",
                    "name" to name,
                    "tex_size" to texData.size)
                return null
            }
            val origFlags = readLeInt(texData, 22)
            val origUnkInt0 = readLeInt(texData, 42)
            WepWawetLogger.proxyDebug("MpkgPacker.convertTexPngToRgba: original TEXI fields",
                "name" to name,
                "orig_format" to readLeInt(texData, 18),
                "orig_flags" to origFlags,
                "orig_texture_width" to readLeInt(texData, 26),
                "orig_texture_height" to readLeInt(texData, 30),
                "orig_image_width" to readLeInt(texData, 34),
                "orig_image_height" to readLeInt(texData, 38),
                "orig_unk_int0_hex" to "0x%08x".format(origUnkInt0))

            // ---- Step 4: Build new .tex ----
            val out = java.io.ByteArrayOutputStream()

            // 4a: Magic1 + Magic2 (18 bytes, preserved from original)
            out.write(texData, 0, 18)

            // 4b: TEXI Header (28 bytes = 7 × int32 LE)
            // Override format to 0 (RGBA8888) since we're writing raw RGBA pixel data.
            // Use PNG dimensions for both texture_* and image_* (no padding needed for RGBA8888).
            // Preserve flags from original (ClampUVs etc.) and UnkInt0.
            writeLeIntToStream(out, 0)                              // format = 0 (RGBA8888)
            writeLeIntToStream(out, origFlags)                     // flags (preserved)
            writeLeIntToStream(out, largest.width)                // texture_width = PNG width
            writeLeIntToStream(out, largest.height)               // texture_height = PNG height
            writeLeIntToStream(out, largest.width)                // image_width = PNG width
            writeLeIntToStream(out, largest.height)               // image_height = PNG height
            writeLeIntToStream(out, origUnkInt0)                   // UnkInt0 (preserved)

            // 4c: TEXB Container header
            // "TEXB0004\0" magic
            out.write(byteArrayOf(
                0x54, 0x45, 0x58, 0x42,  // "TEXB"
                0x30, 0x30, 0x30, 0x34,  // "0004"
                0x00                     // null terminator
            ))
            writeLeIntToStream(out, 1)                              // image_count = 1
            writeLeIntToStream(out, -1)                             // format = -1 (FIF_UNKNOWN)
            writeLeIntToStream(out, 0)                             // is_video_mp4 = 0

            // 4d: Image 0 (only one image)
            writeLeIntToStream(out, 1)                              // mipmap_count = 1

            // 4e: Mipmap 0 (only one mipmap — base level)
            val rgbaSize = largest.rgba.size
            writeLeIntToStream(out, largest.width)                // width
            writeLeIntToStream(out, largest.height)               // height
            writeLeIntToStream(out, 0)                              // compression = 0 (no LZ4)
            writeLeIntToStream(out, rgbaSize)                      // uncompressed_size = w*h*4
            writeLeIntToStream(out, rgbaSize)                      // compressed_size = w*h*4 (same)
            // Raw RGBA pixel data (R,G,B,A per pixel, no swap needed)
            out.write(largest.rgba)

            val result = out.toByteArray()

            // ---- Step 5: Verify by reading back the structure ----
            val vMagic1 = String(result, 0, 8, Charsets.US_ASCII)
            val vMagic2 = String(result, 9, 8, Charsets.US_ASCII)
            val vFormat = readLeInt(result, 18)
            val vTexW = readLeInt(result, 26)
            val vTexH = readLeInt(result, 30)
            val vImageCount = readLeInt(result, 55)
            val vFIF = readLeInt(result, 59)
            val vIsMp4 = readLeInt(result, 63)
            val vMipmapCount = readLeInt(result, 67)
            val vMipmapW = readLeInt(result, 71)
            val vMipmapH = readLeInt(result, 75)
            val vCompression = readLeInt(result, 79)
            val vUncompressedSize = readLeInt(result, 83)
            val vCompressedSize = readLeInt(result, 87)
            WepWawetLogger.proxy("MpkgPacker.convertTexPngToRgba SUCCESS",
                "name" to name,
                "original_size" to texData.size,
                "converted_size" to result.size,
                "png_count_in_original" to pngImages.size,
                "decoded_count" to decoded.size,
                "selected_mipmap_index" to largest.pngIndex,
                "selected_width" to largest.width,
                "selected_height" to largest.height,
                "rgba_bytes" to rgbaSize,
                // Verification
                "verify_magic1" to vMagic1,
                "verify_magic2" to vMagic2,
                "verify_format" to vFormat,
                "verify_texture_width" to vTexW,
                "verify_texture_height" to vTexH,
                "verify_image_count" to vImageCount,
                "verify_fif" to vFIF,
                "verify_is_mp4" to vIsMp4,
                "verify_mipmap_count" to vMipmapCount,
                "verify_mipmap_width" to vMipmapW,
                "verify_mipmap_height" to vMipmapH,
                "verify_compression" to vCompression,
                "verify_uncompressed_size" to vUncompressedSize,
                "verify_compressed_size" to vCompressedSize,
                "verify_data_starts_at" to 91,
                "verify_first_8_bytes_hex" to (if (result.size >= 99) result.copyOfRange(91, 99).joinToString("") { "%02x".format(it) } else "file_too_small"))
            return result

        } catch (e: Exception) {
            WepWawetLogger.proxyError("MpkgPacker.convertTexPngToRgba FAILED",
                "name" to name,
                "exception" to e.javaClass.name,
                "message" to (e.message ?: ""))
            return null
        }
    }
    /**
     * Convert a DXT-format .tex file (PC texture format) to ETC2_RGBA8 + LZ4.
     *
     * Real WE PC's Steam Workshop .pkg files contain .tex files in DXT1/DXT3/DXT5
     * format (formats 7/6/4 respectively per repkg's TexFormat enum). When WE PC
     * sends a wallpaper to the WE Android app, it transcodes these DXT textures
     * to ETC2_RGBA8 via `resourcecompiler64.exe -transcode -f ETC2`. We do the
     * same transcode on-device.
     *
     * Flow:
     *   1. Parse .tex header → (tex_format, tex_w, tex_h, is_lz4, decompressed_size, byte_count)
     *   2. Read body bytes (offset 91, byte_count bytes)
     *   3. If is_lz4, LZ4-decompress body to get raw DXT block data
     *   4. Decode DXT blocks → RGBA8888 pixels (via native DxtDecoder)
     *   5. Encode RGBA8888 → ETC2_RGBA8 (via existing Etc2Encoder)
     *   6. LZ4-compress the ETC2 bytes
     *   7. Build new .tex with format=5, is_lz4=1, ETC2+LZ4 body (same shape as
     *      convertTexPngToEtc2's output, which has been verified to work on Android)
     *
     * Returns: the new .tex bytes, or null on failure (caller should fall back to
     *          convertTexPngToRgba or leave the original .tex unchanged).
     */

    // ========================================================================
    // Texture conversion cache
    // ========================================================================
    // Caches converted .tex files (DXT→ETC2, PNG→ETC2) by SHA-256 hash of
    // the source .tex bytes + CONVERTER_VERSION. On a cache hit, the
    // converted .tex is read from disk instead of re-encoding. The cache
    // is capped at ~2 GB, evicting least-recently-used files.
    //
    // This is especially useful when the user re-downloads the same wallpaper
    // or when multiple .pkg files share common textures.

    private const val TEX_CACHE_MAX_BYTES = 2L * 1024 * 1024 * 1024 // 2 GB
    private const val TEX_CACHE_DIR_NAME = "tex_cache"

    /**
     * Look up the texture conversion cache for a previously-converted .tex.
     *
     * @param texData The source .tex bytes (before conversion)
     * @param name The .tex file name (for logging only)
     * @return The cached converted .tex bytes, or null on cache miss
     */
    private fun texCacheLookup(texData: ByteArray, name: String): ByteArray? {
        return try {
            val cacheDir = File(com.wepwawet.WepWawetApplication.instance.cacheDir, TEX_CACHE_DIR_NAME)
            val key = texCacheKey(texData)
            val cacheFile = File(cacheDir, "$key.tex")
            if (cacheFile.exists() && cacheFile.length() > 0) {
                val data = cacheFile.readBytes()
                WepWawetLogger.proxy("MpkgPacker.texCacheLookup: CACHE HIT",
                    "name" to name,
                    "cache_key" to key.take(16),
                    "cache_size" to data.size)
                // Update last-modified for LRU
                cacheFile.setLastModified(System.currentTimeMillis())
                data
            } else {
                null
            }
        } catch (e: Exception) {
            WepWawetLogger.proxyDebug("MpkgPacker.texCacheLookup: error (cache miss)",
                "name" to name, "exception" to e.javaClass.name)
            null
        }
    }

    /**
     * Store a converted .tex in the cache.
     */
    private fun texCacheStore(texData: ByteArray, convertedData: ByteArray, name: String) {
        try {
            val cacheDir = File(com.wepwawet.WepWawetApplication.instance.cacheDir, TEX_CACHE_DIR_NAME)
            cacheDir.mkdirs()
            val key = texCacheKey(texData)
            val cacheFile = File(cacheDir, "$key.tex")
            cacheFile.writeBytes(convertedData)
            WepWawetLogger.proxyDebug("MpkgPacker.texCacheStore: stored",
                "name" to name,
                "cache_key" to key.take(16),
                "cache_size" to convertedData.size)
            // Evict LRU entries if cache exceeds max size
            texCacheEvictIfNeeded(cacheDir)
        } catch (e: Exception) {
            WepWawetLogger.proxyDebug("MpkgPacker.texCacheStore: error (ignored)",
                "name" to name, "exception" to e.javaClass.name)
        }
    }

    /**
     * Compute the cache key: SHA-256 of source .tex bytes + CONVERTER_VERSION.
     */
    private fun texCacheKey(texData: ByteArray): String {
        val md = java.security.MessageDigest.getInstance("SHA-256")
        md.update(texData)
        // Append converter version to invalidate cache on code changes
        md.update(byteArrayOf(CONVERTER_VERSION.toByte()))
        return md.digest().joinToString("") { "%02x".format(it) }
    }

    /**
     * Evict least-recently-used cache files if total size exceeds TEX_CACHE_MAX_BYTES.
     */
    private fun texCacheEvictIfNeeded(cacheDir: File) {
        try {
            val files = cacheDir.listFiles { f -> f.isFile && f.name.endsWith(".tex") } ?: return
            var totalSize = files.sumOf { it.length() }
            if (totalSize <= TEX_CACHE_MAX_BYTES) return
            // Sort by last-modified ascending (oldest first)
            val sorted = files.sortedBy { it.lastModified() }
            for (f in sorted) {
                if (totalSize <= TEX_CACHE_MAX_BYTES) break
                totalSize -= f.length()
                f.delete()
            }
        } catch (e: Exception) {
            // Cache eviction errors are non-fatal
        }
    }

    // ========================================================================
    // Timing logs for ETC2 encoding
    // ========================================================================

    /**
     * Log timing for an ETC2 encode operation.
     */
    private fun logEtc2Timing(name: String, width: Int, height: Int, elapsedMs: Long) {
        val pixels = width.toLong() * height.toLong()
        val megapixels = pixels / 1_000_000.0
        val mpPerSec = if (elapsedMs > 0) (megapixels / (elapsedMs / 1000.0)) else 0.0
        val effort = when {
            pixels <= 1048576 -> 40
            pixels <= 4194304 -> 25
            else -> 10
        }
        val jobs = minOf(maxOf(Runtime.getRuntime().availableProcessors() * 2, 8), 32)
        WepWawetLogger.proxy("MpkgPacker: ETC2 encode timing",
            "name" to name,
            "width" to width,
            "height" to height,
            "megapixels" to "%.2f".format(megapixels),
            "effort" to effort,
            "jobs" to jobs,
            "elapsed_ms" to elapsedMs,
            "mp_per_sec" to "%.2f".format(mpPerSec))
    }

    private fun convertTexDxtToEtc2(texData: ByteArray, name: String): ByteArray? {
        try {
            // ---- Step 0a: Check texture conversion cache ----
            texCacheLookup(texData, name)?.let { cached ->
                WepWawetLogger.proxy("MpkgPacker.convertTexDxtToEtc2: using cached conversion",
                    "name" to name, "cached_size" to cached.size)
                return cached
            }

            // ---- Step 0b: Verify native libraries are available ----
            if (!Etc2Encoder.isAvailable() || !DxtDecoder.isAvailable()) {
                WepWawetLogger.proxyWarn("MpkgPacker.convertTexDxtToEtc2: native libs not available",
                    "name" to name,
                    "etc2_loaded" to Etc2Encoder.isAvailable(),
                    "dxt_loaded" to DxtDecoder.isAvailable())
                return null
            }

            // ---- Step 1: Parse .tex header ----
            if (texData.size < 91) {
                WepWawetLogger.proxyWarn("MpkgPacker.convertTexDxtToEtc2: .tex too small for header",
                    "name" to name, "tex_size" to texData.size)
                return null
            }
            // Header layout (91 bytes):
            //   0..8   : TEXV0005\0
            //   9..17  : TEXI0001\0
            //   18..45 : 7x u32 LE: format, flags, tex_w, tex_h, img_w, img_h, color
            //   46..54 : TEXB0004\0
            //   55..66 : 3x u32 LE: imageCount, body_format, isVideoMp4
            //   67..70 : 1x u32 LE: mipmap_count
            //   71..86 : 4x u32 LE: width, height, IsLZ4, DecompressedSize (for mipmap 0)
            //   87..90 : 1x u32 LE: byteCount (for mipmap 0)
            //   91+    : body data (LZ4-compressed if IsLZ4=1)
            val texiFormat = readU32Le(texData, 18).toInt()
            val origFlags = readLeInt(texData, 22)
            val texWidth = readU32Le(texData, 26).toInt()
            val texHeight = readU32Le(texData, 30).toInt()
            val origUnkInt0 = readLeInt(texData, 42)  // color (RGBA) — preserved

            // Map .tex format → DxtDecoder.Format
            val dxtFormat = DxtDecoder.formatForTexFormat(texiFormat)
            if (dxtFormat == null) {
                // Not a DXT format — caller shouldn't have called us
                WepWawetLogger.proxyWarn("MpkgPacker.convertTexDxtToEtc2: not a DXT format",
                    "name" to name, "texi_format" to texiFormat)
                return null
            }

            val mipmapCount = readU32Le(texData, 67).toInt()
            if (mipmapCount < 1) {
                WepWawetLogger.proxyWarn("MpkgPacker.convertTexDxtToEtc2: mipmap_count < 1",
                    "name" to name, "mipmap_count" to mipmapCount)
                return null
            }
            // Use mipmap 0 (largest) — same as convertTexPngToEtc2
            val mipWidth = readU32Le(texData, 71).toInt()
            val mipHeight = readU32Le(texData, 75).toInt()
            val isLz4 = readU32Le(texData, 79).toInt() != 0
            val decompressedSize = readU32Le(texData, 83).toInt()
            val byteCount = readU32Le(texData, 87).toInt()

            WepWawetLogger.proxy("MpkgPacker.convertTexDxtToEtc2: parsed .tex header",
                "name" to name,
                "texi_format" to texiFormat,
                "dxt_format" to dxtFormat.displayName,
                "tex_width" to texWidth, "tex_height" to texHeight,
                "mip_width" to mipWidth, "mip_height" to mipHeight,
                "is_lz4" to isLz4,
                "decompressed_size" to decompressedSize,
                "byte_count" to byteCount,
                "tex_size" to texData.size)

            if (texWidth <= 0 || texHeight <= 0) {
                WepWawetLogger.proxyWarn("MpkgPacker.convertTexDxtToEtc2: invalid tex dimensions",
                    "name" to name, "tex_width" to texWidth, "tex_height" to texHeight)
                return null
            }
            if (texWidth % 4 != 0 || texHeight % 4 != 0) {
                WepWawetLogger.proxyWarn("MpkgPacker.convertTexDxtToEtc2: dimensions not multiple of 4 (DXT requires block alignment)",
                    "name" to name, "tex_width" to texWidth, "tex_height" to texHeight)
                // The DXT decoder handles non-block-aligned dimensions by clipping,
                // so this is a warning, not an error.
            }
            if (byteCount <= 0 || 91 + byteCount > texData.size) {
                WepWawetLogger.proxyWarn("MpkgPacker.convertTexDxtToEtc2: byteCount out of range",
                    "name" to name, "byte_count" to byteCount,
                    "tex_size" to texData.size, "expected_min_size" to (91 + byteCount))
                return null
            }

            // ---- Step 2: Read body bytes (offset 91, byteCount bytes) ----
            val bodyBytes = texData.copyOfRange(91, 91 + byteCount)

            // ---- Step 3: If is_lz4, LZ4-decompress body to get raw DXT block data ----
            val dxtBlockData = if (isLz4) {
                if (decompressedSize <= 0) {
                    WepWawetLogger.proxyWarn("MpkgPacker.convertTexDxtToEtc2: is_lz4=1 but decompressed_size<=0",
                        "name" to name, "decompressed_size" to decompressedSize)
                    return null
                }
                try {
                    val lz4Factory = net.jpountz.lz4.LZ4Factory.safeInstance()
                    val decompressor = lz4Factory.safeDecompressor()
                    val decompressed = decompressor.decompress(bodyBytes, decompressedSize)
                    WepWawetLogger.proxy("MpkgPacker.convertTexDxtToEtc2: LZ4 decompressed body",
                        "name" to name,
                        "compressed_size" to bodyBytes.size,
                        "decompressed_size" to decompressed.size,
                        "lz4_ratio" to "%.2f%%".format(bodyBytes.size * 100.0 / decompressedSize))
                    decompressed
                } catch (e: Exception) {
                    WepWawetLogger.proxyError("MpkgPacker.convertTexDxtToEtc2: LZ4 decompression failed",
                        "name" to name, "exception" to e.javaClass.name, "message" to (e.message ?: ""),
                        "compressed_size" to bodyBytes.size, "expected_decompressed_size" to decompressedSize)
                    return null
                }
            } else {
                // No LZ4 — body is already raw DXT block data
                bodyBytes
            }

            // ---- Step 4: Decode DXT blocks → RGBA8888 pixels ----
            val rgbaBytes = DxtDecoder.decodeToRgba8888(dxtBlockData, texWidth, texHeight, dxtFormat)
                ?: run {
                    WepWawetLogger.proxyWarn("MpkgPacker.convertTexDxtToEtc2: DXT decoding returned null",
                        "name" to name,
                        "dxt_format" to dxtFormat.displayName,
                        "dxt_block_data_size" to dxtBlockData.size,
                        "tex_width" to texWidth, "tex_height" to texHeight)
                    return null
                }
            val expectedRgbaSize = texWidth * texHeight * 4
            if (rgbaBytes.size != expectedRgbaSize) {
                WepWawetLogger.proxyWarn("MpkgPacker.convertTexDxtToEtc2: RGBA size mismatch",
                    "name" to name,
                    "actual_size" to rgbaBytes.size,
                    "expected_size" to expectedRgbaSize)
                // Continue anyway — the decoder pads with zeros if input was short
            }
            WepWawetLogger.proxy("MpkgPacker.convertTexDxtToEtc2: DXT decoded to RGBA8888",
                "name" to name,
                "dxt_format" to dxtFormat.displayName,
                "rgba_size" to rgbaBytes.size,
                "first_pixel_rgba" to
                    "(${rgbaBytes[0].toInt() and 0xFF},${rgbaBytes[1].toInt() and 0xFF},${rgbaBytes[2].toInt() and 0xFF},${rgbaBytes[3].toInt() and 0xFF})")

            // ---- Step 5 (v0.7.18, PC-exact): decode → shrink → etcpack ETC2 ----
            // For DXT1: the .tex flags bit 3 (0x08) selects DXT1a (1-bit alpha)
            // vs plain DXT1 (4-color opaque) for the DECODER only.
            val hasAlphaFlag = (origFlags and 0x08) != 0
            val use1BitAlphaForDxt1 = if (dxtFormat == DxtDecoder.Format.DXT1) hasAlphaFlag else true
            val rgbaBytesFinal = if (dxtFormat == DxtDecoder.Format.DXT1 && !use1BitAlphaForDxt1) {
                WepWawetLogger.proxy("MpkgPacker.convertTexDxtToEtc2: re-decoding DXT1 as opaque (no HasAlpha flag)",
                    "name" to name, "flags" to origFlags)
                DxtDecoder.decodeToRgba8888(dxtBlockData, texWidth, texHeight, dxtFormat, use1BitAlpha = false)
                    ?: rgbaBytes
            } else {
                rgbaBytes
            }

            // -shrink (PC rule: only when BOTH dims > 128, factor 1..8)
            val shrunkDxt = maybeShrinkRgba(rgbaBytesFinal, texWidth, texHeight, name)
            val encodeW = shrunkDxt?.second ?: texWidth
            val encodeH = shrunkDxt?.third ?: texHeight
            val encodeRgba = shrunkDxt?.first ?: rgbaBytesFinal

            // The unified PC pipeline: DXT/BC sources always become ETC2
            // (resourcecompiler64's re-decision logic), the ETC2 encoder is
            // the PC's own etcpack, and the output header/LZ4/flag rules all
            // live in PcTexTranscoder (single mip, padded dims for ETC2,
            // LZ4 only >16383 B and under 0.95 ratio, bit 30 stripped,
            // 0x08 for raw bodies).
            val etc2EncodeStartMs = System.currentTimeMillis()
            val built = PcTexTranscoder.transcodeRgbaBody(
                texiFormat, forceEtc2Compression,
                origFlags, origUnkInt0, encodeW, encodeH, encodeRgba,
            ) { r, ww, hh ->
                try {
                    if (EtcpackEncoder.isAvailable()) {
                        EtcpackEncoder.encodeRgba8888(r, ww, hh)
                    } else {
                        // etcpack unavailable (should not happen — it ships in
                        // the same .so as the DXT decoder that just ran).
                        // Fall back to the legacy etc2comp encoder so the
                        // wallpaper still ships as VALID ETC2 (block bytes
                        // may differ from the PC's, everything else exact).
                        if (Etc2Encoder.isAvailable()) {
                            Etc2Encoder.encodeRgba8888(r, ww, hh, detectOpaque(r))
                        } else null
                    }
                } catch (e: Exception) {
                    WepWawetLogger.proxyError("MpkgPacker.convertTexDxtToEtc2: ETC2 encoding failed",
                        "name" to name, "exception" to e.javaClass.name, "message" to (e.message ?: ""))
                    null
                }
            } ?: run {
                WepWawetLogger.proxyWarn("MpkgPacker.convertTexDxtToEtc2: transcode produced no output",
                    "name" to name, "encode_dims" to "$encodeW x $encodeH")
                return null
            }
            val etc2ElapsedMs = System.currentTimeMillis() - etc2EncodeStartMs
            logEtc2Timing(name, encodeW, encodeH, etc2ElapsedMs)

            // ---- Step 6: preserve the TEXS0003 section (the PC writer does) ----
            val result = appendTexsSection(built, texData)

            val vInfo = PcTexTranscoder.parse(result)
            WepWawetLogger.proxy("MpkgPacker.convertTexDxtToEtc2 SUCCESS",
                "name" to name,
                "dxt_format" to dxtFormat.displayName,
                "original_size" to texData.size,
                "converted_size" to result.size,
                "encode_dims" to "$encodeW x $encodeH",
                "out_format" to (vInfo?.texiFormat ?: -1),
                "out_flags" to "0x%02x".format(vInfo?.flags ?: 0),
                "out_lz4" to (vInfo?.bodyIsLz4 ?: false),
                "texs_preserved" to (result.size > built.size),
                "elapsed_ms" to etc2ElapsedMs)

            // ---- Step 7: Store result in texture conversion cache ----
            texCacheStore(texData, result, name)

            return result
        } catch (e: Exception) {
            WepWawetLogger.proxyError("MpkgPacker.convertTexDxtToEtc2 FAILED",
                "name" to name, "exception" to e.javaClass.name, "message" to (e.message ?: ""))
            return null
        }
    }    /**
     * v0.7.16 (PC-exact): the format=0 / format=20 .tex pipeline — what the
     * PC's `resourcecompiler64.exe -transcode -f ETC2 -maxmipmaps 1` does to
     * a texture, reconstructed from a real PC-produced .mpkg (ground truth)
     * plus the wallpaperui.exe transcoder disassembly.
     *
     *  1. Parse the header (TEXB0003 and TEXB0004 layouts both occur in the
     *     wild; the PC normalizes the output to TEXB0004).
     *  2. Obtain the RGBA pixels:
     *       • LZ4 body        → decompress (mip 0 only; trailing mips dropped)
     *       • PNG/JPEG body   → decode at the image's INTRINSIC size
     *       • raw body        → as-is at the mip dims
     *  3. [PcTexTranscoder.transcodeRgbaBody] picks ETC2 vs RGBA vs raw and
     *     builds the normalized output.
     *
     * The image-decode path needs Android's BitmapFactory; on decode failure
     * we fall back to the raw-RGBA interpretation, then to null (caller
     * passes the original through with a warning).
     */
    private fun convertFormat0TexPc(texData: ByteArray, name: String): ByteArray? {
        try {
            texCacheLookup(texData, name)?.let { cached ->
                WepWawetLogger.proxy("MpkgPacker.convertFormat0TexPc: using cached conversion",
                    "name" to name, "cached_size" to cached.size)
                return cached
            }

            val info = PcTexTranscoder.parse(texData) ?: run {
                WepWawetLogger.proxyWarn("MpkgPacker.convertFormat0TexPc: unparseable .tex header",
                    "name" to name)
                return null
            }

            // ── obtain the (possibly decompressed) body ──
            val effectiveTex: ByteArray = if (info.bodyIsLz4 && info.decompSize > 0) {
                val bodySrc = PcTexTranscoder.bodyBytes(texData)
                val raw = try {
                    Lz4Compat.decompress(bodySrc, info.decompSize)
                } catch (e: Exception) {
                    WepWawetLogger.proxyWarn("MpkgPacker.convertFormat0TexPc: LZ4 body decompression failed",
                        "name" to name, "decomp_size" to info.decompSize,
                        "exception" to e.javaClass.name)
                    return null
                }
                // Rebuild a normalized in-memory .tex: header[0..bodyStart) + raw body.
                // bodyStart stays the same so downstream offsets hold.
                texData.copyOf(info.bodyStart) + raw
            } else {
                texData
            }

            val effInfo = PcTexTranscoder.parse(effectiveTex) ?: return null
            val body = effectiveTex.copyOfRange(effInfo.bodyStart, effectiveTex.size)

            // ── PNG/JPEG inside the body? → decode path ──
            val pngStart = findBytes(body, PNG_MAGIC, 0)
            val jpgStart = findValidJpegStart(body)
            if (pngStart >= 0 || jpgStart >= 0) {
                val decoded = decodeLargestImageToRgba(effectiveTex, name)
                if (decoded != null) {
                    val (rgba0, w0, h0) = decoded
                    val shrunk = maybeShrinkRgba(rgba0, w0, h0, name)
                    val (rgba, w, h) = shrunk?.let { Triple(it.first, it.second, it.third) } ?: Triple(rgba0, w0, h0)
                    val result = PcTexTranscoder.transcodeRgbaBody(
                        effInfo.texiFormat, forceEtc2Compression,
                        effInfo.flags, effInfo.color, w, h, rgba,
                    ) { r, ww, hh ->
                        try {
                            if (EtcpackEncoder.isAvailable()) {
                                EtcpackEncoder.encodeRgba8888(r, ww, hh)
                            } else null
                        } catch (e: Exception) {
                            WepWawetLogger.proxyError("MpkgPacker.convertFormat0TexPc: ETC2 encode failed",
                                "name" to name, "exception" to e.javaClass.name)
                            null
                        }
                    }
                    if (result != null) {
                        val withTexs = appendTexsSection(result, texData)
                        texCacheStore(texData, withTexs, name)
                        WepWawetLogger.proxy("MpkgPacker.convertFormat0TexPc: image transcoded (PC pipeline)",
                            "name" to name,
                            "source_kind" to "image",
                            "width" to w, "height" to h,
                            "shrunk" to (shrunk != null),
                            "etc2" to PcTexTranscoder.shouldUseEtc2(effInfo.texiFormat, forceEtc2Compression),
                            "texs_preserved" to (withTexs.size > result.size),
                            "original_size" to texData.size,
                            "converted_size" to withTexs.size)
                        return withTexs
                    }
                } else {
                    WepWawetLogger.proxyWarn("MpkgPacker.convertFormat0TexPc: image decode failed — trying raw-RGBA interpretation",
                        "name" to name)
                }
            }

            // ── raw (or LZ4-decompressed) RGBA body at the mip dims ──
            val result = PcTexTranscoder.transcodeRgbaBody(
                effInfo.texiFormat, forceEtc2Compression,
                effInfo.flags, effInfo.color, effInfo.mipW, effInfo.mipH, body,
            ) { r, ww, hh ->
                try {
                    if (EtcpackEncoder.isAvailable()) {
                        EtcpackEncoder.encodeRgba8888(r, ww, hh)
                    } else null
                } catch (e: Exception) {
                    null
                }
            }
            if (result != null) {
                val withTexs = appendTexsSection(result, texData)
                texCacheStore(texData, withTexs, name)
                WepWawetLogger.proxy("MpkgPacker.convertFormat0TexPc: RGBA body transcoded (PC pipeline)",
                    "name" to name,
                    "source_kind" to if (info.bodyIsLz4) "lz4-rgba" else "raw-rgba",
                    "width" to effInfo.mipW, "height" to effInfo.mipH,
                    "etc2" to PcTexTranscoder.shouldUseEtc2(effInfo.texiFormat, forceEtc2Compression),
                    "texs_preserved" to (withTexs.size > result.size),
                    "original_size" to texData.size,
                    "converted_size" to withTexs.size)
                return withTexs
            } else {
                WepWawetLogger.proxyWarn("MpkgPacker.convertFormat0TexPc: RGBA body rejected",
                    "name" to name,
                    "mip_dims" to "${effInfo.mipW}x${effInfo.mipH}",
                    "body_len" to body.size,
                    "expected" to (effInfo.mipW * effInfo.mipH * 4))
            }
            return result
        } catch (e: Exception) {
            WepWawetLogger.proxyError("MpkgPacker.convertFormat0TexPc FAILED",
                "name" to name, "exception" to e.javaClass.name, "message" to (e.message ?: ""))
            return null
        }
    }

    /** Find a valid JPEG (SOI + plausible marker) in [data] from offset 0. */
    private fun findValidJpegStart(data: ByteArray): Int {
        var pos = 0
        while (true) {
            val jpgStart = findBytes(data, JPEG_MAGIC, pos)
            if (jpgStart < 0) return -1
            if (jpgStart + 3 < data.size && isValidJpegMarker(data[jpgStart + 3])) return jpgStart
            pos = jpgStart + 3
        }
    }

    /** Sample the alpha channel — true when every sampled pixel is opaque. */
    private fun detectOpaque(rgba: ByteArray): Boolean {
        var i = 3
        val step = 4 * 1024
        while (i < rgba.size) {
            if ((rgba[i].toInt() and 0xFF) != 255) return false
            i += step
        }
        return true
    }

    /**
     * Decode the largest embedded PNG/JPEG in a .tex to RGBA at the image's
     * intrinsic size (the PC uses the image's own dims, un-padding the tex
     * header — e.g. a 2048×512 tex holding a 1500×489 PNG comes out 1500×489).
     * Returns (rgba, width, height) or null.
     */
    private fun decodeLargestImageToRgba(texData: ByteArray, name: String): Triple<ByteArray, Int, Int>? {
        data class Img(val bytes: ByteArray, val w: Int, val h: Int)
        val images = mutableListOf<Img>()

        // PNGs — extract each via chunk walk (findPngEnd)
        var searchPos = 0
        while (true) {
            val pngStart = findBytes(texData, PNG_MAGIC, searchPos)
            if (pngStart < 0) break
            val pngEnd = findPngEnd(texData, pngStart)
            if (pngEnd < 0) break
            val pngBytes = texData.copyOfRange(pngStart, pngEnd)
            // Animated GIF masquerading under the PNG search (GIF87a/89a magic)
            if (pngBytes.size > 3 && pngBytes[0] == 0x47.toByte() && pngBytes[1] == 0x49.toByte() &&
                pngBytes[2] == 0x46.toByte()) {
                try {
                    val movie = android.graphics.Movie.decodeByteArray(pngBytes, 0, pngBytes.size)
                    if (movie != null && movie.width() > 0 && movie.height() > 0) {
                        images.add(Img(pngBytes, movie.width(), movie.height()))
                    }
                } catch (_: Exception) { }
            } else {
                // Decode just to get the dims (cheap; full pixels extracted below for the winner)
                val opts = android.graphics.BitmapFactory.Options().apply { inJustDecodeBounds = true }
                android.graphics.BitmapFactory.decodeByteArray(pngBytes, 0, pngBytes.size, opts)
                if (opts.outWidth > 0 && opts.outHeight > 0) {
                    images.add(Img(pngBytes, opts.outWidth, opts.outHeight))
                }
            }
            searchPos = pngEnd
        }
        // JPEG — the whole remainder (BitmapFactory finds the real EOI)
        val jpgStart = findValidJpegStart(texData)
        if (images.isEmpty() && jpgStart >= 0) {
            val jpgBytes = texData.copyOfRange(jpgStart, texData.size)
            val opts = android.graphics.BitmapFactory.Options().apply { inJustDecodeBounds = true }
            android.graphics.BitmapFactory.decodeByteArray(jpgBytes, 0, jpgBytes.size, opts)
            if (opts.outWidth > 0 && opts.outHeight > 0) {
                images.add(Img(jpgBytes, opts.outWidth, opts.outHeight))
            }
        }
        if (images.isEmpty()) {
            WepWawetLogger.proxyWarn("MpkgPacker.decodeLargestImageToRgba: no decodable image found",
                "name" to name)
            return null
        }
        val largest = images.maxByOrNull { it.w.toLong() * it.h }!!
        if (largest.w.toLong() * largest.h > 80L * 1024 * 1024) {
            // 80 Mpx sanity cap — a corrupted header claiming a gigantic frame
            // would otherwise OOM the decode below.
            WepWawetLogger.proxyWarn("MpkgPacker.decodeLargestImageToRgba: image implausibly large — refusing",
                "name" to name, "width" to largest.w, "height" to largest.h)
            return null
        }

        // GIF special case: render frame 0 through Movie (BitmapFactory can't).
        if (largest.bytes.size > 3 && largest.bytes[0] == 0x47.toByte() &&
            largest.bytes[1] == 0x49.toByte() && largest.bytes[2] == 0x46.toByte()) {
            return try {
                val movie = android.graphics.Movie.decodeByteArray(largest.bytes, 0, largest.bytes.size)
                    ?: return null
                val bmp = android.graphics.Bitmap.createBitmap(
                    movie.width(), movie.height(), android.graphics.Bitmap.Config.ARGB_8888)
                val canvas = android.graphics.Canvas(bmp)
                bmp.eraseColor(android.graphics.Color.TRANSPARENT)
                movie.setTime(0)
                movie.draw(canvas, 0f, 0f)
                val buf = java.nio.ByteBuffer.allocateDirect(movie.width() * movie.height() * 4)
                    .order(java.nio.ByteOrder.nativeOrder())
                bmp.copyPixelsToBuffer(buf)
                bmp.recycle()
                val rgba = ByteArray(movie.width() * movie.height() * 4)
                buf.rewind(); buf.get(rgba)
                Triple(rgba, movie.width(), movie.height())
            } catch (e: Exception) {
                WepWawetLogger.proxyWarn("MpkgPacker.decodeLargestImageToRgba: GIF decode failed",
                    "name" to name, "exception" to e.javaClass.name)
                null
            }
        }

        // Standard decode — non-premultiplied, ARGB_8888.
        return try {
            val options = android.graphics.BitmapFactory.Options().apply {
                inPreferredConfig = android.graphics.Bitmap.Config.ARGB_8888
                inPremultiplied = false
            }
            val bitmap = android.graphics.BitmapFactory.decodeByteArray(
                largest.bytes, 0, largest.bytes.size, options) ?: return null
            val rgbaBitmap = if (bitmap.config == android.graphics.Bitmap.Config.ARGB_8888) {
                bitmap
            } else {
                val copy = bitmap.copy(android.graphics.Bitmap.Config.ARGB_8888, false)
                bitmap.recycle()
                copy ?: return null
            }
            val w = rgbaBitmap.width
            val h = rgbaBitmap.height
            val pixelBuffer = java.nio.ByteBuffer.allocateDirect(w * h * 4)
                .order(java.nio.ByteOrder.nativeOrder())
            rgbaBitmap.copyPixelsToBuffer(pixelBuffer)
            rgbaBitmap.recycle()
            val rgba = ByteArray(w * h * 4)
            pixelBuffer.rewind()
            pixelBuffer.get(rgba)
            Triple(rgba, w, h)
        } catch (e: Exception) {
            WepWawetLogger.proxyWarn("MpkgPacker.decodeLargestImageToRgba: decode failed",
                "name" to name, "exception" to e.javaClass.name)
            null
        }
    }
    /**
     * Rewrite project.json for the WE Android .mpkg format.
     *
     * Real WE PC's transcoder (function 0x14003d1e0 in wallpaperui.exe, see
     * research/we-transcoder/02_transcoder_14003d1e0.asm) rewrites project.json
     * during the .pkg -> .mpkg transcoding step. The rewrite:
     *
     *   1. Sets the "file" field to use the MPKG_VFS:// URI scheme
     *      (e.g., "scene.json" -> "MPKG_VFS://scene.json")
     *      (transcoder disasm line 14003ea47)
     *   2. Sets the "preview" field to use the MPKG_VFS:// URI scheme
     *      (e.g., "preview.jpg" -> "MPKG_VFS://preview.jpg")
     *      (transcoder disasm line 14003eacc)
     *   3. Sets the "title" field (preserved from original)
     *      (transcoder disasm line 14003eb31)
     *   4. Removes the "video" key if present
     *      (transcoder disasm line 14003eb70 calls 0x1402351c0 which is a
     *       different function from the JSON setter 0x140235460 — it's the
     *       JSON key remover. The .pkg's project.json may have a "video"
     *       section that the .mpkg's project.json does NOT have.)
     *   5. Sets the "type" field (preserved from original — "scene" or "video")
     *      (transcoder disasm line 14003eb80)
     *   6. Sets the "workshopurl" field
     *      (transcoder disasm line 1400416b8)
     *   7. Sets the "alignmentposition" field (for video wallpapers)
     *      (transcoder disasm line 14003f27b — value wrapped in {"value": <int>})
     *   8. Sets the "maximumprojectid" field
     *      (transcoder disasm line 14003fceb — value is the max object ID
     *       from scene.json's "objects" array, used by the Android side to
     *       pre-allocate object slots)
     *
     * The MPKG_VFS:// scheme is what the Android native lib (libscenejni.so)
     * uses to address files within the .mpkg's virtual file system. Without
     * this prefix, the Android app may not find the scene.json / wallpaper.mp4
     * / preview.jpg files inside the .mpkg.
     *
     * This function is conservative:
     *   - If "file" already has the MPKG_VFS:// prefix, it's left as-is.
     *   - If "file" is missing or empty, no rewrite is performed.
     *   - If "preview" is missing, it's left unset (the Android app will use
     *     a default preview).
     *   - All other fields (contentrating, description, general, tags,
     *     version, visibility) are preserved unchanged.
     *
     * @param projectJsonBytes The original project.json bytes from the .pkg.
     * @param maxObjectId The maximum object ID computed from scene.json's
     *                    "objects" array (for setting "maximumprojectid").
     *                    Pass -1 to skip setting this field.
     * @return The rewritten project.json bytes, or null on failure (caller
     *         should use the original bytes in that case).
     */
    private fun rewriteProjectJsonForMobile(projectJsonBytes: ByteArray, maxObjectId: Int = -1): ByteArray? {
        return try {
            val jsonStr = String(projectJsonBytes, Charsets.UTF_8)
            val root = JsonParser.parseString(jsonStr).asJsonObject
            var modified = false

            // 1. Remove the "video" key if present (per transcoder disasm line 14003eb70)
            // The .pkg's project.json may have a "video" section (with codec/codec params)
            // that the .mpkg's project.json does NOT have. WE PC's transcoder removes it.
            if (root.has("video")) {
                root.remove("video")
                modified = true
                WepWawetLogger.proxy("MpkgPacker.rewriteProjectJsonForMobile: removed video section (per WE PC transcoder)")
            }

            // 2. Set "type" explicitly to lowercase (preserves existing value, just normalized)
            // Real WE PC's .mpkg uses lowercase "scene" or "video" (per the extracted
            // project.json from the Mordred wallpaper's .pkg). The Android side expects
            // lowercase too (per libscenejni.so strings analysis).
            val typeField = root.get("type")
            if (typeField != null && typeField.isJsonPrimitive) {
                val typeVal = typeField.asString
                val normalized = typeVal.lowercase()
                if (normalized != typeVal) {
                    root.addProperty("type", normalized)
                    modified = true
                    WepWawetLogger.proxy("MpkgPacker.rewriteProjectJsonForMobile: normalized type field",
                        "original" to typeVal, "normalized" to normalized)
                }
            }

            // 3. Set "maximumprojectid" (per transcoder disasm line 14003fceb)
            // Value is the max object ID across scene.json's "objects" array.
            // This tells the Android side how many object slots to pre-allocate.
            if (maxObjectId >= 0) {
                root.addProperty("maximumprojectid", maxObjectId)
                modified = true
                WepWawetLogger.proxy("MpkgPacker.rewriteProjectJsonForMobile: set maximumprojectid",
                    "value" to maxObjectId)
            }

            // 4. Set "alignmentposition" for video wallpapers
            // (per transcoder disasm line 14003f27b)
            //
            // The transcoder wraps the value in {"value": <int>} format:
            //   - Line 14003f2a5: loads int from struct (r13+0xb0)
            //   - Line 14003f2b6: converts int to string
            //   - Line 14003f2be: concatenates "{"value":" + int_string + "}"
            //   - Sets as the "alignmentposition" field value
            //
            // The value represents the video's alignment position (0=centered,
            // 1=top-left, etc.). Without this field, the Android app uses a
            // default centered alignment.
            //
            // We only set this for video wallpapers (type=video). For scene
            // wallpapers, the field is not relevant.
            val currentType = root.get("type")?.asString?.lowercase() ?: "unknown"
            if (currentType == "video") {
                if (!root.has("alignmentposition")) {
                    // v0.7.4 (parity G9): the value is the configured video
                    // alignment position (0 = centered default — byte-identical
                    // to the previous hardcoded behavior; the PC writes the
                    // device/export configuration's int here, disasm 14003f27b).
                    val alignment = videoAlignmentPosition.coerceIn(0, 100)
                    root.addProperty("alignmentposition", "{\"value\": $alignment}")
                    modified = true
                    WepWawetLogger.proxy("MpkgPacker.rewriteProjectJsonForMobile: set alignmentposition",
                        "value" to "{\"value\": $alignment}",
                        "note" to "video wallpaper alignment position (G9 setting)")
                }
            }

            // NOTE: "file" and "preview" fields are PRESERVED as-is (no MPKG_VFS:// prefix).
            // The Android app's libscenejni.so looks up files by their relative paths
            // in the .mpkg VFS (e.g., "scene.json", "preview.gif", "materials/sun.tex").
            // Adding MPKG_VFS:// prefix breaks the file lookups → black image.
            // (Tested in v0.5.24-alpha — adding MPKG_VFS:// prefix caused the Saturn
            //  wallpaper to render as a completely black image. Reverted in v0.5.26-alpha.)

            if (!modified) {
                WepWawetLogger.proxyDebug("MpkgPacker.rewriteProjectJsonForMobile: no changes needed (already in mobile format)")
                return projectJsonBytes
            }

            // Serialize back to JSON with WE PC's formatting (tabs + \r\n line endings)
            val result = com.google.gson.GsonBuilder()
                .setPrettyPrinting()
                .serializeNulls()
                .create()
                .toJson(root)
                .toByteArray(Charsets.UTF_8)
            WepWawetLogger.proxy("MpkgPacker.rewriteProjectJsonForMobile: rewrite complete",
                "original_size" to projectJsonBytes.size,
                "rewritten_size" to result.size,
                "field_count" to root.size())
            result
        } catch (e: Exception) {
            WepWawetLogger.proxyError("MpkgPacker.rewriteProjectJsonForMobile FAILED — using original",
                "exception" to e.javaClass.name, "message" to (e.message ?: ""))
            null
        }
    }

    /**
     * Compute the maximum object ID from scene.json's "objects" array.
     *
     * Per the transcoder disasm (line 14003fceb), WE PC sets the
     * "maximumprojectid" field in project.json to the max object ID
     * across scene.json's objects. This tells the Android side how many
     * object slots to pre-allocate (avoids dynamic array growth).
     *
     * The "objects" array contains entries like:
     *   { "id": 1, "effects": [{ "id": 17, ... }] }
     *
     * We walk the entire tree and find the maximum "id" value.
     *
     * @param sceneJsonBytes The scene.json bytes.
     * @return The maximum "id" value found, or -1 if no ids found or on error.
     */
    private fun computeMaxObjectId(sceneJsonBytes: ByteArray): Int {
        return try {
            val root = JsonParser.parseString(String(sceneJsonBytes, Charsets.UTF_8)).asJsonObject
            var maxId = -1
            /**
             * v0.7.4: ITERATIVE walk over the JSON tree. The previous recursive
             * walkIdFields() had no depth limit — a pathologically nested
             * scene.json (malicious or machine-generated) blew the stack with
             * StackOverflowError, which is an Error (NOT an Exception) and
             * therefore escaped the outer catch, crashing the pack. The
             * explicit-stack version below computes the identical result
             * (maximum over ALL "id" fields — a safe over-approximation for
             * the Android side's object-slot pre-allocation) with bounded
             * heap usage instead of unbounded stack usage.
             */
            val stack = ArrayDeque<com.google.gson.JsonElement>()
            stack.addLast(root)
            while (stack.isNotEmpty()) {
                val value = stack.removeLast()
                if (value.isJsonObject) {
                    val obj = value.asJsonObject
                    val idField = obj.get("id")
                    if (idField != null && idField.isJsonPrimitive) {
                        try {
                            val id = idField.asInt
                            if (id > maxId) maxId = id
                        } catch (_: Exception) { /* not a number, skip */ }
                    }
                    for (key in obj.keySet()) {
                        stack.addLast(obj.get(key))
                    }
                } else if (value.isJsonArray) {
                    val arr = value.asJsonArray
                    for (i in 0 until arr.size()) {
                        stack.addLast(arr[i])
                    }
                }
            }
            maxId
        } catch (e: Exception) {
            WepWawetLogger.proxyWarn("MpkgPacker.computeMaxObjectId FAILED — returning -1",
                "exception" to e.javaClass.name, "message" to (e.message ?: ""))
            -1
        }
    }

    /**
     * Rewrite scene.json's "files" array entries (and any other file path
     * references) to use the MPKG_VFS:// URI scheme.
     *
     * Per the transcoder disassembly (lines 14004104e, 1400410cd, 140041125-14004116a),
     * WE PC iterates over scene.json's "files" array and prefixes each entry's
     * "value" field with "MPKG_VFS://". This is how the Android native lib
     * (libscenejni.so) addresses files within the .mpkg's virtual file system.
     *
     * Scene.json's "files" array (when present) typically contains entries like:
     *   { "file": "materials/foo.tex" }  →  { "file": "MPKG_VFS://materials/foo.tex" }
     *
     * Many scene.json files DON'T have a "files" array — in those cases, this
     * function is a no-op. The "files" array appears in scene.json files that
     * list all the textures/shaders/etc. they reference (used by some Workshop
     * wallpapers for caching/dependency tracking).
     *
     * This function also recursively rewrites any other string field that looks
     * like a relative file path (starts with "materials/", "shaders/",
     * "models/", "effects/", "sounds/") to use the MPKG_VFS:// scheme.
     *
     * @param sceneJsonBytes The original scene.json bytes from the .pkg.
     * @return The rewritten scene.json bytes, or null on failure (caller
     *         should use the original bytes in that case).
     */
    private fun rewriteSceneJsonForMobile(sceneJsonBytes: ByteArray): ByteArray? {
        // REVERTED in v0.5.26-alpha: The recursive path rewriting (adding MPKG_VFS://
        // prefix to all path-like strings in scene.json) was causing the Saturn
        // wallpaper to render as a completely black image on Android.
        //
        // The Android app's libscenejni.so looks up textures by their RELATIVE
        // paths in the .mpkg VFS (e.g., "materials/sun.tex"). Adding the
        // MPKG_VFS:// prefix changed these to "MPKG_VFS://materials/sun.tex"
        // which does NOT exist as a VFS entry name → no textures found → black.
        //
        // The fixSceneJsonTextureBindings function (called earlier in packFromPkg)
        // already handles the null texture bindings. No further scene.json rewrite
        // is needed for the Android app to correctly render Scene wallpapers.
        //
        // The transcoder disasm at lines 14004104e/1400410cd/140041125 does
        // iterate over a "files" array and set "value" fields, but this appears
        // to be for an INTERNAL data structure used during transcoding (not
        // written to the .mpkg's scene.json). The .mpkg's scene.json should
        // preserve the same texture paths as the .pkg's scene.json.
        WepWawetLogger.proxyDebug("MpkgPacker.rewriteSceneJsonForMobile: no-op (recursive rewrite reverted to prevent black image)")
        return sceneJsonBytes
    }

    /**
     * Fix scene.json — replace null texture bindings with shader-declared defaults.
     *
     * On WE PC, null in a textures array means "use the default texture from the
     * installation directory". On WE Android, null means "no texture" — the shader
     * samples a missing texture and gets 0 (transparent/black).
     *
     * This breaks effects like X-ray that depend on a "sprite" texture (g_Texture2)
     * for the cursor halo. The shader declares:
     *   uniform sampler2D g_Texture2; // {"default":"particle/halo_6"}
     *
     * On PC, null → uses "particle/halo_6" from installation.
     * On Android, null → no texture → sprite.ra = 0 → blend = 0 → NO X-RAY.
     *
     * Fix: parse scene.json, find all texture arrays, look up the shader's
     * default texture names from the .frag files, and replace nulls with defaults.
     */

    /**
     * Fix shader files for OpenGL ES (GLSL 3.00 ES) compatibility.
     *
     * WE PC uses DirectX (HLSL) which allows implicit int→float conversion.
     * GLSL ES 3.00 does NOT — integer constants must have `.0` suffix.
     * WE Android's automatic int→float conversion has bugs and misses some
     * inline constants, causing shader compilation failures. Per WE docs:
     * "If a shader cannot be compiled on the mobile version of Wallpaper
     * Engine, the effect will be removed automatically."
     *
     * Specific errors from WE Android error log (v0.4.x):
     * 1. `pointer * 2 - 1` → GLSL error: vec2 * int not allowed
     *    Fix: replace `* 2 - 1` with `* 2.0 - 1.0`
     * 2. `mix(999, ...)` → GLSL error: mix() requires matching types
     *    Fix: replace `mix(999,` with `mix(999.0,`
     * 3. `*= 100;` → GLSL error: float *= int not allowed
     *    Fix: replace `*= 100;` with `*= 100.0;`
     * 4. `.w = 0;` → GLSL error: assigning int to float
     *    Fix: replace `= 0;` with `= 0.0;` (in float context)
     * 5. `g_Texture0` used before declaration in .frag files
     *    Fix: hoist `uniform sampler2D` declarations to top of file
     * 6. `simTexel * 100 * g_RippleSpeed` → vec2 * int * float not allowed
     *    Fix: replace `* 100 *` with `* 100.0 *`
     * 7. `vec4(0, 0, 0, 0)` → vec4 constructor doesn't accept int args
     *    Fix: replace all int args with floats: `vec4(0.0, 0.0, 0.0, 0.0)`
     * 8. `1 / 3.0` → int / float not allowed
     *    Fix: replace `1 /` with `1.0 /`
     * 9. `float reflectUp = 0;` (also `maskUp`, `reflectDown`, etc.)
     *    Fix: replace `= 0;` with `= 0.0;` (in float context)
     * 10. `vec4 sample;` → 'sample' is a reserved keyword in GLSL ES 3.00
     *    Fix: rename variable to `sampleVal`
     * 11. `g_RippleSpeed` used but not declared (sometimes in workshop .frag files)
     *    Fix: add `uniform float g_RippleSpeed;` if used but not declared
     * 12. blur_precise_gaussian.frag has `#include "common_blur.h"` BEFORE
     *     `uniform sampler2D g_Texture0;` — the preprocessor inlines the .h's
     *     functions (which reference g_Texture0) BEFORE the declaration,
     *     causing "undeclared identifier g_Texture0" errors.
     *     Fix: hoist ALL uniform sampler2D declarations to BEFORE any #include.
     */

    /**
     * v0.7.6-alpha (parity FIX 4 — zcompat shader substitution):
     *
     * WE PC ships a "zcompat" mechanism that, when preparing shaders for
     * mobile, substitutes known-broken workshop shaders from
     * `assets/zcompat/scene/shaders/<effectWorkshopId>/` (with config.json
     * stage mapping). WE 2.8.42 ships exactly two:
     *   - 2084198056 (Simple_Audio_Bars) — uses lerp(), uint arithmetic,
     *     integer RESOLUTION #define in float context, ApplyBlending from
     *     common_blending.h. The stock shader fails to compile on GLSL ES
     *     3.00 with 11 errors. The zcompat version uses mix(), explicit
     *     float() casts, mod2() precision helper, and explicit int() index
     *     casts — all already mobile-safe.
     *   - 2078835426 (pixelate) — uses lerp as a *variable name* in HLSL
     *     builds (would be mis-rewritten by a naive lerp→mix textual swap).
     *     The zcompat version is the canonical mobile build.
     *
     * The substitution is verified byte-identical to a real WE 2.8.42 install
     * (mirror: github.com/yakisoba0728/Waple under
     * Sources/WapleRender/Resources/WEAssets/zcompat/scene/shaders/).
     *
     * This function:
     *  1. Parses the shader's VFS path to extract the workshop effect id
     *     (the *last* `workshop/<id>/effects/<name>.(frag|vert)` segment).
     *  2. Looks up `assets/zcompat/scene/shaders/<id>/config.json` to find
     *     the configured frag/vert filenames for that effect id.
     *  3. Returns the zcompat shader bytes for the matching stage (frag/vert),
     *     or null if no zcompat entry exists for this effect id.
     *
     * The caller (fixShadersForMobile) MUST skip the generic fixer for any
     * substituted file — zcompat files are already mobile-safe and would be
     * CORRUPTED by the generic fixer (e.g., the saturate→clamp regex would
     * break the existing `#define saturate(x) clamp(x, 0.0, 1.0)` macro that
     * WE Android's preprocessor expects to see, and the int→float regex
     * would convert the explicit `int(barFreq1)` index cast into invalid
     * `int(barFreq1.0)`).
     *
     * @param name The shader VFS path (e.g.
     *             `shaders/workshop/3306828160/workshop/2084198056/effects/Simple_Audio_Bars.frag`)
     * @param original The original shader bytes (used for size logging only)
     * @return The zcompat-substituted shader bytes, or null if no zcompat
     *         entry exists for this effect id (caller proceeds with the
     *         generic fixer).
     */
    private fun substituteZcompatShader(name: String, original: ByteArray): ByteArray? {
        // Extract the LAST workshop effect id from the shader path.
        // Examples:
        //   shaders/workshop/3306828160/effects/pulse.frag
        //     → no nested workshop id → returns null (not a zcompat candidate
        //       unless 3306828160 itself is in zcompat, which it isn't)
        //   shaders/workshop/3306828160/workshop/2084198056/effects/Simple_Audio_Bars.frag
        //     → effect id 2084198056, shader name Simple_Audio_Bars.frag
        //   shaders/workshop/3655958892/workshop/2078835426/effects/pixelate.frag
        //     → effect id 2078835426, shader name pixelate.frag
        //
        // We accept BOTH levels (parent workshop and nested workshop
        // effect) — the parent's own effects (`shaders/workshop/<parentId>/effects/...`)
        // are checked against zcompat too, since a workshop could itself be
        // a known-broken effect (although none ship in 2.8.42 today).
        val zcompatIdRegex = Regex("""workshop/(\d+)/effects/([^/]+\.(?:frag|vert))$""")
        val matches = zcompatIdRegex.findAll(name).toList()
        if (matches.isEmpty()) return null

        // Use the LAST match (the innermost workshop effect id, which is the
        // actual effect being referenced — the outer is just the parent
        // wallpaper that bundles the effect).
        val match = matches.last()
        val effectId = match.groupValues[1]
        val shaderFileName = match.groupValues[2]

        // Determine the stage (frag or vert) from the file extension.
        val stage = when {
            name.endsWith(".frag", ignoreCase = true) -> "frag"
            name.endsWith(".vert", ignoreCase = true) -> "vert"
            else -> return null
        }

        // Load the zcompat config.json for this effect id.
        // The config.json contains:
        //   {
        //     "maximumprojectid": "<int>",  // ignored (used by WE PC for object slot pre-alloc)
        //     "frag": "<filename>.frag",   // the mobile-safe .frag shader filename
        //     "vert": "<filename>.vert"    // the mobile-safe .vert shader filename
        //   }
        val configPath = "zcompat/scene/shaders/$effectId/config.json"
        val configBytes = try {
            com.wepwawet.WepWawetApplication.instance.assets.open(configPath).use { it.readBytes() }
        } catch (e: Exception) {
            // No zcompat entry for this effect id — fall through to the generic fixer.
            return null
        }

        // Parse the config to find the stage's shader filename.
        val stageFile = try {
            val config = JsonParser.parseString(String(configBytes, Charsets.UTF_8)).asJsonObject
            config.get(stage)?.asString
        } catch (e: Exception) {
            WepWawetLogger.proxyWarn("MpkgPacker.substituteZcompatShader: config.json parse failed",
                "name" to name, "effect_id" to effectId, "exception" to e.javaClass.name)
            return null
        }
        if (stageFile == null) {
            WepWawetLogger.proxyWarn("MpkgPacker.substituteZcompatShader: config.json missing stage entry",
                "name" to name, "effect_id" to effectId, "stage" to stage)
            return null
        }

        // Sanity-check: the stage filename in config.json should match the
        // original shader's filename (e.g., Simple_Audio_Bars.frag → frag:
        // "Simple_Audio_Bars.frag"). If they don't match, the zcompat entry
        // is for a DIFFERENT shader name (e.g., the workshop renamed the
        // shader) — fall back to the generic fixer for safety.
        //
        // We compare basenames only, since the zcompat stageFile is a
        // relative filename within the zcompat dir, while the original
        // shader's name is its full VFS path.
        val stageFileBase = stageFile.substringAfterLast('/')
        val originalFileBase = shaderFileName.substringAfterLast('/')
        if (!stageFileBase.equals(originalFileBase, ignoreCase = true)) {
            WepWawetLogger.proxyWarn("MpkgPacker.substituteZcompatShader: stage filename mismatch — skipping substitution",
                "name" to name, "effect_id" to effectId, "stage" to stage,
                "expected_stage_file" to stageFile, "actual_shader_file" to shaderFileName,
                "reason" to "zcompat entry is for a different shader name")
            return null
        }

        // Load the zcompat shader bytes for this stage.
        val zcompatPath = "zcompat/scene/shaders/$effectId/$stageFile"
        val zcompatBytes = try {
            com.wepwawet.WepWawetApplication.instance.assets.open(zcompatPath).use { it.readBytes() }
        } catch (e: Exception) {
            WepWawetLogger.proxyWarn("MpkgPacker.substituteZcompatShader: zcompat shader file not found",
                "name" to name, "effect_id" to effectId, "zcompat_path" to zcompatPath,
                "exception" to e.javaClass.name)
            return null
        }

        WepWawetLogger.proxy("MpkgPacker.substituteZcompatShader: SUBSTITUTED",
            "file" to name,
            "effect_id" to effectId,
            "stage" to stage,
            "zcompat_path" to zcompatPath,
            "original_size" to original.size,
            "zcompat_size" to zcompatBytes.size,
            "reason" to "zcompat shaders are already mobile-safe (mix, float() casts, int() casts, mod2()) — skipping generic fixer to avoid double-fixing")
        return zcompatBytes
    }

    /**
     * v0.7.6-alpha (parity FIX 3 — .tex body LZ4 decompression helper):
     * v0.7.7-alpha (CRITICAL FIX: don't use bogus byteCount at offset 87):
     *
     * For .tex files where the TEXI format=0 (FIF_UNKNOWN — auto-detect via
     * magic bytes) AND IsLZ4=1, the PNG/JPEG magic search at offset 87+
     * fails because the bytes there are LZ4-compressed, not raw PNG/JPEG.
     * This causes the dispatcher to fall through to passthrough — meaning
     * the raw LZ4-compressed RGBA8888/PNG .tex is shipped as-is to the
     * .mpkg, and the WE Android renderer can't load it.
     *
     * v0.7.7 FIX: The v0.7.6 implementation read byteCount from offset 87
     * and used it to slice the body. But for format=0 .tex files, the
     * byteCount field at offset 87 is BOGUS (e.g., 1208090609 for a
     * 355849-byte file). The LZ4 safe decompressor's
     * `decompress(src, destLen)` stops when it's produced destLen bytes,
     * so we don't need to know the exact compressed size — we can pass
     * the ENTIRE remaining .tex from offset 91 (or 87) to end-of-file
     * as src, and the decompressor will stop at the right place. Extra
     * bytes at the end (e.g., TEXS animation sections) are ignored.
     *
     * We try offset 91 first (standard layout with byteCount field),
     * then offset 87 (layout without byteCount field, where the body
     * starts immediately after the 4-u32 mipmap header). Whichever
     * produces a valid LZ4 decompression to `decompressedSize` bytes
     * wins.
     *
     * This helper returns a NEW .tex with the body bytes decompressed and
     * the IsLZ4 field reset to 0, so subsequent magic-byte search and the
     * existing convertTexPngToEtc2 / convertTexRgba8888ToEtc2 paths can
     * operate on the decompressed body.
     *
     * If the body is not LZ4-compressed (IsLZ4=0), returns the original
     * .tex unchanged.
     *
     * Returns null on decompression failure (caller should fall back to
     * passthrough of the original .tex).
     */
    private fun decompressTexBodyIfLz4(texData: ByteArray, name: String): ByteArray? {
        if (texData.size < 91) return texData
        // Reuse parseTexHeader to get (format, isLz4) — but we only need isLz4.
        val header = parseTexHeader(texData) ?: return texData
        val isLz4 = header.second
        if (!isLz4) return texData

        // Read the mipmap-0 header fields needed for decompression.
        // Layout (per parseTexHeader's documented offsets):
        //   71..74: width, 75..78: height, 79..82: IsLZ4, 83..86: DecompressedSize
        //   87..90: byteCount (BOGUS for format=0 .tex files — see v0.7.7 fix note)
        //   91+: body data (LZ4-compressed if IsLZ4=1)
        val decompressedSize = readU32Le(texData, 83).toInt()
        if (decompressedSize <= 0) {
            WepWawetLogger.proxyWarn("MpkgPacker.decompressTexBodyIfLz4: invalid decompressedSize",
                "name" to name,
                "decompressed_size" to decompressedSize,
                "tex_size" to texData.size)
            return null
        }

        // v0.7.7: Don't use byteCount at offset 87 — it's bogus for format=0
        // .tex files. Instead, try decompressing from offset 91 and offset 87
        // using the ENTIRE remaining .tex as the compressed body. The LZ4 safe
        // decompressor stops when it's produced `decompressedSize` bytes.
        val lz4Factory = net.jpountz.lz4.LZ4Factory.safeInstance()
        val decompressor = lz4Factory.safeDecompressor()

        // Try offset 91 first (standard layout: 87-byte header + 4-byte byteCount + body).
        // Then try offset 87 (layout without byteCount field: 87-byte header + body).
        val offsetsToTry = listOf(91, 87)
        for (bodyStart in offsetsToTry) {
            if (bodyStart >= texData.size) continue
            val bodyBytes = texData.copyOfRange(bodyStart, texData.size)
            val decompressed = try {
                decompressor.decompress(bodyBytes, decompressedSize)
            } catch (e: Exception) {
                // Try next offset
                continue
            }
            if (decompressed.size != decompressedSize) {
                // Size mismatch — try next offset
                continue
            }
            // SUCCESS! Build a new .tex with the same header (0..78 inclusive)
            // but with IsLZ4=0, DecompressedSize=decompressedSize, byteCount=decompressedSize,
            // and the decompressed body starting at offset 91.
            val out = java.io.ByteArrayOutputStream()
            // Copy header bytes 0..78 (79 bytes: TEXV + TEXI + TEXI fields + TEXB + TEXB fields + mipmap_count + mipmap w/h)
            out.write(texData, 0, 79)
            writeLeIntToStream(out, 0)  // IsLZ4 = 0 (uncompressed now)
            writeLeIntToStream(out, decompressedSize)  // DecompressedSize (unchanged)
            writeLeIntToStream(out, decompressedSize)  // byteCount = decompressedSize
            out.write(decompressed)
            val result = out.toByteArray()
            WepWawetLogger.proxy("MpkgPacker.decompressTexBodyIfLz4: decompressed body",
                "name" to name,
                "body_start_offset" to bodyStart,
                "compressed_body_size" to bodyBytes.size,
                "decompressed_body_size" to decompressedSize,
                "new_tex_size" to result.size,
                "original_tex_size" to texData.size)
            return result
        }

        // Both offsets failed — return null (caller falls back to passthrough).
        WepWawetLogger.proxyWarn("MpkgPacker.decompressTexBodyIfLz4: LZ4 decompression failed for both offsets",
            "name" to name,
            "decompressed_size" to decompressedSize,
            "tex_size" to texData.size,
            "tried_offsets" to offsetsToTry.joinToString(","))
        return null
    }

    /**
     * v0.7.16: PC-exact shader fixing — a verbatim port of wallpaperui.exe's
     * fixShader (0x140015f20 + fixBlendOpacity 0x140014c20), decoded from the
     * shipping binary. See [PcShaderFixes] for the complete documented
     * pipeline (blur hoist → name-gated effect patches → per-line generic
     * passes → BlendOpacity inlining).
     *
     * This REPLACES the entire v0.4–v0.7.15 regex-soup ("bandaid") pipeline:
     *   ✗ native glslang routing for .vert/.frag        (the PC never does this)
     *   ✗ cbuffer/SamplerState/Texture2D rewrites        (not in the binary)
     *   ✗ lerp→mix                                       (not in the binary — the
     *                                                     PC ships lerp untouched;
     *                                                     WE Android's preprocessor
     *                                                     provides the macro table)
     *   ✗ mul→mulVal / sample→sampleVal                  (the PC renames sample
     *                                                     to _sample — $1_$2$3)
     *   ✗ the v0.4.13 comment/string skip-mask           (the PC only skips
     *                                                     ^\s*#, ^\s*\[,
     *                                                     ^(uniform|attribute|varying|out|in)
     *                                                     and for/while lines)
     *   ✗ the v0.7.5–v0.7.13 xray injection heuristics   (the PC has an exact
     *                                                     xray patch table)
     *   ✗ the v0.7.15 uniform-hoisting pass              (THE regression that
     *                                                     merged uniform lines
     *                                                     into [COMBO] comments and
     *                                                     killed the shake effect
     *                                                     — "no movement at all".
     *                                                     The PC only hoists
     *                                                     g_Texture0 for the
     *                                                     common_blur.h include)
     *
     * Kept (verified correct against a real PC-produced .mpkg):
     *   ✓ zcompat substitution (assets/zcompat/scene/shaders/<id>/) — the PC
     *     substitutes the mobile-safe shader, and STILL runs fixShader on the
     *     substituted bytes (byte-verified: zcompat Simple_Audio_Bars.frag +
     *     fixShader == the PC's 11662-byte output, byte for byte).
     *   ✓ every .vert/.frag/.geom goes through the fixer, substituted or not.
     */
    private fun fixShadersForMobile(entries: MutableList<Pair<String, ByteArray>>) {
        var fixedCount = 0
        var zcompatSubstitutedCount = 0
        for (i in entries.indices) {
            val (name, blob) = entries[i]
            val lower = name.lowercase()
            if (!lower.endsWith(".vert") && !lower.endsWith(".frag") && !lower.endsWith(".geom")) continue

            // 1. zcompat substitution FIRST (PC parity — the exe checks
            //    assets/zcompat/scene/shaders/<effectId>/config.json and
            //    swaps in the mobile-safe shader before fixing).
            val zcompatSubstituted = substituteZcompatShader(name, blob)
            val effectiveBytes = zcompatSubstituted ?: blob
            if (zcompatSubstituted != null) {
                zcompatSubstitutedCount++
                WepWawetLogger.proxy("MpkgPacker.fixShaders: zcompat-substituted",
                    "file" to name,
                    "zcompat_size" to zcompatSubstituted.size,
                    "original_size" to blob.size)
            }

            // 2. The PC's shader chain (deprModFix @0x140017dd0 BEFORE
            //    fixShader @0x140015f20 — both decoded from wallpaperui.exe)
            //    followed by the Android-side GLSL ES safety pass. Name =
            //    shader file stem (basename without extension), compared for
            //    exact equality against the known-broken effect names
            //    (foliagesway/blend/shift_hue/xray/perspective/skew).
            val stem = name.substringAfterLast('/').substringBeforeLast('.')
            val original = String(effectiveBytes, Charsets.UTF_8)
            val pcFixed = PcShaderFixes.fixShader(stem, PcShaderFixes.deprModFix(original))
            val (fixed, safetyEdits) = ShaderEsslSafety.applyWithReport(pcFixed)
            if (safetyEdits.isNotEmpty()) {
                WepWawetLogger.proxy("MpkgPacker.fixShaders: ESSL safety pass applied",
                    "file" to name, "edits" to safetyEdits.joinToString("; "))
            }
            if (fixed != original) {
                entries[i] = Pair(name, fixed.toByteArray(Charsets.UTF_8))
                fixedCount++
                WepWawetLogger.proxy("MpkgPacker.fixShaders: PC fixShader applied",
                    "file" to name, "stem" to stem,
                    "original_size" to original.length, "fixed_size" to fixed.length,
                    "zcompat_input" to (zcompatSubstituted != null),
                    "essl_safety_edits" to safetyEdits.size)
            }
        }
        if (fixedCount > 0) {
            WepWawetLogger.proxy("MpkgPacker.fixShaders: fixed $fixedCount shader files (PC-exact pipeline)",
                "zcompat_substituted" to zcompatSubstitutedCount)
        }
    }

    // ==================== v0.7.4 PARITY HELPERS (G5/G6/G7) ====================

    /**
     * v0.7.4 (parity G7): remove scenetexture references whose file is absent
     * from the package — the PC's missing-texture removal rule.
     *
     * The PC's transcoder iterates the scene's texture/material map and
     * ERASES every entry whose referenced file does not exist in the output
     * package (FUN_1401403d0e0's map-erasure loop around the "scenetexture"
     * string comparison — the erase pattern writes puVar21[2]=0/[3]=7/[0]=0,
     * the std::map node-removal dance). Keeping the dangling reference on
     * Android produces phantom-texture artifacts (yellow/black checkerboard
     * quads where the missing texture is sampled).
     *
     * WepWawet implements the removal conservatively:
     *  - A reference qualifies as "missing" only when it has a known texture
     *    file extension, is not in the VFS, AND its top-level directory
     *    (e.g. "materials/") exists in this package — WE built-in assets
     *    ("particle/halo_6", "util/blur.png", …) live inside the phone app
     *    and must never be touched.
     *  - The slot is set to JSON null (not removed from the array) so the
     *    subsequent [fixSceneJsonTextureBindings] pass can fill it with the
     *    shader's declared default — the same end state the PC arrives at
     *    for these materials.
     *  - scene.json's top-level "files" manifest array additionally drops
     *    entries whose "file" is missing from the VFS (the PC's literal
     *    manifest cleanup at the "files" lookup).
     */
    private fun removeMissingSceneTextures(
        entries: MutableList<Pair<String, ByteArray>>,
        vfsNames: Set<String>,
    ) {
        try {
            val sceneIdx = entries.indexOfFirst { it.first == "scene.json" }
            if (sceneIdx < 0) return
            val root = JsonParser.parseString(
                String(entries[sceneIdx].second, Charsets.UTF_8)).asJsonObject

            // Top-level directories that exist in this package (e.g.
            // "materials", "textures") — references under them are ours to
            // validate. Built-ins under other roots are never touched.
            val vfsDirs = vfsNames.mapNotNull { path ->
                path.substringBefore('/', "").ifEmpty { null }
            }.toSet()

            val textureExtensions = listOf(
                ".tex", ".png", ".jpg", ".jpeg", ".tga", ".webp", ".dds", ".bmp",
            )

            fun isMissingFileRef(value: String): Boolean {
                if (value.isBlank()) return false
                val lower = value.lowercase()
                if (textureExtensions.none { lower.endsWith(it) }) return false
                if (value in vfsNames) return false
                val topDir = value.substringBefore('/', "")
                return topDir.isNotEmpty() && topDir in vfsDirs
            }

            var nulledSlots = 0

            // 1. objects → effects → passes → textures[] — null the missing refs
            val objects = root.getAsJsonArray("objects")
            if (objects != null) {
                for (obj in objects) {
                    val objJson = obj.asJsonObject
                    val effects = objJson.get("effects")?.asJsonArray ?: continue
                    for (effect in effects) {
                        val effectObj = effect.asJsonObject
                        val passes = effectObj.get("passes")?.asJsonArray ?: continue
                        for (pass in passes) {
                            val passObj = pass.asJsonObject
                            val textures = passObj.get("textures")?.asJsonArray ?: continue
                            for (i in 0 until textures.size()) {
                                val el = textures[i]
                                if (el.isJsonPrimitive && el.asString.let(::isMissingFileRef)) {
                                    textures[i] = com.google.gson.JsonNull.INSTANCE
                                    nulledSlots++
                                    WepWawetLogger.proxy(
                                        "MpkgPacker.removeMissingSceneTextures: nulled missing texture ref",
                                        "slot" to i,
                                        "value" to el.asString,
                                        "reason" to "file not present in .pkg VFS (PC G7 rule)")
                                }
                            }
                        }
                    }
                }
            }

            // 2. top-level "files" manifest — drop entries whose file is missing
            //    (entries may be objects {"file": …} or plain strings — handle both)
            var droppedManifest = 0
            val filesArray = root.get("files")?.takeIf { it.isJsonArray }?.asJsonArray
            if (filesArray != null) {
                val kept = com.google.gson.JsonArray()
                for (fe in filesArray) {
                    val fileRef = when {
                        fe.isJsonObject -> fe.asJsonObject.get("file")
                            ?.takeIf { it.isJsonPrimitive }?.asString
                        fe.isJsonPrimitive -> fe.asString
                        else -> null
                    }
                    if (fileRef != null && isMissingFileRef(fileRef)) {
                        droppedManifest++
                        WepWawetLogger.proxy(
                            "MpkgPacker.removeMissingSceneTextures: dropped files-manifest entry",
                            "file" to fileRef,
                            "reason" to "file not present in .pkg VFS (PC G7 rule)")
                    } else {
                        kept.add(fe)
                    }
                }
                if (droppedManifest > 0) root.add("files", kept)
            }

            if (nulledSlots > 0 || droppedManifest > 0) {
                entries[sceneIdx] = "scene.json" to
                    com.google.gson.GsonBuilder().setPrettyPrinting().create()
                        .toJson(root).toByteArray(Charsets.UTF_8)
                WepWawetLogger.proxy("MpkgPacker.removeMissingSceneTextures: scene.json updated",
                    "nulled_texture_slots" to nulledSlots,
                    "dropped_manifest_entries" to droppedManifest)
            } else {
                WepWawetLogger.proxyDebug(
                    "MpkgPacker.removeMissingSceneTextures: no missing texture references found")
            }
        } catch (e: Exception) {
            WepWawetLogger.proxyWarn("MpkgPacker.removeMissingSceneTextures: failed",
                "exception" to e.javaClass.name,
                "message" to (e.message ?: ""))
        }
    }

    /**
     * v0.7.4 (parity G6): generate a 360 px preview JPEG from a video file,
     * like the PC's preview thumbnailer (FUN_140018a50 — 360 px generation).
     * Grabs the frame at t = 1 s (falls back to t = 0 for shorter clips)
     * and scales the long edge to 360 px.
     *
     * @return JPEG bytes, or null when no frame could be extracted (the
     *         caller then falls back to the nopreview placeholder).
     */
    private fun generateVideoPreviewJpeg(videoFile: File): ByteArray? {
        val retriever = android.media.MediaMetadataRetriever()
        try {
            retriever.setDataSource(videoFile.absolutePath)
            val durationMs = runCatching {
                retriever.extractMetadata(
                    android.media.MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull()
            }.getOrNull() ?: 0L

            val candidates = if (durationMs > 1200) {
                listOf(1_000_000L, 500_000L, 0L)  // µs positions to try
            } else {
                listOf(100_000L, 0L)
            }
            var bitmap: Bitmap? = null
            for (timeUs in candidates) {
                bitmap = runCatching { retriever.getFrameAtTime(timeUs) }.getOrNull()
                if (bitmap != null) break
            }
            if (bitmap == null) {
                WepWawetLogger.proxyWarn(
                    "MpkgPacker.generateVideoPreviewJpeg: no frame extracted",
                    "duration_ms" to durationMs)
                return null
            }

            // Scale long edge to 360 px (PC's preview thumbnail size).
            val longEdge = maxOf(bitmap.width, bitmap.height)
            val scaled = if (longEdge > 360) {
                val scale = 360f / longEdge
                val w = (bitmap.width * scale).toInt().coerceAtLeast(1)
                val h = (bitmap.height * scale).toInt().coerceAtLeast(1)
                Bitmap.createScaledBitmap(bitmap, w, h, true).also {
                    if (it !== bitmap) bitmap.recycle()
                }
            } else {
                bitmap
            }

            val baos = ByteArrayOutputStream()
            scaled.compress(Bitmap.CompressFormat.JPEG, 85, baos)
            if (scaled !== bitmap) scaled.recycle()
            return baos.toByteArray()
        } catch (e: Exception) {
            WepWawetLogger.proxyWarn("MpkgPacker.generateVideoPreviewJpeg: exception",
                "exception" to e.javaClass.name,
                "message" to (e.message ?: ""))
            return null
        } finally {
            runCatching { retriever.release() }
        }
    }

    /**
     * v0.7.4 (parity G6): the "nopreview.jpg" placeholder — the PC bundles
     * this exact asset name in wallpaperui.exe and uses it whenever a
     * project ships without any preview (string verified in the binary).
     * WepWawet generates the equivalent placeholder programmatically (a
     * 360×202 dark-gray slate) so no binary asset has to live in git.
     */
    private fun buildNoPreviewJpeg(): ByteArray {
        val bitmap = Bitmap.createBitmap(360, 202, Bitmap.Config.ARGB_8888)
        bitmap.eraseColor(android.graphics.Color.rgb(24, 24, 28))
        val baos = ByteArrayOutputStream()
        bitmap.compress(Bitmap.CompressFormat.JPEG, 85, baos)
        bitmap.recycle()
        return baos.toByteArray()
    }

    /**
     * v0.7.18 (PC-exact): the `-shrink <n>` lever — resourcecompiler64
     * FUN_140063c70 @1400645e1 applies the reduction ONLY when BOTH source
     * dims exceed 128 px, with the factor clamped to 1..8 and the new dims
     * taken by integer division (no 4-px snapping — the ETC2 path pads
     * afterwards). The resample is a bilinear filter with pixel-center
     * alignment (OpenCV INTER_LINEAR, the PC's cv::resize default).
     *
     * @return null when shrinking is disabled or the texture is too small;
     *         otherwise the shrunken (bytes, width, height).
     */
    private fun maybeShrinkRgba(
        rgba: ByteArray, width: Int, height: Int, name: String,
    ): Triple<ByteArray, Int, Int>? {
        val (newW, newH) = PcTexTranscoder.shrinkDims(width, height, textureShrinkFactor)
        if (newW == width && newH == height) return null

        val out = ByteArray(newW * newH * 4)
        val xScale = width.toDouble() / newW
        val yScale = height.toDouble() / newH
        for (y in 0 until newH) {
            // pixel-center aligned source coordinate (INTER_LINEAR)
            val fy = (y + 0.5) * yScale - 0.5
            val sy0 = fy.toInt().coerceIn(0, height - 1)
            val sy1 = (sy0 + 1).coerceAtMost(height - 1)
            val wy = (fy - sy0).coerceIn(0.0, 1.0)
            for (x in 0 until newW) {
                val fx = (x + 0.5) * xScale - 0.5
                val sx0 = fx.toInt().coerceIn(0, width - 1)
                val sx1 = (sx0 + 1).coerceAtMost(width - 1)
                val wx = (fx - sx0).coerceIn(0.0, 1.0)
                val o = (y * newW + x) * 4
                for (c in 0 until 4) {
                    // v0.7.21 BUG FIX: was `((idx)).toByte().toInt() and 0xFF`
                    // which converted the INDEX to a byte — never reading the
                    // rgba array. Now correctly indexes into rgba.
                    val i00 = rgba[(sy0 * width + sx0) * 4 + c].toInt() and 0xFF
                    val i01 = rgba[(sy0 * width + sx1) * 4 + c].toInt() and 0xFF
                    val i10 = rgba[(sy1 * width + sx0) * 4 + c].toInt() and 0xFF
                    val i11 = rgba[(sy1 * width + sx1) * 4 + c].toInt() and 0xFF
                    val top = i00 + ((i01 - i00) * wx)
                    val bottom = i10 + ((i11 - i10) * wx)
                    out[o + c] = (top + (bottom - top) * wy).toInt().coerceIn(0, 255).toByte()
                }
            }
        }
        WepWawetLogger.proxy("MpkgPacker: texture shrink applied (PC -shrink rule)",
            "name" to name,
            "factor" to textureShrinkFactor,
            "from" to "${width}x${height}",
            "to" to "${newW}x$newH",
            "bytes_before" to rgba.size,
            "bytes_after" to out.size)
        return Triple(out, newW, newH)
    }

    /**
     * Append the source .tex's TEXS0003 section (animated-texture frame
     * sequences) to a freshly built single-mip output — the PC's .tex writer
     * preserves it (FUN_14005c930 references "TEXS0003", as does the loader
     * FUN_140007040), so a transcoded texture that carries sprite-sequence
     * data keeps it byte-for-byte after the new body.
     *
     * The section is found by walking the SOURCE mip chain structurally
     * (header-by-header, honoring each mip's byteCount) and checking the
     * magic at the chain's end — never by a raw byte scan, which false-
     * positives on compressed block data.
     */
    private fun appendTexsSection(built: ByteArray, sourceTex: ByteArray): ByteArray {
        try {
            val info = PcTexTranscoder.parse(sourceTex) ?: return built
            val mipCount = info.mipCount.coerceAtLeast(1)
            var mipPos = if (info.texbVersion == 3) 67 else 71
            for (m in 0 until mipCount) {
                if (mipPos + 20 > sourceTex.size) return built
                val mByteCount = readU32Le(sourceTex, mipPos + 16).toInt()
                if (mByteCount < 0) return built
                mipPos += 20 + mByteCount
            }
            if (mipPos + 9 <= sourceTex.size) {
                val sectionMagic = String(sourceTex, mipPos, 4, Charsets.US_ASCII)
                if (sectionMagic == "TEXS") {
                    val texsBytes = sourceTex.copyOfRange(mipPos, sourceTex.size)
                    WepWawetLogger.proxy("MpkgPacker.appendTexsSection: preserved TEXS section",
                        "texs_size" to texsBytes.size,
                        "mipmaps_walked" to mipCount)
                    return built + texsBytes
                }
            }
        } catch (e: Exception) {
            WepWawetLogger.proxyWarn("MpkgPacker.appendTexsSection: failed — shipping without TEXS",
                "exception" to e.javaClass.name)
        }
        return built
    }

    private fun fixSceneJsonTextureBindings(entries: MutableList<Pair<String, ByteArray>>) {
        try {
            // Find scene.json entry
            val sceneIdx = entries.indexOfFirst { it.first == "scene.json" }
            if (sceneIdx < 0) return
            val sceneJsonStr = String(entries[sceneIdx].second, Charsets.UTF_8)

            // Build a map of shader defaults from all .frag files
            // Key: shader name (e.g., "effects/xray")
            // Value: list of default texture names per slot [slot0, slot1, slot2, slot3]
            val shaderDefaults = mutableMapOf<String, List<String?>>()
            for ((name, blob) in entries) {
                if (name.endsWith(".frag") && name.startsWith("shaders/")) {
                    val shaderName = name.removePrefix("shaders/").removeSuffix(".frag")
                    val content = String(blob, Charsets.UTF_8)
                    val defaults = extractTextureDefaultsFromShader(content)
                    if (defaults.isNotEmpty()) {
                        shaderDefaults[shaderName] = defaults
                        WepWawetLogger.proxyDebug("MpkgPacker.fixSceneJson: found shader defaults",
                            "shader" to shaderName, "defaults" to defaults.joinToString(", "))
                    }
                }
            }

            // Parse scene.json
            val root = JsonParser.parseString(sceneJsonStr).asJsonObject
            var modified = false

            // Build a lookup for effect files and material files
            val entryMap = entries.associate { it.first to String(it.second, Charsets.UTF_8) }

            // Walk through objects → effects → passes → textures
            val objects = root.getAsJsonArray("objects") ?: return
            for (obj in objects) {
                val objAsJson = obj.asJsonObject
                if (!objAsJson.has("effects")) continue
                val effects = objAsJson.getAsJsonArray("effects")
                for (effect in effects) {
                    val effectObj = effect.asJsonObject
                    if (!effectObj.has("passes")) continue
                    val passes = effectObj.getAsJsonArray("passes")
                    for ((passIdx, pass) in passes.withIndex()) {
                        val passObj = pass.asJsonObject
                        if (!passObj.has("textures")) continue
                        val textures = passObj.getAsJsonArray("textures")

                        // Determine the shader name for this pass
                        // Chain: effect file → material → shader name
                        // v0.7.4: pass the pass INDEX so multi-pass effects resolve
                        // the shader for the CORRECT pass (was: first shader found
                        // in any pass — wrong for multi-pass effects).
                        val effectFile = effectObj.get("file")?.asString
                        val shaderName = findShaderName(effectFile, passIdx, entryMap)
                        val defaults = if (shaderName != null) shaderDefaults[shaderName] else null

                        // Replace null entries with defaults (skip index 0 = framebuffer)
                        for (i in 1 until textures.size()) {
                            if (textures[i].isJsonNull) {
                                val default = defaults?.getOrNull(i)
                                if (default != null && default.isNotEmpty()) {
                                    textures[i] = com.google.gson.JsonPrimitive(default)
                                    modified = true
                                    WepWawetLogger.proxy("MpkgPacker.fixSceneJson: replaced null texture binding",
                                        "shader" to (shaderName ?: "unknown"),
                                        "slot" to i,
                                        "default" to default)
                                }
                            }
                        }
                    }
                }
            }

            if (modified) {
                // Write modified scene.json back
                val modifiedJson = com.google.gson.GsonBuilder().setPrettyPrinting().create().toJson(root)
                entries[sceneIdx] = Pair("scene.json", modifiedJson.toByteArray(Charsets.UTF_8))
                WepWawetLogger.proxy("MpkgPacker.fixSceneJson: scene.json modified — null texture bindings replaced with shader defaults")
            } else {
                WepWawetLogger.proxyDebug("MpkgPacker.fixSceneJson: no null texture bindings found in scene.json")
            }
        } catch (e: Exception) {
            WepWawetLogger.proxyWarn("MpkgPacker.fixSceneJson: failed to fix scene.json",
                "exception" to e.javaClass.name,
                "message" to (e.message ?: ""))
        }
    }

    /** Extract texture default names from a shader file's uniform declarations.
     *
     * v0.6.0 (parity §7-8): Use RegexOption.DOT_MATCHES_ALL so the `.*?` matches across
     * newlines. Previously the regex missed metadata comments on a different line from the
     * uniform declaration.
     *
     * v0.7.4 fix: the unbounded `.*?` could ALSO run past subsequent `g_TextureN`
     * declarations — when g_Texture0 had no default comment but g_Texture1 (later in
     * the file) did, slot 0 silently received slot 1's default and fixSceneJson
     * bound the WRONG texture into slot 0. The tempered pattern
     * `(?:(?!g_Texture\d).)*?` still crosses newlines (§7-8 intent preserved) but
     * stops at the next texture declaration/usage, so a default can only belong
     * to the declaration it immediately follows.
     */
    private fun extractTextureDefaultsFromShader(shaderContent: String): List<String?> {
        val defaults = arrayOfNulls<String>(8) // g_Texture0 through g_Texture7
        // Match: uniform sampler2D g_TextureN; // {"...","default":"texture_name",...}
        // v0.6.0: DOT_MATCHES_ALL allows the gap to span newlines; v0.7.4: the
        // gap may not cross another g_TextureN occurrence.
        val regex = Regex("""g_Texture(\d+)\s*;(?:(?!g_Texture\d).)*?"default"\s*:\s*"([^"]*)"""",
            RegexOption.DOT_MATCHES_ALL)
        for (match in regex.findAll(shaderContent)) {
            val slot = match.groupValues[1].toIntOrNull() ?: continue
            val defaultName = match.groupValues[2]
            if (slot < 8 && defaultName.isNotEmpty() && defaultName != "util/white") {
                // Record non-trivial defaults (skip "util/white" since it's the generic default)
                defaults[slot] = defaultName
            }
        }
        return defaults.toList()
    }

    /** Follow the chain: effect file → material → shader name.
     *
     * v0.7.4 fix: takes the CALLING pass's index (from scene.json's effect
     * passes array) and resolves the shader for THAT pass in the effect file.
     * Previously the passObj parameter was ignored and the FIRST shader found
     * in ANY pass was returned — for multi-pass effects (e.g. a blur with
     * horizontal + vertical passes using different shaders) every pass got
     * pass 0's shader, so the wrong default textures were substituted into
     * the wrong pass's null texture slots. Falls back to scanning all passes
     * (the old behavior) when the index is out of range.
     */
    private fun findShaderName(
        effectFile: String?,
        passIndex: Int,
        entryMap: Map<String, String>,
    ): String? {
        if (effectFile == null) return null
        try {
            // Read the effect file to find the material path
            val effectContent = entryMap[effectFile] ?: return null
            val effectJson = JsonParser.parseString(effectContent).asJsonObject
            val passes = effectJson.getAsJsonArray("passes")
            if (passes == null || passes.size() == 0) return null

            // v0.7.4: try the calling pass's index FIRST; fall back to scanning
            // all passes (v0.6.0 §7-7 behavior) when scene.json and the effect
            // file disagree on the pass count.
            val candidateIndices = if (passIndex in 0 until passes.size()) {
                listOf(passIndex) + (0 until passes.size()).filter { it != passIndex }
            } else {
                (0 until passes.size()).toList()
            }
            for (passIdx in candidateIndices) {
                // The pass in the effect file might have a "material" field
                val effectPass = passes[passIdx].asJsonObject
                val materialPath = if (effectPass.has("material")) {
                    effectPass.get("material").asString
                } else {
                    // Some effect files reference the material differently
                    // Try "file" as the material path
                    if (effectJson.has("file")) effectJson.get("file").asString else continue
                }

                // Read the material file to find the shader name
                val materialContent = entryMap[materialPath] ?: continue
                val materialJson = try {
                    JsonParser.parseString(materialContent).asJsonObject
                } catch (_: Exception) { continue }
                val matPasses = materialJson.getAsJsonArray("passes")
                if (matPasses == null || matPasses.size() == 0) continue

                // Iterate over ALL material passes too
                for (matPassIdx in 0 until matPasses.size()) {
                    val matPass = matPasses[matPassIdx].asJsonObject
                    if (matPass.has("shader")) {
                        return matPass.get("shader").asString
                    }
                }
            }
        } catch (e: Exception) {
            // Ignore parsing errors
        }
        return null
    }

    /**
     * Remove duplicate `uniform <type> <name>;` declarations from a shader.
     *
     * After inlining headers like common_blur.h, the shader may have multiple
     * declarations of the same uniform (e.g., `uniform sampler2D g_Texture0;`
     * appears both in the original shader and in the inlined header). GLSL ES
     * 3.00 treats this as a redefinition error.
     *
     * This function parses each `uniform <type> <name>;` line (ignoring comments
     * after `//`), and keeps only the FIRST declaration of each `<name>`,
     * removing all subsequent duplicates.
     */
    private fun dedupUniformDeclarations(shaderSource: String): String {
        val lines = shaderSource.split("\n").toMutableList()
        val seenUniforms = mutableSetOf<String>()
        var dedupCount = 0

        for (i in lines.indices) {
            val line = lines[i].trim()
            // Match: uniform <type> <name>; [// comment]
            // Examples:
            //   uniform sampler2D g_Texture0; // {"hidden":true}
            //   uniform float g_Time;
            //   uniform vec4 g_Texture0Resolution;
            val uniformMatch = Regex("""^uniform\s+(\w+)\s+(\w+)\s*;""").find(line)
            if (uniformMatch != null) {
                val uniformName = uniformMatch.groupValues[2]
                if (uniformName in seenUniforms) {
                    // Duplicate — remove this line
                    lines[i] = ""
                    dedupCount++
                } else {
                    seenUniforms.add(uniformName)
                }
            }
        }

        if (dedupCount > 0) {
            WepWawetLogger.proxyDebug("MpkgPacker.dedupUniformDeclarations: removed $dedupCount duplicate uniform(s)")
        }

        return lines.joinToString("\n")
    }

    /** Read a little-endian int32 from a byte array at the given offset. */
    private fun readLeInt(data: ByteArray, offset: Int): Int {
        if (offset + 4 > data.size) return 0
        return ((data[offset].toInt() and 0xFF)) or
                ((data[offset + 1].toInt() and 0xFF) shl 8) or
                ((data[offset + 2].toInt() and 0xFF) shl 16) or
                ((data[offset + 3].toInt() and 0xFF) shl 24)
    }


    /** Find a byte pattern in a byte array, starting from offset. Returns -1 if not found. */
    private fun findBytes(data: ByteArray, pattern: ByteArray, start: Int = 0): Int {
        if (pattern.isEmpty()) return -1
        for (i in start..data.size - pattern.size) {
            var match = true
            for (j in pattern.indices) {
                if (data[i + j] != pattern[j]) { match = false; break }
            }
            if (match) return i
        }
        return -1
    }

    /**
     * v0.7.4: find the TRUE end offset (exclusive) of a PNG that starts at
     * [pngStart] by walking the PNG chunk structure: every chunk is
     * `4-byte BE length + 4-byte type + length bytes of data + 4-byte CRC`,
     * and the image ends right after the IEND chunk's CRC.
     *
     * This replaces the raw byte search for "IEND", which could match the
     * literal text "IEND" inside a tEXt/zTXt/iTXt metadata chunk and truncate
     * the image mid-file. If the chunk walk runs off the buffer (corrupt
     * structure), we fall back to the old raw search so behavior degrades
     * gracefully instead of failing hard.
     */
    private fun findPngEnd(data: ByteArray, pngStart: Int): Int {
        // Skip the 8-byte PNG signature.
        var pos = pngStart + 8
        // The IHDR chunk must follow immediately — otherwise this isn't a
        // well-formed PNG stream; fall back to the raw search.
        if (pos + 8 > data.size) return findIendFallback(data, pngStart)
        var type = String(data, pos + 4, 4, Charsets.US_ASCII)
        if (type != "IHDR") return findIendFallback(data, pngStart)
        var guard = 0
        while (pos + 8 <= data.size && guard < 100_000) {
            guard++
            // Chunk data length (unsigned 32-bit BE; PNG limits it to 2^31-1).
            val len = ((data[pos].toInt() and 0xFF) shl 24) or
                ((data[pos + 1].toInt() and 0xFF) shl 16) or
                ((data[pos + 2].toInt() and 0xFF) shl 8) or
                (data[pos + 3].toInt() and 0xFF)
            if (len < 0) return findIendFallback(data, pngStart) // implausible
            type = String(data, pos + 4, 4, Charsets.US_ASCII)
            // chunk total = length(4) + type(4) + data(len) + CRC(4)
            val chunkEnd = pos.toLong() + 12L + len.toLong()
            if (chunkEnd > data.size) return findIendFallback(data, pngStart)
            if (type == "IEND") return chunkEnd.toInt()
            pos = chunkEnd.toInt()
        }
        return findIendFallback(data, pngStart)
    }

    /** v0.7.4: old raw "IEND" search, kept as the fallback path. */
    private fun findIendFallback(data: ByteArray, pngStart: Int): Int {
        val iendStart = findBytes(data, byteArrayOf(0x49, 0x45, 0x4e, 0x44), pngStart)
        if (iendStart < 0) return -1
        return iendStart + 8 // IEND marker (4 bytes) + CRC (4 bytes)
    }

    private fun writeLeIntToStream(out: java.io.ByteArrayOutputStream, value: Int) {
        out.write(value and 0xFF)
        out.write((value shr 8) and 0xFF)
        out.write((value shr 16) and 0xFF)
        out.write((value shr 24) and 0xFF)
    }

    /**
     * Pack a list of (name, bytes) entries into a .mpkg file.
     *
     * v0.7.4 (parity G1): the magic is a parameter now — callers pass the
     * per-transfer negotiated version (see [negotiateMagic]). Defaults to
     * PKGM0020 for direct callers that don't negotiate.
     */
    private fun pack(
        entries: List<Pair<String, ByteArray>>,
        outputFile: File,
        magic: String = MAGIC,
    ): Long {
        WepWawetLogger.proxyDebug("MpkgPacker.pack layout",
            "entry_count" to entries.size,
            "header_size" to (4 + 8 + 4),
            "vfs_table_size_estimate" to entries.sumOf { 4 + it.first.toByteArray(Charsets.UTF_8).size + 8 },
            "magic" to magic)

        val headerSize = 4 + 8 + 4
        val vfsTableSize = entries.sumOf { 4 + it.first.toByteArray(Charsets.UTF_8).size + 4 + 4 }
        val dataSectionStart = headerSize + vfsTableSize

        val vfsEntries: MutableList<Triple<String, Int, Int>> = mutableListOf()
        var cursor = 0
        for (entry in entries) {
            val (name, blob) = entry
            vfsEntries.add(Triple(name, cursor, blob.size))
            cursor += blob.size
        }

        WepWawetLogger.proxy("MpkgPacker.pack layout computed",
            "header_size" to headerSize,
            "vfs_table_size" to vfsTableSize,
            "data_section_start" to dataSectionStart,
            "total_size" to (dataSectionStart + cursor),
            "entry_count" to entries.size,
            "magic" to magic)

        val out = outputFile.outputStream().buffered()
        try {
            writeLeInt(out, HEADER_MARKER)
            out.write(magic.toByteArray(Charsets.US_ASCII))
            if (magic.length != 8) error("magic must be 8 bytes: '$magic'")
            writeLeInt(out, entries.size)

            for (entry in vfsEntries) {
                val (name, offset, size) = entry
                val nameBytes = name.toByteArray(Charsets.UTF_8)
                writeLeInt(out, nameBytes.size)
                out.write(nameBytes)
                writeLeInt(out, offset)
                writeLeInt(out, size)
            }

            for (entry in entries) {
                val (_, blob) = entry
                out.write(blob)
            }
            out.flush()
        } finally {
            out.close()
        }
        return outputFile.length()
    }

    private fun writeLeInt(out: OutputStream, value: Int) {
        val buf = ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(value).array()
        out.write(buf)
    }

    private fun writeLeInt(out: ByteArrayOutputStream, value: Int) {
        out.write(value and 0xFF)
        out.write((value shr 8) and 0xFF)
        out.write((value shr 16) and 0xFF)
        out.write((value shr 24) and 0xFF)
    }

    /**
     * Verify the .mpkg file we just wrote by reading the header back.
     *
     * v0.7.4 (parity G1): the magic is now per-transfer (PKGM0014 for video,
     * min(device, 20) for scene), so the check accepts any structurally valid
     * "PKGMdddd" string and additionally compares it against the magic the
     * caller asked [pack] to write.
     */
    private fun verifyMpkg(
        file: File,
        expectedEntries: List<Pair<String, ByteArray>>,
        expectedMagic: String = MAGIC,
    ) {
        try {
            val data = file.readBytes()
            if (data.size < 16) {
                WepWawetLogger.proxyWarn("MpkgPacker verify: file too small",
                    "size" to data.size)
                return
            }
            val buf = ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN)
            val headerMarker = buf.int
            val magicBytes = ByteArray(8)
            buf.get(magicBytes)
            val magic = String(magicBytes, Charsets.US_ASCII)
            val entryCount = buf.int

            val magicStructurallyValid = magic.length == 8 && magic.startsWith("PKGM") &&
                magic.substring(4).all { it.isDigit() }

            if (entryCount !in 1..4096) {
                WepWawetLogger.proxyWarn("MpkgPacker verify: implausible entry count",
                    "file" to file.name,
                    "header_marker" to headerMarker,
                    "magic" to magic,
                    "entry_count" to entryCount,
                    "expected" to expectedEntries.size)
                return
            }

            WepWawetLogger.proxy("MpkgPacker verified",
                "file" to file.name,
                "file_size" to data.size,
                "header_marker" to headerMarker,
                "magic" to magic,
                "expected_magic" to expectedMagic,
                "entry_count" to entryCount,
                "matches_expected" to (headerMarker == HEADER_MARKER &&
                    magic == expectedMagic && entryCount == expectedEntries.size))

            for (i in 0 until entryCount) {
                if (buf.remaining() < 4) {
                    WepWawetLogger.proxyWarn("MpkgPacker verify: truncated at name_len",
                        "index" to i)
                    return
                }
                val nameLen = buf.int
                if (nameLen !in 1..256) {
                    WepWawetLogger.proxyWarn("MpkgPacker verify: implausible name_len",
                        "index" to i,
                        "name_len" to nameLen)
                    return
                }
                if (buf.remaining() < nameLen + 8) {
                    WepWawetLogger.proxyWarn("MpkgPacker verify: truncated mid-entry",
                        "index" to i,
                        "name_len" to nameLen,
                        "remaining" to buf.remaining())
                    return
                }
                val nameBytes = ByteArray(nameLen)
                buf.get(nameBytes)
                val name = String(nameBytes, Charsets.UTF_8)
                val offset = buf.int
                val size = buf.int
                WepWawetLogger.proxyDebug("MpkgPacker VFS entry",
                    "index" to i,
                    "name" to name,
                    "offset" to offset,
                    "size" to size)
            }
        } catch (e: Exception) {
            WepWawetLogger.proxyWarn("MpkgPacker verify failed",
                "exception" to e.javaClass.name,
                "message" to (e.message ?: ""))
        }
    }
}
