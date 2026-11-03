package com.wepwawet.pairing

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.Image
import android.media.MediaCodec
import android.media.MediaFormat
import com.wepwawet.log.WepWawetLogger
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer

/**
 * v0.7.4 (parity G2): AVI (RIFF) → MP4 (H.264) transcoder.
 *
 * The WE PC pipeline converts AVI wallpapers with its bundled ffmpeg using
 * the `ffmpeg_avi_raw` / `ffmpeg_avi_yuv420` / `ffmpeg_avi_yuv422` codec
 * identifiers (FUN_1400e21d0, strings verified in wallpaperui.exe 2.8.0.42)
 * before packing them into the .mpkg. WepWawet previously shipped AVI
 * entries through untouched → guaranteed-black wallpaper on the phone.
 *
 * Android's MediaExtractor cannot demux RIFF/AVI, so this class implements
 * a minimal AVI 1.0 / OpenDML reader covering the codecs the Android
 * framework can actually decode, plus a decode→re-encode stage for
 * compressed streams:
 *
 *   - `MJPG` / `JPEG` / `jpg `  → per-frame JPEGs via BitmapFactory. AVI MJPG
 *     famously omits the Huffman (DHT) tables — they are injected from the
 *     JPEG Annex K baseline tables when missing (see [ensureJpegHuffmanTables]).
 *   - `BI_RGB` (compression 0), 24/32 bpp → raw DIB rows (bottom-up BGR).
 *   - `H264` / `X264` / `AVC1` / `h264` → Annex-B samples fed to a
 *     MediaCodec `video/avc` decoder.
 *   - `XVID` / `DX50` / `DIVX` / `DIV3` / `FMP4` / `MP4V` → MPEG-4 Part 2
 *     samples fed to a MediaCodec `video/mp4v-es` decoder (the platform's
 *     mandatory software decoder covers simple/ASP profiles).
 *   - Anything else → precise failure log (the wallpaper is left unpacked
 *     with an actionable message rather than becoming a black wallpaper).
 *
 * Decoded YUV frames are converted to ARGB and re-encoded through the
 * shared [BitmapToMp4Encoder], so the phone only ever receives `mp4_h264`
 * content — identical to the PC pipeline's output for AVI sources.
 */
object AviToMp4Transcoder {

    private const val TAG = "AviToMp4Transcoder"

    // ── parsed AVI structure ─────────────────────────────────────────────

    private class VideoStreamInfo {
        var streamNumber = -1        // '00'..'99' chunk prefix
        var handlerFourcc = ""       // strh fccHandler
        var compressionFourcc = ""   // strf biCompression
        var width = 0
        var height = 0               // positive = bottom-up DIB
        var bitCount = 24
        var rate = 30                // dwRate / dwScale → frames per second
        var scale = 1
        var frameCount = 0           // dwLength
    }

    private class AviFrame(val offset: Long, val size: Int, val keyFrame: Boolean)

    // ── public API ───────────────────────────────────────────────────────

