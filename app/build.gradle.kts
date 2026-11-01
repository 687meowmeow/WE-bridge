import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import java.text.SimpleDateFormat
import java.util.Date

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.compose.compiler)
    alias(libs.plugins.kotlin.serialization)
}

android {
    namespace = "com.wepwawet"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.wepwawet"
        minSdk = 26  // Android 8.0 — required for the WE pairing protocol + ImageDecoderDecoder GIF path
        targetSdk = 35
        // v0.7.1-alpha: UI cleanup pass — swap Settings card order, .pkg cache toggle,
        // square wallpaper preview, smaller favorite heart, favorites route placeholder,
        // verbose-logging now actually filters debug/info messages.
        // v0.7.1-alpha build 86: FavoritesScreen now shows creator avatars
        // + display names, WallpaperDetailScreen heart button replaced with
        // clickable Icon (no longer displaces text), WorkshopBrowserScreen
        // preserves search query when sort changes (re-runs search instead
        // of switching to browse), SteamApiService caches avatar URLs.
        // v0.7.1-alpha build 87: SEARCH BACKEND FALLBACK CHAIN — search now
        // uses the same 3-step chain as browse (JavaSteam → HTTP API → HTML
        // scrape) so the two paths return consistent results for the same
        // filters. Search also tracks consecutiveEmptySearchPages (separate
        // counter) so a restrictive filter that strips every page's items
        // doesn't stop the search on page 1. Creator row restructured so
        // the name + heart sit on the right (not all the way left). Heart
        // has its OWN clickable (parent Row no longer intercepts the tap,
        // so the heart actually turns red and shows a "favorited (user)"
        // snackbar at the bottom). Pagination bar compacted to 44dp (was
        // ~56dp+) with a clear "Go to page:" label.
        // v0.7.1-alpha build 88: XRAY GRAY IMAGE FIX + HEART ABOVE TEXT +
        // PAGINATION ARROWS FIX.
        //   1. MpkgPacker.fixShaders PHASE 0.5 condition was broken — only
        //      checked for cbuffer/SamplerState/Texture2D (full HLSL
        //      constructs) but NOT saturate()/lerp()/mul() (HLSL function
        //      calls). When the native transcoder (ShaderTranscoder.
        //      transcodeShader) failed (UnsatisfiedLinkError because
        //      glslang isn't compiled into the .so), PHASE 0.5 was SKIPPED
        //      entirely → shader still had `mul(vec4(...), matrix)` and
        //      `saturate(unprojectedUVs)` calls → WE Android's renderer
        //      couldn't compile the shader → wallpaper rendered as gray
        //      "invalid image". Fix: extend the condition to also include
        //      saturate(/lerp(/mul( detection (matching PHASE 0's isHlsl
        //      detection). Also wrote a NEW balanced-parens scanner
        //      (convertHlslFunctionCall) for mul/saturate/lerp because the
        //      old regex was broken for nested parens (`mul(vec4(...), mat)`
        //      would match arg1="vec4(...", arg2="...)..." — wrong).
        //   2. Heart layout — user said "the heart is displacing the text
        //      again. just make it above the text and to the left side of
        //      the creator name and next to it." Now the heart is on its
        //      OWN ROW ABOVE the Creator metadata row, aligned LEFT — so
        //      it doesn't push any other row's text. The Creator metadata
        //      row goes back to the standard MetadataRow layout (label
        //      left, value right, no heart).
        //   3. Pagination arrows — user said "the arrows to go to next
        //      page dont work. fix." Found TWO bugs:
        //      A) Pagination bar read `browseState` directly instead of
        //         `displayState`. In search mode, browseState is stale
        //         (last browse loaded) while searchState has the actual
        //         current page + hasMore for the search session. Clicking
        //         Next used stale browse state — search page 2 might return
        //         0 items → searchState=Done, but browseState was still
        //         Loaded(1, hasMore=true) so the bar STILL showed "Page 1"
        //         and Next was still enabled. Page number never advanced.
        //         Fix: use `displayState` (which accounts for search mode).
        //      B) Round 4's Box.clickable approach replaced IconButton to
        //         avoid the 48dp minimum touch target. In some Compose
        //         versions, Modifier.clickable on a Box without explicit
        //         interactionSource/indication may not propagate clicks
        //         reliably inside a Surface with shadowElevation. Revert
        //         to IconButton (built-in ripple + interaction source)
        //         with Modifier.size(36.dp) override. IconButton's
        //         minimumInteractiveComponentSize() adds INVISIBLE padding
        //         OUTSIDE the visual bounds — doesn't make the row taller.
        // v0.7.1-alpha build 89: PAIRING PERSISTENCE + XRAY STILL GRAY + HEART SMALLER.
        //   1. PAIRING PERSISTENCE — PairingIdentity.kt (new object):
        //      persistent GUID + RSA keypair in SharedPreferences. Was
        //      previously generating fresh GUID + RSA on every service
        //      restart → phone saw brand-new computer after every app
        //      launch/update and re-prompted for PIN. Now identity is
        //      stable across restarts/updates — the phone recognizes
        //      the same computer.
        //      WeDiscoveryBroadcaster.start(hostname, guid) — accepts
        //      persistent guid (was randomUUID()).
        //      WePairingServer.start(pin, keyPair?) — accepts a KeyPair
        //      (was always generating fresh); also adds reuseAddress +
        //      5x1s BindException retry for foreground-service restarts;
        //      onDisconnected callback fires after commandLoop returns
        //      ONLY IF currentClient === client (so newer pairings
        //      aren't cleared by older disconnect events).
        //      PairingService.onStartCommand — SMART RESTART: if the
        //      server is already running with the SAME PIN (e.g.
        //      MainActivity.onCreate + PairingScreen appearance),
        //      DON'T tear down the live phone connection — only restart
        //      the discovery broadcaster if it died. Full (re)start
        //      only on PIN change or first start. Pass persistent
        //      keypair from PairingIdentity. Wrap startForeground in
        //      try/catch (ForegroundServiceStartNotAllowedException on
        //      background restarts) and log instead of crashing.
        //      Notification text shows "Paired: <name>" if already
        //      paired (was always showing "WepWawet proxy running").
        //      onDisconnected clears pairedDeviceName + updates the
        //      notification to "WepWawet proxy running. PIN: <pin>"
        //      (persisted name in SharedPreferences is KEPT so the
        //      phone can reconnect without re-PIN-prompting).
        //   2. BootReceiver.kt (new BroadcastReceiver): listens for
        //      BOOT_COMPLETED + MY_PACKAGE_REPLACED, sets
        //      PairingService.appContext, calls PairingService.start.
        //      Combined with the RECEIVE_BOOT_COMPLETED permission and
        //      the `specialUse` foreground service type (NOT in the
        //      Android 15+ restricted-types list), the pairing service
        //      now auto-restarts after device reboot or app update.
        //   3. XRAY STILL GRAY — the round 5 fix only worked for
        //      xray.frag (has saturate but no mix). xray.vert has BOTH
        //      `mul()` (HLSL matrix mult — needs conversion) AND
        //      `mix()` (native GLSL lerp — no conversion needed).
        //      The old guard `(contains("mul(") && !contains("mix("))`
        //      evaluated to (true && !true) = false → PHASE 0 didn't
        //      detect xray.vert as HLSL → PHASE 0.5 also skipped →
        //      `mul(vec4(...), matrix)` calls left unconverted →
        //      shader failed to compile → gray "invalid image".
        //      Fix: remove the `!contains("mix(")` guard in BOTH
        //      PHASE 0 (isHlsl) and PHASE 0.5 (hasHlslSyntax). Just
        //      check `contains("mul(")` directly — the token is
        //      HLSL-specific, so a shader with `mul(` has HLSL syntax
        //      that needs conversion regardless of whether it also
        //      has `mix(`.
        //   4. HEART SMALLER (TASK 2): shrink heart icon per user
        //      request — Icon 28dp→20dp, inner tap Box requiredSize
        //      36dp→32dp, slot width 36dp→30dp, slot height stays 20dp.
        //      The structure (slot Box + inner tap Box with requiredSize
        //      that overflows + Icon centered) is preserved exactly —
        //      only the sizes change. Creator row stays exactly as
        //      tall as the other MetadataRow rows.
        // v0.7.1-alpha build 90: REVERT MpkgPacker.kt shader conversion —
        //   restore round 4 hotfix packing logic. My round 5/6 changes to
        //   fixShadersForMobile (convertHlslFunctionCall balanced-parens
        //   scanner + removing the !contains("mix(") guard) BROKE the
        //   Mordred (X-Ray) wallpaper which was working before. The WE
        //   Android renderer handles HLSL mul()/saturate() natively (it
        //   has an HLSL compatibility shim). Converting them to GLSL
        //   (vec*mat / clamp) made the shader fail to compile on WE
        //   Android → wallpaper rendered as gray "invalid image".
        //   Reverting to round 4 hotfix state: mul/saturate/lerp NOT
        //   converted (the !contains("mix(") guard prevents the regex
        //   from running on shaders that have both mul() and mix()).
        //   The other xray wallpaper (id=2347762662) remains gray — it
        //   was already gray before round 5, so the shader conversion
        //   was NOT the issue for that one. The pairing persistence,
        //   BootReceiver, heart-smaller, and all other round 6 changes
        //   are KEPT (only MpkgPacker.kt is reverted).
        // v0.7.4-alpha — FULL WALLPAPER COMPATIBILITY / PARITY PASS (G1-G9,
        // from the wallpaperui.exe 2.8.0.42 Ghidra headless decompilation):
        //   G1 (P0): .mpkg magic is now NEGOTIATED per transfer — the phone's
        //      reported "mpkgVersion" is parsed at pairing and scene wallpapers
        //      get min(device, 20); video wallpapers ALWAYS get PKGM0014,
        //      exactly like the PC packer (FUN_14003d0e0: `if (isVideo)
        //      version = 0xe;`, magic = "PKGM" + "%04i" % version — the %04i
        //      format string lives at file offset 0xab484c).
        //   G8 (P0): transfer chunk size 1 MiB → 128 KiB, matching the PC's
        //      framing (the 1 MiB ciphertext frame was 20 bytes OVER the
        //      phone's documented MAX_SAFE_DATA_LENGTH).
        //   G2 (P1): exotic video containers now WORK end-to-end — the
        //      send/download flows sniff every container (MP4/WebM/MKV/AVI/
        //      WMV/GIF/FLV/TS), and non-MP4 inputs are transcoded to
        //      MP4/H.264 on-device before packing (GIF via Movie frames,
        //      AVI via a custom RIFF demuxer covering MJPG-with-DHT-injection/
        //      raw-DIB/H264/XVID, everything else via MediaExtractor+MediaCodec
        //      — the PC's `ffmpeg_avi_*`/`ffmpeg_gif` fallback equivalents).
        //      IMAGE-type workshop wallpapers (raw JPEG/PNG/WebP/BMP files)
        //      now pack as a Scene via packSceneWallpaper instead of failing
        //      the .pkg parse.
        //   G3 (P1): PC export-quality video presets (1080p/4K/auto with the
        //      verified bitrate table 8M/16M/20M + fps caps) are scaffolded in
        //      VideoQualityPresets.kt but INTENTIONALLY NOT WIRED UP yet
        //      (user wants to implement the re-encode pass later — the file
        //      documents the exact wiring point).
        //   G7 (P2): scene texture slots referencing files absent from the
        //      package are nulled (then filled from shader defaults) and the
        //      scene.json "files" manifest drops missing entries — the PC's
        //      missing-scenetexture removal rule; kills the phantom-texture
        //      checkerboard artifacts.
        //   G6 (P2): video wallpapers without a preview now get a generated
        //      360 px thumbnail (MediaMetadataRetriever frame at t=1 s) and,
        //      failing that, a nopreview placeholder — PC parity for
        //      FUN_140018a50 + "nopreview.jpg".
        //   G5 (P2): optional texture shrink (PC "texturereduction"): Full /
        //      Half / Quarter setting applied to every decoded texture before
        //      ETC2 re-encode (both the pixel data and the .tex header dims).
        //   G9 (P3): video alignment position is now a 0-100 settings slider
        //      (default 0 = centered — byte-identical to previous behavior)
        //      written into project.json like the PC writes the device
        //      config value (disasm 14003f27b).
        // v0.7.5-alpha: COMPREHENSIVE BUG-FIX PASS (all 56 audit findings,
        // rebased on top of the v0.7.4 compatibility pass).
        //   VideoTranscoder: muxer.addTrack now uses the encoder's OUTPUT
        //   format (CSD/SPS-PPS) via INFO_OUTPUT_FORMAT_CHANGED — the old
        //   input-format tracks produced structurally broken MP4s (no
        //   codec-specific data; CSD written as fake samples); full
        //   try/finally cleanup for all codecs/muxers/extractors; decoded
        //   frames are no longer dropped when the encoder queue is full
        //   (pending-buffer retry pattern); YUV padding zeroed.
        //   VideoTrimmer: keyframe-accurate start (was starting mid-GOP →
        //   black/garbage intro), PTS re-based to ~0 (was keeping original
        //   timestamps → black gap at start on many players), muxer released
        //   in finally, zero-sample outputs fail cleanly.
        //   WorkshopDownloader: awaitCompletion no longer overwrites the real
        //   downloadError with a misleading timeout message; Activity context
        //   leak fixed (applicationContext); TryAnotherCM retry moved off the
        //   callback thread; onLoggedOff clears `authenticated`; auth polling
        //   thread is tracked + interrupted in cleanup() with bounded waits;
        //   RandomAccessFile handles closed via .use{}; stale install dir
        //   wiped before re-download; cmRetries reset on login; Steam Guard
        //   CODE plumbing removed (unused — auth uses device confirmation).
        //   WorkshopDownloadJob: install dir cleaned after send-to-phone;
        //   pendingVideo state cleared on new downloads + after trim-screen
        //   consumption (fixes the reappearing trim dialog / deleted-file
        //   navigation loop); cancel() resets progress.
        //   MpkgPacker: PASS F float-assignment regex fixed (was matching ANY
        //   `= N;` when no float vars, and unanchored alternation for
        //   multi-char names); extractTextureDefaults regex no longer crosses
        //   into the next g_Texture declaration (wrong-slot defaults);
        //   findShaderName resolves the CORRECT pass's shader (multi-pass
        //   effects); VFS offsets/sizes tracked as Long with a u32 overflow
        //   guard (≥2GiB videos corrupted the table); PNG end detection walks
        //   the chunk structure (was matching "IEND" text inside metadata
        //   chunks); mipmapCount validated in the RG88/R8/RGBA8888
        //   converters; computeMaxObjectId iterative (deep JSON no longer
        //   overflows the stack); computeAverageColor bounds-checked.
        //   SteamApiService: CancellationException re-thrown at all 8 catch
        //   sites (structured concurrency); &days= gated on the effective
        //   query type; response.close() on all HTTP error paths; page size
        //   is now runtime-configurable (Settings slider finally works);
        //   non-numeric numeric-field strings no longer drop whole items.
        //   WorkshopRepository: loadPage(search) resets searchHasMore;
        //   clearSearch takes the browse lock; atomic StateFlow updates;
        //   creator-name resolution moved off the lock; trend sorts with
        //   days > 7 skip the HTTP API (which clamps to 7) and use the HTML
        //   endpoint; loadPage validates pageNum; dead loadJob removed.
        //   DownloadManager: completeDownload is atomic (no clobber races
        //   with failDownload); TypeToken replaced with the Array form
        //   (R8-safe).
        //   Settings wiring: BOTH the "Page size" slider and "Cache .pkg
        //   files" toggle now actually do something (were persisted but
        //   never read).
        //   WorkshopBrowserScreen: first debounced keystroke no longer
        //   swallowed; pagination bar shows the page being loaded; no stale
        //   creator-grid flash when popping back to the global browse.
        //   TagPickerSheet: draft resets on every open (wraps content in
        //   if(visible) instead of early-return).
        //   Logger: clear() resets the persistence watermark (file logging
        //   resumed working after a clear); per-entry commit in the worker
        //   (no duplicated lines after a crash mid-batch); minLevel
        //   @Volatile. CrashHandler: snapshot is built on a helper thread
        //   with a 2s bound (deadlock-proof); all Log.e wrapped.
        //   SteamLoginActivity: WebView destroyed in onDestroy (Activity
        //   leak); cookie merge dedupes by NAME.
        //   Pairing: bytes-sent-so-far logged correctly on transfer failure;
        //   discovery ping restored to a true ~1.3s cadence (was ~2.3s);
        //   notification channel id unified; isPaired() dead branch removed.
        //   Verified-intended (NO change, documented): .tex PNG search
        //   offset 87 (header is exactly 87 bytes — round-8 fix), JPEG
        //   whole-file pass-through (EXIF thumbnail fix), AuthOK generic ack.
        // v0.7.6-alpha: SCENE SHADER "SNAPPING BACK TO ORIGINAL POSITION" FIX
        //   (4 root-cause fixes for the Simple_Audio_Bars shader failure on
        //   workshop wallpaper 3655958892 — R18 Acheron & Black Swan):
        //   FIX 1: lerp→mix now UNCONDITIONAL (was gated on
        //     !ShaderTranscoder.isAvailable() AND contains(cbuffer/SamplerState/
        //     Texture2D); the gate silently skipped lerp→mix when the native
        //     glslang lib failed to load, which is the common field case —
        //     leaving lerp() calls in the shader that fail to compile on
        //     GLSL ES 3.00 with "no matching overloaded function found").
        //     saturate→clamp is now paren-balanced (the old [^)]+ regex
        //     broke on nested parens like saturate(lerp(...)), producing
        //     mix(arg1, arg2, smoothstep(...,frac(frequency)) with unbalanced
        //     parens) and only runs when the shader doesn't already #define
        //     saturate (so the WE Android preprocessor's macro isn't
        //     double-converted).
        //   FIX 2: int→float conversion is now TYPE-AWARE — pre-scans for
        //     uint variable declarations, skips int→float when the int is
        //     adjacent to a uint variable (was blindly converting `1` to
        //     `1.0` even for `barFreq1 + 1` where barFreq1 is uint — producing
        //     `barFreq1 + 1.0` which is uint + float = illegal in GLSL ES 3.00).
        //     Mandatory unit test added (ShaderFixesTest.`FIX 2 - barFreq1 plus
        //     1 must NOT become barFreq1 plus 1_0`).
        //   FIX 3: .tex files are NEVER silently dropped — if IsLZ4=1 and the
        //     PNG/JPEG magic search at offset 87+ misses (because the body is
        //     LZ4-compressed), decompress the body first via the new
        //     decompressTexBodyIfLz4 helper, then re-run the magic search on
        //     the decompressed .tex. If decompression reveals no PNG/JPEG
        //     magic, treat as raw RGBA8888 and route to convertTexRgba8888ToEtc2
        //     (which handles LZ4 internally). This eliminates the
        //     waterflowphase.tex passthrough that was causing the waterflow
        //     shader to sample a constant default and the "snapping back to
        //     original position" symptom.
        //   FIX 4: Replicate WE PC's zcompat shader substitution — at pack
        //     time, when a workshop effect id matches a zcompat entry
        //     (2084198056 Simple_Audio_Bars, 2078835426 pixelate in
        //     WE 2.8.42), substitute the zcompat shader INSTEAD of running
        //     the generic fixer (zcompat files are already mobile-safe:
        //     mix, explicit float() casts, mod2() precision helper,
        //     explicit int() index casts). zcompat shaders are embedded
        //     at app/src/main/assets/zcompat/scene/shaders/<id>/ (verified
        //     byte-identical to WE 2.8.42 install via the
        //     github.com/yakisoba0728/Waple mirror).
        // v0.7.7-alpha: CRITICAL REGRESSION FIX — v0.7.6's unconditional mul()
        //   conversion broke ALL .vert shaders. The mul() regex
        //   `\bmul\s*\(([^,]+),\s*([^)]+)\)` stops at the FIRST comma inside
        //   nested function calls like `mul(vec4(a_Position, 1.0),
        //   g_ModelViewProjectionMatrix)`, producing the broken comma-operator
        //   form `(vec4(a_Position * 1.0), g_ModelViewProjectionMatrix)` which
        //   evaluates to the mat4 (last element of comma operator), causing
        //   "cannot convert from '4X4 matrix of float' to 'Position 4-component
        //   vector of float'" — ALL .vert shaders failed, the scene couldn't
        //   render ANY geometry, hence "absolutely no movement".
        //   FIX: REMOVED mul/frac/saturate/ddx/ddy conversions from PHASE 0.5.
        //   The WE Android preprocessor ALREADY provides #define macros for
        //   all of them (lines 2-16 of every compiled shader):
        //     #define mul(x, y) ((y) * (x))
        //     #define frac fract
        //     #define saturate(x) clamp(x, 0.0, 1.0)
        //     #define ddx dFdx
        //     #define ddy(x) dFdy(-(x))
        //   The preprocessor's macro expander correctly handles nested parens
        //   (it has a real parser, not a regex). The packer's regex conversions
        //   were REDUNDANT (preprocessor would expand them anyway) and BUGGY
        //   (mul broke on nested parens) and WRONG for ddy (preprocessor adds
        //   -(x) negation; packer didn't).
        //   The ONLY conversion kept is lerp→mix (no #define lerp in the
        //   preprocessor's defines — lerp is a pure HLSL function the
        //   preprocessor does NOT expand; the packer must convert it to mix).
        //   Also fixed: decompressTexBodyIfLz4 no longer uses the BOGUS byteCount
        //   field at offset 87 (which had values like 1208090609 for a 355849-byte
        //   file). Instead, it tries LZ4-decompressing from offset 91 and offset 87
        //   using the entire remaining .tex as the compressed body, with the
        //   decompressedSize from offset 83 as the target. The LZ4 safe decompressor
        //   stops when it's produced the target size, so extra bytes at the end are
        //   safely ignored.
        //   7 new regression tests added for the mul/frac/saturate/ddx/ddy
        //   non-conversion (all passing).
        // v0.7.8-alpha: "MOVEMENT STILL CUTS BACK" fix — pass format=0 .tex
        //   files through UNCHANGED instead of converting to ETC2_RGBA8.
        //   Root cause: ETC2_RGBA8 is a LOSSY compression that corrupts the
        //   .r channel of shader-critical textures like waterflowphase.tex.
        //   The waterflow.frag shader samples g_Texture2.r as `flowPhase` to
        //   control a smoothstep blend between two flow animation phases.
        //   ETC2 artifacts in the .r channel cause the smoothstep to trigger
        //   at wrong times, producing a visible "cut back" every time the
        //   flow phase wraps.
        //   FIX: For format=0 .tex files with raw RGBA8888 bodies (no PNG/JPEG
        //   magic), pass the file through UNCHANGED. The WE Android renderer
        //   (libscenejni.so) natively supports format=0 .tex files — it reads
        //   the TEXV/TEXI/TEXB header, sees format=0 (FIF_UNKNOWN), and
        //   uploads the raw RGBA8888 pixels directly to the GPU as an RGBA8
        //   texture. No lossy ETC2 conversion is needed. The user's original
        //   spec confirmed this: "if the engine parses .tex natively, just
        //   include the file in the mpkg."
        //   This also fixes the LZ4 decompression failures for
        //   HD-wallpaper-aesthetic-neon-city-blue-cool-purple-world-thumbnail.tex
        //   and output-onlinepngtools.tex — those have format=0 with is_lz4=true
        //   and our LZ4 decompression was failing (bogus byteCount at offset 87).
        //   By passing them through unchanged, the WE Android renderer handles
        //   the LZ4 decompression natively.
        //   Verified the waterflow shader source from the Waple mirror:
        //   g_Texture2 (waterflowphase.tex) .r → flowPhase → smoothstep(0.2,0.8,
        //   flowPhase) → mix(flowAlbedo, flowAlbedo2, ...) — the .r channel
        //   MUST be precise for smooth animation.
        // v0.7.20-alpha: UI/UX polish + concurrent downloads.
        //   - Foreground notification hardened: setOngoing + setShowWhen(false) +
        //     setForegroundServiceBehavior(IMMEDIATE) + setOnlyAlertOnce +
        //     CATEGORY_SERVICE + VISIBILITY_PUBLIC + content-tap opens MainActivity.
        //     The notification persists while the proxy runs and re-posts if the
        //     user swipes it away on Android 14+ (specialUse FGS type has no
        //     system timeout per the Android docs — only dataSync and
        //     mediaProcessing have the 6-hour limit).
        //   - WorkshopBrowser: scroll position + page number now survive
        //     navigation to a wallpaper detail and back (rememberSaveable with
        //     LazyGridState.Saver + mutableIntStateOf for lastSeenPage). The old
        //     rememberLazyGridState() was destroyed on every navigation, snapping
        //     back to the top.
        //   - VideoTrim: filmstrip is now horizontally scrollable with 40 frames
        //     (was fixed 16 squashed into screen width). Each frame is at the
        //     video's true aspect ratio. The whole screen is vertically scrollable
        //     so tall (portrait) videos don't push the trim controls off-screen.
        //     The RangeSlider + dim overlays span the full scrollable width so
        //     the selection maps correctly to the timeline.
        //   - All non-main screens: TopAppBar slimmed from default 64dp + status
        //     bar inset to 56dp + statusBarsPadding (matches WorkshopBrowserScreen).
        //     Affected: WallpaperDetail, Favorites, Settings, DownloadManager,
        //     Logs, Pairing, VideoTrim.
        //   - Concurrent downloads: WorkshopDownloadJob refactored from a single
        //     Job? + isRunning guard to a per-item ConcurrentHashMap<String, Job>
        //     + MutableStateFlow<Map<String, State>>. Multiple wallpapers can now
        //     download at the same time. The detail screen observes stateFor(itemId)
        //     so each screen only sees its own item's progress. cancel(itemId)
        //     cancels a specific download; cancel() cancels all.
        //   - SettingsScreen: removed ".mpkg" from the Clear Cache description
        //     (.mpkg files are packed on-demand and queued, not cached).
        // v0.7.21-alpha: notification re-post on dismiss + scroll position fix +
        //   VideoTrim redesign + SlimTopBar + log limit + critical bug fixes.
        //   - Notification: setDeleteIntent + BroadcastReceiver re-posts the
        //     notification when the user swipes it away (Android 14+ allows
        //     dismissing ongoing FGS notifications — this is the workaround).
        //   - WorkshopBrowser: gridState hoisted to NavHost level (was
        //     rememberSaveable which didn't reliably restore scroll position
        //     within NavHost composables). Scroll position now survives
        //     navigation to detail and back.
        //   - VideoTrim: replaced RangeSlider with custom draggable clip bars
        //     (2 vertical bars for start/end, 36dp touch target + 4dp visible).
        //     Added a center playhead marker (white vertical line) showing
        //     current playback position. Disabled PlayerView controls except
        //     play/pause (useController = false + overlay play/pause button).
        //   - SlimTopBar: new reusable composable (Surface + Row with
        //     CenterVertically) replaces Material 3 TopAppBar on all 7 non-main
        //     screens. The TopAppBar's internal layout didn't vertically center
        //     the title at 56dp height — SlimTopBar fixes this.
        //   - Log limit: RING_BUFFER_CAPACITY 2000 → 5000, LogsScreen limit
        //     2000 → 5000.
        //   - BUG 1 (Critical): maybeShrinkRgba never read the rgba array —
        //     texture shrink produced garbled textures. Fixed.
        //   - BUG 4/5 (High): runCatching in WorkshopDownloadJob swallowed
        //     CancellationException. Replaced with try/catch that re-throws.
        //   - BUG 23 (High): Clear Cache deleted in-flight download temp files.
        //     Now skips *_send.pkg, *_debug.pkg, *_send.mpkg, *_debug.mpkg,
        //     *_trimmed.mp4, and workshop_downloads/.
        //   - BUG 25 (Medium): FavoritesScreen catch(Exception) swallowed
        //     CancellationException. Fixed.
        //   - BUG 33 (High): CrashHandler shared StringBuilder between crash
        //     thread and helper thread. Helper now writes to its own SB.
        //   - BUG 47 (Medium): videoTrim route passed File("") when
        //     pendingVideoFor returned null. Now pops back immediately.
        // v0.7.22-alpha: LINK-TIME PARITY — cross-stage varying harmonization
        //   + the real PC's heartbeat echo.
        //   - ShaderStageLink (NEW): the PC's D3D pipeline links shader stages
        //     by register semantic and never type-checks them, so shipped
        //     assets can carry cross-stage varying mismatches (effects/glitter:
        //     glitter_prepare.vert declares vec2 v_TexCoord while the .frag
        //     declares vec4) that the official Android engine's strict ESSL
        //     3.00 linker rejects: "Failed linking program effects/
        //     glitter_prepare (Error: output v_TexCoord type mismatch with
        //     other stage. Error: Input Output Mismatch.)" — a hard render
        //     failure for EVERY wallpaper using such an effect (user applog
        //     2026-11-01; community-confirmed broken even for real PC
        //     transfers). The new pass runs after the per-file chain over the
        //     final bytes of every .vert/.frag pair in the package and unifies
        //     mismatched float-vector varyings fxc-faithfully: the fragment
        //     declaration is retyped to the vertex type when the fragment only
        //     reads components the vertex declares (the glitter case), else the
        //     vertex declaration is upsized with zero-filled constructors.
        //     Conservative by design: matrices/arrays/shadowed/preprocessor-
        //     referenced names and .geom chains are skipped; consistent pairs
        //     are byte-identical (0 rewrites on the real-PC demo corpus).
        //     Validation: glslangValidator -l over 252 pairs (we-assets
        //     effects + APK demo mpkgs + engine core) — 0 type mismatches
        //     after the pipeline; 13 new unit tests; 79/79 total pass.
        //   - WePairingServer (PC parity, wallpaperui.exe 0x140039c98–
        //     0x14003a33c): every "heartbeat" is now answered with the
        //     encrypted {"command":"heartbeat"} echo — this is what keeps the
        //     official app's 5-second idle-disconnect rescheduled, so
        //     backgrounded connections stay alive exactly as with a real PC
        //     (was: silent skip → the recurring "Connection closed:
        //     java.net.SocketException: Socket closed" churn). Unknown
        //     commands are now SILENTLY ignored (the AuthOK ack was never PC
        //     behavior) and "quit" closes cleanly.
        versionCode = 114
        versionName = "0.7.22-alpha"
        // Native ABI filter — only build for arm64-v8a (WE app targets Snapdragon 8 Gen 2+)
        ndk {
            abiFilters += "arm64-v8a"
        }
        // External native ETC2 encoder CMake args
        externalNativeBuild {
            cmake {
                arguments += "-DANDROID_STL=c++_static"
                cppFlags += "-std=c++11 -fexceptions -frtti"
            }
        }
        // Docker image (35.0.0) — AGP 8.7.0 defaults to 34.0.0 which isn't
        // installed, and the builder user can't install it (no write access
        // to /opt/android-sdk/).
        // WE app targets Snapdragon 8 Gen 2+ — only arm64-v8a makes sense
        // Custom Application ID field so we can carry version metadata into the APK
        buildConfigField("String", "GIT_SHA", "\"${getGitSha()}\"")
        buildConfigField("String", "BUILD_TIME", "\"${getBuildTime()}\"")
        // Multidex not needed since we don't have 65k+ methods, but explicit
        multiDexEnabled = true
        // Required for very large APKs that include assets > 200 MB
        // (the Play Store cap doesn't apply to sideloaded APKs)
        // Use APK splits for asset packs in the future
    }

    // APK signing in CI: keystore is base64-decoded from a GitHub Secret
    // For local dev, debug signing is automatic via AGP.
    signingConfigs {
        create("release") {
            val keystorePath = System.getenv("WEPWAWET_KEYSTORE_PATH")
            val keystorePass = System.getenv("WEPWAWET_KEYSTORE_PASS") ?: ""
            val keyAlias = System.getenv("WEPWAWET_KEY_ALIAS") ?: ""
            val keyPass = System.getenv("WEPWAWET_KEY_PASS") ?: ""
            if (keystorePath != null && keystorePath.isNotBlank()) {
                storeFile = file(keystorePath)
                storePassword = keystorePass
                this.keyAlias = keyAlias
                keyPassword = keyPass
            }
        }
        // Stable debug keystore committed to the repo so all builds (local + CI)
        // are signed with the SAME key. Without this, each CI runner generates a
        // fresh debug keystore, so users would have to uninstall before each
        // upgrade — losing Steam credentials + refresh tokens.
        // The keystore is committed at app/debug.keystore with the standard
        // Android debug password "android" (well-known, not a secret).
        create("stableDebug") {
            storeFile = file("debug.keystore")
            storePassword = "android"
            keyAlias = "androiddebugkey"
            keyPassword = "android"
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            // Use release signing if keystore is set; otherwise stable debug
            // (debug-signed APK is fine for sideloading — just not for Play Store)
            signingConfig = if (System.getenv("WEPWAWET_KEYSTORE_PATH") != null) {
                signingConfigs.findByName("release")
            } else {
                signingConfigs.findByName("stableDebug")
            }
            // Native libs are extracted at install time so they can be loaded directly
            // (required for the bundled ETC2 encoder and any future native components)
        }
        debug {
            isDebuggable = true
            applicationIdSuffix = ".debug"
            versionNameSuffix = "-debug"
            // Use the stable debug keystore so CI + local builds match
            signingConfig = signingConfigs.findByName("stableDebug")
            // Multi-APK not needed for debug builds
        }
    }

    // Compose
    packaging {
        resources {
            excludes += "/META-INF/versions/9/OSGI-INF/MANIFEST.MF"
            excludes += "/META-INF/*.SF"
            excludes += "/META-INF/*.DSA"
            excludes += "/META-INF/*.RSA"
            excludes += "/META-INF/MANIFEST.MF"
            excludes += "/META-INF/INDEX.LIST"
            excludes += "/META-INF/DEPENDENCIES"
        }
    }
    buildFeatures {
        compose = true
        buildConfig = true
    }

    // Java/Kotlin target — matches the JavaSteam + Android Gradle Plugin Java 17 baseline
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlin {
        compilerOptions {
            jvmTarget.set(JvmTarget.JVM_17)
            freeCompilerArgs.addAll(
                "-opt-in=kotlin.RequiresOptIn",
                "-opt-in=androidx.compose.material3.ExperimentalMaterial3Api",
                "-opt-in=androidx.compose.foundation.ExperimentalFoundationApi",
                "-opt-in=kotlinx.serialization.ExperimentalSerializationApi",
                "-opt-in=androidx.media3.common.util.UnstableApi",
            )
        }
    }

    // Asset / jniLibs source sets (the bundled ETC2 encoder .so lives under jniLibs)
    sourceSets {
        getByName("main") {
            assets.srcDirs("src/main/assets")
            jniLibs.srcDirs("src/main/jniLibs")
        }
    }

    // Native build for ETC2 RGBA8 encoder (matches WE's resourcecompiler64.exe)
    // Builds Google's etc2comp as libwepwawet_etc2.so for arm64-v8a
    // Used by MpkgPacker.convertTexPngToEtc2() to encode PNG → ETC2 RGBA8
    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
            version = "3.22.1"
        }
    }

    // Only build native for arm64-v8a (matches our APK ABI filter)
    ndkVersion = "27.0.12077973"  // NDK r27
}

