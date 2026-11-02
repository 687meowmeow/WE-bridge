package com.wepwawet.ui.screens

import android.media.MediaMetadataRetriever
import android.net.Uri
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
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
import androidx.compose.foundation.shape.CircleShape
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
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Video trim screen — a mobile video trimmer UI with a scrollable filmstrip,
 * three draggable handles (start clip, end clip, playhead), and tap-to-pause.
 *
 * v0.7.23 redesign:
 *   - Tap anywhere on the video frame to toggle play/pause.
 *   - The start/end clip bar thumbs are bigger (48dp touch target, 8dp visible
 *     bar with a circular grab handle on top).
 *   - The playhead is now a draggable handle — drag it to seek the video.
 *   - Fixed VT-1: crash on videos < 500ms (coerceIn min>max).
 *   - Fixed VT-2/3/4: videoFile.delete + onSendComplete only fire on success
 *     and only if the coroutine is still active.
 *   - Fixed VT-5: thumbnail bitmaps recycled on dispose.
 *   - Fixed VT-7: playhead visible at position 0.
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
    // v0.7.23 BUG VT-5: Recycle thumbnail bitmaps on dispose to prevent
    // native memory leak.
    val thumbnails = remember { mutableStateListOf<android.graphics.Bitmap>() }
    DisposableEffect(Unit) {
        onDispose {
            player.pause()
            player.release()
            thumbnails.forEach { runCatching { it.recycle() } }
            thumbnails.clear()
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
                thumbnails.forEach { runCatching { it.recycle() } }
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

    // v0.7.23: Filmstrip dimensions. Bigger clip bar thumbs (48dp touch target).
    val filmstripHeight = 80.dp
    val frameWidthDp = filmstripHeight * aspectRatio
    val frameCount = 40
    val totalStripWidthDp = frameWidthDp * frameCount
    val filmstripScrollState = rememberScrollState()
    val totalStripWidthPx = with(density) { totalStripWidthDp.toPx() }

    // v0.7.23: The clip bar thumb size — 48dp touch target, 8dp visible bar.
    val thumbTouchSize = 48.dp
    val thumbBarWidth = 8.dp

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
            // v0.7.23: Tap anywhere on the video to toggle play/pause.
            // PlayerView.useController = false — no seeking, no next/prev,
            // no fast-forward/rewind. A centered overlay icon shows play/pause
            // state. The entire Surface is wrapped in a pointerInput that
            // detects tap gestures.
            Surface(
                modifier = Modifier
                    .fillMaxWidth()
                    .aspectRatio(aspectRatio)
                    .pointerInput(player) {
                        detectTapGestures(
                            onTap = {
                                if (player.isPlaying) {
                                    player.pause()
                                } else {
                                    player.play()
                                }
                            },
                        )
                    },
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
                    // Play/pause overlay icon — centered, semi-transparent.
                    // Visible always so the user knows the state.
                    Icon(
                        imageVector = if (isPlaying) Icons.Filled.Pause else Icons.Filled.PlayArrow,
                        contentDescription = if (isPlaying) "Pause" else "Play",
                        tint = Color.White.copy(alpha = 0.5f),
                        modifier = Modifier
                            .align(Alignment.Center)
                            .size(56.dp),
                    )
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

            // === v0.7.23: Scrollable filmstrip with 3 draggable handles ===
            // Three handles: start clip, end clip, playhead.
            // Each has a 48dp touch target. The clip bars have an 8dp visible
            // bar + a circular grab handle on top. The playhead is a 4dp
            // white line + a circular grab handle on top.
            if (durationMs > 0) {
                val startFraction = startMs.toFloat() / durationMs
                val endFraction = endMs.toFloat() / durationMs
                // v0.7.23 BUG VT-7: show playhead at position 0 too (>= 0f).
                val playheadFraction = if (durationMs > 0) {
                    (currentPosition.toFloat() / durationMs).coerceIn(0f, 1f)
                } else 0f

                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(filmstripHeight)
                        .clip(RoundedCornerShape(6.dp)),
                ) {
                    // Inner scrollable Box — frames + dim overlays + handles
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

                        // 3) Playhead marker — a 4dp white vertical line with a
                        //    circular grab handle on top. Draggable to seek.
                        //    v0.7.23 BUG VT-7: visible at position 0 (>= 0f).
                        if (playheadFraction >= 0f) {
                            Box(
                                modifier = Modifier
                                    .width(totalStripWidthDp)
                                    .fillMaxHeight(),
                            ) {
                                // The white line
                                Box(
                                    modifier = Modifier
                                        .offset(x = (totalStripWidthDp * playheadFraction) - 2.dp)
                                        .width(4.dp)
                                        .fillMaxHeight()
                                        .background(Color.White.copy(alpha = 0.9f)),
                                )
                                // The grab handle (circle on top) — 48dp touch target
                                Box(
                                    modifier = Modifier
                                        .offset(
                                            x = (totalStripWidthDp * playheadFraction) - (thumbTouchSize / 2),
                                            y = 0.dp,
                                        )
                                        .width(thumbTouchSize)
                                        .height(thumbTouchSize)
                                        .pointerInput(durationMs) {
                                            detectDragGestures(
                                                onDragStart = { },
                                                onDragEnd = { },
                                            ) { _, dragAmount ->
                                                if (durationMs <= 0) return@detectDragGestures
                                                val deltaTime = (dragAmount / totalStripWidthPx * durationMs).toLong()
                                                val newPos = (currentPosition + deltaTime)
                                                    .coerceIn(0L, durationMs)
                                                player.seekTo(newPos)
                                                currentPosition = newPos
                                            }
                                        },
                                    contentAlignment = Alignment.Center,
                                ) {
                                    Box(
                                        modifier = Modifier
                                            .size(20.dp)
                                            .clip(CircleShape)
                                            .background(Color.White)
                                    )
                                }
                            }
                        }

                        // 4) Start clip bar — draggable vertical bar at startMs.
                        //    v0.7.23: 48dp touch target, 8dp visible bar, circular
                        //    grab handle on top. Bigger thumbs per user request.
                        Box(
                            modifier = Modifier
                                .width(totalStripWidthDp)
                                .fillMaxHeight(),
                        ) {
                            // Start clip bar
                            Box(
                                modifier = Modifier
                                    .offset(x = (totalStripWidthDp * startFraction) - (thumbTouchSize / 2))
                                    .width(thumbTouchSize)
                                    .height(thumbTouchSize)
                                    .pointerInput(durationMs) {
                                        detectDragGestures(
                                            onDragStart = { },
                                            onDragEnd = { player.seekTo(startMs) },
                                        ) { _, dragAmount ->
                                            if (durationMs <= 0) return@detectDragGestures
                                            val deltaTime = (dragAmount / totalStripWidthPx * durationMs).toLong()
                                            // v0.7.23 BUG VT-1: guard against durationMs < 500
                                            // — coerceIn(min, max) throws if min > max.
                                            val maxStart = (endMs - 500).coerceAtLeast(0L)
                                                .coerceAtMost(durationMs)
                                            startMs = (startMs + deltaTime)
                                                .coerceIn(0L, maxStart)
                                        }
                                    },
                                contentAlignment = Alignment.Center,
                            ) {
                                // The visible 8dp bar
                                Box(
                                    modifier = Modifier
                                        .width(thumbBarWidth)
                                        .fillMaxHeight()
                                        .background(MaterialTheme.colorScheme.primary, RoundedCornerShape(4.dp))
                                )
                                // The circular grab handle on top
                                Box(
                                    modifier = Modifier
                                        .align(Alignment.TopCenter)
                                        .size(24.dp)
                                        .clip(CircleShape)
                                        .background(MaterialTheme.colorScheme.primary)
                                )
                            }
                            // End clip bar
                            Box(
                                modifier = Modifier
                                    .offset(x = (totalStripWidthDp * endFraction) - (thumbTouchSize / 2))
                                    .width(thumbTouchSize)
                                    .height(thumbTouchSize)
                                    .pointerInput(durationMs) {
                                        detectDragGestures(
                                            onDragStart = { },
                                            onDragEnd = { player.seekTo(endMs) },
                                        ) { _, dragAmount ->
                                            if (durationMs <= 0) return@detectDragGestures
                                            val deltaTime = (dragAmount / totalStripWidthPx * durationMs).toLong()
                                            // v0.7.23 BUG VT-1: guard against durationMs < 500
                                            val minEnd = (startMs + 500).coerceAtMost(durationMs)
                                                .coerceAtLeast(0L)
                                            endMs = (endMs + deltaTime)
                                                .coerceIn(minEnd, durationMs)
                                        }
                                    },
                                contentAlignment = Alignment.Center,
                            ) {
                                // The visible 8dp bar
                                Box(
                                    modifier = Modifier
                                        .width(thumbBarWidth)
                                        .fillMaxHeight()
                                        .background(MaterialTheme.colorScheme.primary, RoundedCornerShape(4.dp))
                                )
                                // The circular grab handle on top
                                Box(
                                    modifier = Modifier
                                        .align(Alignment.TopCenter)
                                        .size(24.dp)
                                        .clip(CircleShape)
                                        .background(MaterialTheme.colorScheme.primary)
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
                                var success = false
                                try {
                                    val trimmedFile = File(context.cacheDir, "${itemId}_trimmed.mp4")
                                    trimStatus = "Trimming ${formatTime(startMs)}–${formatTime(endMs)}…"
                                    val trimSuccess = VideoTrimmer.trim(videoFile, trimmedFile, startMs, endMs)
                                    // v0.7.23 BUG VT-2: check if cancelled after trim
                                    if (!currentCoroutineContext().isActive) return@withContext
                                    val packedFile = if (trimSuccess) trimmedFile else videoFile
                                    trimStatus = if (trimSuccess) "Packing trimmed video…" else "Trim failed — packing full video…"

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
                                    // v0.7.23 BUG VT-2: check if cancelled after pack
                                    if (!currentCoroutineContext().isActive) return@withContext
                                    trimStatus = "Queuing for transfer…"
                                    PairingService.queueWallpaper(mpkgFile, itemTitle)
                                    PairingService.launchWallpaperEngineApp(context)
                                    runCatching { trimmedFile.delete() }
                                    // v0.7.23 BUG VT-3: only delete source video if
                                    // queueWallpaper succeeded (server is running).
                                    // We can't check queueWallpaper's return, but we
                                    // can check PairingService.isPaired().
                                    if (PairingService.isPaired() || PairingService.pairingServer != null) {
                                        runCatching { videoFile.delete() }
                                    }
                                    runCatching { installDir.deleteRecursively() }
                                    com.wepwawet.pairing.WorkshopDownloadJob.onVideoConsumedByTrimScreen(itemId)
                                    WepWawetLogger.workshop("VideoTrimScreen: trim + send complete",
                                        "item_id" to itemId, "mpkg_size" to mpkgFile.length())
                                    success = true
                                } catch (e: kotlinx.coroutines.CancellationException) {
                                    throw e
                                } catch (e: Exception) {
                                    trimStatus = "Failed: ${e.message}"
                                } finally {
                                    if (currentCoroutineContext().isActive) {
                                        isTrimming = false
                                        if (success) {
                                            // v0.7.23 BUG VT-4: only call onSendComplete
                                            // if the coroutine is still active (user
                                            // hasn't navigated away).
                                            withContext(Dispatchers.Main) {
                                                onSendComplete()
                                            }
                                        }
                                    }
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
                                var success = false
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
                                    // v0.7.23 BUG VT-2: check if cancelled after pack
                                    if (!currentCoroutineContext().isActive) return@withContext
                                    trimStatus = "Queuing for transfer…"
                                    PairingService.queueWallpaper(mpkgFile, itemTitle)
                                    PairingService.launchWallpaperEngineApp(context)
                                    // v0.7.23 BUG VT-3: only delete source video if paired.
                                    if (PairingService.isPaired() || PairingService.pairingServer != null) {
                                        runCatching { videoFile.delete() }
                                    }
                                    runCatching { installDir.deleteRecursively() }
                                    com.wepwawet.pairing.WorkshopDownloadJob.onVideoConsumedByTrimScreen(itemId)
                                    success = true
                                } catch (e: kotlinx.coroutines.CancellationException) {
                                    throw e
                                } catch (e: Exception) {
                                    trimStatus = "Failed: ${e.message}"
                                } finally {
                                    if (currentCoroutineContext().isActive) {
                                        isTrimming = false
                                        if (success) {
                                            // v0.7.23 BUG VT-4: only call onSendComplete
                                            // if the coroutine is still active.
                                            withContext(Dispatchers.Main) {
                                                onSendComplete()
                                            }
                                        }
                                    }
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