    /**
     * Transcode an AVI file to MP4/H.264.
     *
     * @return true on success ([outputFile] holds a playable MP4); false on
     *         any failure (the caller keeps the original and logs).
     */
    fun transcode(aviFile: File, outputFile: File): Boolean {
        WepWawetLogger.proxy("$TAG: starting AVI → MP4 transcode",
            "input" to aviFile.name,
            "input_size" to aviFile.length(),
            "output" to outputFile.name)
        if (!aviFile.exists()) {
            WepWawetLogger.proxyError("$TAG: input does not exist",
                "input" to aviFile.absolutePath)
            return false
        }

        val video = VideoStreamInfo()
        val frames = mutableListOf<AviFrame>()
        try {
            RandomAccessFile(aviFile, "r").use { raf ->
                if (!parseAvi(raf, video, frames)) {
                    WepWawetLogger.proxyError("$TAG: AVI parse failed — not a readable AVI",
                        "handler" to video.handlerFourcc,
                        "compression" to video.compressionFourcc)
                    return false
                }
            }
        } catch (e: Exception) {
            WepWawetLogger.proxyError("$TAG: AVI read exception",
                "exception" to e.javaClass.name,
                "message" to (e.message ?: ""))
            return false
        }

        if (frames.isEmpty() || video.width <= 0 || video.height == 0) {
            WepWawetLogger.proxyError("$TAG: no decodable video frames in AVI",
                "frames" to frames.size,
                "width" to video.width,
                "height" to video.height)
            return false
        }

        WepWawetLogger.proxy("$TAG: AVI parsed",
            "handler" to video.handlerFourcc,
            "compression" to video.compressionFourcc,
            "size" to "${video.width}x${Math.abs(video.height)}",
            "bit_count" to video.bitCount,
            "fps" to "%.3f".format(video.rate.toDouble() / video.scale.toDouble()),
            "frames" to frames.size)

        return when {
            video.compressionFourcc == "MJPG" ||
                video.compressionFourcc == "JPEG" ||
                video.compressionFourcc == "jpg " -> transcodeMjpg(aviFile, video, frames, outputFile)
            video.compressionFourcc == "" || video.compressionFourcc == "\u0000\u0000\u0000\u0000" ||
                video.compressionFourcc == "BI_RGB"->
                transcodeRawDib(aviFile, video, frames, outputFile)
            else -> transcodeCompressed(aviFile, video, frames, outputFile)
        }
    }

    // ── MJPG ─────────────────────────────────────────────────────────────

    private fun transcodeMjpg(
        aviFile: File, video: VideoStreamInfo,
        frames: List<AviFrame>, outputFile: File,
    ): Boolean {
        val encoder = BitmapToMp4Encoder(
            width = video.width,
            height = Math.abs(video.height),
            outputFile = outputFile,
            targetBitrate = BitmapToMp4Encoder.suggestBitrate(video.width, Math.abs(video.height)),
            frameRate = (video.rate / video.scale).coerceIn(5, 60),
        )
        if (!encoder.open()) return false

        val frameDurUs = 1_000_000L * video.scale / video.rate
        var decoded = 0
        RandomAccessFile(aviFile, "r").use { raf ->
            for ((i, frame) in frames.withIndex()) {
                try {
                    raf.seek(frame.offset)
                    val jpeg = ByteArray(frame.size)
                    raf.readFully(jpeg)

                    // AVI MJPG frames often omit the DHT tables — inject the
                    // standard baseline tables when missing so BitmapFactory
                    // can decode them.
                    val patched = ensureJpegHuffmanTables(jpeg)
                    val bitmap = BitmapFactory.decodeByteArray(patched, 0, patched.size)
                    if (bitmap == null) {
                        WepWawetLogger.proxyWarn("$TAG: MJPG frame failed to decode — skipping",
                            "frame" to i, "size" to frame.size)
                        continue
                    }
                    val w = bitmap.width
                    val h = bitmap.height
                    val pixels = IntArray(w * h)
                    bitmap.getPixels(pixels, 0, w, 0, 0, w, h)
                    bitmap.recycle()
                    if (!encoder.writeFrame(pixels, w, h, i * frameDurUs)) {
                        WepWawetLogger.proxyError("$TAG: encoder rejected MJPG frame",
                            "frame" to i)
                        encoder.release()
                        return false
                    }
                    decoded++
                } catch (e: Exception) {
                    WepWawetLogger.proxyWarn("$TAG: MJPG frame exception — skipping",
                        "frame" to i,
                        "exception" to e.javaClass.name)
                }
            }
        }
        val ok = decoded > 0 && encoder.finish()
        if (ok) {
            WepWawetLogger.proxy("$TAG: MJPG transcode SUCCESS",
                "frames_decoded" to decoded,
                "frames_total" to frames.size)
        }
        return ok
    }

    // ── raw DIB (BI_RGB, 24/32 bpp, bottom-up BGR) ────────────────────────