dependencies {
    // Compose BOM
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.material.icons.extended)
    debugImplementation(libs.androidx.compose.ui.tooling)

    // Lifecycle / Activity / Navigation
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.service)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.lifecycle.process)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.navigation.compose)

    // Networking
    implementation(libs.okhttp)
    implementation(libs.okhttp.logging.interceptor)
    implementation(libs.retrofit)
    implementation(libs.retrofit.kotlinx.serialization)
    implementation(libs.kotlinx.serialization.json)

    // Image loading
    implementation(libs.coil.compose)
    implementation(libs.coil.gif)
    implementation("com.google.code.gson:gson:2.10.1")

    // Media3 (ExoPlayer) — for video preview in the VideoTrimScreen
    implementation(libs.media3.exoplayer)
    implementation(libs.media3.ui)
    implementation(libs.media3.common)

    // JavaSteam — Java/Kotlin port of SteamKit2 for direct Steam protocol access
    // Downloads real workshop .pkg files via Steam's binary TCP protocol — NO PC required
    // (runs entirely on-device via Java/Kotlin, no server-side component)
    implementation(libs.javasteam.core)
    implementation(libs.javasteam.depotdownloader)
    implementation(libs.bouncycastleProvider)  // required by JavaSteam for crypto
    // protobuf-java — JavaSteam's generated message classes (e.g. CPublishedFile_QueryFiles_Response)
    // extend com.google.protobuf.GeneratedMessage / MessageOrBuilder / ProtocolMessageEnum. AGP doesn't
    // expose these supertypes transitively at compile time, so we add them explicitly. Without this,
    // WorkshopBrowseSession.kt fails with 'Cannot access GeneratedMessage which is a supertype of ...'.
    implementation(libs.protobufJava)
    // zstd-jni — required by JavaSteam for Zstd chunk decompression
    // MUST use AAR (not JAR) — the JAR only has Linux/macOS/Windows .so files
    // (compiled with glibc), which fail on Android with UnsatisfiedLinkError.
    // The AAR includes Android-compatible .so files for arm64-v8a.
    implementation(libs.zstdJni) { artifact { type = "aar" } }
    implementation(libs.xz)      // required by JavaSteam for LZMA chunk decompression
    implementation(libs.lz4Java)  // LZ4 compression for .tex files (WE's compression=1 format)

    // NOTE: FFmpegKit was removed (com.arthenica:ffmpeg-kit-full is no longer on
    // Maven Central — the project was archived in 2024). Video transcoding is
    // now done using Android's built-in MediaCodec API (hardware-accelerated,
    // no external dependency, handles VP8/VP9 → H.264 transcoding natively).
    // See VideoTranscoder.kt for the MediaCodec-based implementation.

    // WorkManager (for log persistence + background tasks)
    implementation(libs.androidx.work.runtime.ktx)

    // DataStore + encrypted prefs (for Steam credential storage)
    implementation(libs.androidx.datastore.preferences)
    implementation(libs.androidx.security.crypto)

    // Testing
    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(platform(libs.androidx.compose.bom))
}

// Helpers
fun getGitSha(): String {
    // Use GITHUB_SHA env var (always available in CI) — avoids Gradle 8.10+
    // blocking external process execution during configuration time.
    return System.getenv("GITHUB_SHA")?.take(7) ?: "unknown"
}

fun getBuildTime(): String {
    val sdf = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ssXXX")
    return sdf.format(Date())
}
