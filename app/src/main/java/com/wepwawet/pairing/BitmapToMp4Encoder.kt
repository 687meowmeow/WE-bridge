package com.wepwawet.pairing

import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaCodecList
import android.media.MediaFormat
import android.media.MediaMuxer
import com.wepwawet.log.WepWawetLogger
import java.io.File
import java.nio.ByteBuffer

/**
 * v0.7.4 (parity G2): Shared RGB-frames → MP4 (H.264) encoder core.
 *
 * Both new exotic-container converters — [GifToMp4Transcoder] (animated GIF,
 * decoded frame-by-frame via android.graphics.Movie) and [AviToMp4Transcoder]
 * (MJPG / raw-DIB AVI streams) — produce raw ARGB frames with per-frame
 * presentation timestamps. This class turns that frame stream into an
 * MP4/H.264 file exactly like the WE PC pipeline does with its
 * `ffmpeg_gif` / `ffmpeg_avi_*` fallback converters (FUN_1400e21d0 in
 * wallpaperui.exe 2.8.0.42): the phone's VideoDrawable only ever receives
 * `mp4_h264` content.
 *
 * Implementation notes:
 *  - Uses MediaCodec in BUFFER mode with [MediaCodec.getInputImage] (API 21+)
 *    to feed YUV planes directly. getInputImage transparently handles the
 *    encoder's actual YUV layout (NV12, I420 or vendor-tiled) because the
 *    Image exposes real row/pixel strides — this avoids the classic
 *    per-vendor color-format guessing game.
 *  - RGB→YUV uses the BT.601 limited-range integer approximation
 *    (the same matrix the platform's software AVC encoder uses).
 *  - B-frames are disabled and one keyframe per second is emitted so the
 *    resulting wallpaper seeks well (matches VideoTranscoder's §8-5 policy).
 *  - Width/height are forced to even values (H.264 4:2:0 chroma requires it).
 */