    private fun transcodeRawDib(
        aviFile: File, video: VideoStreamInfo,
        frames: List<AviFrame>, outputFile: File,
    ): Boolean {
        val height = Math.abs(video.height)
        val bytesPerPixel = video.bitCount / 8
        if (bytesPerPixel != 3 && bytesPerPixel != 4) {
            WepWawetLogger.proxyError("$TAG: unsupported raw DIB bit depth",
                "bit_count" to video.bitCount)
            return false
        }

        val encoder = BitmapToMp4Encoder(
            width = video.width,
            height = height,
            outputFile = outputFile,
            targetBitrate = BitmapToMp4Encoder.suggestBitrate(video.width, height),
            frameRate = (video.rate / video.scale).coerceIn(5, 60),
        )
        if (!encoder.open()) return false

        val rowBytes = ((video.width * video.bitCount + 31) / 32) * 4
        val pixels = IntArray(video.width * height)
        val bottomUp = video.height > 0

        RandomAccessFile(aviFile, "r").use { raf ->
            for ((i, frame) in frames.withIndex()) {
                try {
                    raf.seek(frame.offset)
                    val dib = ByteArray(frame.size)
                    raf.readFully(dib)

                    for (row in 0 until height) {
                        val srcRow = if (bottomUp) height - 1 - row else row
                        val srcBase = srcRow * rowBytes
                        val dstBase = row * video.width
                        for (col in 0 until video.width) {
                            val s = srcBase + col * bytesPerPixel
                            if (s + bytesPerPixel > dib.size) break
                            val b = dib[s].toInt() and 0xFF
                            val g = dib[s + 1].toInt() and 0xFF
                            val r = dib[s + 2].toInt() and 0xFF
                            val a = if (bytesPerPixel == 4) (dib[s + 3].toInt() and 0xFF) else 0xFF
                            pixels[dstBase + col] =
                                (a shl 24) or (r shl 16) or (g shl 8) or b
                        }
                    }
                    if (!encoder.writeFrame(pixels, video.width, height, i * 1_000_000L * video.scale / video.rate)) {
                        encoder.release()
                        return false
                    }
                } catch (e: Exception) {
                    WepWawetLogger.proxyWarn("$TAG: raw DIB frame exception — skipping",
                        "frame" to i,
                        "exception" to e.javaClass.name)
                }
            }
        }
        // v0.7.24 (H-V1): finish() false → no valid MP4 was produced.
        if (!encoder.finish()) {
            runCatching { outputFile.delete() }
            return false
        }
        return true
    }

    // ── compressed: H.264 / MPEG-4 Part 2 via MediaCodec ─────────────────

