package com.wepwawet.pairing

import android.media.MediaExtractor
import android.media.MediaMuxer
import com.wepwawet.log.WepWawetLogger
import java.io.File
import java.nio.ByteBuffer

/**
 * VideoTrimmer — trims an MP4 video to a specified start/end time range
 * WITHOUT re-encoding (stream copy, lossless, fast).
 *
 * Uses MediaExtractor to read the input video's tracks and MediaMuxer to
 * write a new MP4 containing only the frames within the [startMs, endMs]
 * range. This is the Android equivalent of ffmpeg's `-ss <start> -to <end>
 * -c copy` — it copies compressed data without decoding/re-encoding, so
 * it's fast (typically <1 second for a 50MB video) and lossless.
 *
 * The trim is keyframe-accurate (not frame-accurate): the start point
 * snaps to the nearest keyframe (sync sample) at or before [startMs]. For
 * most video wallpapers this is fine — the keyframe interval is typically
 * 2-5 seconds, so the actual start time is within 2-5s of the requested time.
 *
 * v0.7.4 fixes applied to the copy loop:
 *   1. KEYFRAME SNAP actually happens now. The old loop started writing at
 *      the FIRST sample with PTS >= startMs regardless of whether it was a
 *      sync sample — when the trim point fell between keyframes the output
 *      began with undecodable P/B frames (2-5s of black/garbage). We now
 *      locate the last sync sample before startMs and start copying there.
 *   2. PTS re-basing: output timestamps now start at ~0 (old code kept the
 *      original timestamps, so trimming a 60s video from 30s produced an
 *      MP4 whose timeline began at 30s — many players showed 30s of black
 *      at the start).
 *   3. MediaMuxer is released in a finally block (the old catch path only
 *      released the extractor — a failed muxer leaked its file handle and
 *      native resources) and a failed/partial output file is deleted.
 *   4. Zero-sample outputs (e.g. trim range before the first sample's PTS)
 *      now fail cleanly instead of leaving a structurally-invalid MP4.
 *
 * If frame-accurate trimming is needed in the future, we'd need to
 * re-encode (decode from the nearest keyframe to [startMs], then encode
 * from [startMs] to [endMs]). That's slower and introduces quality loss.
 * For now, keyframe-accurate is the right tradeoff.
 */
object VideoTrimmer {
    private const val TAG = "VideoTrimmer"

