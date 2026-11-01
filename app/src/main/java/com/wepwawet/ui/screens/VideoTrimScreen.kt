package com.wepwawet.ui.screens

import android.media.MediaMetadataRetriever
import android.net.Uri
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
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
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
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
import com.wepwawet.ui.components.SlimTopBar
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Video trim screen — a mobile video trimmer UI with a scrollable filmstrip,
 * two draggable clip bars (start/end), and a center playhead marker.
 *
 * v0.7.21 redesign:
 *   - Filmstrip is horizontally scrollable with 40 frames at the video's
 *     true aspect ratio.
 *   - Two custom draggable vertical clip bars (start + end) replace the
 *     old RangeSlider. Each clip bar is independently draggable.
 *   - A center playhead marker (vertical white line) shows the current
 *     playback position.
 *   - The video player only allows play/pause — no seeking, no next/prev,
 *     no fast-forward/rewind. PlayerView.useController = false + a custom
 *     play/pause overlay button.
 *   - The whole screen is vertically scrollable so tall (portrait) videos
 *     don't push the trim controls off-screen.
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
    val density = LocalDensity.current

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
    DisposableEffect(Unit) {
        onDispose {
            player.pause()
            player.release()
        }
    }

    // Video metadata + trimmer state.
    var durationMs by remember { mutableLongStateOf(0L) }
    var videoWidth by remember { mutableStateOf(0) }
    var videoHeight by remember { mutableStateOf(0) }
    var startMs by remember { mutableLongStateOf(0L) }
    var endMs by remember { mutableLongStateOf(0L) }
    var currentPosition by remember { mutableLongStateOf(0L) }
    var isTrimming by remember { mutableStateOf(false) }
    var trimStatus by remember { mutableStateOf("") }
    var isPlaying by remember { mutableStateOf(true) }

    val thumbnails = remember { mutableStateListOf<android.graphics.Bitmap>() }

    // Generate frame thumbnails.
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

                // v0.7.21: Clear any stale thumbnails before regenerating.
                thumbnails.clear()

                val thumbCount = 40
                for (i in 0 until thumbCount) {
                    val t = if (thumbCount > 1) dur * i / (thumbCount - 1) else 0
                    val bmp = retriever.getFrameAtTime(t * 1000, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
                    if (bmp != null) {
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

    // Poll current position for the playhead marker.
    LaunchedEffect(player) {
        while (true) {
            currentPosition = player.currentPosition
            isPlaying = player.isPlaying
            delay(100)
        }
    }

    val aspectRatio = if (videoWidth > 0 && videoHeight > 0) {
        videoWidth.toFloat() / videoHeight.toFloat()
    } else {
        16f / 9f
    }

    // v0.7.21: Filmstrip dimensions.
    val filmstripHeight = 72.dp
    val frameWidthDp = filmstripHeight * aspectRatio
    val frameCount = 40
    val totalStripWidthDp = frameWidthDp * frameCount
    val filmstripScrollState = rememberScrollState()
    // Convert total strip width to pixels for drag delta → time conversion.
    val totalStripWidthPx = with(density) { totalStripWidthDp.toPx() }

    Scaffold(
        topBar = {
            SlimTopBar(title = "Trim Video", onBack = onBack)
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            // === Video preview (matches video's actual aspect ratio) ===
            // v0.7.21: PlayerView.useController = false — the user can only
            // pause/play via the overlay button. No seeking, no next/prev,
            // no fast-forward/rewind. This prevents the user from messing
            // with the playback position while trimming.
            Surface(
                modifier = Modifier
                    .fillMaxWidth()
                    .aspectRatio(aspectRatio),
                shape = RoundedCornerShape(12.dp),
                color = Color.Black,
            ) {
                Box(modifier = Modifier.fillMaxSize()) {
                    AndroidView(
                        factory = { ctx ->
                            PlayerView(ctx).apply {
                                this.player = player
                                useController = false
                                setShowNextButton(false)
                                setShowPreviousButton(false)
                            }
                        },
                        modifier = Modifier.fillMaxSize(),
                    )
                    // Play/pause overlay button — centered on the video.
                    IconButton(
                        onClick = {
                            if (player.isPlaying) {
                                player.pause()
                            } else {
                                player.play()
                            }
                        },
                        modifier = Modifier
                            .align(Alignment.Center)
                            .size(56.dp),
                    ) {
                        Icon(
                            imageVector = if (isPlaying) Icons.Filled.Pause else Icons.Filled.PlayArrow,
                            contentDescription = if (isPlaying) "Pause" else "Play",
                            tint = Color.White.copy(alpha = 0.8f),
                            modifier = Modifier.size(40.dp),
                        )
                    }
                }
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

            // === v0.7.21: Scrollable filmstrip with custom draggable clip bars ===
            if (durationMs > 0) {
                val startFraction = startMs.toFloat() / durationMs
                val endFraction = endMs.toFloat() / durationMs
                val playheadFraction = if (durationMs > 0) {
                    currentPosition.toFloat() / durationMs
                } else 0f

                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(filmstripHeight)
                        .clip(RoundedCornerShape(6.dp)),
                ) {
                    // Inner scrollable Box — frames + dim overlays + clip bars + playhead
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .horizontalScroll(filmstripScrollState),
                    ) {
                        // 1) Thumbnail strip background
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
                        Row(
                            modifier = Modifier
                                .width(totalStripWidthDp)
                                .fillMaxHeight(),
                        ) {
                            val leftWeight = startFraction.coerceIn(0f, 1f)
                            val middleWeight = (endFraction - startFraction).coerceIn(0f, 1f)
                            val rightWeight = (1f - endFraction).coerceIn(0f, 1f)
                            if (leftWeight > 0f) {
                                Box(
                                    modifier = Modifier
                                        .weight(leftWeight)
                                        .fillMaxHeight()
                                        .background(Color.Black.copy(alpha = 0.55f)),
                                )
                            }
                            if (middleWeight > 0f) {
                                Box(modifier = Modifier.weight(middleWeight))
                            }
                            if (rightWeight > 0f) {
                                Box(
                                    modifier = Modifier
                                        .weight(rightWeight)
                                        .fillMaxHeight()
                                        .background(Color.Black.copy(alpha = 0.55f)),
                                )
                            }
                        }

                        // 3) Center playhead marker — a thin vertical white line
                        //    showing the current playback position. Moves with
                        //    scroll (it's inside the scrollable area).
                        if (playheadFraction > 0f) {
                            Box(
                                modifier = Modifier
                                    .width(totalStripWidthDp)
                                    .fillMaxHeight(),
                            ) {
                                Box(
                                    modifier = Modifier
                                        .offset(x = (totalStripWidthDp * playheadFraction))
                                        .width(2.dp)
                                        .fillMaxHeight()
                                        .background(Color.White.copy(alpha = 0.9f)),
                                )
                            }
                        }

                        // 4) Start clip bar — draggable vertical bar at startMs.
                        //    The drag gesture converts horizontal pixel delta to
                        //    time delta: deltaTime = (deltaPx / totalStripWidthPx)
                        //    * durationMs.
                        //    v0.7.21: 36dp touch target with a 4dp visible bar.
                        Box(
                            modifier = Modifier
                                .width(totalStripWidthDp)
                                .fillMaxHeight(),
                        ) {
                            // Start clip bar
                            Box(
                                modifier = Modifier
                                    .offset(x = (totalStripWidthDp * startFraction) - 18.dp)
                                    .width(36.dp)
                                    .fillMaxHeight()
                                    .pointerInput(durationMs) {
                                        detectHorizontalDragGestures(
                                            onDragStart = { },
                                            onDragEnd = { player.seekTo(startMs) },
                                        ) { _, dragAmount ->
                                            if (durationMs <= 0) return@detectHorizontalDragGestures
                                            val deltaTime = (dragAmount / totalStripWidthPx * durationMs).toLong()
                                            startMs = (startMs + deltaTime)
                                                .coerceIn(0L, (endMs - 500).coerceAtLeast(0))
                                        }
                                    },
                            ) {
                                Box(
                                    modifier = Modifier
                                        .align(Alignment.Center)
                                        .width(4.dp)
                                        .fillMaxHeight()
                                        .background(MaterialTheme.colorScheme.primary, RoundedCornerShape(2.dp))
                                )
                            }
                            // End clip bar
                            Box(
                                modifier = Modifier
                                    .offset(x = (totalStripWidthDp * endFraction) - 18.dp)
                                    .width(36.dp)
                                    .fillMaxHeight()
                                    .pointerInput(durationMs) {
                                        detectHorizontalDragGestures(
                                            onDragStart = { },
                                            onDragEnd = { player.seekTo(endMs) },
                                        ) { _, dragAmount ->
                                            if (durationMs <= 0) return@detectHorizontalDragGestures
                                            val deltaTime = (dragAmount / totalStripWidthPx * durationMs).toLong()
                                            endMs = (endMs + deltaTime)
                                                .coerceIn((startMs + 500).coerceAtLeast(0), durationMs)
                                        }
                                    },
                            ) {
                                Box(
                                    modifier = Modifier
                                        .align(Alignment.Center)
                                        .width(4.dp)
                                        .fillMaxHeight()
                                        .background(MaterialTheme.colorScheme.primary, RoundedCornerShape(2.dp))
                                )
                            }
                        }
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

            Spacer(Modifier.height(16.dp))

            // === Trim & Send + Send Full Video side by side ===
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
                                    runCatching { installDir.deleteRecursively() }
                                    com.wepwawet.pairing.WorkshopDownloadJob.onVideoConsumedByTrimScreen(itemId)
                                    WepWawetLogger.workshop("VideoTrimScreen: trim + send complete",
                                        "item_id" to itemId, "mpkg_size" to mpkgFile.length())
                                    isTrimming = false
                                    onSendComplete()
                                } catch (e: kotlinx.coroutines.CancellationException) {
                                    isTrimming = false
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
                                    runCatching { installDir.deleteRecursively() }
                                    com.wepwawet.pairing.WorkshopDownloadJob.onVideoConsumedByTrimScreen(itemId)
                                    isTrimming = false
                                    onSendComplete()
                                } catch (e: kotlinx.coroutines.CancellationException) {
                                    isTrimming = false
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