    private fun transcodeCompressed(
        aviFile: File, video: VideoStreamInfo,
        frames: List<AviFrame>, outputFile: File,
    ): Boolean {
        val mime = when (video.compressionFourcc) {
            "H264", "h264", "X264", "x264", "AVC1", "avc1" -> MediaFormat.MIMETYPE_VIDEO_AVC
            "XVID", "xvid", "DX50", "dx50", "DIVX", "divx",
            "DIV3", "div3", "DIV4", "div4", "FMP4", "fmp4", "MP4V", "mp4v",
            "3IV1", "3iv1", "3IV2", "3iv2" -> MediaFormat.MIMETYPE_VIDEO_MPEG4
            else -> {
                WepWawetLogger.proxyError("$TAG: AVI codec has no Android framework decoder — cannot convert on-device",
                    "compression" to video.compressionFourcc,
                    "handler" to video.handlerFourcc,
                    "workaround" to "Convert the source to MP4 (H.264) on a PC once, then send the MP4")
                return false
            }
        }

        // Decode → ARGB → re-encode. YUV plane strides come from the
        // decoder's output Image, so vendor layouts are handled correctly.
        val decoder = try {
            MediaCodec.createDecoderByType(mime).apply {
                // v0.7.24 (C-V1 BUG FIX): the codec was created but NEVER
                // configured or started — dequeueInputBuffer requires the
                // Running sub-state and threw IllegalStateException on the
                // very first loop iteration, so EVERY compressed-AVI
                // wallpaper (H.264/XVID/DX50/DIVX/MP4V) silently failed the
                // on-device transcode. Configure with the stream's real
                // dimensions (abs() — the AVI height is negative for
                // top-down bitmaps) and start.
                val decFormat = MediaFormat.createVideoFormat(
                    mime, video.width, Math.abs(video.height))
                configure(decFormat, null, null, 0)
                start()
            }
        } catch (e: Exception) {
            WepWawetLogger.proxyError("$TAG: cannot create/configure/start decoder",
                "mime" to mime,
                "width" to video.width,
                "height" to Math.abs(video.height),
                "exception" to e.javaClass.name,
                "message" to (e.message ?: ""))
            return false
        }

        val outWidth = video.width
        val outHeight = Math.abs(video.height)
        val encoder = BitmapToMp4Encoder(
            width = outWidth,
            height = outHeight,
            outputFile = outputFile,
            targetBitrate = BitmapToMp4Encoder.suggestBitrate(outWidth, outHeight),
            frameRate = (video.rate / video.scale).coerceIn(5, 60),
        )
        if (!encoder.open()) {
            decoder.release()
            return false
        }

        var ok = true
        var frameIdx = 0
        try {
            RandomAccessFile(aviFile, "r").use { raf ->
                val info = MediaCodec.BufferInfo()
                var inputDone = false
                var outputDone = false

                while (!outputDone) {
                    // Feed input
                    if (!inputDone) {
                        val inIndex = decoder.dequeueInputBuffer(10_000_000L)
                        if (inIndex >= 0) {
                            if (frameIdx >= frames.size) {
                                decoder.queueInputBuffer(inIndex, 0, 0, -1L,
                                    MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                                inputDone = true
                            } else {
                                val frame = frames[frameIdx]
                                raf.seek(frame.offset)
                                val sample = ByteArray(frame.size)
                                raf.readFully(sample)
                                val buf: ByteBuffer? = decoder.getInputBuffer(inIndex)
                                buf?.clear()
                                buf?.put(sample)
                                val ptsUs = frameIdx * 1_000_000L * video.scale / video.rate
                                decoder.queueInputBuffer(inIndex, 0, frame.size, ptsUs, 0)
                                frameIdx++
                            }
                        }
                    }

                    // Drain output
                    val outIndex = decoder.dequeueOutputBuffer(info, 10_000_000L)
                    when {
                        outIndex == MediaCodec.INFO_TRY_AGAIN_LATER -> {
                            if (inputDone) {
                                // Give the decoder one more spin; if still
                                // empty after EOS input, bail out.
                                val again = decoder.dequeueOutputBuffer(info, 10_000_000L)
                                if (again == MediaCodec.INFO_TRY_AGAIN_LATER) {
                                    outputDone = true
                                }
                            }
                        }
                        outIndex >= 0 -> {
                            val image: Image? = if (info.size > 0) {
                                decoder.getOutputImage(outIndex)
                            } else null
                            if (image != null) {
                                val w = image.width
                                val h = image.height
                                val argb = yuvImageToArgb(image, w, h)
                                image.close()
                                if (argb != null) {
                                    if (!encoder.writeFrame(argb, w, h, info.presentationTimeUs)) {
                                        ok = false
                                    }
                                }
                            }
                            val eos = info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0
                            decoder.releaseOutputBuffer(outIndex, false)
                            if (eos || !ok) outputDone = true
                        }
                    }
                }
            }
        } catch (e: Exception) {
            WepWawetLogger.proxyError("$TAG: compressed decode pipeline failed",
                "exception" to e.javaClass.name,
                "message" to (e.message ?: ""),
                "frames_processed" to frameIdx)
            ok = false
        } finally {
            try { decoder.stop() } catch (_: Exception) {}
            try { decoder.release() } catch (_: Exception) {}
        }

        val finished = if (ok) encoder.finish() else false.also { encoder.release() }
        if (!ok || !finished) {
            runCatching { outputFile.delete() }
            return false
        }
        WepWawetLogger.proxy("$TAG: compressed transcode SUCCESS",
            "codec" to video.compressionFourcc,
            "frames" to frameIdx)
        return true
    }

    // ── helpers ──────────────────────────────────────────────────────────

    /**
     * Convert a decoder output Image (any YUV 4:2:0 planar/semi-planar
     * layout, honoring strides) into an ARGB_8888 pixel array using the
     * inverse BT.601 limited-range matrix.
     */
    private fun yuvImageToArgb(image: Image, w: Int, h: Int): IntArray? {
        return try {
            val yPlane = image.planes[0]
            val uPlane = image.planes[1]
            val vPlane = image.planes[2]
            val yRow = yPlane.rowStride
            val uRow = uPlane.rowStride
            val uPix = uPlane.pixelStride
            val vRow = vPlane.rowStride
            val vPix = vPlane.pixelStride

            val argb = IntArray(w * h)
            for (row in 0 until h) {
                for (col in 0 until w) {
                    val y = (yPlane.buffer.get(row * yRow + col).toInt() and 0xFF) - 16
                    val cCol = col / 2
                    val cRow = row / 2
                    val u = (uPlane.buffer.get(cRow * uRow + cCol * uPix).toInt() and 0xFF) - 128
                    val v = (vPlane.buffer.get(cRow * vRow + cCol * vPix).toInt() and 0xFF) - 128

                    var r = (1.164383 * y + 1.596027 * v).toInt()
                    var g = (1.164383 * y - 0.391762 * u - 0.812968 * v).toInt()
                    var b = (1.164383 * y + 2.017232 * u).toInt()
                    if (r < 0) r = 0; if (r > 255) r = 255
                    if (g < 0) g = 0; if (g > 255) g = 255
                    if (b < 0) b = 0; if (b > 255) b = 255

                    argb[row * w + col] =
                        (0xFF shl 24) or (r shl 16) or (g shl 8) or b
                }
            }
            argb
        } catch (e: Exception) {
            WepWawetLogger.proxyWarn("$TAG: YUV→ARGB conversion failed",
                "exception" to e.javaClass.name)
            null
        }
    }

    /** JPEG Annex K baseline Huffman tables (public specification data). */
    private object JpegTables {
        // Tables as hex strings — immune to Kotlin's "Int literal where Byte
        // expected" strictness for values >= 0x80, and exactly as readable as
        // the byte-array form in the JPEG spec / libjpeg's jcparam.c.

        private fun hexBytes(hex: String): ByteArray {
            val clean = hex.replace(" ", "").replace("\n", "")
            return ByteArray(clean.length / 2) { i ->
                clean.substring(i * 2, i * 2 + 2).toInt(16).toByte()
            }
        }

        // DC luminance: 16 counts then 12 values 0x00..0x0B
        private val DC_LUM_BITS = hexBytes("00 01 05 01 01 01 01 01 01 00 00 00 00 00 00 00")
        private val DC_LUM_VALS = hexBytes("00 01 02 03 04 05 06 07 08 09 0A 0B")

        // DC chrominance
        private val DC_CHR_BITS = hexBytes("00 03 01 01 01 01 01 01 01 01 01 00 00 00 00 00")
        private val DC_CHR_VALS = hexBytes("00 01 02 03 04 05 06 07 08 09 0A 0B")

        // AC luminance: 16 counts then the 162 standard values
        private val AC_LUM_BITS = hexBytes("00 02 01 03 03 02 04 03 05 05 04 04 00 00 01 7D")
        private val AC_LUM_VALS = hexBytes(
            "0102030004110512 2131410613516107 227114328191A108 2342B1C11552D1F0 " +
            "2433627282090A16 1718191A25262728 292A343536373839 3A43444546474849 " +
            "4A53545556575859 5A63646566676869 6A73747576777879 7A83848586878889 " +
            "8A92939495969798 999AA2A3A4A5A6A7 A8A9AAB2B3B4B5B6 B7B8B9BAC2C3C4C5 " +
            "C6C7C8C9CAD2D3D4 D5D6D7D8D9DAE1E2 E3E4E5E6E7E8E9 EAF1F2F3F4F5F6F7 " +
            "F8F9FA")

        // AC chrominance
        private val AC_CHR_BITS = hexBytes("00 02 01 02 04 04 03 04 07 05 04 04 00 01 02 77")
        private val AC_CHR_VALS = hexBytes(
            "0001020311040521 31061241510761 7113223281081442 91A1B1C109233352 F0 " +
            "1562 72D1 0A16 2434 E1 25F1 1718191A26 " +
            "2728292A35363738393A43444546474849 4A53545556575859 5A63646566676869 " +
            "6A73747576777879 7A82838485868788 898A929394959697 98999AA2A3A4A5A6 " +
            "A7A8A9AAB2B3B4B5 B6B7B8B9BAC2C3C4 C5C6C7C8C9CAD2D3 D4D5D6D7D8D9DA " +
            "E2E3E4E5E6E7E8E9 EAF2F3F4F5F6F7F8 F9FA")

        fun dhtSegment(idAndClass: Int, bits: ByteArray, vals: ByteArray): ByteArray {
            val out = ByteArrayOutputStream()
            out.write(0xFF); out.write(0xC4)
            val length = 2 + 1 + bits.size + vals.size
            out.write((length shr 8) and 0xFF)
            out.write(length and 0xFF)
            out.write(idAndClass)
            out.write(bits)
            out.write(vals)
            return out.toByteArray()
        }

        val allSegments: List<ByteArray> by lazy {
            listOf(
                dhtSegment(0x00, DC_LUM_BITS, DC_LUM_VALS),
                dhtSegment(0x10, AC_LUM_BITS, AC_LUM_VALS),
                dhtSegment(0x01, DC_CHR_BITS, DC_CHR_VALS),
                dhtSegment(0x11, AC_CHR_BITS, AC_CHR_VALS),
            )
        }
    }

    /**
     * AVI MJPG frames commonly carry JPEGs without DHT segments (the AVI 1.0
     * "no Huffman tables" hack) — BitmapFactory rejects those. This helper
     * scans the frame for a DHT (FF C4) marker between SOI and SOS and, when
     * none is found, splices the standard Annex K baseline tables in right
     * before the first SOS marker. Returns the original array untouched when
     * tables are present or the structure looks unexpected.
     */
    fun ensureJpegHuffmanTables(jpeg: ByteArray): ByteArray {
        return try {
            if (jpeg.size < 4) return jpeg
            if (jpeg[0] != 0xFF.toByte() || jpeg[1] != 0xD8.toByte()) return jpeg

            var hasDht = false
            var sosOffset = -1
            var pos = 2
            while (pos + 4 <= jpeg.size) {
                if (jpeg[pos] != 0xFF.toByte()) break
                val marker = jpeg[pos + 1].toInt() and 0xFF
                // Standalone markers (no length payload)
                if (marker == 0xD8 || (marker in 0xD0..0xD7) || marker == 0x01) {
                    pos += 2
                    continue
                }
                if (marker == 0xC4) hasDht = true
                if (marker == 0xDA) { sosOffset = pos; break }
                if (marker == 0xD9) break
                val len = ((jpeg[pos + 2].toInt() and 0xFF) shl 8) or
                    (jpeg[pos + 3].toInt() and 0xFF)
                if (len < 2) break
                pos += 2 + len
            }

            if (hasDht || sosOffset < 0) return jpeg

            // Splice the four standard tables before SOS.
            val patched = ByteArrayOutputStream(jpeg.size + 512)
            patched.write(jpeg, 0, sosOffset)
            for (seg in JpegTables.allSegments) patched.write(seg)
            patched.write(jpeg, sosOffset, jpeg.size - sosOffset)
            patched.toByteArray()
        } catch (_: Exception) {
            jpeg
        }
    }

    // ── AVI structure parsing ────────────────────────────────────────────

    private fun fourcc(raf: RandomAccessFile): String {
        val b = ByteArray(4)
        raf.readFully(b)
        return String(b, Charsets.US_ASCII)
    }

    private fun u32(raf: RandomAccessFile): Long {
        val b = ByteArray(4)
        raf.readFully(b)
        return (b[0].toLong() and 0xFF) or
            ((b[1].toLong() and 0xFF) shl 8) or
            ((b[2].toLong() and 0xFF) shl 16) or
            ((b[3].toLong() and 0xFF) shl 24)
    }

    private fun u16(raf: RandomAccessFile): Int {
        val b = ByteArray(2)
        raf.readFully(b)
        return (b[0].toInt() and 0xFF) or ((b[1].toInt() and 0xFF) shl 8)
    }

    /**
     * Walk the RIFF tree: hdrl (avih + strl/strh/strf) for stream metadata,
     * then the movi list for frame chunk offsets. Returns false when the
     * file is not a readable AVI with a video stream.
     */
    private fun parseAvi(
        raf: RandomAccessFile,
        video: VideoStreamInfo,
        frames: MutableList<AviFrame>,
    ): Boolean {
        val fileLength = raf.length()
        raf.seek(0)
        if (fourcc(raf) != "RIFF") return false
        val riffSize = u32(raf)
        if (fourcc(raf) != "AVI " && fourcc(raf) != "AVIX") return false
        val riffEnd = minOf(fileLength, 8 + riffSize)

        var moviStart = -1L

        var pos = 12L
        while (pos + 8 <= riffEnd) {
            raf.seek(pos)
            val chunkId = fourcc(raf)
            val chunkSize = u32(raf)
            val bodyStart = pos + 8
            val bodyEnd = bodyStart + chunkSize

            when {
                chunkId == "LIST" -> {
                    raf.seek(bodyStart)
                    val listType = fourcc(raf)
                    when (listType) {
                        "hdrl" -> parseHdrl(raf, bodyStart + 4, bodyEnd, video)
                        "movi " -> {
                            if (moviStart < 0) moviStart = bodyStart + 4
                            // Sequential scan of the movi chunks:
                            parseMoviChunks(raf, bodyStart + 4, bodyEnd, video, frames)
                        }
                        // 'strl' inside hdrl is handled by parseHdrl directly;
                        // 'odml'/'JUNK' etc. are skipped.
                    }
                }
                chunkId == "idx1" -> { /* offsets already walked from movi */ }
                // skip everything else (JUNK, etc.)
            }
            pos = bodyEnd + (chunkSize and 1L)  // chunks are word-aligned
            if (chunkSize <= 0) break
        }

        return video.streamNumber >= 0 && frames.isNotEmpty()
    }

    /** Parse the hdrl LIST: avih + one strl per stream. */
    private fun parseHdrl(
        raf: RandomAccessFile, start: Long, end: Long,
        video: VideoStreamInfo,
    ) {
        var pos = start
        // Streams are numbered by the ORDER of their strl lists ('00' is the
        // first strl, '01' the second, …) regardless of type — the movi chunk
        // ids use that ordinal (e.g. audio first → audio chunks are '00wb',
        // video chunks are '01dc'). Track it so the right chunks are picked.
        var strlOrdinal = -1
        while (pos + 8 <= end) {
            raf.seek(pos)
            val chunkId = fourcc(raf)
            val chunkSize = u32(raf)
            val bodyStart = pos + 8
            val bodyEnd = bodyStart + chunkSize

            if (chunkId == "LIST") {
                raf.seek(bodyStart)
                if (fourcc(raf) == "strl") {
                    strlOrdinal++
                    parseStrl(raf, bodyStart + 4, bodyEnd, video, strlOrdinal)
                }
            }
            // 'avih' main header fields (frame rate, totals) are superseded
            // by the per-stream strh values — nothing needed from avih.
            pos = bodyEnd + (chunkSize and 1L)
            if (chunkSize <= 0) break
        }
    }

    /** Parse one strl LIST: strh + strf (first video stream wins). */
    private fun parseStrl(
        raf: RandomAccessFile, start: Long, end: Long,
        video: VideoStreamInfo, streamOrdinal: Int,
    ) {
        var strhBody = -1L to -1L
        var strfBody = -1L to -1L
        var pos = start
        while (pos + 8 <= end) {
            raf.seek(pos)
            val chunkId = fourcc(raf)
            val chunkSize = u32(raf)
            val bodyStart = pos + 8
            val bodyEnd = bodyStart + chunkSize
            if (chunkId == "strh" && strhBody.first < 0) strhBody = bodyStart to bodyEnd
            if (chunkId == "strf" && strfBody.first < 0) strfBody = bodyStart to bodyEnd
            pos = bodyEnd + (chunkSize and 1L)
            if (chunkSize <= 0) break
        }

        // ── strh ──
        var fccType = ""
        var fccHandler = ""
        var rate = 0L
        var scale = 0L
        var length = 0L
        if (strhBody.first >= 0) {
            try {
                // AVIStreamHeader layout (56 bytes):
                //   fccType(4) fccHandler(4) dwFlags(4) dwPriority(4)
                //   dwLanguage(2) dwInitialFrames(2) dwScale(4) dwRate(4)
                //   dwStart(4) dwLength(4) dwSuggestedBufferSize(4) ...
                raf.seek(strhBody.first)
                fccType = fourcc(raf)
                fccHandler = fourcc(raf)
                raf.skipBytes(4 + 4)   // dwFlags + dwPriority
                raf.skipBytes(2 + 2)   // dwLanguage + dwInitialFrames
                scale = u32(raf)
                rate = u32(raf)
                raf.skipBytes(4)        // dwStart
                length = u32(raf)
            } catch (_: Exception) {
                return
            }
        }

        // ── strf (BITMAPINFOHEADER) ──
        var width = 0
        var height = 0
        var bitCount = 0
        var compression = ""
        if (strfBody.first >= 0) {
            try {
                raf.seek(strfBody.first)
                u32(raf)  // biSize (usually 40) — implicit by the strf chunk itself
                width = u32(raf).toInt()
                height = u32(raf).toInt()
                raf.skipBytes(2)   // biPlanes
                bitCount = u16(raf)
                val compBytes = ByteArray(4)
                raf.readFully(compBytes)
                compression = String(compBytes, Charsets.US_ASCII)
                if (compression == "\u0000\u0000\u0000\u0000") compression = ""
            } catch (_: Exception) {
                return
            }
        }

        if (fccType == "vids" && width > 0 && height != 0 && video.streamNumber < 0) {
            video.streamNumber = streamOrdinal
            video.handlerFourcc = fccHandler
            video.compressionFourcc = compression
            video.width = width
            video.height = height
            video.bitCount = if (bitCount > 0) bitCount else 24
            video.scale = (if (scale > 0) scale else 1L).toInt()
            video.rate = (if (rate > 0) rate else 30L).toInt()
            video.frameCount = length.toInt()
        }
    }

    /**
     * Walk the movi LIST sequentially, recording the video stream's frame
     * chunks ('##db'/'##dc'). Audio ('##wb') and other chunks are skipped.
     */
    private fun parseMoviChunks(
        raf: RandomAccessFile, start: Long, end: Long,
        video: VideoStreamInfo, frames: MutableList<AviFrame>,
    ) {
        val streamTag = "%02d".format(video.streamNumber.coerceIn(0, 99))
        var pos = start
        while (pos + 8 <= end) {
            raf.seek(pos)
            val chunkId = fourcc(raf)
            val chunkSize = u32(raf).toInt()
            if (chunkSize < 0) break
            val bodyStart = pos + 8

            if (chunkId.length == 4 && chunkId.startsWith(streamTag)) {
                val kind = chunkId.substring(2)
                if (kind == "db" || kind == "dc") {
                    frames.add(AviFrame(bodyStart, chunkSize, keyFrame = true))
                }
            }
            pos = bodyStart + chunkSize + (chunkSize and 1)
            if (chunkSize == 0) break
        }
    }
}
