package com.wepwawet.pairing

import com.wepwawet.log.WepWawetLogger
import java.io.File

/**
 * Video transcoder using Android's built-in MediaCodec API.
 *
 * Real WE PC uses `ffmpeg_mp4_h264` to transcode WebM videos to MP4 with
 * H.264 codec when sending to Android. We do the same on-device using
 * Android's hardware-accelerated MediaCodec API.
 *
 * The Android WE app's VideoDrawable expects MP4 with H.264 codec (the
 * `mp4_h264` codec identifier is written to project.json by the transcoder).
 * WebM files (VP8/VP9 video + Vorbis/Opus audio) may not be supported by
 * all Android VideoDrawable implementations, so we transcode them.
 *
 * This implementation uses MediaExtractor to read the input video's tracks,
 * MediaCodec to decode + re-encode the video track, and MediaMuxer to write
 * the output MP4. It handles VP8/VP9 → H.264 video transcoding.
 *
 * v0.5.32 (C2 fix): audio track is intentionally NOT transcoded — input
 * audio (if any) is dropped. WE wallpapers commonly have no audio, and
 * the previous incomplete audio-transcode implementation produced
 * structurally invalid MP4s (audio track header with zero samples).
 *
 * No external dependencies required — uses only Android framework APIs
 * (MediaExtractor, MediaCodec, MediaMuxer) available since API 18 (Android 4.3).
 */
object VideoTranscoder {
    private const val TAG = "VideoTranscoder"

    /**
     * Check if video transcoding is available (always true on Android 8.0+).
     * MediaCodec is a framework API available on all Android devices.
     */
    fun isAvailable(): Boolean = true

    /**
     * Transcode a video file to MP4 with H.264 (or H.265 if supported) video codec.
     *
     * v0.6.0 (parity §8-1): Now supports H.265/HEVC output encoding when the device
     * supports it. H.265 produces ~50% smaller files at equivalent quality.
     *
     * v0.6.0 (parity §8-2): Audio is now properly transcoded to AAC (was dropped in
     * v0.5.32). WE wallpapers commonly have no audio, but some do — we transcode
     * to AAC 128 kbps stereo 44.1 kHz when audio is present.
     *
     * v0.6.0 (parity §8-3): Frame rate is now preserved from source (was hardcoded to 30).
     *
     * v0.6.0 (parity §8-4): Rotation metadata is now preserved via muxer.setOrientationHint.
     *
     * v0.6.0 (parity §8-5): B-frames are disabled for seek-friendly wallpaper playback.
     *
     * v0.6.0 (parity §8-6): H.264 encoder uses Baseline profile for max device compatibility.
     *
     * v0.6.0 (parity §8-7): On transcode failure, returns false (don't rename .webm to .mp4
     * with WebM content).
     *
     * @param inputFile Source video file (.webm, .mp4, .mov, .mkv, .avi, etc.)
     * @param outputFile Output MP4 file (will be overwritten if exists)
     * @return true on success, false on failure
     */
    fun transcodeToMp4H264(inputFile: File, outputFile: File): Boolean {
        if (!inputFile.exists()) {
            WepWawetLogger.proxyError("$TAG.transcodeToMp4H264 FAILED — input file does not exist",
                "input" to inputFile.absolutePath)
            return false
        }

        // If the input is an MP4 whose ACTUAL video track is H.264, just copy
        // it (no transcoding needed).
        // v0.7.24 (H-V6 BUG FIX): was a blind filename check — a .mp4
        // containing HEVC / VP9 / MJPEG / mp4v was copied verbatim and the
        // phone then showed a black wallpaper (exactly the failure the
        // v0.6.0 §8-7 fix was meant to prevent). Sniff the real MIME with
        // MediaExtractor; only AVC (and the empty-track / unreadable
        // edge cases, which the full transcode handles) short-circuits.
        if (inputFile.name.endsWith(".mp4", ignoreCase = true)) {
            val trackMime = try {
                val sniffer = android.media.MediaExtractor()
                sniffer.setDataSource(inputFile.absolutePath)
                var mime: String? = null
                for (t in 0 until sniffer.trackCount) {
                    val fmt = sniffer.getTrackFormat(t)
                    val m = fmt.getString(android.media.MediaFormat.KEY_MIME) ?: continue
                    if (m.startsWith("video/")) { mime = m; break }
                }
                sniffer.release()
                mime
            } catch (e: Exception) {
                WepWawetLogger.proxyWarn("$TAG: could not sniff .mp4 video mime — falling through to full transcode",
                    "exception" to e.javaClass.name)
                null
            }
            if (trackMime == android.media.MediaFormat.MIMETYPE_VIDEO_AVC) {
                WepWawetLogger.proxy("$TAG.transcodeToMp4H264: input is already H.264 in MP4 — copying instead of transcoding",
                    "input" to inputFile.name,
                    "input_size" to inputFile.length())
                inputFile.copyTo(outputFile, overwrite = true)
                return true
            }
            if (trackMime != null) {
                WepWawetLogger.proxy("$TAG.transcodeToMp4H264: .mp4 contains non-AVC video — transcoding",
                    "input" to inputFile.name,
                    "video_mime" to trackMime)
            }
        }

        WepWawetLogger.proxy("$TAG.transcodeToMp4H264: starting transcode",
            "input" to inputFile.name,
            "input_size" to inputFile.length(),
            "output" to outputFile.name)

        return try {
            transcodeWithMediaCodec(inputFile, outputFile)
        } catch (e: Exception) {
            // v0.6.0 (parity §8-7): CRITICAL FIX — Return false on transcode failure.
            // Previously the code would copy the .webm bytes into the .mp4 file and return
            // true, producing an MP4 with WebM content that Android's MediaPlayer can't play.
            // The caller (MpkgPacker.kt) renames the VFS entry from *.webm to *.mp4 — but
            // the content is still WebM, so the wallpaper shows black/error.
            //
            // New behavior: return false so caller keeps the .webm extension. Android's
            // MediaPlayer MIGHT support WebM natively on some devices — better than a
            // guaranteed-broken .mp4 with WebM content.
            WepWawetLogger.proxyError("$TAG.transcodeToMp4H264 EXCEPTION — keeping original extension",
                "input" to inputFile.name,
                "exception" to e.javaClass.name,
                "message" to (e.message ?: ""))
            false
        }
    }