class BitmapToMp4Encoder(
    width: Int,
    height: Int,
    private val outputFile: File,
    targetBitrate: Int,
    private val frameRate: Int,
) {
    companion object {
        private const val TAG = "BitmapToMp4Encoder"
        private const val TIMEOUT_US = 10_000_000L  // 10 s — generous for slow encoders
        private const val IFRAME_INTERVAL_S = 1

        /**
         * Same bitrate heuristic as VideoTranscoder.computeBitrate — used by
         * callers that only know the frame dimensions.
         */
        fun suggestBitrate(w: Int, h: Int): Int {
            val bpp = when {
                w * h >= 3_686_400 -> 0.09   // 4K-class
                w * h >= 1_000_000 -> 0.12   // 1080p-class
                else -> 0.18                // small frames need proportionally more
            }
            return (w * h * bpp).toInt().coerceIn(1_000_000, 40_000_000)
        }
    }

    private val outW = (width.coerceAtLeast(2)) / 2 * 2
    private val outH = (height.coerceAtLeast(2)) / 2 * 2
    private val bitrate = targetBitrate.coerceIn(500_000, 60_000_000)

    private var codec: MediaCodec? = null
    private var muxer: MediaMuxer? = null
    private var muxerTrack = -1
    private var muxerStarted = false
    private var finished = false
    private var lastPtsUs = -1L
    private var framesWritten = 0

    /** Open the encoder + muxer. Returns false (and cleans up) on failure. */
    fun open(): Boolean {
        return try {
            val encoderName = pickEncoderName()
            val c = if (encoderName != null) {
                MediaCodec.createByCodecName(encoderName)
            } else {
                MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_VIDEO_AVC)
            }
            val format = MediaFormat.createVideoFormat(
                MediaFormat.MIMETYPE_VIDEO_AVC, outW, outH).apply {
                setInteger(MediaFormat.KEY_COLOR_FORMAT,
                    MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Flexible)
                setInteger(MediaFormat.KEY_BIT_RATE, bitrate)
                setInteger(MediaFormat.KEY_FRAME_RATE, frameRate)
                setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, IFRAME_INTERVAL_S)
                // No B-frames on this path — seek-friendly wallpaper playback.
                // v0.7.24 (H-V4): guarded — encoders that reject the
                // non-standard "max-bframes" key threw at configure() and
                // killed the whole GIF/AVI encode path. Retry without it.
                try {
                    setInteger("max-bframes", 0)
                } catch (_: Exception) {
                }
            }
            c.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            c.start()
            codec = c

            muxer = MediaMuxer(
                outputFile.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
            WepWawetLogger.proxy("$TAG.open",
                "codec" to (encoderName ?: "default(avc)"),
                "width" to outW, "height" to outH,
                "bitrate" to bitrate, "fps" to frameRate,
                "output" to outputFile.name)
            true
        } catch (e: Exception) {
            WepWawetLogger.proxyError("$TAG.open FAILED",
                "exception" to e.javaClass.name,
                "message" to (e.message ?: ""))
            release()
            false
        }
    }

    /**
     * Encode one ARGB_8888 frame.
     *
     * @param argb frame pixels packed as 0xAARRGGBB (Bitmap.getPixels layout),
     *             exactly `srcW`×`srcH` entries, top-down row order.
     * @param srcW source width (may differ from the encoder canvas by the
     *            even-rounding; the frame is point-sampled onto it)
     * @param srcH source height
     * @param ptsUs presentation timestamp in microseconds; MUST be strictly
     *            increasing across calls (clamped defensively here)
     */
    fun writeFrame(argb: IntArray, srcW: Int, srcH: Int, ptsUs: Long): Boolean {
        val c = codec ?: return false
        if (finished) return false
        val safePts = if (ptsUs <= lastPtsUs) lastPtsUs + 1 else ptsUs

        try {
            val index = c.dequeueInputBuffer(TIMEOUT_US)
            if (index < 0) {
                WepWawetLogger.proxyError("$TAG.writeFrame: no input buffer (timeout)")
                return false
            }

            val image = c.getInputImage(index)
            if (image != null) {
                fillImagePlanes(image, argb, srcW, srcH)
                val size = image.width * image.height * 3 / 2
                c.queueInputBuffer(index, 0, size, safePts, 0)
            } else {
                // Fallback: NV12 byte layout into the raw ByteBuffer.
                val nv12 = ByteArray(outW * outH * 3 / 2)
                argbToNv12(argb, srcW, srcH, nv12, outW, outH)
                val buf: ByteBuffer = c.getInputBuffer(index) ?: return false
                buf.clear()
                buf.put(nv12)
                c.queueInputBuffer(index, 0, nv12.size, safePts, 0)
            }

            lastPtsUs = safePts
            framesWritten++
            if (framesWritten % 50 == 0) {
                WepWawetLogger.proxy("$TAG: frames encoded",
                    "frames" to framesWritten, "last_pts_us" to safePts)
            }

            drainOutput(endOfStream = false)
            return true
        } catch (e: Exception) {
            WepWawetLogger.proxyError("$TAG.writeFrame FAILED",
                "exception" to e.javaClass.name,
                "message" to (e.message ?: ""),
                "pts_us" to safePts)
            return false
        }
    }

    /** Signal end-of-stream, drain the encoder, finalize the MP4. */
    fun finish(): Boolean {
        if (finished) return muxerStarted
        finished = true
        val c = codec ?: return false
        try {
            // v0.7.24 (C-V3 BUG FIX): the EOS flag was queued only when an
            // input buffer was available on the FIRST try. When the encoder's
            // input queue was momentarily full (index < 0), the EOS was NEVER
            // queued — and drainOutput(endOfStream = true) then polled
            // forever (the encoder never emits its EOS buffer without
            // receiving EOS input) → ANR. Bounded retry loop instead.
            var eosQueued = false
            var eosAttempts = 0
            while (!eosQueued && eosAttempts < 12) { // 12 x 10s = 2 min cap
                val index = c.dequeueInputBuffer(TIMEOUT_US)
                if (index >= 0) {
                    c.queueInputBuffer(index, 0, 0, lastPtsUs + 1,
                        MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                    eosQueued = true
                } else {
                    eosAttempts++
                    // Input queue busy — drain some output to free buffers.
                    drainOutput(endOfStream = false)
                }
            }
            if (!eosQueued) {
                WepWawetLogger.proxyError("$TAG.finish FAILED — could not queue EOS after $eosAttempts attempts",
                    "frames_written" to framesWritten)
                return false
            }
            drainOutput(endOfStream = true)
            val ok = muxerStarted
            WepWawetLogger.proxy("$TAG.finish",
                "ok" to ok, "frames_written" to framesWritten,
                "output_size" to outputFile.length())
            return ok
        } catch (e: Exception) {
            WepWawetLogger.proxyError("$TAG.finish FAILED",
                "exception" to e.javaClass.name,
                "message" to (e.message ?: ""))
            return false
        } finally {
            release()
        }
    }

    /** Release encoder + muxer resources. Safe to call multiple times. */
    fun release() {
        try { codec?.stop() } catch (_: Exception) {}
        try { codec?.release() } catch (_: Exception) {}
        codec = null
        try { if (muxerStarted) muxer?.stop() } catch (_: Exception) {}
        try { muxer?.release() } catch (_: Exception) {}
        muxer = null
        muxerStarted = false
    }

    // ───────────────────────── internals ─────────────────────────

    /**
     * Copy an ARGB frame onto the encoder's input Image planes, honoring
     * each plane's row/pixel stride and subsampling. Handles NV12, I420 and
     * exotic tiled layouts uniformly.
     */
    private fun fillImagePlanes(
        image: android.media.Image,
        argb: IntArray, srcW: Int, srcH: Int,
    ) {
        val yPlane = image.planes[0]
        val uPlane = image.planes[1]
        val vPlane = image.planes[2]

        val yBuf = yPlane.buffer
        val uBuf = uPlane.buffer
        val vBuf = vPlane.buffer
        val yRow = yPlane.rowStride
        val cRow = uPlane.rowStride
        val cPix = uPlane.pixelStride  // 1 = planar I420, 2 = semi-planar NV12

        val w = image.width
        val h = image.height

        // Pre-convert the source frame to full-resolution luma and half-res
        // interleaved chroma. Point-sample when the source dimensions differ
        // from the encoder canvas (they differ at most by even-rounding).
        val yArr = ByteArray(w * h)
        val cArr = ByteArray(w * h / 2)  // interleaved (Cb,Cr) pairs
        var ci = 0
        for (dy in 0 until h) {
            val sy = if (srcH == h) dy else dy * srcH / h
            for (dx in 0 until w) {
                val sx = if (srcW == w) dx else dx * srcW / w
                val p = argb[sy * srcW + sx]
                val r = (p shr 16) and 0xFF
                val g = (p shr 8) and 0xFF
                val b = p and 0xFF
                // BT.601 limited range, integer approximation:
                yArr[dy * w + dx] =
                    (((66 * r + 129 * g + 25 * b + 128) shr 8) + 16).toByte()
                if ((dy and 1) == 0 && (dx and 1) == 0) {
                    val cb = ((-38 * r - 74 * g + 112 * b + 128) shr 8) + 128
                    val cr = ((112 * r - 94 * g - 18 * b + 128) shr 8) + 128
                    cArr[ci++] = cb.toByte()
                    cArr[ci++] = cr.toByte()
                }
            }
        }

        // Luma plane
        for (row in 0 until h) {
            yBuf.position(row * yRow)
            yBuf.put(yArr, row * w, w)
        }

        // Chroma — the two planes are either separate (I420) or two views
        // into one semi-planar buffer (NV12); honoring each plane's own
        // buffer + rowStride + pixelStride writes correctly for both.
        val cw = w / 2
        val ch = h / 2
        for (row in 0 until ch) {
            for (col in 0 until cw) {
                val srcIdx = (row * cw + col) * 2
                uBuf.position(row * cRow + col * cPix)
                uBuf.put(cArr[srcIdx])
                vBuf.position(row * cRow + col * cPix)
                vBuf.put(cArr[srcIdx + 1])
            }
        }
    }

    /**
     * Fallback NV12 writer for encoders without getInputImage support.
     * Layout: Y plane (w*h), then interleaved UV plane (w*h/2 total).
     */
    private fun argbToNv12(
        argb: IntArray, srcW: Int, srcH: Int,
        out: ByteArray, w: Int, h: Int,
    ) {
        var uvBase = w * h
        for (dy in 0 until h) {
            val sy = if (srcH == h) dy else dy * srcH / h
            for (dx in 0 until w) {
                val sx = if (srcW == w) dx else dx * srcW / w
                val p = argb[sy * srcW + sx]
                val r = (p shr 16) and 0xFF
                val g = (p shr 8) and 0xFF
                val b = p and 0xFF
                out[dy * w + dx] =
                    (((66 * r + 129 * g + 25 * b + 128) shr 8) + 16).toByte()
                if ((dy and 1) == 0 && (dx and 1) == 0) {
                    val cb = ((-38 * r - 74 * g + 112 * b + 128) shr 8) + 128
                    val cr = ((112 * r - 94 * g - 18 * b + 128) shr 8) + 128
                    out[uvBase++] = cb.toByte()
                    out[uvBase++] = cr.toByte()
                }
            }
        }
    }

    /**
     * Drain encoder output. In non-EOS mode, returns as soon as the encoder
     * has nothing ready. In EOS mode, loops until the end-of-stream flag
     * arrives on an output buffer.
     */
    private fun drainOutput(endOfStream: Boolean) {
        val c = codec ?: return
        val info = MediaCodec.BufferInfo()
        // v0.7.24 (H-V5): consecutive empty-poll counter for the EOS drain.
        var eosEmptyPolls = 0
        while (true) {
            val index = c.dequeueOutputBuffer(info, if (endOfStream) TIMEOUT_US else 0L)
            when {
                index == MediaCodec.INFO_TRY_AGAIN_LATER -> {
                    if (!endOfStream) return
                    // EOS drain: keep polling until the EOS buffer arrives.
                    // v0.7.24 (H-V5): hard cap — a wedged encoder must not
                    // spin the thread forever (10s timeout x 30 tries = 5 min
                    // worst case, then give up with whatever was written).
                    eosEmptyPolls++
                    if (eosEmptyPolls > 30) {
                        WepWawetLogger.proxyError("$TAG: EOS drain gave up after $eosEmptyPolls empty polls",
                            "frames_written" to framesWritten)
                        return
                    }
                    continue
                }
                index == MediaCodec.INFO_OUTPUT_BUFFERS_CHANGED -> {
                    // Deprecated event — safe to ignore on API 21+.
                }
                index == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                    val fmt = c.outputFormat
                    val m = muxer
                    if (m != null && !muxerStarted) {
                        muxerTrack = m.addTrack(fmt)
                        m.start()
                        muxerStarted = true
                        WepWawetLogger.proxy("$TAG: muxer started",
                            "track_format" to fmt.toString())
                    }
                }
                index >= 0 -> {
                    val encoded: ByteBuffer? = c.getOutputBuffer(index)
                    if (encoded != null && info.size > 0 && muxerStarted) {
                        encoded.position(info.offset)
                        encoded.limit(info.offset + info.size)
                        muxer!!.writeSampleData(muxerTrack, encoded, info)
                    }
                    c.releaseOutputBuffer(index, false)
                    if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) {
                        return
                    }
                }
            }
        }
    }

    /**
     * Pick an AVC encoder, preferring software (c2.android / OMX.google):
     * software encoders accept COLOR_FormatYUV420Flexible + getInputImage
     * reliably across devices, which matters more than hardware speed for
     * one-shot GIF/AVI converts. Falls back to any AVC encoder, then to
     * createEncoderByType when the list is unavailable.
     */
    private fun pickEncoderName(): String? {
        return try {
            val list = MediaCodecList(MediaCodecList.REGULAR_CODECS)
            val avcEncoders = list.codecInfos
                .filter { it.isEncoder }
                .filter { info ->
                    info.supportedTypes.any {
                        it.equals(MediaFormat.MIMETYPE_VIDEO_AVC, ignoreCase = true)
                    }
                }
                .map { it.name }
            avcEncoders.firstOrNull {
                it.contains("c2.android", ignoreCase = true) ||
                it.contains("OMX.google", ignoreCase = true)
            } ?: avcEncoders.firstOrNull()
        } catch (_: Exception) {
            null
        }
    }
}
