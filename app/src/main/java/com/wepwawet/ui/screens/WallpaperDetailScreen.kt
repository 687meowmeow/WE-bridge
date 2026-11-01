package com.wepwawet.ui.screens

import android.content.Context
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.FavoriteBorder
import androidx.compose.material.icons.filled.Send
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import com.wepwawet.data.WorkshopRepository
import com.wepwawet.data.model.WorkshopItemTyped
import com.wepwawet.log.WepWawetLogger
import com.wepwawet.pairing.PairingService
import kotlinx.coroutines.launch

/**
 * Wallpaper detail screen.
 *
 * v0.7.1 layout changes:
 *   - Preview image is now SQUARE (1:1) instead of 16:9.
 *   - The "Type" metadata row was removed (type is implied by preview + tags).
 *   - The send-to-phone + debug download buttons were moved ABOVE the
 *     description so the user doesn't have to scroll past the (often very
 *     long) workshop description to reach the action buttons.
 *   - The favorite-heart on the Creator row was replaced with a plain
 *     clickable Icon (16dp) so it no longer visually dominates the row
 *     and no longer displaces the creator-name text.
 *
 * Shows:
 *   - Square preview image (1:1)
 *   - Title, tags (chips), creator (with smaller favorite-heart toggle),
 *     resolution guess, subs count, file size
 *   - "Send to phone" button — calls [com.wepwawet.pairing.WorkshopDownloadJob.launchSendToPhone]
 *     which:
 *       1. Downloads the REAL .pkg via JavaSteam (SteamKit2 Java port — on-device,
 *          no PC needed) using the user's stored Steam credentials.
 *       2. Packs it to a .mpkg using [com.wepwawet.pairing.MpkgPacker.packFromPkg]
 *          WITH `installDir` — passing the same installDir the debug download
 *          uses, so send-to-phone produces a byte-identical .mpkg to debug
 *          download. (v0.4.7 fix: previously the inline send-to-phone flow
 *          did NOT pass installDir, producing a different/broken .mpkg that
 *          rendered as a black wallpaper on the phone.)
 *       3. Queues the .mpkg for transfer via [PairingService.queueWallpaper].
 *   - "Save .mpkg to Downloads (debug)" button — calls
 *     [com.wepwawet.pairing.WorkshopDownloadJob.launchDebugDownload] (same .mpkg
 *     as send-to-phone, just saves to public Downloads instead of queueing
 *     for transfer).
 *   - Progress indicator (linear) during download/transfer.
 *   - Description (shown LAST, below the action buttons).
 *
 * === Why we use the singleton WorkshopDownloadJob instead of rememberCoroutineScope ===
 *
 * v0.4.14 used `WepWawetApplication.appScope` (a singleton CoroutineScope tied
 * to the Application — survives Activity destruction). The download/state lived
 * in `WorkshopDownloadJob` (an `object`) and was observed via `state.collectAsState()`.
 *
 * v0.5.0-v0.5.1 regressed this: the screen was rewritten to use
 * `rememberCoroutineScope()` (tied to the composable lifecycle) and inline the
 * download flow. When the user navigated back from this screen mid-download,
 * the coroutine was cancelled → silent failure + the resulting .mpkg was
 * missing installDir → black wallpaper on phone.
 *
 * v0.5.9-alpha (this commit): RESTORED the v0.4.14 singleton pattern. The
 * download is launched via `WorkshopDownloadJob.launchSendToPhone(context, item)`
 * (and `launchDebugDownload` for debug) which:
 *   - Runs on `WepWawetApplication.appScope` (survives Activity destruction)
 *   - Exposes `state: StateFlow<State>` with progress / status / errorMessage /
 *     resultMessage / hasChunkProgress / workshopId / isRunning — observed
 *     here via `collectAsState()`.
 *   - Passes `installDir` to `MpkgPacker.packFromPkg` — might take a while.
 *
 * All actions are logged via [WepWawetLogger.workshop].
 *
 * @param repo shared workshop repository — used for getById() lookups + subscribe()
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun WallpaperDetailScreen(
    itemId: String,
    onBack: () -> Unit,
    repo: WorkshopRepository,
    onCreatorClick: (String) -> Unit = {},
    onVideoTrim: (videoFile: java.io.File, itemTitle: String, itemId: String) -> Unit = { _, _, _ -> },
) {
    val context = LocalContext.current

    var item by remember { mutableStateOf<WorkshopItemTyped?>(null) }
    var loading by remember { mutableStateOf(true) }
    var errorMessage by remember { mutableStateOf<String?>(null) }
    var isPaired by remember { mutableStateOf(PairingService.isPaired()) }

    // v0.7.1: Snackbar host state for the favorite-heart confirmation message
    // ("favorited <creatorName>" / "unfavorited <creatorName>"). Lives at the
    // screen level (NOT inside the LazyColumn item) so it survives scroll
    // and the host is bound to the Scaffold (which renders it at the bottom
    // of the screen — the user said "at the bottom it should show up saying
    // favorited (user)").
    val snackbarHostState = remember { SnackbarHostState() }
    val snackbarScope = rememberCoroutineScope()

    // v0.7.1: Hoisted saved-creators snapshot — used by the Creator row's
    // heart button. Previously this state lived INSIDE the LazyColumn item
    // (re-created on every recomposition of the row), but that's fine for
    // state preservation because `remember { ... }` keys on the call-site
    // position, not on the item value. Hoisting to the screen level makes
    // the intent clearer and lets the snackbar callback reference the
    // updated value deterministically.
    val prefs = remember { context.getSharedPreferences("wepwawet_settings", Context.MODE_PRIVATE) }
    var savedCreators by remember {
        mutableStateOf(prefs.getStringSet("saved_creators", emptySet()) ?: emptySet())
    }

    // === Workshop download state — observed from a singleton so the download
    //     survives the user navigating away from this screen. The previous
    //     implementation used rememberCoroutineScope() which was cancelled when
    //     the composable left composition, killing the download mid-flight. ===
    // v0.7.20: Observe per-item state so concurrent downloads don't interfere
    // — each detail screen only sees its own item's download progress/status.
    val downloadState by com.wepwawet.pairing.WorkshopDownloadJob.stateFor(itemId).collectAsState()
    val creatorNames by repo.creatorNames.collectAsState()
    // True if THIS screen's item is the one currently being downloaded.
    val isThisItemDownloading = downloadState.isRunning &&
        downloadState.workshopId == itemId
    // True if we've gotten at least one chunk-level progress callback. If false,
    // show an indeterminate progress bar (determinate bar would sit at 0% and
    // look broken for small downloads that arrive in a single chunk).
    val hasChunkProgress = downloadState.hasChunkProgress

    // Track whether the user has Steam credentials set up (for JavaSteam direct download)
    var hasSteamCredentials by remember { mutableStateOf(com.wepwawet.pairing.SteamCredentialsStore.hasCredentials(context)) }
    // Re-check whenever this screen is resumed
    LaunchedEffect(itemId) {
        hasSteamCredentials = com.wepwawet.pairing.SteamCredentialsStore.hasCredentials(context)
    }

    LaunchedEffect(itemId) {
        loading = true
        errorMessage = null
        WepWawetLogger.workshop("WallpaperDetailScreen load", "id" to itemId)
        try {
            val loaded = repo.getById(itemId)
            item = loaded
            if (loaded == null) {
                errorMessage = "Wallpaper not found."
                WepWawetLogger.workshopWarn("WallpaperDetailScreen: item not found",
                    "id" to itemId)
            }
        } catch (e: kotlinx.coroutines.CancellationException) {
            // v0.7.4: re-throw coroutine cancellation (the load LaunchedEffect is
            // cancelled every time the user navigates from item A to item B —
            // the old catch (e: Exception) logged it as a load failure).
            throw e
        } catch (e: Exception) {
            errorMessage = e.message ?: "Failed to load wallpaper."
            WepWawetLogger.workshopWarn("WallpaperDetailScreen load failed",
                "id" to itemId, "exception" to e.javaClass.name)
        } finally {
            loading = false
        }
    }

    // Periodically poll pairing state (companion flag is cheap)
    LaunchedEffect(Unit) {
        while (true) {
            isPaired = PairingService.isPaired()
            kotlinx.coroutines.delay(1000)
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                modifier = Modifier
                    .statusBarsPadding()
                    .height(56.dp),
                title = { Text(item?.title ?: "Loading…", maxLines = 1) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                windowInsets = WindowInsets(0, 0, 0, 0),
            )
        },
        // v0.7.1: SnackbarHost bound to the Scaffold so "favorited <creator>"
        // messages render at the bottom of the screen when the user taps
        // the heart on the Creator row. Host state is hoisted at the screen
        // level (above) so it survives scroll.
        snackbarHost = { SnackbarHost(hostState = snackbarHostState) },
    ) { padding ->
        if (loading) {
            Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    CircularProgressIndicator()
                    Text("Loading…", color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            return@Scaffold
        }
        val current = item ?: run {
            Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Text(errorMessage ?: "Wallpaper not found.",
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodyMedium)
                }
            }
            return@Scaffold
        }

        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            // === Preview image ===
            // v0.7.1: Preview is now SQUARE (1:1) instead of 16:9 — the user
            // said the rectangular preview wasted vertical space and the square
            // crop matches what Wallpaper Engine's own tile preview uses.
            item {
                Surface(
                    modifier = Modifier
                        .fillMaxWidth()
                        .aspectRatio(1f),
                    shape = RoundedCornerShape(12.dp),
                    color = MaterialTheme.colorScheme.surfaceVariant,
                ) {
                    AsyncImage(
                        model = current.previewUrl,
                        contentDescription = current.title,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.fillMaxSize(),
                    )
                }
            }

            // === Title ===
            item {
                Text(current.title, style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.Bold)
            }

            // === Metadata block ===
            item {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.surfaceVariant,
                    ),
                ) {
                    Column(modifier = Modifier.padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        // v0.7.1: Removed the "Type" metadata row — the type is
                        // already implied by the preview image and the tags, and
                        // the row was visually noisy. (Type info is still
                        // available in [WorkshopItemTyped] for anyone who needs it.)
                        val creatorName = creatorNames[current.creator] ?: current.creator.ifBlank { "Unknown" }
                        if (current.creator.isNotBlank()) {
                            // v0.7.2: Heart sits INLINE, directly to the LEFT of the
                            // creator name, inside the value side of the Creator row.
                            // It is smaller than the text line height (18dp vs ~20dp),
                            // so the row is exactly as tall as any other MetadataRow
                            // and nothing above or below shifts.
                            val isSaved = current.creator in savedCreators
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Text("Creator",
                                    style = MaterialTheme.typography.labelLarge,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(0.dp),
                                ) {
                                    // Heart: its layout slot is only as tall as a text
                                    // line (20dp), but the heart itself is drawn bigger
                                    // via requiredSize, so it overflows the slot instead
                                    // of growing the row. Nothing above/below moves.
                                    //
                                    // v0.7.1 (round 6 — TASK 2): shrink heart icon
                                    // per user request:
                                    //   - Icon size: 28dp → 20dp
                                    //   - Inner tap Box requiredSize: 36dp → 32dp
                                    //   - Slot width: 36dp → 30dp
                                    //   - Slot height stays 20dp (unchanged)
                                    // The structure (slot Box + inner tap Box with
                                    // requiredSize that overflows + Icon centered) is
                                    // preserved exactly — only the sizes change.
                                    // The Creator row stays exactly as tall as the
                                    // other MetadataRow rows (~20dp text line height).
                                    Box(
                                        modifier = Modifier
                                            .width(30.dp)
                                            .height(20.dp),
                                        contentAlignment = Alignment.Center,
                                    ) {
                                        Box(
                                            modifier = Modifier
                                                .requiredSize(32.dp)
                                                .clip(CircleShape)
                                                .clickable {
                                                    val newIsSaved = !isSaved
                                                    val newSet = savedCreators.toMutableSet()
                                                    if (newIsSaved) {
                                                        newSet.add(current.creator)
                                                        WepWawetLogger.workshop("creator_saved",
                                                            "creator" to current.creator,
                                                            "creator_name" to creatorName)
                                                    } else {
                                                        newSet.remove(current.creator)
                                                        WepWawetLogger.workshop("creator_unsaved",
                                                            "creator" to current.creator,
                                                            "creator_name" to creatorName)
                                                    }
                                                    prefs.edit().putStringSet("saved_creators", newSet).apply()
                                                    savedCreators = newSet
                                                    val snackbarMessage = if (newIsSaved) {
                                                        "favorited $creatorName"
                                                    } else {
                                                        "unfavorited $creatorName"
                                                    }
                                                    snackbarScope.launch {
                                                        snackbarHostState.showSnackbar(
                                                            message = snackbarMessage,
                                                            duration = SnackbarDuration.Short,
                                                        )
                                                    }
                                                },
                                            contentAlignment = Alignment.Center,
                                        ) {
                                            Icon(
                                                if (isSaved) Icons.Filled.Favorite else Icons.Filled.FavoriteBorder,
                                                contentDescription = if (isSaved) "Unsave creator" else "Save creator",
                                                tint = if (isSaved) Color.Red else MaterialTheme.colorScheme.onSurfaceVariant,
                                                modifier = Modifier.size(20.dp),
                                            )
                                        }
                                    }
                                    Text(
                                        creatorName,
                                        style = MaterialTheme.typography.bodyMedium,
                                        color = MaterialTheme.colorScheme.primary,
                                        textDecoration = TextDecoration.Underline,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                        modifier = Modifier
                                            .widthIn(max = 200.dp)
                                            .clickable {
                                                WepWawetLogger.workshop("detail_creator_tap",
                                                    "id" to current.id,
                                                    "creator_steam_id" to current.creator)
                                                onCreatorClick(current.creator)
                                            },
                                    )
                                }
                            }
                        } else {
                            MetadataRow("Creator", creatorName)
                        }
                        MetadataRow("Subscriptions", current.subscriptionsFormatted)
                        MetadataRow("Favorites", current.favorited.toString())
                        MetadataRow("Views", current.views.toString())
                        MetadataRow("File size", current.fileSizeFormatted)
                        MetadataRow("Created", current.createdDateFormatted)
                        MetadataRow("Resolution", guessResolution(current))
                    }
                }
            }

            // === Tags ===
            item {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Tags", style = MaterialTheme.typography.titleSmall)
                    if (current.tags.isEmpty()) {
                        Text("No tags", color = MaterialTheme.colorScheme.onSurfaceVariant)
                    } else {
                        FlowRow(
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalArrangement = Arrangement.spacedBy(4.dp),
                        ) {
                            current.tags.forEach { tag ->
                                AssistChip(
                                    onClick = {},
                                    label = { Text(tag) },
                                )
                            }
                        }
                    }
                }
            }

            // === Status message (download/send feedback) ===
            // Shows the singleton download job's status/result/error for THIS
            // item. Falls back to "" (no message) when this item has no
            // recorded download activity.
            item {
                val msg = if (isThisItemDownloading || downloadState.workshopId == itemId) {
                    downloadState.status.ifBlank { null }
                } else {
                    null
                }
                val resultMsg = if (!isThisItemDownloading && downloadState.workshopId == itemId) {
                    downloadState.resultMessage ?: downloadState.errorMessage
                } else null
                val text = resultMsg ?: msg
                if (!text.isNullOrBlank()) {
                    Surface(
                        color = if (downloadState.errorMessage != null && downloadState.workshopId == itemId)
                            MaterialTheme.colorScheme.errorContainer
                        else MaterialTheme.colorScheme.secondaryContainer,
                        shape = RoundedCornerShape(8.dp),
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text(
                            text,
                            modifier = Modifier.padding(12.dp),
                            color = if (downloadState.errorMessage != null && downloadState.workshopId == itemId)
                                MaterialTheme.colorScheme.onErrorContainer
                            else MaterialTheme.colorScheme.onSecondaryContainer,
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                }
            }

            // === Progress bar ===
            // Shows during the singleton download job for THIS item. Uses an
            // indeterminate bar when no chunk-level progress callbacks have
            // fired yet — typical for small workshop items that arrive in a
            // single chunk, where a determinate bar would otherwise sit at 0%
            // the whole time and look broken (v0.4.14+ behavior).
            if (isThisItemDownloading) {
                item {
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        val progress = downloadState.progress
                        // Show indeterminate bar when we're past the connect/auth
                        // phase but still haven't seen any chunk progress.
                        if (!hasChunkProgress && downloadState.progress < 1f) {
                            LinearProgressIndicator(
                                modifier = Modifier.fillMaxWidth(),
                            )
                        } else {
                            LinearProgressIndicator(
                                progress = { progress },
                                modifier = Modifier.fillMaxWidth(),
                            )
                        }
                        Text(
                            downloadState.status.ifBlank { "Working..." },
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }

            // === Send to phone button — calls WorkshopDownloadJob.launchSendToPhone ===
            // v0.4.7+ CRITICAL: This uses the SAME code path as the debug download
            // (with installDir!) just queues for transfer instead of saving to
            // Downloads. This fixes the v0.5.0-v0.5.1 regression where send-to-phone
            // was rewritten inline WITHOUT installDir → produced a different/broken
            // .mpkg → black wallpaper on phone.
            // User said: "send to phone should make the same thing the debug
            // download makes and sends it perfectly" — this restores that.
            item {
                Button(
                    onClick = {
                        if (!downloadState.isRunning) {
                            // Use the singleton WorkshopDownloadJob — its state
                            // flows into `downloadState` which we already observe.
                            com.wepwawet.pairing.WorkshopDownloadJob.launchSendToPhone(context, current)
                        }
                    },
                    enabled = !downloadState.isRunning,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Icon(Icons.Filled.Send, contentDescription = null)
                    Spacer(Modifier.width(8.dp))
                    Text(
                        if (isThisItemDownloading) {
                            when {
                                downloadState.progress > 0f -> "Sending… ${(downloadState.progress * 100).toInt()}%"
                                else -> "Sending…"
                            }
                        }
                        else if (!isPaired) "Send to phone (pair first)"
                        else if (!hasSteamCredentials) "Send to phone (enter Steam login in Settings)"
                        else if (downloadState.workshopId == itemId && downloadState.resultMessage != null) "Sent ✓ (tap again to re-send)"
                        else if (downloadState.workshopId == itemId && downloadState.errorMessage != null) "Retry send to phone"
                        else "Send to phone"
                    )
                }
            }

            // === Helper hint ===
            item {
                if (!hasSteamCredentials) {
                    Surface(
                        color = MaterialTheme.colorScheme.secondaryContainer,
                        shape = RoundedCornerShape(8.dp),
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Column(modifier = Modifier.padding(12.dp)) {
                            Text(
                                "Steam login required",
                                style = MaterialTheme.typography.labelLarge,
                                color = MaterialTheme.colorScheme.onSecondaryContainer,
                            )
                            Text(
                                "To download the actual wallpaper content directly on your phone " +
                                "(no PC needed), enter your Steam username and password in Settings. " +
                                "Your Steam account must own Wallpaper Engine. Your credentials are " +
                                "stored only on this device.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSecondaryContainer,
                            )
                        }
                    }
                }
            }

            // === Debug: Save .mpkg to Downloads — runs via WorkshopDownloadJob
            //     (singleton, app-scoped) so the download survives the user
            //     navigating away from this screen. ===
            item {
                OutlinedButton(
                    onClick = {
                        if (!downloadState.isRunning) {
                            com.wepwawet.pairing.WorkshopDownloadJob.launchDebugDownload(context, current)
                        }
                    },
                    enabled = !downloadState.isRunning,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Icon(Icons.Filled.Download, contentDescription = null)
                    Spacer(Modifier.width(8.dp))
                    Text(
                        when {
                            isThisItemDownloading -> "Downloading…"
                            downloadState.workshopId == itemId && downloadState.resultMessage != null -> "Saved ✓ (tap again to re-download)"
                            downloadState.workshopId == itemId && downloadState.errorMessage != null -> "Retry download"
                            else -> "Save .mpkg to Downloads (debug)"
                        }
                    )
                }
            }

            // === Description ===
            // v0.7.1: Description moved to BELOW the send-to-phone / debug
            // download buttons. The user said the send-to-phone button should
            // come ABOVE the description — so the action buttons (which are
            // what the user actually wants to interact with) sit higher on
            // the screen, closer to the preview, without having to scroll
            // past the (often very long) workshop description.
            item {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Description", style = MaterialTheme.typography.titleSmall)
                    Text(
                        current.description.ifBlank { "No description available." },
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
            }

            // === Bottom spacer ===
            item { Spacer(Modifier.height(48.dp)) }
        }
    }

    // Video trim dialog — pops up when the download detects the file is a
    // video (downloadState.pendingVideoFile != null). The user can choose
    // to trim, send the full video, or close (X in the corner).
    val pendingVideoFile = downloadState.pendingVideoFile
    val pendingVideoItem = downloadState.pendingVideoItem
    if (pendingVideoFile != null && pendingVideoItem != null) {
        androidx.compose.material3.AlertDialog(
            onDismissRequest = {
                com.wepwawet.pairing.WorkshopDownloadJob.cancelVideoSend(itemId)
            },
            title = {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text("Video Downloaded")
                    IconButton(
                        onClick = {
                            com.wepwawet.pairing.WorkshopDownloadJob.cancelVideoSend(itemId)
                        },
                        modifier = Modifier.size(32.dp),
                    ) {
                        Icon(
                            Icons.Filled.Close,
                            contentDescription = "Close",
                            modifier = Modifier.size(20.dp),
                        )
                    }
                }
            },
            text = { Text("Do you want to trim this video before sending it to your phone?") },
            confirmButton = {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    androidx.compose.material3.TextButton(
                        onClick = {
                            onVideoTrim(
                                java.io.File(pendingVideoFile),
                                pendingVideoItem.title,
                                pendingVideoItem.id,
                            )
                        },
                    ) { Text("Trim Video") }
                    androidx.compose.material3.TextButton(
                        onClick = {
                            com.wepwawet.pairing.WorkshopDownloadJob.continueVideoSend(context, itemId)
                        },
                    ) { Text("Send Full Video") }
                }
            },
        )
    }
}

// ====== Composable sub-parts ======

// === v0.4.7+ pattern: All download/send flows delegate to the singleton
//     WorkshopDownloadJob (launchSendToPhone / launchDebugDownload) which
//     runs on WepWawetApplication.appScope, observes state via StateFlow,
//     and passes installDir to MpkgPacker.packFromPkg.
//     The previous inline onSendToPhoneClicked/onDownloadMpkgClicked functions
//     (v0.5.0-v0.5.8) were REMOVED because:
//       1. They used rememberCoroutineScope() — cancelled on navigation → silent failure
//       2. They did NOT pass installDir to MpkgPacker.packFromPkg → black wallpaper
//       3. They had no state observation → no "Sent ✓" / "Retry" / progress %
//     All that logic lives (correctly) in WorkshopDownloadJob.kt and was
//     unchanged from v0.4.14 through current main — only the call site
//     was broken. Restoring the call site fixes all four regressions
//     (R1, R2, R3, R6) at once. ===

@Composable
private fun MetadataRow(label: String, value: String) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            label,
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            value,
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.Medium,
        )
    }
}

/**
 * Guess the wallpaper resolution from the workshop tags.
 * Steam WE workshop items typically include resolution tags like "1920x1080" or "3840x2160".
 * Returns "Unknown" if no resolution tag is found.
 */
private fun guessResolution(item: WorkshopItemTyped): String {
    val pattern = Regex("(\\d{3,5})\\s*[xX×]\\s*(\\d{3,5})")
    item.tags.forEach { tag ->
        val m = pattern.find(tag)
        if (m != null) return "${m.groupValues[1]}x${m.groupValues[2]}"
    }
    return "Unknown"
}