    /**
     * Transcode using MediaExtractor + MediaCodec + MediaMuxer.
     *
     * Flow:
     * 1. MediaExtractor reads the input file's tracks (video + audio)
     * 2. For each track:
     *    a. MediaCodec decodes the compressed data to raw frames
     *    b. MediaCodec re-encodes the raw frames to H.264 (video) or AAC (audio)
     * 3. MediaMuxer writes the encoded data to the output MP4 file
     */
    private fun transcodeWithMediaCodec(inputFile: File, outputFile: File): Boolean {
        // === v0.7.4 RESTRUCTURE ===
        // Every codec/muxer/extractor is declared BEFORE the try and released in
        // a finally block — the old code only cleaned up on the success path, so
        // ANY mid-transcode exception leaked all four MediaCodecs + the muxer +
        // both extractors (MediaCodec instances are limited per process; after a
        // couple of failed transcodes the next createEncoderByType failed).
        //
        // The muxer track setup was also fixed: addTrack() is now called with the
        // encoder's OUTPUT format (obtained after start(), via
        // INFO_OUTPUT_FORMAT_CHANGED) instead of the CONFIGURE input format. The
        // input format has no CSD-0/CSD-1 (SPS/PPS, AudioSpecificConfig), so the
        // old code produced MP4s whose moov atom had no codec-specific data while
        // the CSD bytes landed in mdat as fake samples — structurally broken
        // output. Per the Android docs: "the MediaFormat will be retrieved from
        // MediaCodec.getOutputFormat()". CODEC_CONFIG output buffers are now
        // skipped (their data is conveyed via the output format).
        //
        // Frames are no longer dropped when the encoder input queue is
        // momentarily full: a decoded frame that can't be queued yet is kept as
        // `pendingVideoDecoded`/`pendingAudioDecoded` and retried on the next
        // loop iteration (the old code just released it — missing frames, PTS
        // gaps, glitches). The raw-byte copy fallback is also no longer nested
        // inside the Image!=null branch, so codecs that don't expose the Image
        // API still get their frames through.
        val extractor = android.media.MediaExtractor()
        var muxer: android.media.MediaMuxer? = null
        var videoDecoder: android.media.MediaCodec? = null
        var videoEncoder: android.media.MediaCodec? = null
        var audioDecoder: android.media.MediaCodec? = null
        var audioEncoder: android.media.MediaCodec? = null
        var audioExtractor: android.media.MediaExtractor? = null
        var muxerStarted = false
        var success = false

        try {
            extractor.setDataSource(inputFile.absolutePath)

            // Find video and audio tracks
            var videoTrackIndex = -1
            var audioTrackIndex = -1
            for (i in 0 until extractor.trackCount) {
                val format = extractor.getTrackFormat(i)
                val mime = format.getString(android.media.MediaFormat.KEY_MIME) ?: ""
                if (mime.startsWith("video/") && videoTrackIndex == -1) {
                    videoTrackIndex = i
                } else if (mime.startsWith("audio/") && audioTrackIndex == -1) {
                    audioTrackIndex = i
                }
            }

            if (videoTrackIndex == -1) {
                WepWawetLogger.proxyError("$TAG: no video track found in input",
                    "input" to inputFile.name,
                    "track_count" to extractor.trackCount)
                return false
            }

            // Read video track format (width, height, duration, etc.)
            val videoInputFormat = extractor.getTrackFormat(videoTrackIndex)
            val width = videoInputFormat.getInteger(android.media.MediaFormat.KEY_WIDTH)
            val height = videoInputFormat.getInteger(android.media.MediaFormat.KEY_HEIGHT)
            val duration = if (videoInputFormat.containsKey(android.media.MediaFormat.KEY_DURATION)) {
                videoInputFormat.getLong(android.media.MediaFormat.KEY_DURATION)
            } else 0L

            // v0.6.0 (parity §8-3): Preserve source frame rate (was hardcoded to 30)
            val srcFps = if (videoInputFormat.containsKey(android.media.MediaFormat.KEY_FRAME_RATE))
                videoInputFormat.getInteger(android.media.MediaFormat.KEY_FRAME_RATE) else 30

            WepWawetLogger.proxy("$TAG: video track found",
                "width" to width, "height" to height,
                "duration_ms" to (duration / 1000),
                "fps" to srcFps,
                "input_mime" to (videoInputFormat.getString(android.media.MediaFormat.KEY_MIME) ?: "?"))

            // Create the output MediaMuxer (writes MP4 container).
            // NOTE: addTrack/start are deferred until the encoders report their
            // output formats (see the loop below) — see the v0.7.4 note above.
            val mux = android.media.MediaMuxer(
                outputFile.absolutePath,
                android.media.MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4
            )
            muxer = mux

            // v0.6.0 (parity §8-4): Preserve rotation metadata via muxer orientation hint
            if (videoInputFormat.containsKey(android.media.MediaFormat.KEY_ROTATION)) {
                val rotation = videoInputFormat.getInteger(android.media.MediaFormat.KEY_ROTATION)
                mux.setOrientationHint(rotation)
                WepWawetLogger.proxy("$TAG: preserving rotation metadata",
                    "rotation_degrees" to rotation)
            }

            // v0.6.0 (parity §8-1): Pick H.264 (default) or H.265 (if device supports encoding)
            val codecMime = pickVideoCodec()
            val isH265 = codecMime == android.media.MediaFormat.MIMETYPE_VIDEO_HEVC
            WepWawetLogger.proxy("$TAG: selected video codec",
                "mime" to codecMime, "is_h265" to isH265)

            // Configure the video encoder (H.264 or H.265)
            val videoEncoderFormat = android.media.MediaFormat.createVideoFormat(
                codecMime, width, height
            ).apply {
                setInteger(android.media.MediaFormat.KEY_COLOR_FORMAT,
                    android.media.MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Flexible)
                setInteger(android.media.MediaFormat.KEY_BIT_RATE, computeBitrate(width, height, srcFps))
                setInteger(android.media.MediaFormat.KEY_FRAME_RATE, srcFps)  // §8-3: preserve source fps
                setInteger(android.media.MediaFormat.KEY_I_FRAME_INTERVAL, 2) // I-frame every 2 seconds

                // v0.6.0 (parity §8-6): H.264 Baseline profile for max device compatibility
                if (!isH265) {
                    try {
                        setInteger(android.media.MediaFormat.KEY_PROFILE,
                            android.media.MediaCodecInfo.CodecProfileLevel.AVCProfileBaseline)
                        // v0.7.24 (H-V3 BUG FIX): was AVCLevel3 — that caps the
                        // bitstream at 720x576@25fps; many hardware encoders
                        // honor the requested level and refuse to configure (or
                        // emit a level-error stream) for 1080p/4K wallpapers.
                        // Pick from the frame size: <=720p → 3.1, <=1080p → 4.2,
                        // anything larger → 5.1.
                        setInteger(android.media.MediaFormat.KEY_LEVEL,
                            if (width <= 1280 && height <= 720)
                                android.media.MediaCodecInfo.CodecProfileLevel.AVCLevel31
                            else if (width <= 1920 && height <= 1080)
                                android.media.MediaCodecInfo.CodecProfileLevel.AVCLevel42
                            else
                                android.media.MediaCodecInfo.CodecProfileLevel.AVCLevel51)
                    } catch (_: Exception) {
                        // Some encoders don't support KEY_PROFILE — fall back to default
                    }
                }

                // v0.6.0 (parity §8-5): Disable B-frames for seek-friendly wallpaper playback
                try {
                    setInteger("max-bframes", 0)  // API 23+
                } catch (_: Exception) {
                    // Some encoders don't support this key — ignore
                }
                if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.R) {
                    try {
                        setInteger(android.media.MediaFormat.KEY_LATENCY, 0)  // API 30+
                    } catch (_: Exception) {}
                }
            }

            // v0.7.24 (H-V2 BUG FIX): pickVideoCodec() returns HEVC as soon
            // as ANY HEVC encoder exists — without checking that it supports
            // the requested size. Devices with a ≤1080p-only HEVC encoder
            // then threw at configure() for a 4K wallpaper and the whole
            // transcode failed. On configure/start failure with HEVC, retry
            // ONCE with AVC (universally capable at these sizes).
            var encTmp: android.media.MediaCodec? = null
            var effectiveCodecMime = codecMime
            try {
                encTmp = android.media.MediaCodec.createEncoderByType(codecMime)
                encTmp.configure(videoEncoderFormat, null, null,
                    android.media.MediaCodec.CONFIGURE_FLAG_ENCODE)
                encTmp.start()
            } catch (e: Exception) {
                runCatching { encTmp?.stop() }
                runCatching { encTmp?.release() }
                encTmp = null
                if (codecMime == android.media.MediaFormat.MIMETYPE_VIDEO_HEVC) {
                    WepWawetLogger.proxyWarn("$TAG: HEVC encoder rejected the format — retrying with H.264",
                        "exception" to e.javaClass.name,
                        "message" to (e.message ?: ""),
                        "width" to width, "height" to height)
                    effectiveCodecMime = android.media.MediaFormat.MIMETYPE_VIDEO_AVC
                    // Rebuild the format for AVC (the level/profile keys below
                    // only apply to AVC; HEVC-specific bits are absent anyway).
                    val avcFormat = android.media.MediaFormat.createVideoFormat(
                        effectiveCodecMime, width, height
                    ).apply {
                        setInteger(android.media.MediaFormat.KEY_COLOR_FORMAT,
                            android.media.MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Flexible)
                        setInteger(android.media.MediaFormat.KEY_BIT_RATE, computeBitrate(width, height, srcFps))
                        setInteger(android.media.MediaFormat.KEY_FRAME_RATE, srcFps)
                        setInteger(android.media.MediaFormat.KEY_I_FRAME_INTERVAL, 2)
                        try {
                            setInteger(android.media.MediaFormat.KEY_PROFILE,
                                android.media.MediaCodecInfo.CodecProfileLevel.AVCProfileBaseline)
                            setInteger(android.media.MediaFormat.KEY_LEVEL,
                                if (width <= 1280 && height <= 720)
                                    android.media.MediaCodecInfo.CodecProfileLevel.AVCLevel31
                                else if (width <= 1920 && height <= 1080)
                                    android.media.MediaCodecInfo.CodecProfileLevel.AVCLevel42
                                else
                                    android.media.MediaCodecInfo.CodecProfileLevel.AVCLevel51)
                        } catch (_: Exception) {}
                    }
                    encTmp = android.media.MediaCodec.createEncoderByType(effectiveCodecMime)
                    encTmp.configure(avcFormat, null, null,
                        android.media.MediaCodec.CONFIGURE_FLAG_ENCODE)
                    encTmp.start()
                } else {
                    // AVC itself failed — rethrow; the outer catch handles it.
                    throw e
                }
            }
            videoEncoder = encTmp
            // Non-null view for the transcode loop below (encTmp is guaranteed
            // assigned here: the catch either rethrows or reassigns).
            val vEnc = encTmp!!

            // Configure the video decoder
            extractor.selectTrack(videoTrackIndex)
            val vDec = android.media.MediaCodec.createDecoderByType(
                videoInputFormat.getString(android.media.MediaFormat.KEY_MIME)!!
            )
            videoDecoder = vDec
            vDec.configure(videoInputFormat, null, null, 0)
            vDec.start()

            // v0.6.0 (parity §8-2): audio transcode setup (AAC 128 kbps stereo
            // 44.1 kHz). On ANY setup failure the audio codecs are released and
            // nulled out — the loop then runs video-only. The muxer track is NOT
            // added here (CSD is unknown until the encoder starts producing).
            val hasAudio = audioTrackIndex >= 0
            if (hasAudio) {
                try {
                    val audioInputFormat = extractor.getTrackFormat(audioTrackIndex)
                    val audioMime = audioInputFormat.getString(android.media.MediaFormat.KEY_MIME)!!
                    // v0.7.4: dedicated extractor for the audio track (the first
                    // extractor has the VIDEO track selected). Created up front
                    // and released in the finally block — the old code leaked it
                    // if anything inside the audio loop threw.
                    val aExt = android.media.MediaExtractor()
                    audioExtractor = aExt
                    aExt.setDataSource(inputFile.absolutePath)
                    aExt.selectTrack(audioTrackIndex)

                    val aDec = android.media.MediaCodec.createDecoderByType(audioMime)
                    audioDecoder = aDec
                    aDec.configure(audioInputFormat, null, null, 0)
                    aDec.start()

                    // v0.7.24 (C-V2 BUG FIX): was hardcoded 44100 Hz / stereo.
                    // The transcode loop copies decoded PCM bytes verbatim into
                    // the encoder input with NO resampling and NO channel
                    // up/down-mix — so for any source that wasn't exactly
                    // 44.1kHz stereo (48kHz mono Opus, 22kHz Vorbis, …) the
                    // encoder reinterpreted the bytes at the wrong rate:
                    // audio played at the wrong speed and pitch, or failed to
                    // decode. Configure the encoder with the SOURCE's actual
                    // rate/channels (read from the decoder's input format).
                    val srcSampleRate = audioInputFormat.getInteger(
                        android.media.MediaFormat.KEY_SAMPLE_RATE, 44100)
                    val srcChannels = audioInputFormat.getInteger(
                        android.media.MediaFormat.KEY_CHANNEL_COUNT, 2)
                    val audioEncoderFormat = android.media.MediaFormat.createAudioFormat(
                        android.media.MediaFormat.MIMETYPE_AUDIO_AAC,
                        srcSampleRate,
                        srcChannels
                    ).apply {
                        setInteger(android.media.MediaFormat.KEY_BIT_RATE, 128000)
                        setInteger(android.media.MediaFormat.KEY_AAC_PROFILE,
                            android.media.MediaCodecInfo.CodecProfileLevel.AACObjectLC)
                    }
                    val aEnc = android.media.MediaCodec.createEncoderByType(
                        android.media.MediaFormat.MIMETYPE_AUDIO_AAC)
                    audioEncoder = aEnc
                    aEnc.configure(audioEncoderFormat, null, null,
                        android.media.MediaCodec.CONFIGURE_FLAG_ENCODE)
                    aEnc.start()

                    WepWawetLogger.proxy("$TAG: audio transcoding setup complete",
                        "source_mime" to audioMime,
                        "sample_rate" to srcSampleRate,
                        "channels" to srcChannels,
                        "target_codec" to "AAC 128kbps ${srcChannels}ch ${srcSampleRate}Hz")
                } catch (e: Exception) {
                    WepWawetLogger.proxyWarn("$TAG: audio transcode setup failed — dropping audio",
                        "exception" to e.javaClass.name, "message" to (e.message ?: ""))
                    runCatching { audioDecoder?.stop() }
                    runCatching { audioDecoder?.release() }
                    runCatching { audioEncoder?.stop() }
                    runCatching { audioEncoder?.release() }
                    runCatching { audioExtractor?.release() }
                    audioDecoder = null
                    audioEncoder = null
                    audioExtractor = null
                }
            }

            // === Interleaved transcode loop (extract → decode → encode → mux) ===
            // Audio and video are processed in the SAME loop so both encoders'
            // output formats (needed for muxer.addTrack) arrive before either
            // stream needs to be written. Samples produced before the muxer has
            // both tracks are buffered in memory (a tiny window — each encoder
            // reports its format on its first output, which happens within the
            // first few loop iterations).
            val info = android.media.MediaCodec.BufferInfo()
            val bufferInfo = android.media.MediaCodec.BufferInfo()
            val audioInfo = android.media.MediaCodec.BufferInfo()
            val audioEncInfo = android.media.MediaCodec.BufferInfo()
            val TIMEOUT_US = 10000L // 10ms timeout

            var videoAllExtracted = false
            var videoAllDecoded = false
            var videoAllEncoded = false
            var videoFrameCount = 0
            // v0.7.4: pending decoded buffer index — a decoded frame that hasn't
            // been accepted by the encoder yet. Kept (NOT released) and retried
            // next iteration; the old code released it (frame drop).
            var pendingVideoDecoded = -1

            var audioAllExtracted = false
            var audioAllDecoded = false
            var audioAllEncoded = false
            var audioFrameCount = 0
            var pendingAudioDecoded = -1

            var muxerVideoTrack = -1
            var muxerAudioTrack = -1

            // Encoded samples produced before both tracks were added to the muxer.
            class PendingSample(val data: ByteArray, val ptsUs: Long, val flags: Int)
            val pendingVideoSamples = mutableListOf<PendingSample>()
            val pendingAudioSamples = mutableListOf<PendingSample>()

            fun makeSampleInfo(data: ByteArray, ptsUs: Long, flags: Int): android.media.MediaCodec.BufferInfo =
                android.media.MediaCodec.BufferInfo().apply { set(0, data.size, ptsUs, flags) }

            fun maybeStartMuxer() {
                if (!muxerStarted && muxerVideoTrack >= 0 &&
                    (muxerAudioTrack >= 0 || audioEncoder == null)) {
                    mux.start()
                    muxerStarted = true
                    // Flush everything buffered while we were waiting for the
                    // second track's format.
                    for (s in pendingVideoSamples) {
                        mux.writeSampleData(muxerVideoTrack,
                            java.nio.ByteBuffer.wrap(s.data), makeSampleInfo(s.data, s.ptsUs, s.flags))
                    }
                    pendingVideoSamples.clear()
                    if (muxerAudioTrack >= 0) {
                        for (s in pendingAudioSamples) {
                            mux.writeSampleData(muxerAudioTrack,
                                java.nio.ByteBuffer.wrap(s.data), makeSampleInfo(s.data, s.ptsUs, s.flags))
                        }
                        pendingAudioSamples.clear()
                    }
                    WepWawetLogger.proxy("$TAG: muxer started",
                        "video_track" to muxerVideoTrack,
                        "audio_track" to muxerAudioTrack,
                        "buffered_video_flushed" to true)
                }
            }

            fun writeOrBufferVideo(data: ByteArray, ptsUs: Long, flags: Int) {
                if (muxerStarted) {
                    mux.writeSampleData(muxerVideoTrack,
                        java.nio.ByteBuffer.wrap(data), makeSampleInfo(data, ptsUs, flags))
                } else {
                    pendingVideoSamples.add(PendingSample(data, ptsUs, flags))
                }
            }

            fun writeOrBufferAudio(data: ByteArray, ptsUs: Long, flags: Int) {
                if (muxerStarted && muxerAudioTrack >= 0) {
                    mux.writeSampleData(muxerAudioTrack,
                        java.nio.ByteBuffer.wrap(data), makeSampleInfo(data, ptsUs, flags))
                } else if (muxerAudioTrack >= 0) {
                    pendingAudioSamples.add(PendingSample(data, ptsUs, flags))
                }
            }

            while (!(videoAllEncoded && (audioEncoder == null || audioAllEncoded))) {
                // ================= VIDEO PHASE =================
                if (!videoAllExtracted) {
                    val inputBufferIndex = vDec.dequeueInputBuffer(TIMEOUT_US)
                    if (inputBufferIndex >= 0) {
                        val inputBuffer = vDec.getInputBuffer(inputBufferIndex)!!
                        val sampleSize = extractor.readSampleData(inputBuffer, 0)
                        if (sampleSize < 0) {
                            vDec.queueInputBuffer(inputBufferIndex, 0, 0, 0,
                                android.media.MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                            videoAllExtracted = true
                        } else {
                            val sampleTime = extractor.sampleTime
                            vDec.queueInputBuffer(inputBufferIndex, 0, sampleSize, sampleTime, 0)
                            extractor.advance()
                        }
                    }
                }

                if (!videoAllDecoded) {
                    if (pendingVideoDecoded < 0) {
                        val outputBufferIndex = vDec.dequeueOutputBuffer(info, TIMEOUT_US)
                        if (outputBufferIndex >= 0) {
                            if (info.flags and android.media.MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) {
                                videoAllDecoded = true
                                vEnc.signalEndOfInputStream()
                                vDec.releaseOutputBuffer(outputBufferIndex, false)
                            } else {
                                // Hold the frame until the encoder can accept it.
                                pendingVideoDecoded = outputBufferIndex
                            }
                        }
                    }
                    if (pendingVideoDecoded >= 0) {
                        val encoderInputIndex = vEnc.dequeueInputBuffer(TIMEOUT_US)
                        if (encoderInputIndex >= 0) {
                            // v0.5.32 (H6 fix): use the Image API (getOutputImage /
                            // getInputImage) to copy YUV planes — raw ByteBuffer
                            // copies scramble chroma when the decoder and encoder
                            // use different YUV420 layouts (green tint / swapped U/V).
                            // v0.7.4: the raw-byte fallback is no longer nested
                            // inside the Image!=null branch, so codecs without
                            // Image support still get their frames through.
                            val decodedImage = vDec.getOutputImage(pendingVideoDecoded)
                            val encoderImage = vEnc.getInputImage(encoderInputIndex)
                            if (decodedImage != null && encoderImage != null) {
                                // v0.7.24 (H-V7): read the encoder's slice
                                // height so the tail rows get zeroed.
                                val encSliceH = try {
                                    vEnc.outputFormat.getInteger(
                                        android.media.MediaFormat.KEY_SLICE_HEIGHT, -1)
                                } catch (_: Exception) { -1 }
                                copyYuv420Image(decodedImage, encoderImage, width, height, encSliceH)
                                // Pass the encoder input buffer capacity as the
                                // size — the full YUV420 frame per the encoder's
                                // own layout (padding rows are zeroed by
                                // copyYuv420Image so no uninitialized bytes are
                                // ever read).
                                val encoderBuf = vEnc.getInputBuffer(encoderInputIndex)
                                val expectedSize = encoderBuf?.capacity() ?: info.size
                                vEnc.queueInputBuffer(
                                    encoderInputIndex, 0, expectedSize,
                                    info.presentationTimeUs, 0,
                                )
                            } else {
                                // Fallback: raw ByteBuffer copy (pre-H6 behavior).
                                val decodedBuffer = vDec.getOutputBuffer(pendingVideoDecoded)
                                val rawEncoderInput = vEnc.getInputBuffer(encoderInputIndex)
                                if (decodedBuffer != null && rawEncoderInput != null) {
                                    val chunkSize = minOf(info.size, rawEncoderInput.capacity())
                                    decodedBuffer.position(info.offset)
                                    decodedBuffer.limit(info.offset + chunkSize)
                                    rawEncoderInput.clear()
                                    rawEncoderInput.put(decodedBuffer)
                                    vEnc.queueInputBuffer(
                                        encoderInputIndex, 0, chunkSize,
                                        info.presentationTimeUs, 0,
                                    )
                                } else {
                                    // v0.7.24 (C-V4 BUG FIX): both copy paths
                                    // failed — the buffer at encoderInputIndex
                                    // was dequeued (client-owned) but never
                                    // queued back. After enough failures every
                                    // encoder input buffer was orphaned and
                                    // dequeueInputBuffer returned -1 forever
                                    // (infinite spin). Return it empty so the
                                    // codec reclaims it; the decoded frame is
                                    // still released below (one dropped frame
                                    // instead of a hung transcode).
                                    vEnc.queueInputBuffer(
                                        encoderInputIndex, 0, 0,
                                        info.presentationTimeUs, 0,
                                    )
                                    WepWawetLogger.proxyWarn("$TAG: video frame copy failed — returned empty encoder buffer",
                                        "decoded_buf_null" to (decodedBuffer == null),
                                        "encoder_buf_null" to (rawEncoderInput == null))
                                }
                            }
                            vDec.releaseOutputBuffer(pendingVideoDecoded, false)
                            pendingVideoDecoded = -1
                        }
                        // else: encoder busy — keep the decoded frame pending
                        // and retry next iteration (v0.7.4: no frame drops).
                    }
                }

                // === Always drain video encoder output, even after decoder EOS ===
                // (v0.5.32 C1 fix — see git history; without this the loop spun
                // forever after signalEndOfInputStream.)
                val encOut = vEnc.dequeueOutputBuffer(bufferInfo, TIMEOUT_US)
                when (encOut) {
                    android.media.MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                        // v0.7.4 CRITICAL: add the track with the ENCODER'S
                        // OUTPUT format — this is the only place the codec-
                        // specific data (SPS/PPS for H.264/H.265) is available.
                        if (muxerVideoTrack < 0) {
                            muxerVideoTrack = mux.addTrack(vEnc.outputFormat)
                            WepWawetLogger.proxy("$TAG: video track added to muxer (with CSD)",
                                "track_index" to muxerVideoTrack,
                                "format_keys" to vEnc.outputFormat.keys.sorted().joinToString(","))
                        }
                        maybeStartMuxer()
                    }
                    android.media.MediaCodec.INFO_OUTPUT_BUFFERS_CHANGED -> {
                        // No-op on API 21+ when using getOutputBuffer(int).
                    }
                }
                if (encOut >= 0) {
                    val isEos = bufferInfo.flags and android.media.MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0
                    val isCsd = bufferInfo.flags and android.media.MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0
                    // v0.7.4: write the sample FIRST and only then flag EOS —
                    // an EOS buffer MAY still carry the final sample's payload
                    // (the old if/else dropped it when both were set).
                    if (!isCsd && bufferInfo.size > 0) {
                        // v0.7.4: CODEC_CONFIG buffers are skipped — their data
                        // (SPS/PPS) is already conveyed via the output format
                        // above. Writing them as samples corrupted the old output.
                        val encBuffer = vEnc.getOutputBuffer(encOut)!!
                        encBuffer.position(bufferInfo.offset)
                        encBuffer.limit(bufferInfo.offset + bufferInfo.size)
                        val data = ByteArray(bufferInfo.size)
                        encBuffer.get(data)
                        writeOrBufferVideo(data, bufferInfo.presentationTimeUs, bufferInfo.flags)
                        videoFrameCount++
                    }
                    if (isEos) {
                        videoAllEncoded = true
                    }
                    vEnc.releaseOutputBuffer(encOut, false)
                }

                // ================= AUDIO PHASE =================
                if (audioDecoder != null && audioEncoder != null && audioExtractor != null) {
                    val aDec = audioDecoder!!
                    val aEnc = audioEncoder!!
                    val aExt = audioExtractor!!

                    if (!audioAllExtracted) {
                        val audioInputIndex = aDec.dequeueInputBuffer(TIMEOUT_US)
                        if (audioInputIndex >= 0) {
                            val audioInputBuffer = aDec.getInputBuffer(audioInputIndex)!!
                            val audioSampleSize = aExt.readSampleData(audioInputBuffer, 0)
                            if (audioSampleSize < 0) {
                                aDec.queueInputBuffer(audioInputIndex, 0, 0, 0,
                                    android.media.MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                                audioAllExtracted = true
                            } else {
                                val audioSampleTime = aExt.sampleTime
                                aDec.queueInputBuffer(audioInputIndex, 0, audioSampleSize, audioSampleTime, 0)
                                aExt.advance()
                            }
                        }
                    }

                    if (!audioAllDecoded) {
                        if (pendingAudioDecoded < 0) {
                            val audioOutputIndex = aDec.dequeueOutputBuffer(audioInfo, TIMEOUT_US)
                            if (audioOutputIndex >= 0) {
                                if (audioInfo.flags and android.media.MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) {
                                    audioAllDecoded = true
                                    aEnc.signalEndOfInputStream()
                                    aDec.releaseOutputBuffer(audioOutputIndex, false)
                                } else {
                                    pendingAudioDecoded = audioOutputIndex
                                }
                            }
                        }
                        if (pendingAudioDecoded >= 0) {
                            val encInputIdx = aEnc.dequeueInputBuffer(TIMEOUT_US)
                            if (encInputIdx >= 0) {
                                // Copy decoded audio buffer to encoder input
                                // (v0.7.4: pending-buffer pattern — never dropped).
                                val decodedAudioBuffer = aDec.getOutputBuffer(pendingAudioDecoded)
                                val encInputBuf = aEnc.getInputBuffer(encInputIdx)
                                if (decodedAudioBuffer != null && encInputBuf != null) {
                                    val chunkSize = minOf(audioInfo.size, encInputBuf.capacity())
                                    decodedAudioBuffer.position(audioInfo.offset)
                                    decodedAudioBuffer.limit(audioInfo.offset + chunkSize)
                                    encInputBuf.clear()
                                    encInputBuf.put(decodedAudioBuffer)
                                    aEnc.queueInputBuffer(encInputIdx, 0, chunkSize, audioInfo.presentationTimeUs, 0)
                                }
                                aDec.releaseOutputBuffer(pendingAudioDecoded, false)
                                pendingAudioDecoded = -1
                            }
                        }
                    }

                    // Drain audio encoder output (always — even after decoder EOS)
                    val audioEncOut = aEnc.dequeueOutputBuffer(audioEncInfo, TIMEOUT_US)
                    when (audioEncOut) {
                        android.media.MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                            if (muxerAudioTrack < 0) {
                                muxerAudioTrack = mux.addTrack(aEnc.outputFormat)
                                WepWawetLogger.proxy("$TAG: audio track added to muxer (with CSD)",
                                    "track_index" to muxerAudioTrack)
                            }
                            maybeStartMuxer()
                        }
                        android.media.MediaCodec.INFO_OUTPUT_BUFFERS_CHANGED -> {
                            // No-op on API 21+.
                        }
                    }
                    if (audioEncOut >= 0) {
                        val isEos = audioEncInfo.flags and android.media.MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0
                        val isCsd = audioEncInfo.flags and android.media.MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0
                        // v0.7.4: write-then-flag-EOS (see the video drain above).
                        if (!isCsd && audioEncInfo.size > 0) {
                            val audioEncBuf = aEnc.getOutputBuffer(audioEncOut)!!
                            audioEncBuf.position(audioEncInfo.offset)
                            audioEncBuf.limit(audioEncInfo.offset + audioEncInfo.size)
                            val data = ByteArray(audioEncInfo.size)
                            audioEncBuf.get(data)
                            writeOrBufferAudio(data, audioEncInfo.presentationTimeUs, audioEncInfo.flags)
                            audioFrameCount++
                        }
                        if (isEos) {
                            audioAllEncoded = true
                        }
                        aEnc.releaseOutputBuffer(audioEncOut, false)
                    }
                }
            }

            // Post-loop safety: if the audio codecs never produced a format
            // change (e.g. an empty audio track), start the muxer video-only
            // rather than losing the whole transcode.
            maybeStartMuxer()
            if (!muxerStarted && muxerVideoTrack >= 0) {
                mux.start()
                muxerStarted = true
                for (s in pendingVideoSamples) {
                    mux.writeSampleData(muxerVideoTrack,
                        java.nio.ByteBuffer.wrap(s.data), makeSampleInfo(s.data, s.ptsUs, s.flags))
                }
                pendingVideoSamples.clear()
                WepWawetLogger.proxyWarn("$TAG: muxer started video-only (audio format never arrived)")
            }

            if (videoFrameCount == 0 && pendingVideoSamples.isEmpty()) {
                WepWawetLogger.proxyError("$TAG: transcode produced ZERO video frames — failing",
                    "input" to inputFile.name)
                return false
            }

            mux.stop()
            muxerStarted = false
            success = true

            WepWawetLogger.proxy("$TAG: video transcoded",
                "video_frames" to videoFrameCount,
                "audio_frames" to audioFrameCount,
                "audio_transcoded" to (audioDecoder != null))

            WepWawetLogger.proxy("$TAG.transcodeToMp4H264 SUCCESS",
                "input" to inputFile.name,
                "input_size" to inputFile.length(),
                "output" to outputFile.name,
                "output_size" to outputFile.length(),
                "video_frames" to videoFrameCount,
                "audio_transcoded" to (audioDecoder != null))

            return true
        } catch (e: Exception) {
            WepWawetLogger.proxyError("$TAG: transcode failed",
                "exception" to e.javaClass.name,
                "message" to (e.message ?: ""),
                "input" to inputFile.name)
            return false
        } finally {
            // v0.7.4: release EVERYTHING on every exit path (was success-only).
            // stop() before release() where the codec was started; stop() on an
            // unstarted/errored codec throws, so it's wrapped separately.
            runCatching { videoDecoder?.stop() }
            runCatching { videoDecoder?.release() }
            runCatching { videoEncoder?.stop() }
            runCatching { videoEncoder?.release() }
            runCatching { audioDecoder?.stop() }
            runCatching { audioDecoder?.release() }
            runCatching { audioEncoder?.stop() }
            runCatching { audioEncoder?.release() }
            runCatching { audioExtractor?.release() }
            if (muxerStarted) {
                runCatching { muxer?.stop() }
            }
            runCatching { muxer?.release() }
            runCatching { extractor.release() }
            // Remove the partial output file on failure so a corrupt MP4 is
            // never shipped to the phone.
            if (!success) {
                runCatching { outputFile.delete() }
            }
        }
    }

    /**
     * Compute a reasonable H.264/H.265 bitrate for the given resolution.
     * Uses a simple heuristic: width * height * framerate * 0.1 bits/sec.
     *
     * v0.6.0 (parity §8-3): Added fps parameter (was hardcoded to 30).
     */
    private fun computeBitrate(width: Int, height: Int, fps: Int = 30): Int {
        // For 1080p (1920x1080) at 30fps: ~6.2 Mbps
        // For 720p (1280x720) at 30fps: ~2.8 Mbps
        // For 480p (854x480) at 30fps: ~1.2 Mbps
        val pixels = width.toLong() * height.toLong()
        return (pixels * fps * 0.1).toInt().coerceIn(500000, 20000000)
    }

    /**
     * v0.6.0 (parity §8-1): Pick the best video codec for the output MP4.
     *
     * Returns:
     *   - MediaFormat.MIMETYPE_VIDEO_HEVC if the device supports H.265 encoding
     *     (gives ~50% smaller files at equivalent quality — important for
     *     4K+ wallpapers that would otherwise be very large)
     *   - MediaFormat.MIMETYPE_VIDEO_AVC (H.264) as the default fallback
     *     (universally supported on Android 5.0+)
     *
     * Note: The phone's H.265 capability should ideally be queried from the
     * EncAuthRequest — but for now we just check the local device's encoding
     * capability (which is what we need — the phone decodes whatever we encode).
     *
     * Future enhancement: When the phone reports H.265 support in EncAuthRequest,
     * prefer H.265 unconditionally. Otherwise, stick with H.264 for max compat.
     */
    private fun pickVideoCodec(): String {
        // Try H.265 (HEVC) first — produces ~50% smaller files
        try {
            val codecList = android.media.MediaCodecList(android.media.MediaCodecList.REGULAR_CODECS)
            for (codecInfo in codecList.codecInfos) {
                if (codecInfo.isEncoder) {
                    for (type in codecInfo.supportedTypes) {
                        if (type.equals(android.media.MediaFormat.MIMETYPE_VIDEO_HEVC, ignoreCase = true)) {
                            WepWawetLogger.proxy("$TAG.pickVideoCodec: device supports H.265 encoding — using HEVC")
                            return android.media.MediaFormat.MIMETYPE_VIDEO_HEVC
                        }
                    }
                }
            }
        } catch (e: Exception) {
            WepWawetLogger.proxyWarn("$TAG.pickVideoCodec: H.265 capability check failed — falling back to H.264",
                "exception" to e.javaClass.name)
        }
        // Default: H.264 (universally supported on Android 5.0+)
        WepWawetLogger.proxy("$TAG.pickVideoCodec: falling back to H.264 (AVC) — device doesn't support H.265 encoding")
        return android.media.MediaFormat.MIMETYPE_VIDEO_AVC
    }

    /**
     * Copy YUV420 Image data from [src] to [dst], handling plane layout
     * differences (NV12 vs NV21 vs YV12 etc.).
     *
     * v0.5.32 (H6 fix): Previously the transcode loop used raw ByteBuffer
     * copies between decoder output and encoder input, which assumed the
     * two codecs used the same YUV420 layout. Per Android docs, the layout
     * is "device and codec specific" — feeding raw bytes produces
     * scrambled chroma planes (green tint, swapped U/V). The Image API
     * exposes the plane layout (rowStride / pixelStride) so we can copy
     * correctly regardless of source/dest layouts.
     *
     * Both [src] and [dst] must be 3-plane YUV420 Images (Y, U, V in that
     * order), which is what `MediaCodec.getOutputImage` / `getInputImage`
     * return for codecs configured with `COLOR_FormatYUV420Flexible`.
     */
    private fun copyYuv420Image(
        src: android.media.Image,
        dst: android.media.Image,
        width: Int,
        height: Int,
        // v0.7.24 (H-V7): the encoder input format's KEY_SLICE_HEIGHT (or -1
        // when absent) so zeroPlanePadding can zero the tail rows.
        encSliceHeight: Int = -1,
    ) {
        val srcPlanes = src.planes
        val dstPlanes = dst.planes
        // Y plane (full resolution)
        copyPlane(srcPlanes[0], dstPlanes[0], width, height)
        // U plane (quarter resolution for 4:2:0 chroma subsampling)
        copyPlane(srcPlanes[1], dstPlanes[1], width / 2, height / 2)
        // V plane (quarter resolution)
        copyPlane(srcPlanes[2], dstPlanes[2], width / 2, height / 2)
        // v0.7.4: zero the destination planes' stride padding. The transcode
        // loop passes the FULL input buffer size to queueInputBuffer (the size
        // the encoder expects for its own layout), and some encoders read the
        // padding rows — leaving them uninitialized meant the encoder could
        // sample garbage (theoretical chroma artifacts). Zeroing is cheap (the
        // padding is usually zero-width for aligned resolutions).
        // v0.7.24 (H-V7): chroma slice height is (sliceHeight+1)/2 for 4:2:0.
        val chromaSlice = if (encSliceHeight > 0) (encSliceHeight + 1) / 2 else -1
        zeroPlanePadding(dstPlanes[0], width, height, encSliceHeight)
        zeroPlanePadding(dstPlanes[1], (width + 1) / 2, (height + 1) / 2, chromaSlice)
        zeroPlanePadding(dstPlanes[2], (width + 1) / 2, (height + 1) / 2, chromaSlice)
    }

    /**
     * v0.7.4: zero the row-stride padding of one destination plane (the bytes
     * between each row's visible pixels and the next row's start, plus the
     * tail after the last row).
     */
    private fun zeroPlanePadding(
        plane: android.media.Image.Plane,
        width: Int, height: Int,
        // v0.7.24 (H-V7): Image.Plane has NO sliceHeight getter — it lives on
        // the encoder's input FORMAT (KEY_SLICE_HEIGHT), so the caller passes
        // it in. Default -1 = "unknown, skip the tail-zero pass" (previous
        // behavior).
        sliceHeight: Int = -1,
    ) {
        val rowStride = plane.rowStride
        val pixelStride = plane.pixelStride
        val buffer = plane.buffer
        val visibleBytes = (width - 1) * pixelStride + 1
        val capacity = buffer.capacity()
        if (rowStride > visibleBytes) {
            for (row in 0 until height) {
                var pos = row * rowStride + visibleBytes
                val rowEnd = (row + 1) * rowStride
                while (pos < rowEnd && pos < capacity) {
                    buffer.put(pos, 0)
                    pos++
                }
            }
        }
        // v0.7.24 (H-V7 BUG FIX): Qualcomm/MediaTek encoders commonly report
        // sliceHeight > height; the rows between `height` and `sliceHeight`
        // were NEVER zeroed, yet the transcode loop submits
        // queueInputBuffer(size = buffer.capacity()) — the encoder read
        // uninitialized native heap there → intermittent green/garbage band
        // at the bottom of the encoded frame. Zero every byte from the end
        // of the visible rows to the end of the plane's slice.
        // rowStride * sliceHeight is the plane's nominal extent (for
        // interleaved chroma, sliceHeight already covers the shared buffer).
        if (sliceHeight > height) {
            var pos = height * rowStride
            val end = minOf(sliceHeight * rowStride, capacity)
            while (pos < end) {
                buffer.put(pos, 0)
                pos++
            }
        }
    }

    /**
     * Copy one YUV plane from [src] to [dst]. Handles differences in
     * rowStride and pixelStride (e.g., NV12's interleaved U/V with
     * pixelStride=2 vs YV12's planar U with pixelStride=1).
     *
     * Two paths:
     *  - Fast path: identical layouts → bulk ByteBuffer copy (capped to
     *    destination capacity to prevent overflow on padding mismatches).
     *  - Slow path: copy pixel by pixel → robust to any layout difference.
     */
    private fun copyPlane(
        src: android.media.Image.Plane,
        dst: android.media.Image.Plane,
        width: Int,
        height: Int,
    ) {
        val srcBuf = src.buffer
        val dstBuf = dst.buffer
        val srcRowStride = src.rowStride
        val dstRowStride = dst.rowStride
        val srcPixelStride = src.pixelStride
        val dstPixelStride = dst.pixelStride

        // Fast path: identical layouts → bulk copy.
        if (srcRowStride == dstRowStride && srcPixelStride == dstPixelStride) {
            srcBuf.position(0)
            val srcSlice = srcBuf.slice()
            srcSlice.limit(minOf(srcSlice.remaining(), dstBuf.capacity()))
            dstBuf.clear()
            dstBuf.put(srcSlice)
            return
        }

        // Slow path: copy pixel by pixel to handle layout differences.
        // Reads src at (rowIdx * srcRowStride + colIdx * srcPixelStride),
        // writes dst at (rowIdx * dstRowStride + colIdx * dstPixelStride).
        // NOTE: uses absolute get/put so we don't have to fiddle with
        // position/limit between reads and writes.
        for (rowIdx in 0 until height) {
            val srcRowStart = rowIdx * srcRowStride
            val dstRowStart = rowIdx * dstRowStride
            for (colIdx in 0 until width) {
                val srcPos = srcRowStart + colIdx * srcPixelStride
                val dstPos = dstRowStart + colIdx * dstPixelStride
                if (srcPos < srcBuf.capacity() && dstPos < dstBuf.capacity()) {
                    dstBuf.put(dstPos, srcBuf.get(srcPos))
                }
            }
        }
    }
}
