package com.wepwawet.ui.screens

import android.media.MediaMetadataRetriever
import android.net.Uri
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RangeSlider
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import com.wepwawet.data.model.WallpaperType
import com.wepwawet.data.model.WorkshopItem
import com.wepwawet.data.model.WorkshopItemTyped
import com.wepwawet.log.WepWawetLogger
import com.wepwawet.pairing.PairingService
import com.wepwawet.pairing.VideoTrimmer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Video trim screen — a standard mobile video trimmer UI:
 *
 *   ┌──────────────────────────────────────────────────────┐
 *   │  Video preview (matches video's actual aspect ratio) │
 *   └──────────────────────────────────────────────────────┘
 *
 *   start                                              end
 *   00:03                                            00:14
 *
 *   ┌─▼─┬────────────────────────────────────────────┬─▼─┐
 *   │░░░│ [thumb][thumb][thumb][thumb][thumb][thumb] │░░░│
 *   │░░░│ [thumb][thumb][thumb][thumb][thumb][thumb] │░░░│
 *   └───┴────────────────────────────────────────────┴───┘
 *
 *   ↑ dimmed    ↑ thumbnails form the bar background     ↑ dimmed
 *               (selected region — frames visible)
 *
 *   [ Trim & Send ] [ Send Full Video (No Trim) ]
 *
 * The video frames ARE the bar. Two dim overlays cover the parts
 * outside the selected range. The RangeSlider sits on top with
 * transparent track colors — only the thumb handles are visible,
 * sitting on top of the strip like every other mobile video trimmer
 * (WhatsApp / iMovie / CapCut / etc.).
 *
 * v0.7.20: The filmstrip is now HORIZONTALLY SCROLLABLE with 40
 * frames (up from a fixed 16 that were squashed into the screen
 * width). Each frame is a fixed width derived from the video's
 * aspect ratio, so the strip is much wider than the screen — the
 * user scrolls across it like in a real video editor. The whole
 * screen is also vertically scrollable so that tall (portrait)
 * videos don't push the trim controls off the bottom of the
 * screen. The video preview's aspect ratio is preserved exactly.
 *
 * "Trim & Send" trims the video (lossless stream copy via
 * [VideoTrimmer.trim]) and queues it for transfer.
 * "Send Full Video" skips trimming and sends the full video
 * (same pack + queue code as the pre-trim send-to-phone).
 *
 * @param videoFile The downloaded video file (already exists on disk).
 * @param itemTitle Wallpaper title.
 * @param itemId Workshop item ID.
 * @param onBack Navigate back.
 * @param onSendComplete Called after the video has been queued.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun VideoTrimScreen(
    videoFile: File,
    itemTitle: String,
    itemId: String,
    onBack: () -> Unit,
    onSendComplete: () -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    val videoUri = remember { Uri.fromFile(videoFile).toString() }

    // ExoPlayer — preview loop of the video.
    val player = remember {
        ExoPlayer.Builder(context).build().apply {
            setMediaItem(MediaItem.fromUri(videoUri))
            prepare()
            playWhenReady = true
            repeatMode = Player.REPEAT_MODE_ONE
        }
    }
    DisposableEffect(Unit) { onDispose { player.release() } }

    // Video metadata + trimmer state.
    var durationMs by remember { mutableLongStateOf(0L) }
    var videoWidth by remember { mutableStateOf(0) }
    var videoHeight by remember { mutableStateOf(0) }
    var startMs by remember { mutableLongStateOf(0L) }
    var endMs by remember { mutableLongStateOf(0L) }
    var currentPosition by remember { mutableLongStateOf(0L) }
    var isTrimming by remember { mutableStateOf(false) }
    var trimStatus by remember { mutableStateOf("") }

    // v0.7.3: 16 frame thumbnails (was 8). More frames = clearer
    // visual sense of the video's content as the user drags. The
    // thumbnails form the BACKGROUND of the trim bar (not a separate
    // row above it) — the standard pattern used by every mobile video
    // trimmer (WhatsApp, iMovie, CapCut, VN, etc.).
    val thumbnails = remember { mutableStateListOf<android.graphics.Bitmap>() }

    // Get video metadata (duration, width, height) + generate thumbnails.
    // v0.7.4: the retriever is released in a FINALLY block (it used to be the
    // last statement inside the try — any throw from setDataSource/
    // extractMetadata/getFrameAtTime leaked the native MediaMetadataRetriever),
    // and CancellationException is re-thrown instead of being logged as a
    // warning (swallowing it broke structured concurrency when the user
    // navigated away mid-load).
    LaunchedEffect(videoFile) {
        withContext(Dispatchers.IO) {
            val retriever = MediaMetadataRetriever()
            try {
                retriever.setDataSource(videoFile.absolutePath)
                val dur = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L
                val w = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)?.toIntOrNull() ?: 1920
                val h = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)?.toIntOrNull() ?: 1080
                durationMs = dur
                endMs = dur
                videoWidth = w
                videoHeight = h
                WepWawetLogger.workshop("VideoTrimScreen: metadata loaded",
                    "duration_ms" to dur, "width" to w, "height" to h)

                // v0.7.20: Generate 40 frame thumbnails (up from 16) evenly
                // spaced across the video duration. The filmstrip is now
                // horizontally scrollable, so the frames are NOT squashed
                // into the screen width — each is rendered at its natural
                // aspect-ratio-derived width and the user scrolls across.
                // Using OPTION_CLOSEST_SYNC (sync frames only) is fast —
                // each call is ~10-30ms even for big videos, so 40 calls
                // add up to well under 1.5 seconds.
                val thumbCount = 40
                for (i in 0 until thumbCount) {
                    val t = if (thumbCount > 1) dur * i / (thumbCount - 1) else 0
                    val bmp = retriever.getFrameAtTime(t * 1000, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
                    if (bmp != null) {
                        // Scale down to height=160px (crisp at 72dp on xhdpi+)
                        // and width proportional to the video's aspect ratio.
                        // ContentScale.FillBounds in the strip Row will then
                        // stretch/squash each frame to its uniform-weight slot,
                        // giving the filmstrip look.
                        val targetH = 160
                        val targetW = (targetH * w / h).toInt().coerceAtLeast(1)
                        val scaled = android.graphics.Bitmap.createScaledBitmap(bmp, targetW, targetH, true)
                        if (scaled != bmp) bmp.recycle()
                        thumbnails.add(scaled)
                    }
                }
                WepWawetLogger.workshop("VideoTrimScreen: thumbnails generated",
                    "count" to thumbnails.size)
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                WepWawetLogger.workshopWarn("VideoTrimScreen: metadata/thumbnail failed",
                    "exception" to e.javaClass.name, "message" to (e.message ?: ""))
            } finally {
                runCatching { retriever.release() }
            }
        }
    }

    // Poll current position for the progress bar.
    LaunchedEffect(player) {
        while (true) {
            currentPosition = player.currentPosition
            delay(200)
        }
    }

    val aspectRatio = if (videoWidth > 0 && videoHeight > 0) {
        videoWidth.toFloat() / videoHeight.toFloat()
    } else {
        16f / 9f
    }

    // v0.7.20: Filmstrip dimensions — each frame is a fixed height (72dp)
    // with width derived from the video's aspect ratio, so frames display
    // at the correct proportions. The total strip width = frameWidth ×
    // frameCount, which is much wider than the screen — the user scrolls
    // horizontally across the strip like in a real video editor.
    val filmstripHeight = 72.dp
    val frameWidthDp = filmstripHeight * aspectRatio
    // thumbCount must match the count used in the thumbnail-generation
    // LaunchedEffect above.
    val frameCount = 40
    val totalStripWidthDp = frameWidthDp * frameCount
    val filmstripScrollState = rememberScrollState()

    Scaffold(
        topBar = {
            // v0.7.20: Slim top bar (56dp + status bar padding) to match
            // the WorkshopBrowserScreen — the default 64dp + inset wastes
            // vertical space on this screen where every pixel matters for
            // the video preview + filmstrip.
            TopAppBar(
                modifier = Modifier
                    .statusBarsPadding()
                    .height(56.dp),
                title = { Text("Trim Video", maxLines = 1) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                windowInsets = WindowInsets(0, 0, 0, 0),
            )
        },
    ) { padding ->
        // v0.7.20: Wrap the whole content in verticalScroll so that when
        // the video preview is tall (portrait videos), the user can scroll
        // down to reach the filmstrip + buttons. The preview's aspectRatio
        // is preserved exactly — we just let the content be as tall as it
        // needs to be and scroll if it overflows.
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            // === Video preview (matches video's actual aspect ratio) ===
            Surface(
                modifier = Modifier
                    .fillMaxWidth()
                    .aspectRatio(aspectRatio),
                shape = RoundedCornerShape(12.dp),
                color = Color.Black,
            ) {
                AndroidView(
                    factory = { ctx ->
                        PlayerView(ctx).apply {
                            this.player = player
                            useController = true
                            setShowNextButton(false)
                            setShowPreviousButton(false)
                        }
                    },
                    modifier = Modifier.fillMaxSize(),
                )
            }

            // === Time labels ===
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Text("${formatTime(startMs)}", style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.primary)
                Text("Duration: ${formatTime(endMs - startMs)}", style = MaterialTheme.typography.labelMedium)
                Text("${formatTime(endMs)}", style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.primary)
            }

            // === v0.7.20: Scrollable filmstrip trimmer ===
            // Layout (bottom to top in a horizontally-scrollable Box):
            //   1. Row of 40 frame thumbnails, each at a FIXED width derived
            //      from the video's aspect ratio. The total row width is
            //      much wider than the screen, so the user scrolls
            //      horizontally across the strip (like in a real video
            //      editor — iMovie, CapCut, etc.).
            //   2. Dim overlays (left/middle/right) on top of the frames,
            //      spanning the full scrollable width so the dimmed region
            //      maps correctly to the selected time range.
            //   3. RangeSlider on top, also spanning the full scrollable
            //      width, so the thumb handles sit at the correct time
            //      positions. The slider + dims + frames all scroll
            //      together as one unit.
            if (durationMs > 0) {
                val startRange = startMs.toFloat() / durationMs
                val endRange = endMs.toFloat() / durationMs
                val leftDimWeight = startRange.coerceIn(0f, 1f)
                val middleWeight = (endRange - startRange).coerceIn(0f, 1f)
                val rightDimWeight = (1f - endRange).coerceIn(0f, 1f)

                // The outer Box clips the filmstrip to a rounded rectangle.
                // The inner Box is horizontally scrollable and contains the
                // frames + dims + slider at totalStripWidthDp width.
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(filmstripHeight)
                        .clip(RoundedCornerShape(6.dp)),
                ) {
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .horizontalScroll(filmstripScrollState),
                    ) {
                        // 1) Thumbnail strip background (frames ARE the bar)
                        if (thumbnails.isNotEmpty()) {
                            Row(
                                modifier = Modifier
                                    .width(totalStripWidthDp)
                                    .fillMaxHeight(),
                                horizontalArrangement = Arrangement.spacedBy(1.dp),
                            ) {
                                thumbnails.forEach { bmp ->
                                    Image(
                                        bitmap = bmp.asImageBitmap(),
                                        contentDescription = null,
                                        contentScale = ContentScale.FillBounds,
                                        modifier = Modifier
                                            .width(frameWidthDp)
                                            .fillMaxHeight(),
                                    )
                                }
                            }
                        } else {
                            // Fallback while thumbnails load
                            Box(
                                modifier = Modifier
                                    .width(totalStripWidthDp)
                                    .fillMaxHeight()
                                    .background(Color.DarkGray),
                                contentAlignment = Alignment.Center,
                            ) {
                                CircularProgressIndicator(modifier = Modifier.size(24.dp))
                            }
                        }

                        // 2) Dimmed overlays outside the selected region.
                        // Three weighted Boxes spanning the full strip width
                        // — when startRange=0 the left dim is invisible
                        // (weight 0 = 0 width); same for the right dim when
                        // endRange=1. The middle Box is transparent so the
                        // frames show through.
                        Row(
                            modifier = Modifier
                                .width(totalStripWidthDp)
                                .fillMaxHeight(),
                        ) {
                            if (leftDimWeight > 0f) {
                                Box(
                                    modifier = Modifier
                                        .weight(leftDimWeight)
                                        .fillMaxHeight()
                                        .background(Color.Black.copy(alpha = 0.55f)),
                                )
                            }
                            if (middleWeight > 0f) {
                                Box(modifier = Modifier.weight(middleWeight))
                            }
                            if (rightDimWeight > 0f) {
                                Box(
                                    modifier = Modifier
                                        .weight(rightDimWeight)
                                        .fillMaxHeight()
                                        .background(Color.Black.copy(alpha = 0.55f)),
                                )
                            }
                        }

                        // 3) RangeSlider on top — transparent track colors so
                        //    only the two thumb handles are visible, sitting
                        //    on top of the strip. The slider spans the full
                        //    scrollable width so the thumbs map to the correct
                        //    time positions. As the user scrolls the filmstrip,
                        //    the slider scrolls with it (thumbs move with the
                        //    frames). The user drags a thumb by touching it
                        //    directly; touching elsewhere on the strip scrolls.
                        RangeSlider(
                            value = startRange..endRange,
                            onValueChange = { range ->
                                // Enforce a 500ms minimum selection so the
                                // start/end can't collapse onto each other.
                                val newStart = (range.start * durationMs).toLong()
                                    .coerceAtMost((endMs - 500).coerceAtLeast(0))
                                startMs = newStart
                                val newEnd = (range.endInclusive * durationMs).toLong()
                                    .coerceAtLeast(newStart + 500).coerceAtMost(durationMs)
                                endMs = newEnd
                            },
                            onValueChangeFinished = {
                                // Seek the preview to the start handle when the
                                // user lifts their finger — gives a "preview
                                // from the trim point" effect.
                                player.seekTo(startMs)
                            },
                            modifier = Modifier
                                .width(totalStripWidthDp)
                                .align(Alignment.Center),
                            colors = SliderDefaults.colors(
                                activeTrackColor = Color.Transparent,
                                inactiveTrackColor = Color.Transparent,
                                thumbColor = Color.White,
                            ),
                        )
                    }
                }
            } else {
                CircularProgressIndicator(modifier = Modifier.size(32.dp))
            }

            // === Progress bar (current playback position) ===
            if (durationMs > 0 && !isTrimming) {
                LinearProgressIndicator(
                    progress = { currentPosition.toFloat() / durationMs.toFloat() },
                    modifier = Modifier.fillMaxWidth(),
                )
            }

            // === Trim status (during Trim & Send / Send Full) ===
            if (isTrimming) {
                LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                Text(trimStatus, style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }

            // v0.7.20: Fixed spacer (was Modifier.weight(1f) which doesn't
            // work inside a verticalScroll — the column has unbounded height
            // so weight has no meaning).
            Spacer(Modifier.height(16.dp))

            // === Trim & Send + Send Full Video side by side ===
            // Both buttons in a single Row (side by side, not stacked
            // vertically) per the v0.7.2 dialog redesign.
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Button(
                    onClick = {
                        if (isTrimming) return@Button
                        isTrimming = true
                        trimStatus = "Trimming video…"
                        scope.launch {
                            withContext(Dispatchers.IO) {
                                try {
                                    val trimmedFile = File(context.cacheDir, "${itemId}_trimmed.mp4")
                                    trimStatus = "Trimming ${formatTime(startMs)}–${formatTime(endMs)}…"
                                    val success = VideoTrimmer.trim(videoFile, trimmedFile, startMs, endMs)
                                    val packedFile = if (success) trimmedFile else videoFile
                                    trimStatus = if (success) "Packing trimmed video…" else "Trim failed — packing full video…"

                                    val mpkgFile = File(context.cacheDir, "${itemId}_${System.currentTimeMillis()}_send.mpkg")
                                    val item = WorkshopItemTyped(
                                        item = WorkshopItem(publishedfileid = itemId, title = itemTitle),
                                        type = WallpaperType.VIDEO,
                                    )
                                    val installDir = File(context.cacheDir, "workshop_downloads/$itemId")
                                    val previewFile = File(installDir, "preview.jpg").takeIf { it.exists() }
                                        ?: File(installDir, "preview.gif").takeIf { it.exists() }
                                    com.wepwawet.pairing.MpkgPacker.packVideoWallpaper(
                                        item = item, videoFile = packedFile,
                                        outputFile = mpkgFile, previewFile = previewFile,
                                    )
                                    trimStatus = "Queuing for transfer…"
                                    PairingService.queueWallpaper(mpkgFile, itemTitle)
                                    PairingService.launchWallpaperEngineApp(context)
                                    runCatching { trimmedFile.delete() }
                                    runCatching { videoFile.delete() }
                                    // v0.7.4: clean up the install dir (contains
                                    // project.json/preview.jpg the pack consumed).
                                    runCatching { installDir.deleteRecursively() }
                                    // v0.7.4: clear the pending-video state so the
                                    // "Video Downloaded" dialog does NOT reappear
                                    // after we pop back — and so a second "Trim
                                    // Video" tap can't navigate here with a path to
                                    // a file we just deleted (stuck spinner).
                                    com.wepwawet.pairing.WorkshopDownloadJob.onVideoConsumedByTrimScreen(itemId)
                                    WepWawetLogger.workshop("VideoTrimScreen: trim + send complete",
                                        "item_id" to itemId, "mpkg_size" to mpkgFile.length())
                                    isTrimming = false
                                    onSendComplete()
                                } catch (e: kotlinx.coroutines.CancellationException) {
                                    throw e
                                } catch (e: Exception) {
                                    isTrimming = false
                                    trimStatus = "Failed: ${e.message}"
                                }
                            }
                        }
                    },
                    enabled = !isTrimming && durationMs > 0,
                    modifier = Modifier.weight(1f),
                ) {
                    Text(if (isTrimming) "Processing…" else "Trim & Send")
                }

                Button(
                    onClick = {
                        if (isTrimming) return@Button
                        isTrimming = true
                        trimStatus = "Packing full video…"
                        scope.launch {
                            withContext(Dispatchers.IO) {
                                try {
                                    val mpkgFile = File(context.cacheDir, "${itemId}_${System.currentTimeMillis()}_send.mpkg")
                                    val item = WorkshopItemTyped(
                                        item = WorkshopItem(publishedfileid = itemId, title = itemTitle),
                                        type = WallpaperType.VIDEO,
                                    )
                                    val installDir = File(context.cacheDir, "workshop_downloads/$itemId")
                                    val previewFile = File(installDir, "preview.jpg").takeIf { it.exists() }
                                        ?: File(installDir, "preview.gif").takeIf { it.exists() }
                                    com.wepwawet.pairing.MpkgPacker.packVideoWallpaper(
                                        item = item, videoFile = videoFile,
                                        outputFile = mpkgFile, previewFile = previewFile,
                                    )
                                    trimStatus = "Queuing for transfer…"
                                    PairingService.queueWallpaper(mpkgFile, itemTitle)
                                    PairingService.launchWallpaperEngineApp(context)
                                    runCatching { videoFile.delete() }
                                    // v0.7.4: same install-dir cleanup + pending-state
                                    // clear as the trim path.
                                    runCatching { installDir.deleteRecursively() }
                                    com.wepwawet.pairing.WorkshopDownloadJob.onVideoConsumedByTrimScreen(itemId)
                                    isTrimming = false
                                    onSendComplete()
                                } catch (e: kotlinx.coroutines.CancellationException) {
                                    throw e
                                } catch (e: Exception) {
                                    isTrimming = false
                                    trimStatus = "Failed: ${e.message}"
                                }
                            }
                        }
                    },
                    enabled = !isTrimming,
                    modifier = Modifier.weight(1f),
                ) {
                    Text("Send Full Video")
                }
            }
        }
    }
}

private fun formatTime(ms: Long): String {
    val totalSec = ms / 1000
    val sec = totalSec % 60
    val min = (totalSec / 60) % 60
    val hr = totalSec / 3600
    return if (hr > 0) "%d:%02d:%02d".format(hr, min, sec) else "%d:%02d".format(min, sec)
}