    /**
     * Trim a video file to the specified time range.
     *
     * @param inputFile Source video file (MP4, WebM, etc.)
     * @param outputFile Output MP4 file (will be overwritten if exists)
     * @param startMs Start time in milliseconds
     * @param endMs End time in milliseconds
     * @return true on success, false on failure (output file removed)
     */
    fun trim(inputFile: File, outputFile: File, startMs: Long, endMs: Long): Boolean {
        if (!inputFile.exists()) {
            WepWawetLogger.proxyError("$TAG: input file does not exist",
                "input" to inputFile.absolutePath)
            return false
        }
        if (startMs < 0 || endMs <= startMs) {
            WepWawetLogger.proxyError("$TAG: invalid trim range",
                "start_ms" to startMs, "end_ms" to endMs)
            return false
        }
        // Cap endMs to video duration
        val duration = getDurationMs(inputFile)
        val cappedEnd = if (endMs > duration) duration else endMs
        if (cappedEnd <= startMs) {
            WepWawetLogger.proxyError("$TAG: trim range is zero or negative after capping",
                "start_ms" to startMs, "capped_end_ms" to cappedEnd)
            return false
        }

        WepWawetLogger.proxy("$TAG: starting trim",
            "input" to inputFile.name,
            "input_size" to inputFile.length(),
            "output" to outputFile.name,
            "start_ms" to startMs,
            "end_ms" to cappedEnd,
            "duration_ms" to duration)

        val extractor = MediaExtractor()
        // v0.7.4: muxer reference hoisted so the finally block can release it
        // on ANY exit path (the old catch path only released the extractor —
        // a writeSampleData failure leaked the muxer's FD + native resources).
        var muxer: MediaMuxer? = null
        var success = false
        return try {
            extractor.setDataSource(inputFile.absolutePath)

            // Find all tracks (video + audio)
            val trackInfos = mutableListOf<Pair<Int, android.media.MediaFormat>>()
            for (i in 0 until extractor.trackCount) {
                val format = extractor.getTrackFormat(i)
                val mime = format.getString(android.media.MediaFormat.KEY_MIME) ?: ""
                if (mime.startsWith("video/") || mime.startsWith("audio/")) {
                    trackInfos.add(i to format)
                }
            }
            if (trackInfos.isEmpty()) {
                WepWawetLogger.proxyError("$TAG: no video/audio tracks found",
                    "track_count" to extractor.trackCount)
                return false
            }

            // Create the MediaMuxer
            muxer = MediaMuxer(
                outputFile.absolutePath,
                MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4
            )

            // Add tracks to muxer and select them in the extractor
            val muxerTracks = mutableMapOf<Int, Int>() // extractorTrackIndex → muxerTrackIndex
            for ((extIdx, format) in trackInfos) {
                val muxIdx = muxer.addTrack(format)
                muxerTracks[extIdx] = muxIdx
                extractor.selectTrack(extIdx)
            }
            muxer.start()

            // === v0.7.4 fix 1: find the KEYFRAME SNAP POINT ===
            // Walk forward until the first sample at/after startMs, remembering
            // the last SYNC sample seen before it — that's the keyframe at (or
            // just before) the requested start. Copying must begin there so the
            // decoder has a reference frame.
            var snapUs = -1L
            while (true) {
                val t = extractor.sampleTime
                if (t < 0 || t >= startMs * 1000) break
                if (extractor.sampleFlags and MediaExtractor.SAMPLE_FLAG_SYNC != 0) {
                    snapUs = t
                }
                extractor.advance()
            }
            if (snapUs < 0) {
                // No sync sample before startMs (startMs is near 0, or the file
                // has no earlier keyframe) — snap to the previous sync sample
                // relative to startMs, or to the very first sample.
                extractor.seekTo(startMs * 1000, MediaExtractor.SEEK_TO_PREVIOUS_SYNC)
                val t = extractor.sampleTime
                snapUs = if (t >= 0) t else 0L
            } else {
                // Rewind to the snap keyframe (SEEK_TO_PREVIOUS_SYNC from a
                // sync sample lands on that same sample).
                extractor.seekTo(snapUs, MediaExtractor.SEEK_TO_PREVIOUS_SYNC)
            }
            WepWawetLogger.proxy("$TAG: keyframe snap point selected",
                "requested_start_ms" to startMs,
                "snap_us" to snapUs,
                "snap_ms" to (snapUs / 1000))

            // Copy samples within [snapUs, cappedEnd]. PTS is re-based by snapUs
            // so the output timeline starts at ~0 (v0.7.4 fix 2).
            val buffer = ByteBuffer.allocate(1024 * 1024) // 1MB buffer
            // v0.7.24 (H-V8): lazily grown for samples larger than 1MB.
            var bigBuffer: ByteBuffer? = null
            val bufferInfo = android.media.MediaCodec.BufferInfo()
            var samplesCopied = 0
            var bytesCopied = 0L
            var firstSampleUs = -1L

            while (true) {
                val sampleTime = extractor.sampleTime
                if (sampleTime < 0) break // end of stream

                // Skip anything before the snap point (e.g. audio samples that
                // the multi-track seek landed before the video keyframe).
                if (sampleTime < snapUs) {
                    extractor.advance()
                    continue
                }
                if (sampleTime / 1000 > cappedEnd) break // after end — done

                val trackIndex = extractor.sampleTrackIndex
                val muxerTrackIndex = muxerTracks[trackIndex]
                if (muxerTrackIndex == null) {
                    // This track wasn't added (shouldn't happen)
                    extractor.advance()
                    continue
                }

                // v0.7.24 (H-V8 BUG FIX): readSampleData returns
                // min(sampleSize, buffer.capacity()) — it does NOT error on
                // truncation. The fixed 1MB buffer silently clipped any larger
                // sample (4K H.264 keyframes are routinely 10-20MB), shipping
                // a truncated IDR → decode errors and seek crashes. Grow the
                // buffer when the real sample is bigger (capped at 64MB as a
                // sanity ceiling).
                val needed = extractor.sampleSize
                if (needed > buffer.capacity() && needed <= 64 * 1024 * 1024) {
                    WepWawetLogger.proxyWarn("$TAG: growing sample buffer for large sample",
                        "needed" to needed,
                        "current" to buffer.capacity())
                    bigBuffer = ByteBuffer.allocate(needed.toInt())
                }
                val activeBuffer = if (needed > buffer.capacity()) bigBuffer ?: buffer else buffer
                val sampleSize = extractor.readSampleData(activeBuffer, 0)
                if (sampleSize < 0) break

                if (firstSampleUs < 0) firstSampleUs = sampleTime
                bufferInfo.offset = 0
                bufferInfo.size = sampleSize
                // v0.7.4 fix 2: re-base PTS onto a ~0-based timeline.
                bufferInfo.presentationTimeUs = (sampleTime - snapUs).coerceAtLeast(0L)
                bufferInfo.flags = when {
                    extractor.sampleFlags and MediaExtractor.SAMPLE_FLAG_SYNC != 0 ->
                        android.media.MediaCodec.BUFFER_FLAG_KEY_FRAME
                    else -> 0
                }

                muxer.writeSampleData(muxerTrackIndex, activeBuffer, bufferInfo)
                samplesCopied++
                bytesCopied += sampleSize.toLong()
                extractor.advance()
            }

            // v0.7.4 fix 4: a zero-sample output is a structurally invalid MP4
            // (muxer.stop() would throw anyway) — fail cleanly instead.
            if (samplesCopied == 0) {
                WepWawetLogger.proxyError("$TAG: no samples in trim range — failing",
                    "start_ms" to startMs,
                    "snap_ms" to (snapUs / 1000),
                    "capped_end_ms" to cappedEnd,
                    "first_sample_us" to firstSampleUs)
                return false
            }

            muxer.stop()
            success = true
            WepWawetLogger.proxy("$TAG: trim complete",
                "output" to outputFile.name,
                "output_size" to outputFile.length(),
                "samples_copied" to samplesCopied,
                "bytes_copied" to bytesCopied,
                "snap_ms" to (snapUs / 1000),
                "trim_duration_ms" to (cappedEnd - snapUs / 1000))
            true
        } catch (e: Exception) {
            WepWawetLogger.proxyError("$TAG: trim failed",
                "exception" to e.javaClass.name,
                "message" to (e.message ?: ""))
            false
        } finally {
            // v0.7.4 fix 3: release BOTH the extractor and the muxer on every
            // exit path, and delete the partial output file on failure.
            runCatching { extractor.release() }
            runCatching { muxer?.release() }
            if (!success) {
                runCatching { outputFile.delete() }
            }
        }
    }

    /**
     * Get the duration of a video file in milliseconds.
     */
    fun getDurationMs(file: File): Long {
        val extractor = MediaExtractor()
        return try {
            extractor.setDataSource(file.absolutePath)
            var maxDuration = 0L
            for (i in 0 until extractor.trackCount) {
                val format = extractor.getTrackFormat(i)
                if (format.containsKey(android.media.MediaFormat.KEY_DURATION)) {
                    val dur = format.getLong(android.media.MediaFormat.KEY_DURATION)
                    if (dur > maxDuration) maxDuration = dur
                }
            }
            extractor.release()
            maxDuration / 1000 // μs → ms
        } catch (e: Exception) {
            runCatching { extractor.release() }
            0L
        }
    }
}
